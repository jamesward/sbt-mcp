ThisBuild / scalaVersion := "3.8.4"

// Three compile variants: success, warning-but-compiles, and failure.
lazy val ok   = (project in file("ok"))
lazy val warn = (project in file("warn"))
lazy val fail = (project in file("fail"))

lazy val root = (project in file(".")).aggregate(ok, warn, fail)

// Runs `<variant>/compile` in-process (the same path `sbt-task` uses) and asserts
// we capture the compiler OUTPUT for all three cases — not just success/failure.
commands += Command.command("mcpCompileVariants") { state =>
  def run(cmd: String) = sbt.McpInProcess.runOnLoop(state, cmd)

  // 1) success
  val (_, r1, out1) = run("ok/compile")
  assert(r1.isRight, s"ok/compile should succeed, got: $r1\n$out1")

  // 2) warning: compiles (Right), but the warning text must be captured
  val (_, r2, out2) = run("warn/compile")
  assert(r2.isRight, s"warn/compile should succeed (warning, not error), got: $r2\n$out2")
  assert(
    out2.toLowerCase.contains("exhaustive"),
    s"warn/compile output should contain the exhaustivity warning:\n$out2"
  )

  // 3) failure: fails (Left), and the error text must be captured
  val (_, r3, out3) = run("fail/compile")
  assert(r3.isLeft, s"fail/compile should fail, got: $r3\n$out3")
  assert(
    out3.nonEmpty && (out3.contains("Found") || out3.toLowerCase.contains("error")),
    s"fail/compile output should contain the compile error:\n$out3"
  )

  state.log.info("mcpCompileVariants OK: captured success, warning, and error output")
  state.log.info(s"[warn/compile captured]\n$out2")
  state.log.info(s"[fail/compile captured]\n$out3")
  state
}
