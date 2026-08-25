# sbt-mcp — Development

Architecture, implementation notes, limitations, and how to build/test the plugin.
For user-facing install/usage, see [README.md](README.md).

## Build

```
sbt compile          # main sources; warnings are errors (-Werror)
sbt Test/compile     # test sources (includes the standalone launcher)
sbt scripted         # all scripted integration tests
```

Pinned versions: sbt **2.0.6**, zio-http-mcp **0.5.3** (pulls zio 2.1.26 /
zio-http 3.11.3 / zio-schema 1.8.6), tasty-query **1.8.0**, Scala **3.8.4**.

## Architecture

- **One server per build.** sbt runs a single server JVM shared by every
  subproject and every connected client, so the plugin starts **exactly one** MCP
  server for the whole build:
  - lifecycle hooks live on `Global / onLoad` / `Global / onUnload` (fire once per
    build load/unload, not once per aggregated project);
  - `mcpEnabled` / `mcpPort` / `mcpHost` are **global** settings;
  - the server handle is a process-global `AtomicReference` guarded by an atomic
    compare-and-set, so even a repeated `onLoad` (e.g. `reload`) can't bind the
    port twice.

- **`sbt-task` runs on sbt's command loop, in-process.** The plugin registers an
  `mcpExec` command and, per request, **appends** it to the command loop through an
  existing channel (no sbt-server socket / `sbt/exec` round-trip). `mcpExec` runs
  the command line on the loop thread via `sbt.McpInProcess` and completes the
  waiting request. Because sbt's `~` watch waits on a separate per-channel UI thread
  and leaves the command loop free between file-change triggers, a queued `sbt-task`
  **runs during a `~` watch**, serialized with it — exactly like a command typed at
  the shell. Output is captured by reading the delta of the global log
  (`state.globalLogging.backing.file`) around the command; failure is detected from
  the resulting `State` (a non-zero `Return(Exit)` `next`, a consumed `onFailure`, a
  parse-error callback, or a thrown `Incomplete`). The sub-command's command-flow
  mutations are not leaked back into the loop.

- **Symbol tools auto-refresh, decoupled from the task engine.** `glob-search` /
  `inspect` / `symbol-location` run on zio-http threads. Before each query they
  enqueue an internal refresh onto the command loop (`SbtMcpPlugin.refreshFromState`
  runs `Compile/fullClasspathAsJars` for the current project → the process-global
  `SymbolIndexState`), from which the tools lazily build a cached
  [`tasty-query`](https://github.com/scalacenter/tasty-query) `Context` (plus the
  JRE's `java.base`). Properties:
  - serialized with any `~` watch (runs between triggers);
  - **skipped when the loop is busy** (e.g. the call originates inside another
    command) to avoid a deadlock — the current index is used instead;
  - **cheap when nothing changed**: `SymbolIndexState` caches the `Context` by a
    content fingerprint (the classpath jars' content hashes) and rebuilds only after
    a recompile;
  - if the refresh compile **fails**, the tools answer from the last good index and
    append a `(note: the project does not currently compile …)` line.

  TASTy is emitted by every Scala 3 compile and shipped in dependency jars, so no
  SemanticDB or extra index is required.

- **`list-tasks` reads the build structure in-process.** It enumerates
  `Project.extract(state).structure.index.keyMap` (label + description) from the
  State captured on load — no command executed. Per-task detail (`task` arg) runs
  `help <task>` via `sbt-task`.

- **Documentation tools are proxied.** `McpServerRuntime.ProxyToolSource` implements
  zio-http-mcp's `McpToolSource`: its `listTools` connects to the upstream MCP server
  (`mcpDocsUrl`, default javadocs.dev) and merges those tools into `tools/list`, and
  its `callTool` forwards any name not matched by a built-in tool. It connects per
  request with a fresh zio-http `Client` and **degrades gracefully** (unreachable
  upstream → empty list / `isError` result), so the proxy never breaks our own tools.
  This keeps us from maintaining javadocs.dev's tool set. Set `mcpDocsUrl := None` to
  disable (and avoid the outbound network call).

## Internals map

| Concern | Where |
|---------|-------|
| Plugin, lifecycle, command registration, refresh orchestration | `SbtMcpPlugin` |
| MCP server + tool definitions (zio-http-mcp) | `McpServerRuntime` |
| In-process command execution / output capture / failure detection | `sbt.McpInProcess` (package `sbt`) |
| tasty-query index + glob-search / inspect / location | `SymbolIndex`, `SymbolIndexState` |

`sbt.McpInProcess` lives in `package sbt` to reach the internals sbt exposes for
running against a captured `State` (`Command.process`, `StandardMain.exchange`,
channel `append`) — the same family as `State.unsafeRunTask`.

## Known limitations (stub)

- **`sbt-task` is queued on the command loop.** It waits its turn behind any running
  command or triggered rebuild (returns `[timeout]` only after 30 minutes). Needs at
  least one sbt channel attached; otherwise it reports no channel.
- **`glob-search` / `inspect` fidelity.** Name matching is a case-insensitive
  boundary/`contains` approximation (Metals matches the last FQN segment at a name
  boundary); signatures are rendered via `declaredType.toString`, not pretty Scala
  signatures. Kinds are `class`/`object`/`trait`/`type`/`method`/`term`.
- **Multi-module symbol scope.** `SymbolIndexState` keys entries by project id but
  keeps a single *active* project (the last auto-refresh wins — the current project
  when a symbol tool is invoked). Exposing the target project as a tool argument is a
  planned enhancement.
- **`get-docs` is out of scope** — proxy `javadocs.dev` (MCP or API) instead.

## Running without publishing

The server bootstrap (`McpServerRuntime.start`) has no sbt dependency, so it can be
started from plain code:

**Standalone launcher.** `McpServerMain` (a **test-scoped** helper, so it's not in
the published artifact) starts the server and indexes the current JVM classpath:

```
sbt 'Test/runMain com.jamesward.sbtmcp.McpServerMain'
SBT_MCP_PORT=5099 sbt 'Test/runMain com.jamesward.sbtmcp.McpServerMain'
```

`glob-search` / `inspect` work against the indexed classpath; `sbt-task` /
`list-tasks` report limited results in this mode (no loaded sbt build).

**`test-project/`** consumes the plugin **from source** (no `publishLocal`) via
`dependsOn(RootProject(file("../..")))`, with the server enabled and a sample app to
query. Start it interactively and drive all tools (including `sbt-task`, backed by
the real sbt session). See [`test-project/README.md`](test-project/README.md).

## Scripted tests

Under `src/sbt-test/server/`, run with `sbt scripted` or `sbt 'scripted server/<name>'`:

- **`tools`** — applies the plugin with the server enabled, connects with
  zio-http-mcp's `McpClient`, and asserts `listTools` advertises all five tools and
  that `inspect` / `glob-search` / `list-tasks` read the project's TASTy. Also checks
  the in-process command primitive (`compile` succeeds, a bogus command fails).
- **`compile-output`** — three compile variants (success / warning / failure);
  asserts `sbt-task` captures the exhaustivity **warning**, the type **error**, and a
  clean **success**, and that failures are reported as `[error]` (not `[ok]`).
- **`incremental-symbols`** — lists all symbols in a package (`glob-search "*"`), adds
  a new source, re-indexes, and asserts the new symbol appears (verifying refresh
  invalidates the cached tasty-query context).
- **`symbol-location`** — asserts `symbol-location` returns a symbol's `path:line`.
- **`refresh-error`** — asserts the refresh fails on a non-compiling project (what
  makes the tools surface the stale-index note).
- **`docs-proxy`** — starts a local upstream MCP server (with a `docs-echo` tool),
  points `mcpDocsUrl` at it, and asserts our `tools/list` merges the upstream tool
  and forwards a call to it — hermetic, no external network.
- **`multi-protocol`** — verifies the five built-ins plus a proxied local tool are
  listed under every zio-http-mcp protocol revision (modern `2026-07-28` and all
  legacy Streamable HTTP revisions).
- **`multi-module`** — verifies `mcpStatus` does not aggregate across subprojects,
  so one build-global server produces one status line.

The real-client eval `SbtMcpKiroDocsSpec` is intentionally outside `scripted`: it
connects to production `https://www.javadocs.dev/mcp`, verifies the proxied tools
using kiro-cli's `2025-11-25` protocol, runs `kiro-cli /tools`, and makes a paid
kiro-cli tool call. The protocol check always requires outbound network; the two
kiro-cli tests are marked ignored when kiro-cli is unavailable or unauthenticated.

```
./sbt "testOnly com.jamesward.sbtmcp.SbtMcpKiroDocsSpec"
```

`SbtMcpEvalSpec` runs the compile and test-suite tool-choice scenarios against both
Claude and Kiro CLI, with each agent given a shell and sbt-mcp. It allows benign
shell use but fails if the shell invokes `sbt`, `./sbt`, or `sbtn`. Its Claude judge
is intentionally a no-op to avoid a second model call; pass/fail is based on the
deterministic `ToolCalled("sbt-task")` check plus transcript/tool-trace inspection.
Each available/authenticated provider makes two paid calls; unavailable providers'
model-backed tests are marked ignored.

Neither eval suite executes in CI: `.github/workflows/test.yml` runs only
`./sbt 'compile; scripted'`, not `test`, `Test/test`, or `testOnly`.

```
./sbt "testOnly com.jamesward.sbtmcp.SbtMcpEvalSpec"
```

Because an MCP tool call issued from *inside* a running command would deadlock (the
loop is busy), the scripted tests exercise the underlying primitives
(`sbt.McpInProcess.runOnLoop`, `SbtMcpPlugin.refreshFromState`) directly, and
`tools` seeds the index from task inputs before connecting.

## License

Apache-2.0
