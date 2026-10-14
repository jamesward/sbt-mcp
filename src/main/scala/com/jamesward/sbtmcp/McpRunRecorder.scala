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

  final case class Snapshot(problems: Vector[Problem], tests: Vector[TestCase], suiteErrors: Vector[(String, String)])

  private val active      = new AtomicBoolean(false)
  private val problems    = new ConcurrentLinkedQueue[Problem]()
  private val tests       = new ConcurrentLinkedQueue[TestCase]()
  private val suiteErrors = new ConcurrentLinkedQueue[(String, String)]()

  def isActive: Boolean = active.get

  /** Record everything reported while `body` runs. Call it ON the command loop. */
  def recording[A](body: => A): (A, Snapshot) = {
    problems.clear(); tests.clear(); suiteErrors.clear()
    active.set(true)
    try {
      val result = body
      (result, snapshot())
    } finally {
      active.set(false)
    }
  }

  private def snapshot(): Snapshot =
    Snapshot(problems.asScala.toVector.distinct, tests.asScala.toVector, suiteErrors.asScala.toVector.distinct)

  def recordProblem(problem: xsbti.Problem): Unit =
    if (active.get) {
      val position = problem.position
      val severity = problem.severity match {
        case xsbti.Severity.Error => "error"
        case xsbti.Severity.Warn  => "warning"
        case _                    => "info"
      }
      val file =
        position.sourcePath.toScala.filter(_.nonEmpty)
          .orElse(position.sourceFile.toScala.map(_.getPath))
      problems.add(
        Problem(
          severity,
          file,
          position.line.toScala.map(_.intValue),
          // Zinc's `pointer` is the 0-based column; editors and compilers print 1-based.
          position.pointer.toScala.map(_.intValue + 1),
          problem.message,
        )
      )
      ()
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
