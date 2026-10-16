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
   * Append `commandLine` to sbt's command loop, so it runs on the loop thread
   * (serialized with any `~` watch). Returns false only if no channel could be found
   * or created. See [[channel]] for which channel carries the command.
   */
  def enqueue(commandLine: String, execId: String): Boolean =
    channel match {
      case Some(ch) => ch.append(Exec(commandLine, Some(execId), Some(CommandSource(ch.name))))
      case None     => false
    }

  /** Name of the channel sbt-mcp adds when sbt has no console of its own. */
  private[sbt] final val ChannelName = "sbt-mcp"

  /**
   * The channel MCP commands are appended through: the interactive console when sbt
   * has one, otherwise a console-like channel owned by sbt-mcp. A server started in
   * the background by a thin client (`sbt --client`, sbt 2's default) has no console
   * channel, and its network channels belong to clients that come and go (each
   * `sbtn` invocation, a BSP connection): sbt drops an exiting client's queued
   * commands, so they can't carry an MCP command that waits its turn.
   *
   * The sbt-mcp channel is a [[sbt.internal.ConsoleChannel]] so the command loop
   * treats its commands like typed ones (e.g. reloading after build sources
   * change), but its prompt never reads input. It is added once per sbt JVM and
   * found by name after a `reload` loads a new copy of this plugin.
   */
  private def channel: Option[sbt.internal.CommandChannel] =
    try {
      val exchange = StandardMain.exchange
      exchange.channels
        .collectFirst { case c: sbt.internal.ConsoleChannel if c.name != ChannelName => c }
        .orElse(exchange.channels.find(_.name == ChannelName))
        .orElse(synchronized {
          exchange.channels.find(_.name == ChannelName).orElse {
            val created = new sbt.internal.ConsoleChannel(ChannelName, (_, ch) => new IdleUITask(ch))
            exchange.subscribe(created)
            Some(created)
          }
        })
    } catch { case NonFatal(_) => StandardMain.exchange.channels.headOption }

  /**
   * The sbt-mcp channel's prompt: it waits, reading nothing, until sbt closes it
   * (the next prompt reuses a running task of the same class).
   */
  private final class IdleUITask(override private[sbt] val channel: sbt.internal.CommandChannel)
      extends sbt.internal.ui.UITask {
    private val closed = new java.util.concurrent.CountDownLatch(1)
    override private[sbt] val reader: sbt.internal.ui.UITask.Reader =
      new sbt.internal.ui.UITask.Reader {
        def readLine(): Either[String, String] = {
          closed.await()
          throw sbt.internal.ui.UITask.Reader.interrupted
        }
        override def close(): Unit = closed.countDown()
      }
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
    // A multi-command line (`a; b`) doesn't run anything when processed: sbt's parser only
    // queues its parts on `remainingCommands` for the command loop. The caller returns the
    // loop's original state, so those parts would be dropped and the call would report
    // `[ok]` with no output. Start from an empty queue and run everything the line queued,
    // in order, stopping at the first failure, the way the shell would.
    def step(st: State, cmd: String): Either[String, State] =
      try Right(Command.process(cmd, st, m => parseError = Some(m)))
      catch { case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString)) }
    val isolated = state.copy(remainingCommands = Nil)
    var current: State = isolated
    var threw: Option[String] = None
    var failedViaOnFailure = false
    var pending: List[String] = List(commandLine)
    var steps = 0
    while (pending.nonEmpty && threw.isEmpty && parseError.isEmpty && !isFailure(current) && !failedViaOnFailure && steps < MaxQueuedCommands) {
      val cmd = pending.head
      val before = current.copy(remainingCommands = Nil)
      step(before, cmd) match {
        case Left(err) => threw = Some(err)
        case Right(after) =>
          failedViaOnFailure = before.onFailure.isDefined && after.onFailure.isEmpty
          // Commands this one queued run next, ahead of the rest of the line.
          pending = after.remainingCommands.map(_.commandLine) ++ pending.tail
          current = after.copy(remainingCommands = Nil)
      }
      steps += 1
    }
    val afterState = current
    val captured = readDelta(logFile, start)
    // A failing command/task does NOT throw out of Command.process. sbt records the
    // failure in the State instead, two ways depending on whether an `onFailure`
    // handler is installed (the shell installs one via StashOnFailure):
    //   - no handler: `state.fail` sets `next` to a non-zero `Return(Exit(...))`;
    //   - handler installed: `state.fail` CONSUMES `onFailure` (leaving next=Continue).
    // Mirror sbt's own exit-code logic by checking both, plus parse errors / throws.
    val failed = threw.isDefined || parseError.isDefined || isFailure(afterState) || failedViaOnFailure
    val result: Either[String, Unit] =
      if (failed) Left(threw.orElse(parseError).getOrElse("command failed"))
      else Right(())
    // Reset `next` so a failure isn't propagated as an exit into the State the caller threads on.
    // Give back the caller's own queue: everything this line queued has run.
    (afterState.copy(remainingCommands = state.remainingCommands).continue, result, captured)
  }

  private final val MaxQueuedCommands = 1000

  /**
   * Run `body` (ON the loop) and return its result with the console output it logged,
   * read from the global log file like [[runOnLoop]] does for a command line.
   */
  def captureLog[A](state: State)(body: => A): (A, String) = {
    val logFile = state.globalLogging.backing.file
    val start   = if (logFile.isFile) logFile.length else 0L
    val result  = body
    (result, readDelta(logFile, start))
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
