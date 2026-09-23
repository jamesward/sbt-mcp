// A Toolbook-shaped multi-module build: an aggregating root, application
// subprojects that depend on a shared module, Scala 3.9 project code, and most
// symbols in the empty package. It also retains a transitive aggregate edge so
// both traversal shapes are covered while the plugin itself remains on sbt 2.0's
// Scala 3.8 runtime.
ThisBuild / scalaVersion := "3.9.0"

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
  .dependsOn(b)
lazy val c = (project in file("c"))
  .dependsOn(b)

lazy val root = (project in file("."))
  .aggregate(a, b, c)
  .settings(
    name := "multi-module-test",
    scalaVersion := "3.8.4",
  )

lazy val harness = (project in file("harness"))
  .settings(
    publish / skip := true,
    scalaVersion := "3.9.0",
    Compile / run / fork := true,
    libraryDependencies += "com.jamesward" %% "zio-http-mcp" % "0.8.2",
  )

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

// Seed the process-global index while the aggregating root is active. The forked
// harness performs the HTTP assertions afterward with zio-http-mcp 0.8.2.
commands += Command.command("prepareMultiModuleSymbols") { state =>
  com.jamesward.sbtmcp.SbtMcpPlugin.refreshFromState(state)
  val readerVersion  = com.jamesward.sbtmcp.SymbolIndexState.activeReaderVersion
  val runtimeVersion = com.jamesward.sbtmcp.SymbolIndexState.activeRuntimeScalaVersion
  assert(readerVersion.exists(_.startsWith("1.9")), s"Scala 3.9 project must use isolated TASTy Query 1.9, got: $readerVersion")
  assert(runtimeVersion.exists(_.startsWith("3.9")), s"isolated reader must use the Scala 3.9 project runtime, got: $runtimeVersion")
  state.log.info("prepareMultiModuleSymbols OK: Scala 3.9 index is ready")
  state
}
