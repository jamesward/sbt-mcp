## Build, Test & Dev Workflow

- Use the MCP server named `sbt-mcp-test-project` for ALL sbt interactions. Run
  commands/tasks through its `sbt-task` tool; use `list-tasks` to discover
  tasks/settings or get per-task help. Do not invoke `sbt`, a shell, or a
  separate sbt client when the MCP tool is available. If the MCP server is
  unavailable, state that clearly and use a direct CLI fallback only when
  necessary. Separate multiple sbt commands with `;`.

- Use `sbt-mcp-test-project` for Scala/classpath symbol work: `glob-search` to
  find/list symbols, `inspect` for members/signatures, and `symbol-location`
  for source locations. Prefer these over text search, dependency-jar
  inspection, or guessed APIs whenever the question is about Scala symbols.

- JavaDoc/ScalaDoc lookups are available through the same server via its
  proxied javadocs.dev tools.
