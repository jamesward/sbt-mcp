package harness

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.McpOutput.given
import com.jamesward.ziohttp.mcp.client.McpClient
import zio.*
import zio.http.*
import zio.json.ast.Json

object DocsProxyHarness:
  private def textOf(result: CallToolResult): String =
    result.content.collect { case ToolContent.Text(text, _) => text }.mkString("\n")

  def main(args: Array[String]): Unit =
    val upstream =
      McpServer("docs", "1.0")
        .tool(McpTool("docs-echo").description("upstream echo").handle(ZIO.succeed("hello from upstream docs")))
        .mountedAt("/")
    val upstreamLayer = ZLayer.succeed(Server.Config.default.binding("127.0.0.1", 5198)) >>> Server.live
    val program =
      ZIO.scoped {
        for
          _ <- Server.serve(upstream.statelessRoutes).provide(upstreamLayer).forkScoped
          result <- McpClient.connect("http://127.0.0.1:5099/").flatMap { client =>
                      client.listTools.flatMap { tools =>
                        val names = tools.map(_.name.toString).toSet
                        if names.contains("docs-echo") && names.contains("glob-search") then
                          client.callTool("docs-echo", Json.Obj()).map(response => (names, textOf(response)))
                        else ZIO.fail(RuntimeException(s"waiting for proxied tools; have: $names"))
                      }
                    }.retry(Schedule.recurs(50) && Schedule.spaced(200.millis))
        yield result
      }

    val (names, echo) = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(program.provide(Client.default)).getOrThrow()
    }
    assert(names.contains("glob-search"), s"our built-in tools should be present, got: $names")
    assert(names.contains("docs-echo"), s"proxied upstream tool should be present, got: $names")
    assert(echo.contains("hello from upstream docs"), s"proxied call should be forwarded, got: $echo")
    println(s"mcpCheckProxy OK: merged tools=$names; docs-echo -> $echo")
