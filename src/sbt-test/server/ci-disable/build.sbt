ThisBuild / scalaVersion := "3.8.4"

Global / mcpEnabled := true
Global / mcpPort    := 5096
Global / mcpHost    := "127.0.0.1"
Global / mcpDocsUrl := None

TaskKey[Unit]("checkCiDisable", "Verify the default CI startup guard") := {
  val disableInCI = (Global / mcpDisableInCI).value
  assert(disableInCI, s"mcpDisableInCI should default to true, was $disableInCI")

  val inCI = sys.env.get("CI").exists { rawValue =>
    val value = rawValue.trim
    value.nonEmpty && value != "0" && !value.equalsIgnoreCase("false")
  }
  if (inCI) {
    val socket = new java.net.ServerSocket()
    try socket.bind(new java.net.InetSocketAddress("127.0.0.1", (Global / mcpPort).value))
    finally socket.close()
  }

  streams.value.log.info(s"checkCiDisable OK: default=$disableInCI, inCI=$inCI")
}
