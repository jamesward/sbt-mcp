ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))
  .settings(
    name := "docs-proxy-test",
    Global / mcpEnabled := true,
    Global / mcpPort    := 5099,
    Global / mcpHost    := "127.0.0.1",
    // Proxy a LOCAL upstream MCP server (started by mcpCheckProxy) instead of javadocs.dev,
    // so the test is hermetic (no external network).
    Global / mcpDocsUrl := Some("http://127.0.0.1:5198/"),
  )

// Start a local upstream MCP server exposing a `docs-echo` tool, then connect to OUR
// server and assert its tools/list includes BOTH our built-ins and the upstream tool,
// and that calling the upstream tool is forwarded.
TaskKey[Unit]("mcpCheckProxy", "Validate MCP tool proxying") := Def.uncached {
  import com.jamesward.ziohttp.mcp.*
  import com.jamesward.ziohttp.mcp.McpOutput.given
  import com.jamesward.ziohttp.mcp.client.McpClient
  import zio.*
  import zio.http.*
  import zio.json.ast.Json

  val log = streams.value.log

  def textOf(r: CallToolResult): String =
    r.content.collect { case ToolContent.Text(t, _) => t }.mkString("\n")

  // Upstream MCP server on :5198 with a single `docs-echo` tool.
  val upstream: McpServer[Any] =
    McpServer("docs", "1.0")
      .tool(McpTool("docs-echo").description("upstream echo").handle(ZIO.succeed("hello from upstream docs")))
      .mountedAt("/")
  val upstreamLayer = ZLayer.succeed(Server.Config.default.binding("127.0.0.1", 5198)) >>> Server.live
  val rt            = Runtime.default
  Unsafe.unsafe { implicit u => rt.unsafe.fork(Server.serve(upstream.statelessRoutes).provide(upstreamLayer)) }

  // Query OUR server, retrying until the proxied tool has been merged in.
  val prog =
    ZIO.scoped {
      McpClient.connect("http://127.0.0.1:5099/").flatMap { c =>
        c.listTools.flatMap { tools =>
          val names = tools.map(_.name.toString).toSet
          if (names.contains("docs-echo") && names.contains("glob-search"))
            c.callTool("docs-echo", Json.Obj()).map(r => (names, textOf(r)))
          else ZIO.fail(new RuntimeException(s"waiting for proxied tools; have: $names"))
        }
      }
    }.retry(Schedule.recurs(50) && Schedule.spaced(200.millis))

  val (names, echo) = Unsafe.unsafe { implicit u => rt.unsafe.run(prog.provide(Client.default)).getOrThrow() }

  assert(names.contains("glob-search"), s"our built-in tools should be present, got: $names")
  assert(names.contains("docs-echo"), s"proxied upstream tool should be present, got: $names")
  assert(echo.contains("hello from upstream docs"), s"proxied call should be forwarded, got: $echo")
  log.info(s"mcpCheckProxy OK: merged tools=$names; docs-echo -> $echo")
}
