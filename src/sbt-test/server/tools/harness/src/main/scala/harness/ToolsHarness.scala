package harness

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.client.McpClient
import zio.*
import zio.http.*
import zio.json.ast.Json

object ToolsHarness:
  private def textOf(result: CallToolResult): String =
    result.content.collect { case ToolContent.Text(text, _) => text }.mkString("\n")

  def main(args: Array[String]): Unit =
    val artifactVersion = sys.props("plugin.version")
    val program =
      ZIO.scoped {
        for
          client <- McpClient.connect("http://127.0.0.1:5099/")
          tools <- client.listTools
          inspect <- client.callTool("inspect", Json.Obj("symbol" -> Json.Str("example.Widget")))
          tasks <- client.callTool("list-tasks", Json.Obj())
        yield (tools.map(_.name.toString).toSet, textOf(inspect), textOf(tasks), client.serverInfo)
      }.retry(Schedule.recurs(25) && Schedule.spaced(200.millis))

    val (toolNames, inspectOut, tasksOut, serverInfo) = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(program.provide(Client.default)).getOrThrow()
    }
    val required = Set("sbt-task", "list-tasks", "glob-search", "inspect", "symbol-location")
    assert(required.subsetOf(toolNames), s"missing MCP tools; got: $toolNames")
    assert(inspectOut.contains("label"), s"inspect(example.Widget) did not include label: $inspectOut")
    assert(tasksOut.contains("compile"), s"list-tasks did not include compile: $tasksOut")
    assert(serverInfo.name == "sbt-mcp", s"unexpected MCP server name: $serverInfo")
    assert(serverInfo.version == artifactVersion, s"runtime version ${serverInfo.version} != $artifactVersion")
    println(s"mcpCheckTools OK: tools=$toolNames, runtimeVersion=${serverInfo.version}, artifactVersion=$artifactVersion")
