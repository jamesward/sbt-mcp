package com.jamesward.sbtmcp

import sbt.protocol.testing.TestResult
import sbt.testing.{ Event as TEvent, Fingerprint, OptionalThrowable, Selector, Status as TStatus, SuiteSelector, TestSelector }
import sbt.{ Level, Logger, TestEvent }
import zio.test.*

import scala.collection.mutable.ListBuffer

object McpTestFailureListenerSpec extends ZIOSpecDefault:

  private final class Recording extends Logger:
    val lines = ListBuffer.empty[(Level.Value, String)]
    def trace(t: => Throwable): Unit                           = ()
    def success(message: => String): Unit                      = ()
    def log(level: Level.Value, message: => String): Unit      = lines += level -> message

  private def event(st: TStatus, sel: Selector, thrown: Option[Throwable]): TEvent = new TEvent:
    def fullyQualifiedName: String   = "webjars.MavenCentralWebJarsSpec"
    def fingerprint: Fingerprint     = null
    def selector: Selector           = sel
    def status: TStatus              = st
    def throwable: OptionalThrowable = thrown.fold(new OptionalThrowable())(new OptionalThrowable(_))
    def duration: Long               = 1L

  private def run(events: TEvent*)(active: Boolean): List[String] =
    val log = Recording()
    McpTestFailureListener.active.set(active)
    try new McpTestFailureListener(log).testEvent(TestEvent(events))
    finally McpTestFailureListener.active.set(false)
    log.lines.toList.map(_._2)

  // What a forked ZIO Test failure looks like by the time sbt hands it to listeners.
  private val forkedFailure = new Exception(
    "sbt.internal.worker1.ForkTestMain$ForkError: sbt.internal.worker1.PersistedException: java.lang.Exception:   - finds versions\n" +
      "    \u001B[31m✗ \u001B[0m\"beta\" was not equal to \"alpha\""
  )

  def spec = suite("McpTestFailureListenerSpec")(
    test("logs a failed test's name and rendered message, without ANSI or fork wrappers") {
      val lines = run(event(TStatus.Failure, new TestSelector("finds versions"), Some(forkedFailure)))(active = true)
      assertTrue(
        lines.head == "sbt-mcp test FAILED: webjars.MavenCentralWebJarsSpec / finds versions",
        lines.exists(_.contains("\"beta\" was not equal to \"alpha\"")),
        !lines.exists(_.contains("\u001B")),
        !lines.exists(_.contains("PersistedException")),
        !lines.exists(_.contains("ForkError")),
        !lines.exists(_.contains("java.lang.Exception")),
      )
    },
    test("reports errors, and failures without a throwable") {
      val lines = run(
        event(TStatus.Error, new SuiteSelector, Some(new IllegalStateException("boom"))),
        event(TStatus.Failure, new TestSelector("t"), None),
      )(active = true)
      assertTrue(
        lines.contains("sbt-mcp test ERROR: webjars.MavenCentralWebJarsSpec"),
        lines.exists(_.contains("java.lang.IllegalStateException: boom")),
        lines.exists(_.contains("(no failure message from the test framework)")),
      )
    },
    test("ignores passing tests") {
      assertTrue(run(event(TStatus.Success, new TestSelector("ok"), None))(active = true).isEmpty)
    },
    test("stays silent outside an sbt-task call, so console runs aren't duplicated") {
      assertTrue(run(event(TStatus.Failure, new TestSelector("t"), Some(new Exception("x"))))(active = false).isEmpty)
    },
    test("truncates very long messages") {
      val lines = run(event(TStatus.Failure, new TestSelector("t"), Some(new Exception("x" * 10000))))(active = true)
      assertTrue(lines.exists(_.contains("more characters)")), lines.map(_.length).max < 5000)
    },
  ) @@ TestAspect.sequential
