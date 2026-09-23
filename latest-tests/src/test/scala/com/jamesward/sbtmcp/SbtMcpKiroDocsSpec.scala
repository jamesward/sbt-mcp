package com.jamesward.sbtmcp

import com.jamesward.zio_evals.*
import com.jamesward.zio_evals.cli.KiroCliAgentLoop
import com.jamesward.ziohttp.mcp.ProtocolVersion
import com.jamesward.ziohttp.mcp.client.{ McpClient, McpClientConfig }
import zio.*
import zio.http.*
import zio.test.*
import zio.test.TestAspect.*

import java.net.ServerSocket

/**
 * Does an MCP client — in particular **kiro-cli** — actually receive the
 * documentation tools that sbt-mcp proxies from the REAL javadocs.dev?
 *
 * These tests point [[McpServerRuntime]] at the real `https://www.javadocs.dev/mcp`
 * upstream (the plugin default), so running them triggers a genuine outbound proxy
 * connection — visible in the javadocs.dev prod logs — and asserts the proxied
 * tools are merged into `tools/list`. They REQUIRE outbound network to javadocs.dev
 * and FAIL (not skip) if the proxied tools don't appear, so a broken proxy path is
 * surfaced rather than hidden.
 *
 *   1. [[proxies real javadocs.dev tools over kiro-cli's protocol]] — no model call.
 *      kiro-cli speaks Streamable HTTP with the Rust `rmcp` client and negotiates
 *      revision `2025-11-25` (confirmed from its verbose startup log). We connect the
 *      zio-http-mcp client PINNED to that exact revision and assert `tools/list`
 *      returns the five built-ins PLUS at least one proxied javadocs.dev tool.
 *
 *   2. [[kiro-cli end-to-end can call a proxied javadocs.dev tool]] — a real
 *      `kiro-cli chat` call. It runs whenever kiro-cli is installed + authenticated
 *      and is marked ignored otherwise (this is NOT cost-based gating). kiro-cli is
 *      asked to call the proxied `get_latest_version` tool; a version string can only
 *      come back if kiro-cli received the tool and invoked it through sbt-mcp.
 */
object SbtMcpKiroDocsSpec extends ZIOSpecDefault:

  private val DocsUrl  = "https://www.javadocs.dev/mcp"
  private val builtIns = Set("sbt-task", "list-tasks", "glob-search", "inspect", "symbol-location")

  // A stubbed sbt runner so the built-in `sbt-task` "works" without a real build —
  // this spec only cares about tool LISTING / proxying, not task execution.
  private def fakeSbt(command: String): String = s"[ok] ${command.trim}"

  private def freePort: Task[Int] =
    ZIO.attempt {
      val s = ServerSocket(0)
      try s.getLocalPort
      finally s.close()
    }

  /** Start a real [[McpServerRuntime]] proxying the given docs URL, hand its URL to
    * `use`, and tear it down afterward. */
  private def withServer[A](docsUrl: Option[String])(use: String => Task[A]): Task[A] =
    ZIO.scoped {
      for
        ourPort <- freePort
        _       <- ZIO.acquireRelease(
                     ZIO.attempt(McpServerRuntime.start(
                       host       = "127.0.0.1",
                       port       = ourPort,
                       runCommand = fakeSbt,
                       refresh    = () => None,
                       listTasks  = () => Nil,
                       docsUrl    = docsUrl,
                     ))
                   )(h => ZIO.attempt(h.close()).ignoreLogged)
        _       <- ZIO.sleep(1.second) // let the daemon server bind
        out     <- use(s"http://127.0.0.1:$ourPort/")
      yield out
    }

  /** Connect to OUR server pinned to kiro-cli's protocol (2025-11-25) and list tools,
    * retrying until proxied tools have merged in (or the retry budget is exhausted). */
  private def listToolsLikeKiro(ourUrl: String): Task[(String, Set[String])] =
    ZIO.scoped {
      McpClient
        .connect(McpClientConfig(ourUrl, preferredVersion = ProtocolVersion.V2025_11_25))
        .flatMap { c =>
          c.listTools.map(_.map(_.name.toString).toSet).flatMap { names =>
            if ((names -- builtIns).nonEmpty && builtIns.subsetOf(names)) ZIO.succeed((c.protocolVersion, names))
            else ZIO.fail(new RuntimeException(s"proxied javadocs.dev tools not merged yet; have: $names"))
          }
        }
    }.provide(Client.default)
      .retry(Schedule.recurs(30) && Schedule.spaced(1.second))

  private val ansiControl = "\u001B\\[[0-?]*[ -/]*[@-~]".r

  private def stripAnsi(text: String): String =
    ansiControl.replaceAllIn(text, "").replace("\r", "")


  def spec = suite("SbtMcpKiroDocsSpec")(
    test("zio-evals Kiro config exposes and trusts MCP server tools") {
      val config = KiroCliAgentLoop().agentConfig(
        modelId   = "",
        mcpServers = List(McpServerConfig("sbtmcp", "http://127.0.0.1:1/")),
        policy    = AgentPolicy.default,
      )
      assertTrue(
        config.tools.contains("@sbtmcp"),
        config.allowedTools.contains("@sbtmcp"),
      )
    },
    test("proxies real javadocs.dev tools over kiro-cli's protocol (2025-11-25)") {
      withServer(Some(DocsUrl)) { ourUrl =>
        listToolsLikeKiro(ourUrl).map { case (negotiated, names) =>
          val proxied = names -- builtIns
          assertTrue(
            negotiated == "2025-11-25",
            builtIns.subsetOf(names),
            proxied.nonEmpty,
          )
        }
      }
    },
    test("kiro-cli /tools lists proxied javadocs.dev tools") {
      withServer(Some(DocsUrl)) { ourUrl =>
        val agent = KiroCliAgentLoop(runTimeout = 60.seconds)
        for
          result <- agent.run(
                      "/tools",
                      modelId   = "",
                      mcpServers = List(McpServerConfig("sbtmcp", ourUrl)),
                      policy    = AgentPolicy.default,
                    )
          answer  = result.answer
          _      <- ZIO.logInfo(s"SbtMcpKiroDocsSpec: kiro-cli /tools output=$answer")
        yield assertTrue(
          answer.contains("sbt-task"),
          answer.contains("get_latest_version"),
        )
      }
    } @@ CliTestGates.ifKiroAvailable,
    test("kiro-cli end-to-end can call a proxied javadocs.dev tool") {
      withServer(Some(DocsUrl)) { ourUrl =>
        val agent  = KiroCliAgentLoop(runTimeout = 180.seconds)
        val prompt =
          "An MCP server named `sbtmcp` is connected. It proxies documentation tools from " +
            "javadocs.dev, including one named `get_latest_version`. Call the `get_latest_version` " +
            "tool with arguments {\"groupId\":\"dev.zio\",\"artifactId\":\"zio_3\"} and reply with ONLY " +
            "the version string it returns."
        for
          result <- agent.run(
                      prompt,
                      modelId   = "",
                      mcpServers = List(McpServerConfig("sbtmcp", ourUrl)),
                      policy    = AgentPolicy.default,
                    )
          clean   = stripAnsi(result.answer)
          _      <- ZIO.logInfo(s"SbtMcpKiroDocsSpec: kiro-cli answer=$clean")
        yield assertTrue(
          clean.contains("get_latest_version"),
          clean.contains("from mcp server: sbtmcp"),
          clean.matches("""(?s).*?>\s+\d+\.\d+\.\d+(?:[-+][^\s]+)?.*"""),
          !clean.contains("I don't have access to an sbtmcp MCP server"),
        )
      }
    } @@ CliTestGates.ifKiroAvailable,
  ) @@ withLiveClock @@ timeout(360.seconds) @@ sequential
