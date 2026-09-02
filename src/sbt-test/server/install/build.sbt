scalaVersion := "3.8.4"

// Name starts with `sbt-mcp` on purpose: exercises the guard that avoids a doubled
// `sbt-mcp-sbt-mcp-…` server name when deriving it from the project name.
lazy val root = (project in file("."))
  .settings(
    name := "sbt-mcp-install-check",
    // The instructions task must work whether or not the server is enabled; keep it
    // disabled here so the test is hermetic (no port bound, no javadocs.dev proxy).
    Global / mcpEnabled := false,
    Global / mcpDocsUrl := None,
  )

// Assert `mcpInstall` printed the agent-facing instructions. The task logs via
// streams.log, which sbt mirrors to the global log backing file; read that file and
// check the expected content is present (and the server name isn't double-prefixed).
TaskKey[Unit]("checkInstall", "Verify mcpInstall printed the expected instructions") := Def.uncached {
  val log = streams.value.log
  val txt = IO.read(state.value.globalLogging.backing.file)

  assert(txt.contains("=== sbt-mcp install instructions (for an AI agent) ==="),
    "mcpInstall did not print the instructions banner")
  assert(txt.contains("\"mcpServers\""),
    "mcpInstall did not print an MCP client config block")
  assert(txt.contains("\"sbt-mcp-install-check\""),
    "mcpInstall did not use the derived server name in the config block")
  assert(txt.contains("named `sbt-mcp-install-check` for ALL sbt interactions"),
    "mcpInstall did not print the AGENTS.md usage guidance")
  assert(txt.contains("`glob-search`") && txt.contains("`sbt-task`"),
    "mcpInstall did not mention the core tools")
  assert(!txt.contains("sbt-mcp-sbt-mcp"),
    "mcpInstall produced a doubled `sbt-mcp-` server-name prefix")

  log.info("checkInstall OK: mcpInstall instructions present, server name not double-prefixed")
}
