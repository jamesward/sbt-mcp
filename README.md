# sbt-mcp

[![javadocs.dev](https://www.javadocs.dev/com.jamesward/sbt-mcp_sbt2_3/badge.svg?1)](https://www.javadocs.dev/com.jamesward/sbt-mcp_sbt2_3/latest)

An **opt-in, off-by-default** [Model Context Protocol](https://modelcontextprotocol.io/)
server embedded in an sbt 2.x build. When enabled, it starts an MCP server inside the sbt
JVM and exposes tools an AI agent can call:

| Tool          | What it does                                                                 |
|---------------|------------------------------------------------------------------------------|
| `sbt-task`    | Run an sbt command/task (works during a `~` watch); returns `[ok]`/`[error]` + captured compiler output |
| `list-tasks`  | List the build's tasks/settings with descriptions (optional `all` flag; per-task `help` via `task`) |
| `glob-search` | Search Scala 3 symbols by name (TASTy); `query:"*"` + `inPackage` lists ALL symbols in a package |
| `inspect`     | List a symbol's members and (approximate) signatures (TASTy)                 |
| `symbol-location` | Return a symbol's source location as `path:line` (from its TASTy tree position) |

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
Global / mcpEnabled     := true        // default: false
Global / mcpDisableInCI := true        // default: true; set false to allow startup in CI
Global / mcpPort        := 5010        // default: 5010
Global / mcpHost        := "127.0.0.1" // default: loopback only
```

When enabled, the server starts on the next sbt load and prints:

```
sbt-mcp: MCP server started at http://127.0.0.1:5010/ (loopback, no auth — dev use only)
```

Point an MCP client (Claude Code, Cursor, etc.) at `http://127.0.0.1:5010/`.
`mcpStatus` prints whether the server is running.

`mcpInstall` prints copy-pasteable onboarding for an AI agent: how to register this
server in the local MCP client config (Kiro / Claude Code / Cursor) using the
configured host/port, and the `AGENTS.md` usage guidance to adopt (drive sbt through
the `sbt-task` tool, use `glob-search` / `inspect` / `symbol-location` for symbols).
Run it directly or via the `sbt-task` tool so the text is returned to the agent.

> **After enabling (or after a `reload`), reconnect your MCP client.** MCP clients
> fetch the tool list once at connect time, so a client that connected before the
> server started — or before a `reload` restarted it — won't show the tools (or the
> proxied javadocs.dev tools). Restart the client session / re-init MCP to pick them up.

## Usage

- **Symbols refresh automatically.** `glob-search` / `inspect` / `symbol-location`
  bring the index up to date before each query — there's no manual refresh step.
- **List all symbols in a package.** To discover symbols (including newly-added
  ones) without knowing their names, call `glob-search` with `query:"*"` (or empty)
  and `inPackage` set, e.g. `{"query":"*","inPackage":"com.example"}`.
- **Stale-index note.** If the project doesn't currently compile, the symbol tools
  still answer from the last successful compile and append a
  `(note: the project does not currently compile …)` line — a signal to fix the
  build (e.g. via `sbt-task`) before new symbols will appear.
- **Running tasks.** `sbt-task` (e.g. `{"command":"compile"}`, `{"command":"test"}`,
  `{"command":"testOnly com.example.MySpec"}`) runs on sbt's command loop and works
  even while a `~` watch is active. It returns the status plus captured compiler
  output. It needs an sbt session attached; if another command is running it waits
  its turn.

### Multi-project builds

`mcpEnabled` / `mcpDisableInCI` / `mcpPort` / `mcpHost` are **global** settings
and exactly one MCP
server runs per build (shared by all subprojects and clients). The symbol index
tracks a single active project — the current project when a symbol tool is invoked.
Because the server is a single build-wide instance, `mcpStatus` does not aggregate
across subprojects — it prints one status line even on an aggregating root.

## Configuration

| Setting          | Default       | Meaning                                  |
|------------------|---------------|------------------------------------------|
| `mcpEnabled`     | `false`       | Start the embedded MCP server on load    |
| `mcpDisableInCI` | `true`        | Do not start when `CI` is truthy; use `false` to override |
| `mcpPort`        | `5010`        | Loopback port to bind                    |
| `mcpHost`        | `"127.0.0.1"` | Interface to bind (keep on loopback)     |
| `mcpDocsUrl`     | `Some("https://www.javadocs.dev/mcp")` | Upstream MCP server to proxy/merge; `None` disables |

## Security

- **Off by default.** Nothing binds a port unless `mcpEnabled := true`.
- **Disabled in CI by default.** Even when enabled, the server does not bind when
  the `CI` environment variable has a truthy value unless `mcpDisableInCI := false`.
- **Loopback only.** Binds `127.0.0.1`. Do **not** set `mcpHost` to `0.0.0.0`.
- **No auth yet, and `sbt-task` can run arbitrary tasks** — treat the endpoint as a
  local, dev-only RCE surface. `zio-http-mcp` supports OAuth/token auth (`McpAuth`);
  wire it in before any non-loopback use.
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
  the build (e.g. `sbt-task {"command":"compile"}`) and query again.
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
