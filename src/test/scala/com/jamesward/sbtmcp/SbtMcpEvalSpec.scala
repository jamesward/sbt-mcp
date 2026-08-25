package com.jamesward.sbtmcp

import com.jamesward.zio_evals.*
import com.jamesward.zio_evals.cli.{ ClaudeCliAgentLoop, KiroCliAgentLoop }
import zio.*
import zio.process.{ Command, ProcessInput }
import zio.test.*
import zio.test.TestAspect.*

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

// Eval integration tests: when an agent is asked to compile the project or run
// its tests, does it use sbt-mcp's `sbt-task` tool rather than invoke the sbt CLI?
//
// The two prompt scenarios each run against real Claude and Kiro CLI agents. Each
// model-backed test makes one PAID model call when its CLI is installed and
// authenticated; otherwise that provider's tests are marked ignored. CI runs
// only `./sbt 'compile; scripted'` and never invokes `test`/`Test/test`/`testOnly`, so
// these test bodies (and paid calls) are not executed there. Run locally with:
//   ./sbt "testOnly com.jamesward.sbtmcp.SbtMcpEvalSpec"
//
// Each agent is given BOTH a shell (Claude `Bash`; Kiro `execute_bash`) AND the
// sbt-mcp MCP server, so it must genuinely choose how to drive the build. Benign
// shell use (e.g. `ls`) is allowed; invoking `sbt`, `./sbt`, or `sbtn` in that shell
// fails the eval. A passing Claude arm proves its ToolCalled check passed, its
// transcript contains `sbt-task`, and no Bash event invokes the sbt CLI. A passing
// Kiro arm proves its rendered tool trace contains an sbt-mcp `sbt-task` call and no
// execute_bash block invokes the sbt CLI. The Claude judge is deliberately a no-op
// and is NOT evidence of semantic answer correctness.
object SbtMcpEvalSpec extends ZIOSpecDefault:

  private val compileTask =
    "Compile this sbt project and tell me whether it compiled successfully."

  private val testSuiteTask =
    "Run this project's full test suite and tell me whether all tests pass. " +
      "If something is unclear, first check reload status."

  // A stubbed sbt-task runner so the MCP `sbt-task` tool "works" (returns a
  // successful compile/test) without a full sbt session — the eval measures the
  // agent's tool CHOICE, not real compilation.
  private def fakeSbt(command: String): String =
    val c = command.trim
    if c == "compile" || c.endsWith("/compile") then "[ok] compile\n[success] Total time: 1 s, no errors"
    else if c == "test" || c.endsWith("/test") || c.startsWith("testOnly") then
      "[ok] " + c + "\n[info] All tests passed.\n[success] Total time: 2 s"
    else if c.endsWith("reloadStatus") then "[ok] " + c + "\n[info] No reload needed."
    else s"[ok] $c"

  private def freePort: Task[Int] =
    ZIO.attempt {
      val s = ServerSocket(0)
      try s.getLocalPort
      finally s.close()
    }

  // EvalRunner requires a Judge, but this tool-choice eval has nothing semantic to
  // judge: the authoritative signals are zio-evals' deterministic ToolCalled check
  // plus our sbt-CLI transcript classifier. Returning Pass avoids a second paid model
  // call. Consequently ArmResult.verdict/passRate are plumbing outcomes here, NOT an
  // independent judgment that the answer is correct.
  private val noOpJudge: Judge =
    new Judge:
      def judge(spec: EvalSpec, answers: List[(EvalArm, String)]): Task[JudgeOutcome] =
        ZIO.succeed(JudgeOutcome(answers.map(_ => (EvalVerdict.Pass, "not judged")), Nil))

  private def usedMcpTool(events: List[TranscriptEvent]): Boolean =
    events.exists {
      case TranscriptEvent.ToolCall(n, _) => n.contains("sbt-task") || n.contains("sbt_task")
      case _                              => false
    }

  // The regression this eval guards against is the agent driving the build by
  // invoking the **sbt CLI in a shell** (`./sbt test`, `sbt compile`, …) instead of
  // the `sbt-task` MCP tool. It deliberately does NOT flag *any* shell use: an agent
  // may legitimately run `ls`/`cat` to look around while still using `sbt-task` for
  // the build — that is not the failure mode. So we inspect each Bash tool call's raw
  // JSON `input` and match only an actual sbt invocation.
  //
  // Matches an sbt invocation in a shell command string, in two forms:
  //   - a path form `/sbt` / `/sbtn` (`./sbt`, `/usr/bin/sbt`, `bin/sbt`) — the slash
  //     makes it unambiguously an executable, and a trailing boundary rules out files
  //     like `project/build.sbt` (that ends `.sbt`, and `foo/sbt.txt` fails the boundary);
  //   - a bare `sbt` / `sbtn` ONLY at a command position: the start of the command
  //     string (in the JSON that is right after the opening `"`), or immediately after a
  //     shell separator (`;`/`&`/`|`/`(`/newline/backtick). This is what excludes `sbt`
  //     appearing as a mere argument, e.g. `grep -n sbt project/plugins.sbt`.
  private val sbtCliInvocation =
    """(?i)(?:/sbtn?|(?:^|["`;&|(\n])\s*sbtn?)(?=\s|["';&|)]|$)""".r

  private def shelledOutToSbt(events: List[TranscriptEvent]): Boolean =
    events.exists {
      case TranscriptEvent.ToolCall(n, input) if n == "Bash" || n.toLowerCase.contains("bash") =>
        sbtCliInvocation.findFirstIn(input).isDefined
      case _ => false
    }

  // Kiro's headless output is rendered terminal text rather than structured
  // TranscriptEvents. Normalize ANSI controls, then classify its explicit
  // "Running tool ..." blocks. Keep shell analysis scoped to execute_bash blocks
  // so an answer merely mentioning `./sbt` does not become a false positive.
  private val ansiControl = "\u001B\\[[0-?]*[ -/]*[@-~]".r
  private val kiroMcpCall = """(?i)Running tool sbt-task\b[^\n]*from mcp server:\s*sbtmcp""".r
  private val kiroShellBlock =
    """(?is)Running tool (?:execute_bash|shell|bash)\b(.*?)(?=\n\s*-\s*(?:Completed|Failed)|\z)""".r

  private def stripAnsi(text: String): String =
    ansiControl.replaceAllIn(text, "").replace("\r", "")

  private def kiroUsedMcpTool(output: String): Boolean =
    kiroMcpCall.findFirstIn(stripAnsi(output)).isDefined

  private def kiroShelledOutToSbt(output: String): Boolean =
    kiroShellBlock
      .findAllMatchIn(stripAnsi(output))
      .exists(m => sbtCliInvocation.findFirstIn(m.group(1)).isDefined)

  /**
   * Run kiro-cli with a temporary agent exposing both its shell and sbt-mcp.
   * KiroCliAgentLoop 0.0.2 supplies the corrected MCP server grant in `tools`;
   * this harness adds `execute_bash` so Kiro faces the same real choice as Claude.
   */
  private def runKiroToolChoiceEval(task: String): Task[TestResult] =
    for
      port <- freePort
      output <- ZIO.scoped {
                  for
                    _ <- ZIO.acquireRelease(
                           ZIO.attempt(McpServerRuntime.start(
                             host       = "127.0.0.1",
                             port       = port,
                             runCommand = fakeSbt,
                             refresh    = () => None,
                             listTasks  = () => List("compile" -> "Compiles sources", "test" -> "Runs the tests"),
                             docsUrl    = None,
                           ))
                         )(h => ZIO.attempt(h.close()).ignoreLogged)
                    _ <- ZIO.sleep(1.second)
                    loop    = KiroCliAgentLoop(runTimeout = 180.seconds)
                    servers = List(McpServerConfig("sbtmcp", s"http://127.0.0.1:$port/"))
                    base    = loop.agentConfig("", servers, AgentPolicy.default)
                    config  = base.copy(
                                tools        = (base.tools :+ "execute_bash").distinct,
                                allowedTools = (base.allowedTools :+ "execute_bash").distinct,
                              )
                    args    = loop.cliArgs(task, "", requireMcpStartup = true)
                    cwd <- ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("sbt-mcp-kiro-eval").toFile))(
                             dir => ZIO.attempt(deleteRecursively(dir)).ignoreLogged
                           )
                    _ <- ZIO.attempt {
                           val agentsDir = File(cwd, ".kiro/agents")
                           agentsDir.mkdirs()
                           Files.writeString(File(agentsDir, "eval.json").toPath, EvalCodecs.encode(config))
                           ()
                         }
                    out <- Command("kiro-cli", args*)
                             .workingDirectory(cwd)
                             .stdin(ProcessInput.fromUTF8String(""))
                             .redirectErrorStream(true)
                             .env(Map("KIRO_LOG_NO_COLOR" -> "1"))
                             .string
                             .timeoutFail(RuntimeException("kiro-cli tool-choice eval exceeded 180 seconds"))(180.seconds)
                  yield out
                }
      clean   = stripAnsi(output)
      usedMcp = kiroUsedMcpTool(clean)
      sbtCli  = kiroShelledOutToSbt(clean)
      _      <- ZIO.logInfo(s"SbtMcpEvalSpec Kiro: usedMcpTool=$usedMcp shelledOutToSbt=$sbtCli\noutput:\n$clean")
    yield assertTrue(usedMcp, !sbtCli)

  private def deleteRecursively(file: File): Unit =
    if file.isDirectory then Option(file.listFiles()).foreach(_.foreach(deleteRecursively))
    file.delete()
    ()

  def spec = suite("SbtMcpEvalSpec")(
    // Deterministic (no model call) guard for the transcript classifier itself: the
    // shell-out detector must fire ONLY on a real sbt CLI invocation, not on benign
    // shell use (`ls`, `cat build.sbt`) or the `sbt-task` MCP tool. This is the exact
    // distinction that made the run-the-tests eval flap: the agent used `sbt-task` for
    // the build but ran `ls` to look around, which must NOT count as shelling out.
    test("shelledOutToSbt flags sbt CLI invocations only, not benign shell use") {
      def bash(cmd: String): TranscriptEvent =
        TranscriptEvent.ToolCall("Bash", s"""{"command":"$cmd","description":"d"}""")
      def mcp(cmd: String): TranscriptEvent =
        TranscriptEvent.ToolCall("mcp__sbtmcp__sbt-task", s"""{"command":"$cmd"}""")

      // Should NOT be treated as shelling out to sbt:
      val benign = List(
        bash("ls /tmp/agent-eval123"),
        bash("cat build.sbt"),
        bash("grep -n sbt project/plugins.sbt"),
        mcp("test"),
        mcp("compile"),
      )
      // SHOULD be treated as shelling out to sbt:
      val shellouts = List(
        bash("./sbt test"),
        bash("sbt compile"),
        bash("sbtn shutdown"),
        bash("/home/user/sbt \\\"testOnly com.example.MySpec\\\""),
        bash("cat build.sbt && ./sbt test"),
      )

      assertTrue(
        !shelledOutToSbt(benign),
        benign.forall(e => !shelledOutToSbt(List(e))),
        shellouts.forall(e => shelledOutToSbt(List(e))),
        usedMcpTool(List(mcp("test"))),
        !usedMcpTool(List(bash("ls"))),
      )
    },
    test("Kiro tool trace detects sbt-mcp use and sbt CLI shell-outs") {
      val mcp =
        """Running tool sbt-task with the param (from mcp server: sbtmcp)
          | ⋮  {"command":"test"}
          | - Completed in 1.0s""".stripMargin
      val benignShell =
        """Running tool execute_bash with the param
          | ⋮  {"command":"ls -la"}
          | - Completed in 0.1s""".stripMargin
      val sbtShell =
        """Running tool execute_bash with the param
          | ⋮  {"command":"./sbt test > /tmp/failures.log 2>&1"}
          | - Completed in 2.0s""".stripMargin

      assertTrue(
        kiroUsedMcpTool(mcp),
        !kiroShelledOutToSbt(mcp),
        !kiroShelledOutToSbt(benignShell),
        kiroShelledOutToSbt(sbtShell),
      )
    },
    test("Claude compile prompt uses the sbt-mcp tool, not the sbt CLI") {
      runToolChoiceEval(
        task     = compileTask,
        criteria = "The project is compiled by calling the sbt-mcp `sbt-task` tool, not by running the sbt CLI in a shell.",
      )
    } @@ CliTestGates.ifClaudeAvailable,
    // Regression eval for an observed behavior: on a "run the tests (and fix
    // failures)" task the agent called `sbt-task` once (e.g. Test/reloadStatus) and
    // then REVERTED to `./sbt test` in the shell to capture output for grepping.
    // Running the suite is exactly what `sbt-task {"command":"test"}` is for — and it
    // already returns captured compiler/test output — so shelling out to `./sbt` is
    // the failure this eval guards against.
    test("Claude test-suite prompt uses the sbt-mcp tool, not the sbt CLI") {
      runToolChoiceEval(
        task = testSuiteTask,
        criteria =
          "The tests are run by calling the sbt-mcp `sbt-task` tool (e.g. command \"test\"), " +
            "NOT by running the sbt CLI (`./sbt test` / `sbt test`) in a shell.",
      )
    } @@ CliTestGates.ifClaudeAvailable,
    test("Kiro compile prompt uses the sbt-mcp tool, not the sbt CLI") {
      runKiroToolChoiceEval(compileTask)
    } @@ CliTestGates.ifKiroAvailable,
    test("Kiro test-suite prompt uses the sbt-mcp tool, not the sbt CLI") {
      runKiroToolChoiceEval(testSuiteTask)
    } @@ CliTestGates.ifKiroAvailable,
  ) @@ withLiveClock @@ timeout(900.seconds) @@ sequential

  // Shared harness: stand up the MCP server (with the stubbed sbt runner), give the
  // agent BOTH a shell and the MCP server, run one paid sample, and enforce both
  // zio-evals' built-in ToolCalled check and the stricter transcript assertions:
  // `sbt-task` was used and the shell was not used to invoke an sbt CLI.
  private def runToolChoiceEval(task: String, criteria: String) =
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
                             listTasks  = () => List("compile" -> "Compiles sources", "test" -> "Runs the tests"),
                             docsUrl    = None,
                           ))
                         )(h => ZIO.attempt(h.close()).ignoreLogged)
                    // Give the daemon server a moment to bind the port.
                    _ <- ZIO.sleep(1.second)
                    agent = ClaudeCliAgentLoop(modelOverride = Some("claude-sonnet-4-6"), allowShell = true)
                    spec0 = EvalSpec(
                              task     = task,
                              criteria = criteria,
                              checks   = List(EvalCheck.ToolCalled("sbt-task")),
                            )
                    arm   = EvalArm.mcp("sbtmcp", "Agent with sbt-mcp", List(McpServerConfig("sbtmcp", s"http://127.0.0.1:$port/")))
                    rs   <- EvalRunner.run(spec0, List(arm), List("claude-sonnet-4-6"), samples = 1, agent, noOpJudge)
                  yield rs.head
                }
      events = result.samples.flatMap(_.events)
      mcp    = usedMcpTool(events)
      sbtCli = shelledOutToSbt(events)
      _     <- ZIO.logInfo(s"SbtMcpEvalSpec: usedMcpTool=$mcp shelledOutToSbt=$sbtCli checksPassed=${result.checksPassed}\nanswer: ${result.samples.headOption.map(_.answer).getOrElse("")}")
    yield assertTrue(result.checksPassed, mcp, !sbtCli)
