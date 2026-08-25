package com.jamesward.sbtmcp

import com.jamesward.zio_evals.cli.{ ClaudeCliAgentLoop, KiroCliAgentLoop }
import zio.*
import zio.test.*

/**
 * Availability gates for real CLI/model tests. Modeled on zio-evals'
 * `RealCliTestGates`, which lives in that project's test sources and therefore is
 * not part of the published library.
 *
 * Missing or unauthenticated CLIs mark their model-backed tests IGNORED rather than
 * failed or spuriously passed. There is no cost-based gate: when a CLI is available,
 * its paid test runs.
 */
object CliTestGates:

  private val claudeAvailable: UIO[Boolean] =
    ClaudeCliAgentLoop.isInstalled
      .zipWith(ClaudeCliAgentLoop.hasCredential)(_ && _)
      .catchAllCause(c => ZIO.logWarningCause("Claude CLI availability check failed; tests will be ignored", c).as(false))

  private val kiroAvailable: UIO[Boolean] =
    KiroCliAgentLoop.validate.isSuccess
      .catchAllCause(c => ZIO.logWarningCause("Kiro CLI availability check failed; tests will be ignored", c).as(false))

  val ifClaudeAvailable: TestAspectPoly = availability(claudeAvailable)
  val ifKiroAvailable: TestAspectPoly   = availability(kiroAvailable)

  private def availability(available: UIO[Boolean]): TestAspectPoly =
    new TestAspectPoly:
      def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        spec.whenZIO(Live.live(available))
