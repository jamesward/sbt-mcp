ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))
  .settings(
    name := "multi-protocol-test",
    Global / mcpEnabled := true,
    Global / mcpPort    := 5097,
    Global / mcpHost    := "127.0.0.1",
    // Proxy a LOCAL upstream MCP server (started by mcpCheckProtocols) instead of
    // javadocs.dev, so the test is hermetic (no external network).
    Global / mcpDocsUrl := Some("http://127.0.0.1:5197/"),
  )

// Regression test for "proxied (javadocs.dev) tools don't show up": connect to OUR
// server with the zio-http-mcp client pinned to EACH supported MCP protocol version —
// the modern stateless 2026-07-28 (server/discover + modern tools/list) AND the legacy
// handshake revisions (2025-11-25 / 2025-06-18 / 2025-03-26) — and assert that under
// every protocol tools/list returns ALL tools: our five built-ins PLUS the proxied
// upstream tool. If tool-source merging regressed on any single protocol path, exactly
// that arm fails, pinpointing the era.
TaskKey[Unit]("mcpCheckProtocols", "List tools over every MCP protocol version and assert all are present") := Def.uncached {
  import com.jamesward.ziohttp.mcp.*
  import com.jamesward.ziohttp.mcp.client.{ McpClient, McpClientConfig }
  import zio.*
  import zio.http.*
  import zio.json.ast.Json

  val log         = streams.value.log
  val ourUrl      = s"http://127.0.0.1:${(Global / mcpPort).value}/"
  val upstreamUrl = "http://127.0.0.1:5197/"

  // Upstream MCP server on :5197 exposing a single `docs-echo` tool — stands in for
  // javadocs.dev so the proxy has real tools to merge, with no network dependency.
  val upstream: McpServer[Any] =
    McpServer("docs", "1.0")
      .tool(McpTool("docs-echo").description("upstream echo").handle(ZIO.succeed("hello from upstream docs")))
      .mountedAt("/")
  val upstreamLayer = ZLayer.succeed(Server.Config.default.binding("127.0.0.1", 5197)) >>> Server.live
  val rt            = Runtime.default

  val builtIns = Set("sbt-task", "list-tasks", "glob-search", "inspect", "symbol-location")
  val proxied  = "docs-echo"

  // The protocol versions to exercise, each with the wire id we expect the server to
  // negotiate for it (so we prove the arm really used that protocol, not a downgrade).
  val versions: List[(ProtocolVersion, String)] = List(
    ProtocolVersion.V2026_07_28 -> "2026-07-28", // modern, stateless (server/discover)
    ProtocolVersion.V2025_11_25 -> "2025-11-25", // legacy handshake (newest)
    ProtocolVersion.V2025_06_18 -> "2025-06-18", // legacy handshake
    ProtocolVersion.V2025_03_26 -> "2025-03-26", // legacy handshake (oldest Streamable HTTP)
  )

  // For one protocol: connect, list tools (retrying until the proxied tool has been
  // merged), and assert the negotiated version + the full tool set.
  def checkOne(pref: ProtocolVersion, expectedWire: String): ZIO[Client, Throwable, (String, Set[String])] =
    ZIO.scoped {
      McpClient.connect(McpClientConfig(ourUrl, preferredVersion = pref)).flatMap { c =>
        c.listTools.flatMap { tools =>
          val names = tools.map(_.name.toString).toSet
          if (builtIns.subsetOf(names) && names.contains(proxied)) ZIO.succeed((c.protocolVersion, names))
          else ZIO.fail(new RuntimeException(s"[$expectedWire] tools not fully merged yet; have: $names"))
        }
      }
    }.retry(Schedule.recurs(50) && Schedule.spaced(200.millis))

  val prog =
    ZIO.scoped {
      for
        // Scope the local upstream so zio-http/Netty resources are interrupted and
        // finalized before the scripted task returns (no leaked event-loop threads).
        _ <- Server.serve(upstream.statelessRoutes).provide(upstreamLayer).forkScoped
        // Give both daemon servers a moment to bind, then check every protocol.
        _ <- ZIO.sleep(500.millis)
        results <- ZIO.foreach(versions) { case (pref, wire) =>
                     checkOne(pref, wire).map(r => (wire, r))
                   }
      yield results
    }

  val results =
    Unsafe.unsafe { implicit u =>
      rt.unsafe.run(prog.provide(Client.default)).getOrThrow()
    }

  results.foreach { case (expectedWire, (negotiated, names)) =>
    assert(
      builtIns.subsetOf(names),
      s"[$expectedWire] missing built-in tools; got: $names",
    )
    assert(
      names.contains(proxied),
      s"[$expectedWire] proxied upstream tool '$proxied' missing under protocol $expectedWire; got: $names",
    )
    assert(
      negotiated == expectedWire,
      s"expected server to negotiate protocol $expectedWire but it negotiated $negotiated",
    )
    log.info(s"mcpCheckProtocols [$expectedWire] OK (negotiated=$negotiated): tools=${names.toList.sorted.mkString(",")}")
  }
  log.info(s"mcpCheckProtocols OK: all ${versions.size} protocol versions list all ${builtIns.size + 1} tools")
}
