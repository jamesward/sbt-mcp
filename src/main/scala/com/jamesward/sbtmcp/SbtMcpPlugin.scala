package com.jamesward.sbtmcp

import sbt.*
import sbt.Keys.*

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.concurrent.{ Await, Promise, TimeoutException }
import scala.concurrent.duration.*

/**
 * sbt-mcp — an opt-in (default OFF) MCP server embedded in the sbt JVM.
 *
 * It exposes three tools over MCP (see [[McpServerRuntime]]):
 *   - `sbt-task`   : run an sbt command/task in the running sbt server
 *   - `glob-search`: search Scala 3 symbols by name (tasty-query backed)
 *   - `inspect`    : list a symbol's members/signatures (tasty-query backed)
 *
 * STUB STATUS: this is a scaffold. It is pinned to zio-http-mcp 0.5.3 /
 * tasty-query 1.8.0 / sbt 2.0.6 and encodes the intended architecture, but the
 * exact library call sites should be validated by a compile (some tasty-query
 * flag/signature rendering is intentionally simplified — see [[SymbolIndex]]).
 *
 * ==Single server in a multi-module build==
 * sbt runs ONE server JVM per build, shared by every subproject and every
 * connected client. We must therefore start exactly one MCP server for the
 * whole build, not one per module. This is guaranteed three ways:
 *   1. The lifecycle hooks live in [[globalSettings]] on `Global / onLoad` /
 *      `Global / onUnload`, which fire once per build load/unload — NOT once per
 *      aggregated project.
 *   2. `mcpEnabled` / `mcpPort` / `mcpHost` are global settings, so there is a
 *      single source of truth for whether/where the server runs.
 *   3. [[serverHandle]] is a process-global `AtomicReference` and [[maybeStartServer]]
 *      uses an atomic compare-and-set gate, so even if `onLoad` were invoked
 *      more than once (e.g. a `reload`), only the first call binds a port; the
 *      rest are no-ops.
 */
object SbtMcpPlugin extends AutoPlugin {
  override def requires = sbt.plugins.JvmPlugin
  override def trigger  = allRequirements

  object autoImport {
    val mcpEnabled = settingKey[Boolean]("Start the embedded MCP server on sbt load (default false).")
    val mcpPort    = settingKey[Int]("Loopback port for the embedded MCP server (default 5010).")
    val mcpHost    = settingKey[String]("Interface to bind; keep on loopback (default 127.0.0.1).")
    val mcpDocsUrl = settingKey[Option[String]](
      "Upstream MCP server whose tools are proxied and merged with ours (default javadocs.dev). Set None to disable."
    )
    val mcpStatus  = taskKey[Unit]("Print embedded MCP server status.")
  }
  import autoImport.*

  // One sbt server JVM => at most one MCP server. Both are process-global.
  private val serverHandle = new AtomicReference[Option[McpServerRuntime.Handle]](None)
  private val starting     = new AtomicBoolean(false)

  // Latest build State, captured on each (re)load. Read off-thread by the
  // `list-tasks` tool to enumerate tasks/settings straight from the build
  // structure (in-process), instead of asking the sbt server. State only changes
  // across reloads, so an onLoad-captured value is current between reloads.
  private val currentState = new AtomicReference[Option[State]](None)

  // In-flight `sbt-task` requests, keyed by a generated execId. The `mcpExec`
  // command (run on the loop) looks up the command line + completes the promise
  // with the full result text (status + captured compiler output).
  private val pending =
    new ConcurrentHashMap[String, (String, Promise[String])]()

  // In-flight automatic index-refresh requests, keyed by execId. The completed
  // value is an optional warning note (e.g. the project doesn't currently compile).
  private val pendingRefresh = new ConcurrentHashMap[String, Promise[Option[String]]]()
  private final val RefreshCmd = "mcpRefreshIndexInternal"

  override lazy val globalSettings: Seq[Setting[?]] = Seq(
    mcpEnabled := false,
    mcpPort    := 5010,
    mcpHost    := "127.0.0.1",
    mcpDocsUrl := Some("https://www.javadocs.dev/mcp"),
    // `mcpExec <id>` runs a queued sbt-task ON the command loop (so it is
    // serialized with any `~` watch), then completes the waiting MCP request.
    commands += mcpExecCommand,
    // `mcpRefreshIndexInternal <id>` recomputes the symbol index on the loop.
    commands += mcpRefreshCommand,
    onLoad := { (s: State) =>
      val s1 = onLoad.value(s) // run the previously-registered onLoad first
      currentState.set(Some(s1))
      maybeStartServer(s1)
      s1
    },
    onUnload := { (s: State) =>
      stopServer(s.log)
      currentState.set(None)
      onUnload.value(s)
    },
  )

  /**
   * Command that executes one queued `sbt-task` request on the command loop and
   * completes its promise. Runs on the loop thread, so it is serialized with a
   * `~` watch (it runs between triggers) — no concurrent build execution.
   */
  private def mcpExecCommand: Command =
    Command.single("mcpExec") { (state, id) =>
      Option(pending.remove(id)) match {
        case None => state
        case Some((commandLine, promise)) =>
          val (_, result, output) = sbt.McpInProcess.runOnLoop(state, commandLine)
          val status = result match {
            case Right(_)  => s"[ok] $commandLine"
            case Left(msg) => s"[error] $commandLine: $msg"
          }
          val text = if (output.isEmpty) status else s"$status\n$output"
          promise.trySuccess(text)
          // Return the loop's ORIGINAL state: the sub-command ran for its effects
          // (compile/test results are on disk), but we must not leak its command-flow
          // mutations (a failed command consumes onFailure / rewrites remainingCommands)
          // back into the loop.
          state
      }
    }

  /**
   * Command that recomputes the symbol index ON the command loop and completes the
   * waiting refresh request. Runs [[refreshFromState]] against the live State.
   */
  private def mcpRefreshCommand: Command =
    Command.single(RefreshCmd) { (state, id) =>
      val note: Option[String] =
        try { refreshFromState(state); None }
        catch {
          case scala.util.control.NonFatal(_) =>
            Some("the project does not currently compile — symbols reflect the last successful compile")
        }
      Option(pendingRefresh.remove(id)).foreach(_.trySuccess(note))
      state
    }

  /**
   * Recompute the current project's classpath and (re)index its symbols. MUST be
   * called ON the command loop (it runs the task engine): from the internal refresh
   * command, or directly from a build's own command. Uses `fullClasspathAsJars` so
   * the project's freshly-compiled `.tasty` is packaged into a content-hashed jar;
   * the hashes form the fingerprint that [[SymbolIndexState]] uses to skip rebuilding
   * the tasty-query context when nothing changed.
   */
  def refreshFromState(state: State): Unit = {
    val extracted   = Project.extract(state)
    val (_, cp)     = extracted.runTask(Compile / fullClasspathAsJars, state)
    val converter   = extracted.get(fileConverter)
    val entries     = cp.map(a => converter.toPath(a.data)).toList
    val fingerprint = cp.map(_.data.contentHashStr).toVector
    SymbolIndexState.update(extracted.currentRef.project, entries, fingerprint)
  }

  /**
   * Automatically bring the symbol index up to date before a symbol query. Enqueues
   * [[refreshFromState]] onto the command loop and waits for it, so it is serialized
   * with any `~` watch (running between triggers). If the loop is already busy — e.g.
   * this originates from inside another command — it is SKIPPED (the current index is
   * used) to avoid a deadlock. Returns an optional warning note to surface to the
   * caller (e.g. the project currently fails to compile, so the index is stale).
   */
  private def refreshIndex(): Option[String] = {
    if (sbt.McpInProcess.isBusy || currentState.get.isEmpty) return None
    val id      = "mcp-refresh-" + UUID.randomUUID().toString
    val promise = Promise[Option[String]]()
    pendingRefresh.put(id, promise)
    if (!sbt.McpInProcess.enqueue(s"$RefreshCmd $id", id)) { pendingRefresh.remove(id); None }
    else
      try Await.result(promise.future, 10.minutes)
      catch { case _: TimeoutException => pendingRefresh.remove(id); None }
  }

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    // Opt out of sbt 2.x action caching: this task has side effects / reads
    // process-global state, so a cached (skipped) re-run would print nothing.
    mcpStatus := Def.uncached {
      val log = streams.value.log
      serverHandle.get match {
        case Some(h) => log.info(s"sbt-mcp: running at http://${h.host}:${h.port}/")
        case None    => log.info("sbt-mcp: not running (set `Global / mcpEnabled := true` to enable)")
      }
    },
  )

  /**
   * Start the single MCP server iff enabled and not already running. The
   * `starting` CAS gate makes this idempotent and race-free, so a multi-module
   * build (or a repeated `onLoad`) never binds the port twice.
   */
  private def maybeStartServer(state: State): Unit = {
    if (serverHandle.get.isDefined) return
    val extracted = Project.extract(state)
    val enabled   = extracted.getOpt(mcpEnabled).getOrElse(false)
    if (!enabled) return
    if (!starting.compareAndSet(false, true)) return // another thread is starting it
    try {
      if (serverHandle.get.isEmpty) {
        val host   = extracted.getOpt(mcpHost).getOrElse("127.0.0.1")
        val port   = extracted.getOpt(mcpPort).getOrElse(5010)
        val docsUrl = extracted.getOpt(mcpDocsUrl).flatten
        val handle = McpServerRuntime.start(host, port, runCommand, () => refreshIndex(), () => taskInfos(currentState.get), docsUrl)
        serverHandle.set(Some(handle))
        state.log.info(s"sbt-mcp: MCP server started at http://$host:$port/ (loopback, no auth — dev use only)")
      }
    } catch {
      case scala.util.control.NonFatal(e) =>
        state.log.error(s"sbt-mcp: failed to start MCP server: ${e.getMessage}")
    } finally {
      starting.set(false)
    }
  }

  private def stopServer(log: Logger): Unit =
    serverHandle.getAndSet(None).foreach { h =>
      try h.close()
      catch { case scala.util.control.NonFatal(_) => () }
      log.info("sbt-mcp: MCP server stopped")
    }

  /**
   * Enumerate the build's task/setting keys with their descriptions, read directly
   * from the resolved build structure (`structure.index.keyMap`) — no sbt command
   * executed. This is the in-process alternative to scraping the sbt server's
   * `tasks` output; it powers the `list-tasks` MCP tool's bulk listing.
   */
  /**
   * Run an sbt command line for the `sbt-task` tool. The command is appended to
   * sbt's command loop (via [[sbt.McpInProcess.enqueue]]) so it runs ON the loop
   * thread — serialized with any `~` watch, exactly like a command typed at the
   * shell. We then block until the loop's `mcpExec` completes our promise.
   *
   * This is safe during a `~` watch: sbt's watch waits on a separate UI thread and
   * leaves the command loop free between triggers, so the queued command runs then.
   */
  private def runCommand(commandLine: String): String =
    currentState.get match {
      case None => "sbt-task: build not loaded yet"
      case Some(_) =>
        val id      = "mcp-" + UUID.randomUUID().toString
        val promise = Promise[String]()
        pending.put(id, (commandLine, promise))
        if (!sbt.McpInProcess.enqueue(s"mcpExec $id", id)) {
          pending.remove(id)
          "sbt-task: no sbt channel available yet (is an sbt session attached?)"
        } else {
          try Await.result(promise.future, 30.minutes)
          catch {
            case _: TimeoutException =>
              pending.remove(id)
              s"[timeout] $commandLine (still queued behind a long-running command?)"
          }
        }
    }

  private def taskInfos(stateOpt: Option[State]): List[(String, String)] =
    stateOpt match {
      case None => Nil
      case Some(state) =>
        try {
          val keyMap = Project.extract(state).structure.index.keyMap
          keyMap.values.toList
            .map(k => (k.label, k.description.getOrElse("")))
            .distinct
        } catch { case scala.util.control.NonFatal(_) => Nil }
    }
}
