ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))
  .settings(
    name := "server-tools-test",
    Global / mcpEnabled := true,
    // This fixture intentionally exercises the server even when scripted runs in CI.
    Global / mcpDisableInCI := false,
    Global / mcpPort    := 5099,
    Global / mcpHost    := "127.0.0.1",
    // Keep this test hermetic — don't proxy javadocs.dev over the network.
    Global / mcpDocsUrl := None,
  )

// Integration check: connect to the embedded MCP server (started on load because
// mcpEnabled := true), assert the expected tools are advertised, and that a real
// `inspect` call reads this project's TASTy. Uses the plugin's own transitive
// deps (zio-http-mcp client, zio, zio-http, zio-json), which are on the build's
// classpath via the plugin.
TaskKey[Unit]("mcpCheckTools", "Verify the MCP server advertises tools and inspect works") := Def.uncached {
  import com.jamesward.ziohttp.mcp.*
  import com.jamesward.ziohttp.mcp.client.McpClient
  import zio.*
  import zio.http.*
  import zio.json.ast.Json

  val log  = streams.value.log
  val port = (Global / mcpPort).value
  val url  = s"http://127.0.0.1:$port/"

  // The symbol tools auto-refresh the index only when the command loop is idle; here
  // the loop is busy running this task, so seed the index directly from task inputs.
  locally {
    val converter = fileConverter.value
    val cp        = (Compile / fullClasspathAsJars).value
    com.jamesward.sbtmcp.SymbolIndexState.update(
      thisProject.value.id,
      cp.map(a => converter.toPath(a.data)).toList,
      cp.map(_.data.contentHashStr).toVector,
    )
  }

  def textOf(r: CallToolResult): String =
    r.content.collect { case ToolContent.Text(t, _) => t }.mkString("\n")

  val prog =
    ZIO
      .scoped {
        for {
          client  <- McpClient.connect(url)
          tools   <- client.listTools
          inspect <- client.callTool("inspect", Json.Obj("symbol" -> Json.Str("example.Widget")))
          tasks   <- client.callTool("list-tasks", Json.Obj())
        } yield (tools.map(_.name.toString).toSet, textOf(inspect), textOf(tasks), client.serverInfo)
      }
      .retry(Schedule.recurs(25) && Schedule.spaced(200.millis))

  val (toolNames, inspectOut, tasksOut, serverInfo) =
    Unsafe.unsafe { implicit u => Runtime.default.unsafe.run(prog.provide(Client.default)).getOrThrow() }

  val required        = Set("sbt-task", "list-tasks", "glob-search", "inspect", "symbol-location")
  val artifactVersion = sys.props("plugin.version")
  assert(required.subsetOf(toolNames), s"missing MCP tools; got: $toolNames")
  assert(inspectOut.contains("label"), s"inspect(example.Widget) did not include 'label':\n$inspectOut")
  assert(tasksOut.contains("compile"), s"list-tasks did not include 'compile':\n$tasksOut")
  assert(serverInfo.name == "sbt-mcp", s"unexpected MCP server name: $serverInfo")
  assert(
    serverInfo.version == artifactVersion,
    s"runtime MCP version must match the loaded plugin artifact: $serverInfo vs $artifactVersion"
  )
  log.info(s"mcpCheckTools OK: tools=$toolNames, runtimeVersion=${serverInfo.version}, artifactVersion=$artifactVersion")
}

// Verify the in-process command primitive that `sbt-task` uses: run a real command
// (`compile`) via sbt.McpInProcess and assert success, then a bogus command and
// assert failure. Defined as a command so it runs on the loop thread.
commands += Command.command("mcpInJvmCheck") { state =>
  val (s1, okResult, _)  = sbt.McpInProcess.runOnLoop(state, "compile")
  assert(okResult.isRight, s"in-process `compile` failed: $okResult")
  val (_, badResult, _)  = sbt.McpInProcess.runOnLoop(s1, "totallyNotACommand_xyz")
  assert(badResult.isLeft, s"expected bogus command to fail, got: $badResult")
  state.log.info("mcpInJvmCheck OK: in-process compile succeeded, bogus command failed")
  s1
}
