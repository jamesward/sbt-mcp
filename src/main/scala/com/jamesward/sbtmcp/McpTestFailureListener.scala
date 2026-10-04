package com.jamesward.sbtmcp

import sbt.*
import sbt.protocol.testing.TestResult
import sbt.testing.{ Event as TEvent, NestedTestSelector, Selector, Status as TStatus, SuiteSelector, TestSelector, TestWildcardSelector }

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports test failures through sbt's logger so that they appear in an `sbt-task`
 * response.
 *
 * `sbt-task` returns the delta of sbt's global log file. Test frameworks such as ZIO
 * Test, munit and ScalaTest render their results to the test JVM's stdout, which sbt
 * relays to the server's console (forked or not) and never writes to that file, so a
 * failing `test` / `testOnly` / `testFull` called through MCP returned only
 * `Failed tests: <Spec>`. sbt does hand every test event (status, name, throwable) to
 * its `testListeners`, independently of stdout: the JUnit XML reports are built the
 * same way. This listener logs each failed or errored test with its framework-rendered
 * message, while an `sbt-task` command is running only (so a normal console run isn't
 * duplicated).
 */
private[sbtmcp] final class McpTestFailureListener(log: Logger) extends TestReportListener {
  import McpTestFailureListener.*

  def startGroup(name: String): Unit = ()

  def testEvent(event: TestEvent): Unit =
    if (active.get) event.detail.filter(e => e.status == TStatus.Failure || e.status == TStatus.Error).foreach { e =>
      render(e).linesIterator.foreach(line => log.error(line))
    }

  def endGroup(name: String, t: Throwable): Unit =
    if (active.get) log.error(s"sbt-mcp test error: $name: ${messageOf(t)}")

  def endGroup(name: String, result: TestResult): Unit = ()
}

private[sbtmcp] object McpTestFailureListener {

  /** True while an `sbt-task` (mcpExec) command runs on the loop. */
  val active = new AtomicBoolean(false)

  private val MaxMessageChars = 4000

  private val WrapperPrefixes = List("sbt.internal.worker1.PersistedException:", "java.lang.Exception:")

  def render(e: TEvent): String = {
    // ZIO Test names a test "<Suite> - <test>"; drop a leading suite label that repeats the class.
    val simple = e.fullyQualifiedName.split('.').last
    val test   = testName(e.selector).map(n => n.stripPrefix(s"$simple - ").stripPrefix(s"$simple / "))
    val name   = s"${e.fullyQualifiedName}${test.fold("")(" / " + _)}"
    val status = if (e.status == TStatus.Error) "ERROR" else "FAILED"
    val detail =
      if (e.throwable.isDefined) messageOf(e.throwable.get)
      else "(no failure message from the test framework)"
    s"sbt-mcp test $status: $name\n${indent(detail)}"
  }

  private def testName(s: Selector): Option[String] = s match {
    case t: TestSelector         => Some(t.testName)
    case n: NestedTestSelector   => Some(s"${n.suiteId} / ${n.testName}")
    case w: TestWildcardSelector => Some(w.testWildcard)
    case _: SuiteSelector        => None
    case _                       => None
  }

  def messageOf(t: Throwable): String = {
    // `toString` keeps a real exception class (`java.lang.AssertionError: ...`). A forked test
    // JVM's throwable arrives wrapped (`sbt.internal.worker1.PersistedException:
    // java.lang.Exception: ...`), and frameworks like ZIO Test use a plain `Exception`; those
    // wrappers add nothing, so they are stripped.
    val unwrapped = unwrap(stripAnsi(t.toString).trim)
    val clean     = unwrapped.linesIterator.map(_.stripTrailing).mkString("\n").trim
    if (clean.length <= MaxMessageChars) clean
    else clean.take(MaxMessageChars) + s"\n... (${clean.length - MaxMessageChars} more characters)"
  }

  @annotation.tailrec
  private def unwrap(m: String): String =
    WrapperPrefixes.find(m.startsWith) match {
      case Some(p) => unwrap(m.stripPrefix(p).trim)
      case None    => m
    }

  private def indent(s: String): String = s.linesIterator.map("    " + _).mkString("\n")

  private def stripAnsi(s: String): String = s.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "")
}
