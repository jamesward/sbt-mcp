package com.jamesward.sbtmcp

import sbt.testing.Status as TStatus

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * Records the structured facts of one build-tool run (compiler diagnostics and test
 * events) so the structured MCP tools (`compile`, `test`, …) can return them as data
 * instead of only as log text.
 *
 * Recording is process-global but only active while a structured tool runs a command
 * on sbt's command loop. The loop runs one command at a time, so at most one run is
 * being recorded; events from parallel tasks inside that run are collected in
 * concurrent queues.
 */
private[sbtmcp] object McpRunRecorder {

  final case class Problem(severity: String, file: Option[String], line: Option[Int], column: Option[Int], message: String)

  final case class TestCase(suite: String, test: Option[String], status: String, message: Option[String], durationMillis: Option[Long])

  final case class Snapshot(
      problems: Vector[Problem],
      tests: Vector[TestCase],
      suiteErrors: Vector[(String, String)],
      output: Vector[String],
  )

  private val active      = new AtomicBoolean(false)
  private val problems    = new ConcurrentLinkedQueue[Problem]()
  private val tests       = new ConcurrentLinkedQueue[TestCase]()
  private val suiteErrors = new ConcurrentLinkedQueue[(String, String)]()
  private val output      = new ConcurrentLinkedQueue[String]()

  def isActive: Boolean = active.get

  /** Record everything reported while `body` runs. Call it ON the command loop. */
  def recording[A](body: => A): (A, Snapshot) = {
    problems.clear(); tests.clear(); suiteErrors.clear(); output.clear()
    active.set(true)
    try {
      val result = body
      (result, snapshot())
    } finally {
      active.set(false)
    }
  }

  private def snapshot(): Snapshot =
    Snapshot(
      problems.asScala.toVector.distinct,
      tests.asScala.toVector,
      suiteErrors.asScala.toVector.distinct,
      output.asScala.toVector,
    )

  /**
   * The logger a program run by the `run` tool writes its stdout (info) and stderr
   * (error) lines to: each line is recorded as program output and also logged to
   * `delegate` (sbt's console and log), as sbt's own forked `run` would.
   */
  final class OutputLogger(delegate: sbt.util.Logger) extends sbt.util.Logger {
    def trace(t: => Throwable): Unit        = delegate.trace(t)
    def success(message: => String): Unit   = delegate.success(message)
    def log(level: sbt.util.Level.Value, message: => String): Unit = {
      val line = message
      if (active.get) { output.add(line); () }
      delegate.log(level, line)
    }
  }

  def recordProblem(problem: xsbti.Problem): Unit =
    if (active.get) { problems.add(toProblem(problem)); () }

  def toProblem(problem: xsbti.Problem): Problem = {
    val position = problem.position
    val severity = problem.severity match {
      case xsbti.Severity.Error => "error"
      case xsbti.Severity.Warn  => "warning"
      case _                    => "info"
    }
    val file =
      position.sourcePath.toScala.filter(_.nonEmpty)
        .orElse(position.sourceFile.toScala.map(_.getPath))
    Problem(
      severity,
      file,
      position.line.toScala.map(_.intValue),
      // Zinc's `pointer`: the 0-based column, which is what sbt (and the `check` tool) print.
      position.pointer.toScala.map(_.intValue),
      problem.message.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", ""),
    )
  }

  /**
   * The problems carried by compile failures inside `error` (an `Incomplete` tree or a
   * plain exception). sbt 2 caches a failed compile and, on a repeat, replays the
   * failure without calling the reporter, so this is the only place those problems are.
   */
  def compileFailureProblems(error: Throwable): Vector[Problem] = {
    val roots = error match {
      case incomplete: sbt.Incomplete => sbt.Incomplete.allExceptions(incomplete).toVector
      case other                      => Vector(other)
    }
    roots
      .flatMap(e => Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null))
      .collect { case failed: xsbti.CompileFailed => failed.problems.toVector.map(toProblem) }
      .flatten
      .distinct
  }

  def recordTest(event: sbt.testing.Event): Unit =
    if (active.get) {
      val (suite, test) = McpTestFailureListener.names(event)
      val status = event.status match {
        case TStatus.Success  => "passed"
        case TStatus.Failure  => "failed"
        case TStatus.Error    => "error"
        case TStatus.Skipped  => "skipped"
        case TStatus.Ignored  => "ignored"
        case TStatus.Canceled => "canceled"
        case TStatus.Pending  => "pending"
      }
      val message =
        if (event.status == TStatus.Failure || event.status == TStatus.Error)
          Some(
            if (event.throwable.isDefined) McpTestFailureListener.messageOf(event.throwable.get)
            else "(no failure message from the test framework)"
          )
        else None
      val duration = Option(event.duration).filter(_ >= 0L)
      tests.add(TestCase(suite, test, status, message, duration))
      ()
    }

  def recordSuiteError(suite: String, error: Throwable): Unit =
    if (active.get) { suiteErrors.add(suite -> McpTestFailureListener.messageOf(error)); () }

  /** A compiler reporter that forwards to sbt's own and also records into [[McpRunRecorder]]. */
  final class RecordingReporter(underlying: xsbti.Reporter) extends xsbti.Reporter {
    def reset(): Unit                                = underlying.reset()
    def hasErrors: Boolean                           = underlying.hasErrors
    def hasWarnings: Boolean                         = underlying.hasWarnings
    def printSummary(): Unit                         = underlying.printSummary()
    def problems(): Array[xsbti.Problem]             = underlying.problems()
    def comment(pos: xsbti.Position, msg: String): Unit = underlying.comment(pos, msg)
    def log(problem: xsbti.Problem): Unit = {
      recordProblem(problem)
      underlying.log(problem)
    }
  }
}
