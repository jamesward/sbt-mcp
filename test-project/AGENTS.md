## Build, Test & Dev Workflow

- Use the MCP server named `sbt-mcp-test-project` for ALL sbt interactions. Build,
  test and run with its structured tools: `compile`, `test` (`tests` for
  specific classes, `full` for all), `run`, `clean`, `publish-local`; inspect
  the build with `projects`, `test-names`, `main-classes`, `dependencies` and
  `show` (a setting or task value). They return errors, warnings and test
  failures as data. Use `sbt-task` for any other command (e.g. `reload`) and
  `list-tasks` to discover tasks. Do not invoke `sbt`, a shell, or a
  separate sbt client when the MCP tools are available. If the MCP server is
  unavailable, state that clearly and use a direct CLI fallback only when
  necessary.

- After editing Scala 3 sources, validate them with the `check` tool of
  `sbt-mcp-test-project` (pass the edited files; it is a fast, compile-free type
  check). Use `"scope":"module"` after changing an API. Run the `compile`
  and `test` tools before declaring the work done.

- Use `sbt-mcp-test-project` for Scala/classpath symbol work: `glob-search` to
  find/list symbols, `inspect` for members/signatures, and `symbol-location`
  for source locations. Prefer these over text search, dependency-jar
  inspection, or guessed APIs whenever the question is about Scala symbols.

- JavaDoc/ScalaDoc lookups are available through the same server via its
  proxied javadocs.dev tools.
