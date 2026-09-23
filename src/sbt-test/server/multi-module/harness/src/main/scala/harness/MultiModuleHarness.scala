package harness

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.client.McpClient
import zio.*
import zio.http.*
import zio.json.ast.Json

object MultiModuleHarness:
  private def textOf(result: CallToolResult): String =
    result.content.collect { case ToolContent.Text(text, _) => text }.mkString("\n")

  def main(args: Array[String]): Unit =
    val program =
      ZIO.scoped {
        for
          client <- McpClient.connect("http://127.0.0.1:5098/")
          glob <- client.callTool("glob-search", Json.Obj("query" -> Json.Str("AlphaService")))
          inspect <- client.callTool("inspect", Json.Obj("symbol" -> Json.Str("BetaService")))
        yield (textOf(glob), textOf(inspect))
      }.retry(Schedule.recurs(25) && Schedule.spaced(200.millis))

    val (globOut, inspectOut) = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(program.provide(Client.default)).getOrThrow()
    }
    assert(
      globOut.contains("AlphaService") && inspectOut.contains("betaOnly"),
      s"symbol tools must include aggregated subprojects: glob=$globOut inspect=$inspectOut",
    )
    println(s"checkMultiModuleSymbols OK: glob=$globOut inspect=$inspectOut")
