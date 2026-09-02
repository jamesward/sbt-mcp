package com.jamesward.sbtmcp

import zio.test.*

object SbtMcpPluginSpec extends ZIOSpecDefault:

  private val ToolbookInstructions =
    SbtMcpPlugin.installInstructions(
      serverName = "sbt-mcp-toolbook",
      url        = "http://127.0.0.1:5012/",
      enabled    = true,
      port       = 5012,
    )

  def spec = suite("SbtMcpPluginSpec")(
    test("starts only when enabled") {
      assertTrue(
        SbtMcpPlugin.shouldStartServer(enabled = true, disableInCI = true, environment = Map.empty),
        !SbtMcpPlugin.shouldStartServer(enabled = false, disableInCI = true, environment = Map.empty),
      )
    },
    test("does not start in CI when CI disabling is enabled") {
      assertTrue(
        !SbtMcpPlugin.shouldStartServer(enabled = true, disableInCI = true, environment = Map("CI" -> "true")),
        !SbtMcpPlugin.shouldStartServer(enabled = true, disableInCI = true, environment = Map("CI" -> "1")),
      )
    },
    test("can explicitly start in CI") {
      assertTrue(
        SbtMcpPlugin.shouldStartServer(enabled = true, disableInCI = false, environment = Map("CI" -> "true"))
      )
    },
    test("false-like CI values are not treated as CI") {
      assertTrue(
        SbtMcpPlugin.shouldStartServer(enabled = true, disableInCI = true, environment = Map("CI" -> "false")),
        SbtMcpPlugin.shouldStartServer(enabled = true, disableInCI = true, environment = Map("CI" -> "0")),
        SbtMcpPlugin.shouldStartServer(enabled = true, disableInCI = true, environment = Map("CI" -> "")),
      )
    },
    test("issue 1: generated client timeout covers the full server command wait") {
      assertTrue(
        SbtMcpPlugin.CommandWait.toMillis == 1800000L,
        SbtMcpPlugin.RecommendedClientTimeout >= SbtMcpPlugin.CommandWait,
        ToolbookInstructions.contains("\"timeout\": 1800000"),
      )
    },
    test("issue 2: install output prominently explains that config reconciliation is manual") {
      val lower = ToolbookInstructions.toLowerCase
      assertTrue(
        ToolbookInstructions.contains("\"sbt-mcp-toolbook\""),
        lower.contains("does not inspect or edit"),
        lower.contains("existing config"),
        lower.contains("same url"),
        ToolbookInstructions.contains("RECONNECT / re-init"),
      )
    },
    test("installation lifecycle: enabled instructions say sbt must remain alive") {
      val lower = ToolbookInstructions.toLowerCase
      assertTrue(
        lower.contains("does not daemonize"),
        lower.contains("remain running"),
        lower.contains("one-shot"),
      )
    },
    test("issue 4: successful command with no captured output includes execution evidence") {
      val empty      = SbtMcpPlugin.commandResultText("api/compile", Right(()), "")
      val withOutput = SbtMcpPlugin.commandResultText("api/compile", Right(()), "compiled")
      assertTrue(
        empty.startsWith("[ok] api/compile"),
        empty.contains("elapsed:"),
        empty.contains("captured output: empty"),
        withOutput.contains("elapsed:"),
        withOutput.endsWith("compiled"),
      )
    },
    test("issue 5: installer documents a plain direct-capture command") {
      assertTrue(
        ToolbookInstructions.contains("--server"),
        ToolbookInstructions.contains("--no-colors"),
        ToolbookInstructions.contains("--supershell=false"),
        !ToolbookInstructions.contains('\u001B'),
      )
    },
  )
