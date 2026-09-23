ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))
  .settings(
    name := "docs-proxy-test",
    Global / mcpEnabled := true,
    // This fixture intentionally exercises the server even when scripted runs in CI.
    Global / mcpDisableInCI := false,
    Global / mcpPort    := 5099,
    Global / mcpHost    := "127.0.0.1",
    // Proxy a LOCAL upstream MCP server (started by the harness) instead of
    // javadocs.dev, so the test is hermetic (no external network).
    Global / mcpDocsUrl := Some("http://127.0.0.1:5198/"),
  )

// MCP client/server test code must not enter sbt's Scala 3.8 meta-build
// classloader. Compile and run it in a forked Scala 3.9 process instead.
lazy val harness = (project in file("harness"))
  .settings(
    publish / skip := true,
    scalaVersion := "3.9.0",
    Compile / run / fork := true,
    libraryDependencies += "com.jamesward" %% "zio-http-mcp" % "0.8.2",
  )
