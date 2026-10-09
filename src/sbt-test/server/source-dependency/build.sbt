// Working on a dependency from source: this build depends on another checkout (dep/)
// through RootProject. Both builds use sbt-mcp with their own ports. Only one MCP server
// runs per sbt JVM, and it must be this (the root) build's, on 5091.
// The sbt-mcp settings of each build are in mcp.sbt / dep/mcp.sbt, swapped between
// `Global /` and `ThisBuild /` versions by the test script.
scalaVersion := "3.8.4"

lazy val root = (project in file(".")).dependsOn(RootProject(file("dep")))

def listening(port: Int): Boolean =
  try { val s = new java.net.Socket("127.0.0.1", port); s.close(); true }
  catch { case _: java.io.IOException => false }

// checkSourceDependency <expected port> <expect warning: true|false>
commands += Command.args("checkSourceDependency", "<port> <warn>") { (state, args) =>
  val Seq(port, warn) = args
  val warning = com.jamesward.sbtmcp.SbtMcpPlugin.foreignGlobalMcpSettings(Project.extract(state))
  assert(warning.isDefined == warn.toBoolean, s"foreign Global warning expected=$warn, got: $warning")
  warning.foreach(w => assert(w.contains("dep") && w.contains("Global / mcpPort"), s"warning should name the dep build's file: $w"))
  if (port != "any") {
    val other = if (port == "5091") 5092 else 5091
    assert(listening(port.toInt), s"MCP server should listen on $port")
    assert(!listening(other), s"nothing should listen on $other")
  }
  state.log.info(s"checkSourceDependency OK: server on $port, warning=${warning.isDefined}")
  state
}
