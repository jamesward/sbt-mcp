package com.jamesward.sbtmcp

import zio.test.*

object SbtMcpPluginSpec extends ZIOSpecDefault:

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
  )
