ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))
  .settings(
    name := "server-tools-test",
    Global / mcpEnabled := true,
    // This fixture intentionally exercises the server even when scripted runs in CI.
    Global / mcpDisableInCI := false,
    Global / mcpPort    := 5099,
    Global / mcpHost    := "127.0.0.1",
    Global / mcpDocsUrl := None,
  )

lazy val harness = (project in file("harness"))
  .settings(
    publish / skip := true,
    scalaVersion := "3.9.0",
    Compile / run / fork := true,
    Compile / run / javaOptions += s"-Dplugin.version=${sys.props("plugin.version")}",
    libraryDependencies += "com.jamesward" %% "zio-http-mcp" % "0.8.2",
  )

// The command loop is busy while the forked harness runs, so prepare the symbol
// index first using only plugin APIs available to the Scala 3.8 meta-build.
TaskKey[Unit]("mcpPrepareTools", "Prepare the symbol index for the forked MCP harness") := Def.uncached {
  val converter = fileConverter.value
  val cp        = (Compile / fullClasspathAsJars).value
  com.jamesward.sbtmcp.SymbolIndexState.update(
    thisProject.value.id,
    cp.map(a => converter.toPath(a.data)).toList,
    cp.map(_.data.contentHashStr).toVector,
    scalaVersion.value,
    cp.headOption.map(a => converter.toPath(a.data)).toList,
  )
  streams.value.log.info("mcpPrepareTools OK: symbol index is ready")
}

commands += Command.command("mcpInJvmCheck") { state =>
  val (s1, okResult, _)  = sbt.McpInProcess.runOnLoop(state, "compile")
  assert(okResult.isRight, s"in-process `compile` failed: $okResult")
  val (_, badResult, _)  = sbt.McpInProcess.runOnLoop(s1, "totallyNotACommand_xyz")
  assert(badResult.isLeft, s"expected bogus command to fail, got: $badResult")
  state.log.info("mcpInJvmCheck OK: in-process compile succeeded, bogus command failed")
  s1
}
