ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))

// When the project does not compile, refreshFromState must fail (this is what makes
// the symbol tools surface a "does not currently compile" note instead of silently
// returning a stale index).
commands += Command.command("checkRefreshError") { state =>
  val result = scala.util.Try(com.jamesward.sbtmcp.SbtMcpPlugin.refreshFromState(state))
  assert(result.isFailure, s"refreshFromState should fail when the project does not compile, got: $result")
  state.log.info("checkRefreshError OK: refresh failed on a non-compiling project")
  state
}
