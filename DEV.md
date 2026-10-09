# sbt-mcp — Development

Architecture, implementation notes, limitations, and how to build/test the plugin.
For user-facing install/usage, see [README.md](README.md).

## Build

```
sbt compile          # main sources; warnings are errors (-Werror)
sbt Test/compile     # Scala 3.8 plugin tests (includes the standalone launcher)
sbt latestDependencyTests/Test/compile # Scala 3.9 tests using current MCP/eval libraries
sbt latestDependencyTests/Test/test    # includes opt-in real CLI/network integration tests
sbt scripted         # all scripted integration tests
sbt 'scripted server/multi-module' # sbt 2.0 plugin indexing Scala 3.9 modules
```

Runtime dependencies: zio-http-mcp **0.8.3** (zio 2.1.26 / zio-http
3.11.6 / zio-schema 1.9.0) is compiled in the Scala 3.9
`isolatedMcpRuntime` project and embedded with its full dependency closure. None of
those jars appear on the published plugin's production dependency classpath. Symbol
indexing is also fully isolated: a Scala 3.8 `isolatedSymbolRuntime` jar plus
tasty-query **1.8.0** and **1.9.0** reader jars are embedded resources. No
tasty-query classes or dependencies appear on the published plugin's production
classpath.

Tests that directly consume current Scala 3.9 artifacts live in
`latestDependencyTests`: it uses zio-http-mcp **0.8.3** and zio-evals **0.1.2**.
Scripted MCP client checks likewise run in forked Scala 3.9 fixture subprojects
using zio-http-mcp **0.8.3**, keeping those APIs out of sbt's Scala 3.8
meta-build classloader.

## Architecture

- **One server per build.** sbt runs a single server JVM shared by every
  subproject and every connected client, so the plugin starts **exactly one** MCP
  server for the whole build:
  - lifecycle hooks live on `Global / onLoad` / `Global / onUnload` (fire once per
    build load/unload, not once per aggregated project);
  - `mcpEnabled` / `mcpDisableInCI` / `mcpPort` / `mcpHost` / `mcpDocsUrl` default in Global and
    are read from the root build's root project (`rootRef`), so the build you launched decides.
    Builds set them with `ThisBuild /`: a source dependency's `Global /` value would replace a
    root build's `Global /` value, and `foreignGlobalMcpSettings` warns when that can happen;
  - startup is skipped when `mcpDisableInCI` is true (the default) and either `CI`
    is truthy or Heroku provides a nonblank `SOURCE_VERSION` during its build;
  - the server handle is a process-global `AtomicReference` guarded by an atomic
    compare-and-set, so even a repeated `onLoad` (e.g. `reload`) can't bind the

- **MCP/ZIO dependencies are classloader-isolated.** `McpServerRuntime` in the
  plugin is a JDK-only facade. It extracts a nested Scala 3.9 runtime containing
  `McpServerRuntimeImpl`, zio-http-mcp 0.8.3, ZIO HTTP, Netty, and their dependency
  closure, then launches it in a platform-parented `URLClassLoader`. Commands,
  refreshes, task lists, and symbol operations cross through `IsolatedMcpBridge`
  using only `java.util.function` interfaces, strings, and opaque handles. Closing
  the server closes the child loader and deletes its extracted jars.
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

- **Test failures reach the `sbt-task` response.** Test frameworks (ZIO Test, munit,
  ScalaTest) render results to the test JVM's stdout, forked or not, which sbt relays
  to the console but never writes to the global log, so the captured delta used to
  hold only `Failed tests: <Spec>`. `McpTestFailureListener` is added to
  `Test / testListeners`; sbt hands it every test event (the same events the JUnit XML
  reports use), and it logs each failed or errored test's name and rendered message
  (ANSI and fork wrappers stripped, 4000 characters max) through sbt's logger. It
  only logs while `SbtMcpPlugin.runForTool` (`mcpExec`) runs, so console runs aren't
  duplicated. Test stdout (`println`) still isn't captured.

- **Symbol tools auto-refresh, decoupled from the task engine.** `glob-search` /
  `inspect` / `symbol-location` run on zio-http threads. Before each query they
  enqueue an internal refresh onto the command loop (`SbtMcpPlugin.refreshFromState`
  runs `Compile/fullClasspathAsJars` for the current project and every transitive
  aggregate, deduplicates their classpaths, selects the highest aggregate Scala
  version, then updates process-global `SymbolIndexState`). The full merged classpath
  is retained only for type resolution and exact lookups; glob roots are the first
  output jar from each active/aggregate project, preventing dependency-universe
  traversal. The first query lazily
  creates a platform-parented `URLClassLoader` containing the plugin's index bridge,
  the matching tasty-query reader, and the project's `fullClasspathAsJars`. Only JDK
  types cross the reflective bridge, so a Scala 3.8 sbt plugin can safely run the
  Scala 3.9 reader with the project's Scala 3.9 runtime. Properties:
  - serialized with any `~` watch (runs between triggers);
  - **skipped when the loop is busy** (e.g. the call originates inside another
    command) to avoid a deadlock — the current index is used instead;
  - **cheap when nothing changed**: `SymbolIndexState` caches the isolated session
    by classpath content fingerprint and target Scala version, rebuilding and closing
    the old classloader only after a recompile or version change;
  - classloaders are closed on invalidation and sbt unload/reload;
  - if the refresh compile **fails**, the tools answer from the last good index and
    append a `(note: the project does not currently compile …)` line.

  TASTy is emitted by every Scala 3 compile and shipped in dependency jars, so no
  SemanticDB or extra index is required.

- **`list-tasks` reads the build structure in-process.** It enumerates
  `Project.extract(state).structure.index.keyMap` (label + description) from the
  State captured on load — no command executed. Per-task detail (`task` arg) runs
  `help <task>` via `sbt-task`.

- **Documentation tools are proxied.** `McpServerRuntimeImpl.ProxyToolSource` implements
  zio-http-mcp's `McpToolSource`: its `listTools` connects to the upstream MCP server
  (`mcpDocsUrl`, default javadocs.dev) and merges those tools into `tools/list`, and
  its `callTool` forwards any name not matched by a built-in tool. It connects per
  request with a fresh zio-http `Client` and **degrades gracefully** (unreachable
  upstream → empty list / `isError` result), so the proxy never breaks our own tools.
  This keeps us from maintaining javadocs.dev's tool set. Set `mcpDocsUrl := None` to
  disable (and avoid the outbound network call).

- **`check` is a warm, compile-free checker using the project's own compiler.**
  `IsolatedCheckBridge` (under `src/isolated-check`) is compiled once against the
  oldest supported compiler (Scala 3.3.1, `Provided`) and embedded as a jar. At
  runtime `IsolatedCheck` loads it in a platform-parented `URLClassLoader` together
  with the module's `scalaInstance.allJars`, so each module is checked by exactly the
  compiler version that compiles it; the project classpath is passed as `-classpath`
  (macros load through the compiler's own macro classloader, as in a batch compile).
  The bridge only touches the long-stable driver surface (`Driver.setup`,
  `Compiler.newRun`, `Run.compileSources`, `StoreReporter`, `SourceFile.virtual`).
  - **Phases:** the full `Compiler` pipeline with `-Ystop-before:genBCode`, so every
    diagnostic a compile can produce short of bytecode emission is reported
    (typer, PostTyper, inlining/macros, RefChecks, init checks, pattern matching,
    unused checks, erasure clashes, `@tailrec`). Output goes to a private scratch
    directory; options that write elsewhere or rewrite sources (`-d`, `-rewrite`,
    `-Xsemanticdb`, `-Ypickle-write`, …) are dropped (`IsolatedCheck.sanitizeOptions`).
  - **Reporter parity with sbt:** `UniqueMessagePositions` + `HideNonSensicalMessages`
    (what zinc's `DelegatingReporter` inherits), positions via `pos.nonInlined`, and
    `-explain` text appended — so a check reports the same diagnostics, at the same
    line/column, as `compile`.
  - **Fresh context, warm compiler:** every check builds a new root compiler context
    (non-interactive, byte-for-byte batch-compiler behavior), so nothing from one
    check (e.g. an unsaved overlay's definitions) can leak into the next, and a
    compile's new classes are always seen. What is kept warm is the classloader per
    compiler version (`ModuleCheck` caches up to 4, LRU): the JIT-compiled compiler and
    its cached classpath archives. Reusing one symbol table across checks in
    `Mode.Interactive` (as the REPL / IDE presentation compiler do) was measured about
    1.5x faster but was rejected: symbols entered from source survive into later runs,
    and the interactive parser is more lenient (e.g. a trailing infix operator), both
    of which break parity with `compile`.
  - **Measured** (`zz`-style bench on a ZIO module, JIT warm): full-fidelity check of
    one file ~280-450 ms vs ~550-1000 ms for sbt's incremental compile of the same edit;
    typer-only (`-Dsbt.mcp.check.stopBefore=posttyper`) ~200 ms fresh / ~80 ms with a
    reused context. The phases from inlining to erasure are most of the difference.
    The first check after sbt starts pays ~3 s to load and JIT the compiler.
  - **Module settings** (`checkConfigFromState`, on the command loop via the
    internal `mcpCheckConfigInternal` command): `scalaInstance`, `scalacOptions`,
    `sources`, `classDirectory` + `dependencyClasspath` (which compiles upstream
    modules if needed; if one fails, the upstream class directories + external
    classpath are used with a note), and the last compile's Zinc source stamps
    (`previousCompile`). When the loop is busy, the last computed settings are used
    so a check never waits behind a build. The owning module of a file is found from
    `unmanagedSourceDirectories` / `managedSourceDirectories` (settings, off-loop).
  - **Changed siblings:** module sources whose current Zinc stamp differs from the
    last compile (or that are new) are checked in the same run as the requested files,
    because their compiled classes are stale. A never-compiled module checks all of its
    sources. `scope:"module"` checks every source.

## Internals map

| Concern | Where |
|---------|-------|
| Plugin, lifecycle, command registration, refresh orchestration | `SbtMcpPlugin` |
| Isolated MCP server facade / child implementation | `McpServerRuntime`; `McpServerRuntimeImpl`, `IsolatedMcpBridge` under `src/isolated-runtime` |
| In-process command execution / output capture / failure detection | `sbt.McpInProcess` (package `sbt`) |
| `check`: module settings, sessions, changed-sibling detection, rendering | `SbtMcpPlugin` (settings/owner resolution), `ModuleCheck`, `IsolatedCheck`; embedded `src/isolated-check`: `IsolatedCheckBridge` |
| tasty-query isolation + index + symbol operations | Parent: `IsolatedSymbolIndex`, `SymbolIndexState`; embedded `src/isolated-symbol`: `IsolatedSymbolBridge`, `LazyClasspath`, `SymbolIndex` |

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
  when a symbol tool is invoked). An aggregating active project includes all of its
  transitive aggregates; a leaf includes only its own classpath. Exposing a different
  target project as a tool argument is a planned enhancement.
- **`check` scope.** It checks one module at a time. Modules that *depend on* the
  checked one are not re-checked (a real `compile` of the aggregate is still the final
  gate). Deleted sources' classes remain on the classpath until the next compile.
  `.java` sources cannot be requested (Java sources are seen through their last
  compiled classes). Scala 2 modules and Scala 3 before 3.3 are refused.
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
- **`test-output`** — a forked ZIO Test spec with one failing assertion; asserts
  `runForTool` (the `sbt-task` path) returns the failed test's name and assertion
  message, and that a plain console run doesn't get the extra lines.
- **`source-dependency`** — a root build that depends on another checkout (`dep/`) through
  `RootProject`, both with sbt-mcp on different ports. Asserts the server is the root
  build's when the root uses `ThisBuild /` (even if the dependency uses `Global /`), that
  sbt-mcp warns and the dependency's port wins when both use `Global /`, and that both
  `ThisBuild /` is clean.
- **`incremental-symbols`** — lists all symbols in a package (`glob-search "*"`), adds
  a new source, re-indexes, and asserts the new symbol appears (verifying refresh
  invalidates the cached isolated reader session).
- **`symbol-location`** — asserts `symbol-location` returns a symbol's `path:line`.
- **`refresh-error`** — asserts the refresh fails on a non-compiling project (what
  makes the tools surface the stale-index note).
- **`docs-proxy`** — starts a local upstream MCP server (with a `docs-echo` tool, and rejecting the first forwarded `tools/call` to check the retry),
  points `mcpDocsUrl` at it, and asserts our `tools/list` merges the upstream tool
  and forwards a call to it — hermetic, no external network.
- **`multi-protocol`** — verifies the six built-ins plus a proxied local tool are
  listed under every zio-http-mcp protocol revision (modern `2026-07-28` and all
  legacy Streamable HTTP revisions).
- **`multi-module`** — under sbt 2.0, compiles a Toolbook-shaped aggregate on
  Scala 3.9 (shared-module `dependsOn`, empty-package symbols), asserts the isolated
  reader is tasty-query 1.9, and verifies real `glob-search` / `inspect` calls see
  submodule symbols. Also verifies `mcpStatus` does not aggregate across projects.
- **`check-parity`** — differential oracle for `check`: a corpus of 25 cases (type,
  syntax, name, override/abstract-member, inline `compiletime.error`, exhaustivity,
  unused, deprecation, givens, explicit nulls, `@tailrec`, erasure clash, unreachable
  case, pure-expression, transparent inline, init order, named tuples, Scala 2 syntax,
  Java interop, `Mirror` derivation, cycles) is run through `check` (as an unsaved
  overlay and from disk) AND a real `compile`, for Scala 3.3.1 / 3.3.8 / 3.5.2 /
  3.7.4 / 3.8.4 / 3.9.0 and strict (`-Werror -Wunused:all -Yexplicit-nulls
  -deprecation -feature` + safe-init) / `-source:future -explain` option sets. Every
  positioned diagnostic (file, line, column, severity, error code) and the pass/fail
  status must be identical. Logs median check vs compile time per module.
- **`check-scenarios`** — a multi-module build: `dependsOn`, a quoted macro from an
  upstream module (error reported at the call site, column matching `compile`), the
  Test configuration with munit (and main sources not seeing it), changed siblings
  (content stamps, not mtimes), `scope:"module"`, session invalidation after a compile,
  upstream auto-compile and broken-upstream fallback, Scala 2 / validation refusals,
  output rendering, a warm-vs-compile performance assertion, and the `check` MCP tool
  over HTTP (incl. concurrent calls) while the command loop is busy.
- **`ci-disable`** — verifies `mcpDisableInCI` defaults to true and, when the scripted
  suite runs under CI or with Heroku's `SOURCE_VERSION`, proves an enabled server did
  not bind its configured port.

Server-starting scripted fixtures set `mcpDisableInCI := false` explicitly so they
continue to exercise the embedded server on CI runners. `SbtMcpPluginSpec` tests the
startup decision, including the default CI/Heroku guard and its explicit override.
`ModuleCheckSpec` unit-tests `check`'s option sanitizing, `-Werror` handling, scope
parsing, Scala-version gate, changed-source detection, and rendering.

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
