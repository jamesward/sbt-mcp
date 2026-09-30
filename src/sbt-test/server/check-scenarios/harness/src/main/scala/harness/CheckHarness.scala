package harness

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.client.McpClient
import zio.*
import zio.http.*
import zio.json.ast.Json

// Drives the real `check` MCP tool over HTTP while sbt's command loop is busy
// running this harness: the tool must answer from its cached module settings
// instead of queueing behind the running command.
object CheckHarness:
  private def textOf(result: CallToolResult): String =
    result.content.collect { case ToolContent.Text(text, _) => text }.mkString("\n")

  private val probe = "app/src/main/scala/app/Probe.scala"

  private def callCheck(client: McpClient, content: String) =
    client
      .callTool("check", Json.Obj("files" -> Json.Arr(Json.Str(probe)), "content" -> Json.Str(content)))
      .map(textOf)

  def main(args: Array[String]): Unit =
    val broken = "package app\n\nobject Probe:\n  val x: Int = core.CoreApi.greet(\"a\")\n"
    val clean  = "package app\n\nobject Probe:\n  val x: String = core.CoreApi.greet(\"a\")\n"
    val program =
      ZIO.scoped {
        for
          client <- McpClient.connect("http://127.0.0.1:5097/")
          tools  <- client.listTools
          bad    <- callCheck(client, broken)
          good   <- callCheck(client, clean)
          // Concurrent requests are serialized safely onto the shared warm compiler.
          many <- ZIO.foreachPar(1 to 6)(i => callCheck(client, if i % 2 == 0 then broken else clean))
        yield (tools.map(_.name), bad, good, many)
      }.retry(Schedule.recurs(25) && Schedule.spaced(200.millis))

    val (tools, bad, good, many) = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(program.provide(Client.default)).getOrThrow()
    }
    assert(tools.contains("check"), s"tools/list must include check: $tools")
    assert(bad.startsWith("[error] check app/compile (Scala 3.9.0): 1 error"), bad)
    assert(bad.contains(s"$probe:4:"), bad)
    assert(bad.contains("sbt is busy; used the module settings from its last refresh"), bad)
    assert(good.startsWith("[ok] check app/compile"), good)
    many.zipWithIndex.foreach { case (text, index) =>
      val expected = if (index + 1) % 2 == 0 then "[error]" else "[ok]"
      assert(text.startsWith(expected), s"concurrent call ${index + 1}: $text")
    }
    println(s"checkHarness OK:\n$bad\n---\n$good")
