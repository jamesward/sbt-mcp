# sbt-mcp

> [!WARNING]
> **sbt-mcp is deprecated. Use [sbta](https://github.com/jamesward/sbta) instead.**
>
> sbta is an sbt launcher (`./sbta` is sbt) whose `./sbta --mcp` is an MCP server for
> the build, with the same tools: `compile`, `test`, `run`, `check`, the symbol tools,
> `sbt-task` and the rest. It needs **no plugin**, works with **sbt 1.4+ and 2.x**, and
> sets up with one command for each agent: `./sbta --mcp --setup kiro|claude|cursor`.
>
> What building sbt-mcp taught us, and why sbta replaces it:
>
> - **An MCP server inside the build is the wrong place.** It needs a plugin in every
>   build, `mcpEnabled`/`mcpDisableInCI` settings, and care with `Global` vs `ThisBuild`.
>   It also restarts on every `reload`, dropping calls in flight, and runs one server
>   per sbt JVM, which source dependencies can override.
> - **MCP clients connect before sbt is up.** Bridging that took a committed launcher
>   (`.sbt-mcp/SbtMcpStdio.java`) with a snapshot of the tool list, which had to be
>   regenerated on every upgrade. A plain sbt task can't own the stdio pipe: sbt
>   prints its own output on stdout, and a long-running task blocks the command loop
>   the tools need.
> - **sbt already has a server.** Its protocol (`sbt/exec`, `sbt/attach`,
>   `sbt/cancelRequest`, `sbt/setting`) and BSP on the same user-only socket provide what
>   the tools need, on sbt 1 and 2: commands with their own console output, compiler
>   diagnostics, test and main classes, classpaths, and cancellation. Test results come
>   from the JUnit XML sbt writes by default, dependencies from its update report, and
>   aggregation from `show`. So a client like `sbtn` can do everything the plugin did.
> - **One launcher is simpler.** sbta embeds sbt's own launcher, so it replaces `./sbt`
>   rather than sitting beside it. It starts sbt when needed (shared with your own
>   commands) and survives `reload`. The `check` and symbol engines from this
>   plugin now run in sbta, fed from BSP.
>
> sbt-mcp gets no further releases. To migrate, remove `addSbtPlugin("com.jamesward" % "sbt-mcp" % …)`,
> the `mcp*` settings and `.sbt-mcp/`, then follow sbta's
> [agent setup](https://github.com/jamesward/sbta#agent-setup).

[![javadocs.dev](https://www.javadocs.dev/com.jamesward/sbt-mcp_sbt2_3/badge.svg?1)](https://www.javadocs.dev/com.jamesward/sbt-mcp_sbt2_3/latest)

An **opt-in, off-by-default** [Model Context Protocol](https://modelcontextprotocol.io/)
server embedded in an sbt 2.x build. When enabled, it starts an MCP server inside the sbt
JVM and exposes tools an AI agent can call.

Structured build tools return JSON (`structuredContent`) that matches each tool's
declared `outputSchema`, and take typed, validated arguments:

| Tool            | What it does                                                               |
|-----------------|----------------------------------------------------------------------------|
| `compile`       | Compile (`configuration`: `compile`/`test`); compiler errors and warnings as `{severity, file, line, column, message}` |
| `test`          | Run tests (incremental by default, `full` for all, `tests` for specific classes/globs, `frameworkArgs`); pass/fail counts and each failure's suite, test and message |
| `run`           | Run a main class (forked, stdin disconnected) with `args`; returns the program's `output` |
| `clean`         | Delete build outputs                                                       |
| `publish-local` | Publish to the local Ivy repository; returns the published coordinates     |
| `projects`      | The build's projects: ids, base dirs, versions, Scala version, aggregation, `dependsOn` (answers even while sbt is busy) |
| `test-names`    | Test classes per project, for `test`'s `tests`                             |
| `main-classes`  | Main classes per project and the one `run` uses                            |
| `dependencies`  | Declared, resolved (direct/transitive) and evicted library dependencies    |
| `show`          | A setting's value, or a task's result, as text and JSON                    |

The action tools (`compile`, `test`, `clean`, `publish-local`) take an optional
`project` and, like the sbt shell, also run on the projects it aggregates. Every action
result has `command`, `success`, `elapsedMs`, `errorCount`, `warningCount`,
`problems`, and the captured `log`.

General and code tools:

| Tool          | What it does                                                                 |
|---------------|------------------------------------------------------------------------------|
| `sbt-task`    | Run any other sbt command/task (works during a `~` watch); returns `[ok]`/`[error]` + captured compiler output, and each failed test's name and assertion message |
| `list-tasks`  | List the build's tasks/settings with descriptions (optional `all` flag; per-task `help` via `task`) |
| `glob-search` | Search Scala 3 symbols by name (TASTy); `query:"*"` + `inPackage` lists ALL symbols in a package |
| `inspect`     | List a symbol's members and (approximate) signatures (TASTy)                 |
| `symbol-location` | Return a symbol's source location as `path:line` (from its TASTy tree position) |
| `check`       | Fast syntax + type check of Scala 3 files **without compiling** (the project's own compiler, warm and in-process; doesn't wait behind a build) |

Plus **documentation tools proxied from [javadocs.dev](https://www.javadocs.dev/mcp)**:
its tools are merged into the tool list, and any tool call that doesn't match a
built-in above is forwarded there (so you get JavaDoc/ScalaDoc lookup without this
plugin maintaining that tool set). Configure or disable via `mcpDocsUrl`.

## Install

```scala
// project/plugins.sbt
addSbtPlugin("com.jamesward" % "sbt-mcp" % "<version>")
```

The plugin triggers on all JVM projects but does nothing until you enable it.

## Enable

```scala
// build.sbt (or a local, git-ignored dev override)
ThisBuild / mcpEnabled     := true        // default: false
ThisBuild / mcpDisableInCI := true        // default: true; set false to allow startup in CI/Heroku
// Optional: also serve HTTP on a port (off by default)
ThisBuild / mcpPort        := Some(5010)  // default: None (stdio launcher only)
ThisBuild / mcpHost        := "127.0.0.1" // default: loopback only; only with mcpPort
```

Use `ThisBuild /` (or plain root-project settings), not `Global /`. Global is one scope
shared by every build sbt loads, so when this build depends on another checkout from
source (`RootProject(file("../other"))` or `ProjectRef`) and that build sets
`Global / mcpEnabled`, it can replace yours. Only one MCP server runs per sbt JVM, and it's
always the one configured by the build you launched (its root project); a source
dependency's own sbt-mcp settings are ignored. sbt-mcp warns at load when another
build's `Global /` setting would win. To use the dependency's MCP server too, run sbt in
its own checkout, where it has its own server.

When enabled, the server starts on the next sbt load and prints:

```
sbt-mcp: MCP server started (socket ~/.sbt/2/sbt-mcp/<hash>/mcp.sock, for the stdio launcher)
```

The server listens on a Unix domain socket that only your user can open, speaking
MCP's stdio framing (one JSON-RPC message per line). MCP clients reach it through the
[stdio launcher](#stdio-launcher), which also starts sbt when it isn't running. With
`mcpPort := Some(5010)` it also serves Streamable HTTP on `http://127.0.0.1:5010/`, for
clients that only take a URL. `mcpStatus` prints whether the server is running and
where.

### stdio launcher

MCP clients connect to their servers when a session starts, which is usually before
anyone has started sbt. The stdio launcher is what the client starts: it connects to
the build's server, and starts sbt if needed.

```bash
./sbt mcpInstallStdio
```

writes `.sbt-mcp/SbtMcpStdio.java` and `.sbt-mcp/snapshot.json` (commit both) and
prints the client entry:

```json
{
  "mcpServers": {
    "sbt-mcp-myproject": {
      "type": "stdio",
      "command": "java",
      "args": [".sbt-mcp/SbtMcpStdio.java"],
      "timeout": 1800000
    }
  }
}
```

The launcher is a single Java file with no dependencies, run by the JDK's source
launcher (JDK 17+). It relays messages between the client and the server's socket.
When sbt isn't running it starts it with `sbt --client mcpStatus` (sbt's own
background server, the one later `./sbt` commands connect to; it keeps running after
the client exits). Until the server is up, it answers `initialize`,
`server/discover` and `tools/list` from the snapshot, so the client connects and
lists the built-in tools within seconds; tool calls wait for sbt. Once sbt is up, a
changed tool list (e.g. the proxied javadocs.dev tools) is announced with
`notifications/tools/list_changed`. A `reload` restarts the server: a call running
across it fails with a "connection was lost" error (it may or may not have
completed), and calls made meanwhile wait for the restarted server. It speaks the
legacy (`initialize`) protocol revisions and 2026-07-28.

- `--start=lazy` starts sbt on the first request that needs it instead of right
  away; `--start=never` only connects to an sbt started some other way.
- It finds the server's socket through `project/target/sbt-mcp/server.json`, which
  sbt-mcp writes while it runs.
- Diagnostics: `project/target/sbt-mcp/stdio.log` (the launcher) and
  `project/target/sbt-mcp/boot.log` (sbt's startup output).
- Re-run `mcpInstallStdio` after upgrading sbt-mcp; sbt warns at startup when the
  committed files are out of date.
- Clients start project servers in the project's root directory. If yours starts
  them elsewhere, use an absolute path to the `.java` file and add
  `--project-dir=<build root>`.

`mcpInstall` prints copy-pasteable onboarding for an AI agent: how to register this
server in the local MCP client config (Kiro / Claude Code / Cursor), with the stdio
launcher (or HTTP, which needs `mcpPort`), and the `AGENTS.md` usage guidance to adopt (drive sbt through
the `compile` / `test` / … tools and `sbt-task`, use `glob-search` / `inspect` / `symbol-location` for symbols).
It is deliberately non-mutating: it does not inspect or edit existing config, so
reconcile any existing key that uses the same URL or command manually. The generated
client timeout is 30 minutes, matching the server-side task wait.

Run it directly or via the `sbt-task` tool so the text is returned to the agent.
For plain direct capture without sbt supershell/logger control sequences, use:

```bash
./sbt --server --no-colors --supershell=false mcpInstall
```

`mcpInstall` does not daemonize sbt. A one-shot invocation exits after printing and
stops its embedded server. The stdio launcher starts a long-lived sbt itself; with
HTTP, keep a long-lived sbt session loaded while clients use MCP.

> **With HTTP, reconnect your MCP client after enabling (or after a `reload`).** MCP
> clients fetch the tool list once at connect time, so an HTTP client that connected
> before the server started, or before a `reload` restarted it, won't show the tools
> (or the proxied javadocs.dev tools). The stdio launcher handles this itself.

## Usage

- **Symbols refresh automatically.** `glob-search` / `inspect` / `symbol-location`
  bring the index up to date before each query — there's no manual refresh step.
- **Glob search is project-scoped.** It enumerates symbols from the active project's
  output and transitive aggregated subprojects, not every dependency jar. Dependencies
  remain on the TASTy resolution classpath and can still be queried exactly with
  `inspect` / `symbol-location`. Searches are additionally bounded by root, symbol,
  result, and wall-clock budgets.
- **List all symbols in a package.** To discover symbols (including newly-added
  ones) without knowing their names, call `glob-search` with `query:"*"` (or empty)
  and `inPackage` set, e.g. `{"query":"*","inPackage":"com.example"}`.
- **Stale-index note.** If the project doesn't currently compile, the symbol tools
  still answer from the last successful compile and append a
  `(note: the project does not currently compile …)` line — a signal to fix the
  build (e.g. via the `compile` tool) before new symbols will appear.
- **Fast checks while editing.** `check` validates Scala 3 sources without
  compiling, typically in a few hundred milliseconds:
  `{"files":["app/src/main/scala/app/Service.scala"]}`. It runs the module's own
  Scala compiler, kept loaded and JIT-warm in-process, through every checking phase
  (parser, typer, macro/inline expansion, override and abstract-member checks,
  erasure clashes, `@tailrec`, pattern-match exhaustivity, unused/deprecation
  warnings) with the module's classpath and `scalacOptions`, stopping only before bytecode generation.
  It never writes class files and runs off sbt's command loop, so it doesn't queue
  behind a running compile or `~` watch.
  - `content` checks unsaved text instead of the file on disk (one file).
  - Sources of the same module edited since the last compile are checked along with
    the requested files automatically (their compiled classes are stale).
  - `scope:"module"` re-checks every source of the module — use it after changing
    an API to find breakage in the files that *use* it.
  - Upstream modules are compiled as needed so their current API is visible; if one
    doesn't compile, its last compiled classes are used (with a note).
  - Still run `compile` / `test` before finishing: downstream modules,
    Java sources, and code generation are only covered by a real build.
- **Building and testing.** `compile` and `test` return diagnostics and test results
  as data, e.g. `{"project":"core","configuration":"test"}` or
  `{"tests":["com.example.MySpec"]}`. They run on sbt's command loop like `sbt-task`
  (so they work during a `~` watch and wait behind a running command). Notes:
  - `test` without arguments is sbt 2's incremental `test`: only tests that failed
    before, haven't run, or whose code changed. Pass `"full":true` to run all.
  - Compiles are incremental, so warnings in unchanged files aren't reported again.
  - `column` is 0-based, as sbt prints it.
  - `show` runs the task it is given (with its side effects) and doesn't aggregate.
    Input tasks (`testOnly`, `runMain`, …) aren't supported by `show`; use `sbt-task`.
- **Running other tasks.** `sbt-task` (e.g. `{"command":"reload"}`,
  `{"command":"scalafmtAll"}`, `{"command":"clean; compile"}`) runs any command line on
  sbt's command loop and works even while a `~` watch is active. It returns status, elapsed milliseconds, and
  either captured compiler output or an explicit `captured output: empty` marker.
  It works in an interactive sbt shell and in a background server started by
  `sbt --client` (no client needs to stay connected); if another command is running it
  waits its turn.

### Multi-project builds

`mcpEnabled` / `mcpDisableInCI` / `mcpPort` / `mcpHost` are **global** settings
and exactly one MCP
server runs per build (shared by all subprojects and clients). The symbol index
tracks a single active project — the current project when a symbol tool is invoked.
When that project aggregates other projects, its index includes all transitive
aggregates; selecting a leaf project scopes queries to that leaf's classpath.
Because the server is a single build-wide instance, `mcpStatus` does not aggregate
across subprojects — it prints one status line even on an aggregating root.

## Configuration

| Setting          | Default       | Meaning                                  |
|------------------|---------------|------------------------------------------|
| `mcpEnabled`     | `false`       | Start the embedded MCP server on load    |
| `mcpDisableInCI` | `true`        | Do not start when `CI` is truthy or `SOURCE_VERSION` identifies a Heroku build; use `false` to override |
| `mcpPort`        | `None`        | `Some(port)` also serves Streamable HTTP on `mcpHost:port` |
| `mcpHost`        | `"127.0.0.1"` | Interface for HTTP (with `mcpPort`; keep on loopback) |
| `mcpDocsUrl`     | `Some("https://www.javadocs.dev/mcp")` | Upstream MCP server to proxy/merge; `None` disables |

Tasks: `mcpStatus` (server status), `mcpInstall` (onboarding text for an agent),
`mcpInstallStdio` (write or update the [stdio launcher](#stdio-launcher)).

## Security

- **Off by default.** Nothing listens unless `mcpEnabled := true`.
- **Disabled in automated builds by default.** Even when enabled, the server does
  not start when `CI` is truthy or Heroku provides a nonblank `SOURCE_VERSION`, unless
  `mcpDisableInCI := false`.
- **User-only socket by default.** The server listens on a Unix domain socket in an
  owner-only (0700) directory under sbt's global base (`~/.sbt/2/sbt-mcp/`), with the
  socket itself 0600, like sbt's own server socket. Other users can't connect.
- **HTTP is opt-in and unauthenticated.** With `mcpPort := Some(port)` the server also
  binds `127.0.0.1:port`, which **any local process or user** can reach. Do **not** set
  `mcpHost` to `0.0.0.0`.
- **The tools can run arbitrary code** (`sbt-task`, `run`, `show`): treat any way in
  as a local, dev-only RCE surface. `zio-http-mcp` supports OAuth/token auth
  (`McpAuth`); wire it in before any non-loopback HTTP use.
- **Outbound proxy.** When enabled, tool listing/calls make an outbound request to
  the `mcpDocsUrl` upstream (javadocs.dev by default) to merge/forward its tools. Set
  `mcpDocsUrl := None` to keep everything local (no outbound network).

## Troubleshooting

- **Only the built-in tools show; no javadocs.dev tools.** Reconnect the MCP client
  after the server (re)starts (see the note under *Enable*). If they still don't
  appear, the upstream connection likely failed — the sbt console logs
  `sbt-mcp: docs proxy (…) listTools failed: <reason>` (network / proxy / firewall).
  Set `mcpDocsUrl := None` to disable the proxy.
- **Symbol tools return nothing / say "not available".** The project must compile so
  its `.tasty` exists. If it doesn't compile, the tools answer from the last good
  compile and append a `(note: the project does not currently compile …)` line — fix
  the build (e.g. `compile` tool) and query again.
- **The stdio launcher doesn't connect.** Read `project/target/sbt-mcp/stdio.log`
  and `project/target/sbt-mcp/boot.log`. A tool call that fails with "sbt-mcp is not
  running after starting sbt" means sbt started but the server didn't: check
  `ThisBuild / mcpEnabled := true` (and `mcpDisableInCI` in CI).
- **Tools change after editing the build/plugin.** When consuming the plugin from
  source, `reload` (or restart sbt) to recompile it, then reconnect the client.

## Requirements

- sbt 2.0.0+
- Scala 3 projects (the TASTy-based tools rely on `.tasty` output)

## Development

See [DEV.md](DEV.md) for architecture, implementation notes, limitations, and how
to run/test the plugin (including the standalone launcher, the scripted tests, and
the source-consuming [`test-project/`](test-project/README.md)).

## License

Apache-2.0
