// A multi-module build: an aggregating root plus three subprojects, all with the
// plugin auto-triggered. Regression coverage for the single build-wide server and
// for root-active symbol indexing across direct and transitive aggregates.
ThisBuild / scalaVersion := "3.8.4"

Global / mcpEnabled := true
// This fixture intentionally exercises the server even when scripted runs in CI.
Global / mcpDisableInCI := false
Global / mcpPort    := 5098
Global / mcpHost    := "127.0.0.1"
// Keep this test hermetic — don't proxy javadocs.dev over the network.
Global / mcpDocsUrl := None

lazy val b = (project in file("b"))
lazy val a = (project in file("a"))
  .aggregate(b)
lazy val c = (project in file("c"))

lazy val root = (project in file("."))
  .aggregate(a, c)
  .settings(name := "multi-module-test")

// Verify the fix that prevents duplicated status output: `mcpStatus` must not
// aggregate, so invoking it on the root runs it in a single (root) scope and prints
// once. We assert the mechanism directly on the root and on each subproject: if any
// were still aggregating, `mcpStatus` on the root would fan out and print N times.
TaskKey[Unit]("checkStatusNoAggregate", "Assert mcpStatus does not aggregate across modules") := {
  val rootAgg = (root / mcpStatus / aggregate).value
  val aAgg    = (a / mcpStatus / aggregate).value
  val bAgg    = (b / mcpStatus / aggregate).value
  val cAgg    = (c / mcpStatus / aggregate).value
  assert(!rootAgg, s"root/mcpStatus/aggregate should be false, was $rootAgg")
  assert(!aAgg, s"a/mcpStatus/aggregate should be false, was $aAgg")
  assert(!bAgg, s"b/mcpStatus/aggregate should be false, was $bAgg")
  assert(!cAgg, s"c/mcpStatus/aggregate should be false, was $cAgg")
  streams.value.log.info("checkStatusNoAggregate OK: mcpStatus/aggregate=false in root and all subprojects")
}

// Regression check: with the aggregating root active, refresh through the real
// production path and verify both MCP symbol handlers see aggregated subprojects.
// This is a command (rather than an aggregating task) so there is one active project
// and one writer to the process-global symbol index.
commands += Command.command("checkMultiModuleSymbols") { state =>
  import com.jamesward.ziohttp.mcp.*
  import com.jamesward.ziohttp.mcp.client.McpClient
  import zio.*
  import zio.http.*
  import zio.json.ast.Json

  com.jamesward.sbtmcp.SbtMcpPlugin.refreshFromState(state)

  def textOf(result: CallToolResult): String =
    result.content.collect { case ToolContent.Text(text, _) => text }.mkString("\n")

  val extracted = Project.extract(state)
  val url       = s"http://127.0.0.1:${extracted.get(Global / mcpPort)}/"
  val program =
    ZIO
      .scoped {
        for {
          client <- McpClient.connect(url)
          glob <- client.callTool(
            "glob-search",
            Json.Obj(
              "query"     -> Json.Str("AlphaService"),
              "inPackage" -> Json.Str("modulea"),
            ),
          )
          inspect <- client.callTool(
            "inspect",
            Json.Obj("symbol" -> Json.Str("moduleb.BetaService")),
          )
        } yield (textOf(glob), textOf(inspect))
      }
      .retry(Schedule.recurs(25) && Schedule.spaced(200.millis))

  val (globOut, inspectOut) =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(program.provide(Client.default)).getOrThrow()
    }

  assert(
    globOut.contains("modulea.AlphaService") && inspectOut.contains("betaOnly"),
    s"""symbol tools must include aggregated subprojects when root is active:
       |glob-search output:
       |$globOut
       |inspect output:
       |$inspectOut""".stripMargin,
  )
  state.log.info(s"checkMultiModuleSymbols OK: glob=$globOut inspect=$inspectOut")
  state
}
