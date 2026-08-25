package com.jamesward.sbtmcp

import com.jamesward.zio_evals.*
import com.jamesward.zio_evals.cli.ClaudeCliAgentLoop
import zio.*
import zio.test.*
import zio.test.TestAspect.*

import java.net.ServerSocket

// Eval integration test: does an agent asked to "compile the project" use the
// sbt-mcp MCP server's `sbt-task` tool, or shell out to the sbt CLI?
//
// This makes a real, PAID `claude` call, so it is gated on claude being
// installed + credentialed (SKIPPED — a passing no-op — otherwise) and is NOT
// run by CI (which runs `compile; scripted`, not `test`). Run explicitly with:
//   ./sbt -Dlocal "testOnly com.jamesward.sbtmcp.SbtMcpEvalSpec"
//
// The agent is given BOTH a shell (`allowShell = true` -> claude's `Bash`) AND
// the sbt-mcp MCP server, so it must genuinely CHOOSE between running the sbt
// CLI in the shell and calling the `sbt-task` MCP tool. The eval passes only if
// it used the MCP tool and did NOT shell out.
//
// NOTE: this is a BASELINE. With no prompt/MCP-instruction tuning yet, the agent
// may well pick the shell — so this eval is expected to be able to fail until we
// tune the server's tool descriptions / instructions. That's the signal it
// exists to provide.
object SbtMcpEvalSpec extends ZIOSpecDefault:

  // A stubbed sbt-task runner so the MCP `sbt-task` tool "works" (returns a
  // successful compile) without a full sbt session — the eval measures the
  // agent's tool CHOICE, not real compilation.
  private def fakeSbt(command: String): String =
    val c = command.trim
    if c == "compile" || c.endsWith("/compile") then "[ok] compile\n[success] Total time: 1 s, no errors"
    else s"[ok] $c"

  private def freePort: Task[Int] =
    ZIO.attempt {
      val s = ServerSocket(0)
      try s.getLocalPort
      finally s.close()
    }

  // A trivial judge so the run makes exactly ONE paid model call (the arm); the
  // authoritative signal here is the deterministic transcript inspection below,
  // not a judge verdict.
  private val passJudge: Judge =
    new Judge:
      def judge(spec: EvalSpec, answers: List[(EvalArm, String)]): Task[JudgeOutcome] =
        ZIO.succeed(JudgeOutcome(answers.map(_ => (EvalVerdict.Pass, "not judged")), Nil))

  private def usedMcpTool(events: List[TranscriptEvent]): Boolean =
    events.exists {
      case TranscriptEvent.ToolCall(n, _) => n.contains("sbt-task") || n.contains("sbt_task")
      case _                              => false
    }

  private def usedShell(events: List[TranscriptEvent]): Boolean =
    events.exists {
      case TranscriptEvent.ToolCall(n, _) => n == "Bash" || n.toLowerCase.contains("bash")
      case _                              => false
    }

  def spec = suite("SbtMcpEvalSpec")(
    test("compile-the-project prompt uses the sbt-mcp tool, not the sbt CLI") {
      ClaudeCliAgentLoop.isInstalled.zipWith(ClaudeCliAgentLoop.hasCredential)(_ && _).flatMap {
        case false =>
          ZIO.logInfo("SbtMcpEvalSpec skipped: claude not installed or no credential").as(assertCompletes)
        case true =>
          for
            port <- freePort
            result <- ZIO.scoped {
                        for
                          _ <- ZIO.acquireRelease(
                                 ZIO.attempt(McpServerRuntime.start(
                                   host       = "127.0.0.1",
                                   port       = port,
                                   runCommand = fakeSbt,
                                   refresh    = () => None,
                                   listTasks  = () => List("compile" -> "Compiles sources"),
                                   docsUrl    = None,
                                 ))
                               )(h => ZIO.attempt(h.close()).ignoreLogged)
                          // Give the daemon server a moment to bind the port.
                          _ <- ZIO.sleep(1.second)
                          agent = ClaudeCliAgentLoop(modelOverride = Some("claude-sonnet-4-6"), allowShell = true)
                          spec0 = EvalSpec(
                                    task     = "Compile this sbt project and tell me whether it compiled successfully.",
                                    criteria = "The project is compiled by calling the sbt-mcp `sbt-task` tool, not by running the sbt CLI in a shell.",
                                    checks   = List(EvalCheck.ToolCalled("sbt-task")),
                                  )
                          arm   = EvalArm.mcp("sbtmcp", "Agent with sbt-mcp", List(McpServerConfig("sbtmcp", s"http://127.0.0.1:$port/")))
                          rs   <- EvalRunner.run(spec0, List(arm), List("claude-sonnet-4-6"), samples = 1, agent, passJudge)
                        yield rs.head
                      }
            events = result.samples.flatMap(_.events)
            mcp    = usedMcpTool(events)
            shell  = usedShell(events)
            _     <- ZIO.logInfo(s"SbtMcpEvalSpec: usedMcpTool=$mcp usedShell=$shell checksPassed=${result.checksPassed}\nanswer: ${result.samples.headOption.map(_.answer).getOrElse("")}")
          yield assertTrue(mcp, !shell)
      }
    }
  ) @@ withLiveClock @@ timeout(300.seconds) @@ sequential
