package harness

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.McpOutput.given
import com.jamesward.ziohttp.mcp.client.{ McpClient, McpClientConfig }
import zio.*
import zio.http.*

object MultiProtocolHarness:
  private val builtIns = Set("sbt-task", "list-tasks", "glob-search", "inspect", "symbol-location")
  private val proxied  = "docs-echo"

  def main(args: Array[String]): Unit =
    val upstream =
      McpServer("docs", "1.0")
        .tool(McpTool("docs-echo").description("upstream echo").handle(ZIO.succeed("hello from upstream docs")))
        .mountedAt("/")
    val upstreamLayer = ZLayer.succeed(Server.Config.default.binding("127.0.0.1", 5197)) >>> Server.live
    val versions = List(
      ProtocolVersion.V2026_07_28 -> "2026-07-28",
      ProtocolVersion.V2025_11_25 -> "2025-11-25",
      ProtocolVersion.V2025_06_18 -> "2025-06-18",
      ProtocolVersion.V2025_03_26 -> "2025-03-26",
    )

    def checkOne(version: ProtocolVersion, expected: String): ZIO[Client, Throwable, (String, Set[String])] =
      ZIO.scoped {
        McpClient.connect(McpClientConfig("http://127.0.0.1:5097/", preferredVersion = version)).flatMap { client =>
          client.listTools.flatMap { tools =>
            val names = tools.map(_.name.toString).toSet
            if builtIns.subsetOf(names) && names.contains(proxied) then ZIO.succeed((client.protocolVersion, names))
            else ZIO.fail(RuntimeException(s"[$expected] tools not fully merged yet; have: $names"))
          }
        }
      }.retry(Schedule.recurs(50) && Schedule.spaced(200.millis))

    val program =
      ZIO.scoped {
        for
          _ <- Server.serve(upstream.statelessRoutes).provide(upstreamLayer).forkScoped
          _ <- ZIO.sleep(500.millis)
          results <- ZIO.foreach(versions) { case (version, wire) => checkOne(version, wire).map(wire -> _) }
        yield results
      }
    val results = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(program.provide(Client.default)).getOrThrow()
    }
    results.foreach { case (expected, (negotiated, names)) =>
      assert(builtIns.subsetOf(names), s"[$expected] missing built-in tools; got: $names")
      assert(names.contains(proxied), s"[$expected] missing proxied tool; got: $names")
      assert(negotiated == expected, s"expected $expected but negotiated $negotiated")
      println(s"mcpCheckProtocols [$expected] OK (negotiated=$negotiated): tools=${names.toList.sorted.mkString(",")}")
    }
    println(s"mcpCheckProtocols OK: all ${versions.size} protocol versions list all ${builtIns.size + 1} tools")
