scalaVersion := "3.8.4"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio-test"     % "2.1.26" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.1.26" % Test,
)

// A forked test JVM, like most real builds: its stdout never reaches sbt's log.
Test / fork := true

// Runs a failing `testOnly` the way `sbt-task` does and asserts the response names the
// failed test and its assertion message, which the test framework only printed to stdout.
commands += Command.command("mcpTestVariants") { state =>
  val (_, result, out) = com.jamesward.sbtmcp.SbtMcpPlugin.runForTool(state, "Test/testOnly FailingSpec")
  assert(result.isLeft, s"testOnly should fail, got: $result\n$out")
  assert(out.contains("sbt-mcp test FAILED: FailingSpec / compares words"), s"missing failed test name:\n$out")
  assert(out.contains("compares words"), s"missing test name:\n$out")
  assert(out.contains("\"beta\" was not equal to \"alpha\""), s"missing assertion message:\n$out")
  assert(!out.contains("FailingSpec / passes"), s"passing test reported:\n$out")

  // Outside an sbt-task call the listener stays silent (console runs aren't duplicated).
  val (_, _, plain) = sbt.McpInProcess.runOnLoop(state, "Test/testOnly FailingSpec")
  assert(!plain.contains("sbt-mcp test FAILED"), s"listener active outside sbt-task:\n$plain")

  state.log.info("mcpTestVariants OK")
  state
}
