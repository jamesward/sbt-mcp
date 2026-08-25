# test-project

A tiny build that uses **sbt-mcp from source** (no `publishLocal`) so you can
manually exercise the plugin end-to-end. `project/plugins.sbt` depends on the
plugin project two directories up:

```scala
lazy val root = (project in file(".")).dependsOn(RootProject(file("../..")))
```

`build.sbt` enables the server (`Global / mcpEnabled := true`, port `5010`). The
plugin auto-triggers on this JVM project.

## Manual test

1. Start an **interactive** sbt session (the server runs while sbt is up):

   ```
   cd test-project
   sbt
   ```

   On load you'll see:

   ```
   sbt-mcp: MCP server started at http://127.0.0.1:5010/ (loopback, no auth — dev use only)
   ```

2. Build the sample app (the symbol tools index automatically on first use):

   ```
   sbt:sbt-mcp-test-project> compile
   sbt:sbt-mcp-test-project> mcpStatus
   ```

3. From your MCP client (Claude Code, Cursor, …), connect to
   `http://127.0.0.1:5010/` and call the tools:

   - `glob-search` `{ "query": "Widget", "inPackage": "example" }`
   - `inspect` `{ "symbol": "example.Main" }` (or `example.Widget`)
   - `list-tasks` `{}` (or `{ "task": "compile" }` for one task's help)
   - `sbt-task` `{ "command": "compile" }` or `{ "command": "test" }`
     — queued onto sbt's command loop and run there. It works even while a
     `~` watch is running (it runs between the watch's triggers).

   **Kiro CLI**: a workspace MCP config is already provided at
   [`.kiro/settings/mcp.json`](.kiro/settings/mcp.json), so running `kiro chat`
   from this directory auto-connects to the `sbt-mcp` server (verify with `/mcp`).
   The equivalent Claude Code registration is:

   ```
   claude mcp add --transport http sbt-mcp http://127.0.0.1:5010/
   ```

The sample sources under `src/main/scala/example` (`Main`, `greeting`, `Widget`,
`Greeter`) give the TASTy tools real symbols to return.

> Note: keep sbt running while you drive the tools — the MCP server lives inside
> the sbt JVM and stops on `exit`/`reload`.
