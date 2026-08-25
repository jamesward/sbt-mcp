// A multi-module build: an aggregating root plus three subprojects, all with the
// plugin auto-triggered. Regression test for duplicated `mcpStatus` output — the MCP
// server is a single process-global instance, so `mcpStatus` on the root must print
// its status exactly once, not once per aggregated module.
ThisBuild / scalaVersion := "3.8.4"

Global / mcpEnabled := true
Global / mcpPort    := 5098
Global / mcpHost    := "127.0.0.1"
// Keep this test hermetic — don't proxy javadocs.dev over the network.
Global / mcpDocsUrl := None

lazy val a = (project in file("a"))
lazy val b = (project in file("b"))
lazy val c = (project in file("c"))

lazy val root = (project in file("."))
  .aggregate(a, b, c)
  .settings(name := "multi-module-test")

// Verify the fix that prevents duplicated status output: `mcpStatus` must not
// aggregate, so invoking it on the root runs it in a single (root) scope and prints
// once. We assert the mechanism directly on the root and on each subproject: if any
// were still aggregating, `mcpStatus` on the root would fan out and print N times.
TaskKey[Unit]("checkStatusNoAggregate", "Assert mcpStatus does not aggregate across modules") := {
  val rootAgg = (root / mcpStatus / aggregate).value
  val aAgg    = (a / mcpStatus / aggregate).value
  val bAgg    = (b / mcpStatus / aggregate).value
  val cAgg    = (c / mcpStatus / aggregate).value
  assert(!rootAgg, s"root/mcpStatus/aggregate should be false, was $rootAgg")
  assert(!aAgg, s"a/mcpStatus/aggregate should be false, was $aAgg")
  assert(!bAgg, s"b/mcpStatus/aggregate should be false, was $bAgg")
  assert(!cAgg, s"c/mcpStatus/aggregate should be false, was $cAgg")
  streams.value.log.info("checkStatusNoAggregate OK: mcpStatus/aggregate=false in root and all subprojects")
}
