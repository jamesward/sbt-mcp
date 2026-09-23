ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))
  .settings(
    name := "multi-protocol-test",
    Global / mcpEnabled := true,
    // This fixture intentionally exercises the server even when scripted runs in CI.
    Global / mcpDisableInCI := false,
    Global / mcpPort    := 5097,
    Global / mcpHost    := "127.0.0.1",
    // Proxy a LOCAL upstream MCP server started by the forked harness.
    Global / mcpDocsUrl := Some("http://127.0.0.1:5197/"),
  )

lazy val harness = (project in file("harness"))
  .settings(
    publish / skip := true,
    scalaVersion := "3.9.0",
    Compile / run / fork := true,
    libraryDependencies += "com.jamesward" %% "zio-http-mcp" % "0.8.2",
  )
