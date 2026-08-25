package sbt

import java.io.{ File, RandomAccessFile }
import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

/**
 * In-process bridge to sbt's command engine, used by the sbt-mcp plugin's
 * `sbt-task` tool. Lives in `package sbt` to reach internals sbt exposes for this.
 *
 * ==Why enqueue instead of just running it?==
 * sbt has ONE command loop that owns build execution. A `~` (continuous) build
 * does NOT hold that loop: its waiting happens on a separate per-channel UI thread
 * (`WatchUITask`), which `append`s `runWatch` back onto the exchange when a file
 * changes. Between triggers the loop is idle (`currentExec == None`) and will run
 * any command appended to the queue — which is how sbt runs commands during `~`.
 *
 * So to run a command safely during a watch we do what sbt does: **append it to
 * the command loop** (via an existing channel) rather than executing it on our own
 * thread. The command then runs on the loop thread, serialized with the watch.
 *
 * ==Capturing output==
 * Every task logger feeds `state.globalLogging.backed`, which writes to a global
 * log file (`state.globalLogging.backing.file`). [[runOnLoop]] reads that file's
 * delta around the command, so the returned output includes compiler warnings and
 * errors — not just success/failure. Because the command runs on the loop
 * (serialized), nothing else writes to the log concurrently, so the delta is exactly
 * this command's output.
 */
object McpInProcess {

  /** True if sbt's command loop is currently executing a command or a `~` watch. */
  def isBusy: Boolean = StandardMain.exchange.currentExec.isDefined

  /**
   * Append `commandLine` to sbt's command loop via an existing channel, so it runs
   * on the loop thread (serialized with any `~` watch). Returns false if there is
   * no channel to append through (no sbt session attached yet).
   */
  def enqueue(commandLine: String, execId: String): Boolean =
    StandardMain.exchange.channels.headOption match {
      case Some(ch) => ch.append(Exec(commandLine, Some(execId), Some(CommandSource(ch.name))))
      case None     => false
    }

  /**
   * Run `commandLine` against `state` — intended to be called ON the loop thread
   * (e.g. from within the plugin's `mcpExec` command). Returns the new `State`,
   * either `Right(())` on success or `Left(message)` on failure (invalid command,
   * parse error, or task failure), and the captured console output (warnings /
   * errors / info) produced while the command ran. Never throws.
   */
  def runOnLoop(state: State, commandLine: String): (State, Either[String, Unit], String) = {
    val logFile = state.globalLogging.backing.file
    val start   = if (logFile.isFile) logFile.length else 0L
    var parseError: Option[String] = None
    val (afterState, threw): (State, Option[String]) =
      try (Command.process(commandLine, state, m => parseError = Some(m)), None)
      catch { case NonFatal(e) => (state, Some(Option(e.getMessage).getOrElse(e.toString))) }
    val captured = readDelta(logFile, start)
    // A failing command/task does NOT throw out of Command.process. sbt records the
    // failure in the State instead, two ways depending on whether an `onFailure`
    // handler is installed (the shell installs one via StashOnFailure):
    //   - no handler: `state.fail` sets `next` to a non-zero `Return(Exit(...))`;
    //   - handler installed: `state.fail` CONSUMES `onFailure` (leaving next=Continue).
    // Mirror sbt's own exit-code logic by checking both, plus parse errors / throws.
    val failedViaOnFailure = state.onFailure.isDefined && afterState.onFailure.isEmpty
    val failed = threw.isDefined || parseError.isDefined || isFailure(afterState) || failedViaOnFailure
    val result: Either[String, Unit] =
      if (failed) Left(threw.orElse(parseError).getOrElse("command failed"))
      else Right(())
    // Reset `next` so a failure isn't propagated as an exit into the State the caller threads on.
    (afterState.continue, result, captured)
  }

  private def isFailure(state: State): Boolean =
    state.next match {
      case ret: State.Return =>
        ret.result match {
          case exit: xsbti.Exit => exit.code() != 0
          case _                => false
        }
      case _ => false
    }

  private def readDelta(file: File, start: Long): String =
    try {
      if (!file.isFile) ""
      else {
        val end = file.length
        if (end <= start) ""
        else {
          val raf = new RandomAccessFile(file, "r")
          try {
            raf.seek(start)
            val buf = new Array[Byte]((end - start).toInt)
            raf.readFully(buf)
            stripAnsi(new String(buf, StandardCharsets.UTF_8)).trim
          } finally raf.close()
        }
      }
    } catch { case NonFatal(_) => "" }

  private def stripAnsi(s: String): String = s.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "")
}
