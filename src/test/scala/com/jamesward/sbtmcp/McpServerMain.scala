package com.jamesward.sbtmcp

import java.util.concurrent.CountDownLatch

/**
 * Standalone launcher for the MCP server — lets you start it directly from this
 * project (no sbt-plugin publish or downstream build needed) for manual testing:
 *
 * {{{
 *   sbt 'Test/runMain com.jamesward.sbtmcp.McpServerMain'
 *   SBT_MCP_PORT=5099 sbt 'Test/runMain com.jamesward.sbtmcp.McpServerMain'
 * }}}
 *
 * It indexes THIS JVM's classpath (so `glob-search` / `inspect` have TASTy to read
 * — e.g. search within `com.jamesward.sbtmcp`) and serves MCP at `http://host:port/`.
 * `sbt-task` / `list-tasks` report "no build" here, since there is no loaded sbt
 * build backing this standalone process (that path only works inside the plugin).
 */
object McpServerMain {
  def main(args: Array[String]): Unit = {
    val host = sys.env.getOrElse("SBT_MCP_HOST", "127.0.0.1")
    val port = sys.env.get("SBT_MCP_PORT").flatMap(_.toIntOption).getOrElse(5010)

    SymbolIndexState.updateFromClasspathString("standalone", sys.props.getOrElse("java.class.path", ""))

    val runCommand: String => String = _ => "sbt-task: unavailable in standalone mode (no loaded sbt build)"
    val handle = McpServerRuntime.start(host, port, runCommand, () => None, () => Nil, None)
    println(s"[sbt-mcp] standalone MCP server: http://$host:$port/")
    println("[sbt-mcp] indexed this JVM's classpath as project 'standalone'.")
    println("[sbt-mcp] try glob-search {\"query\":\"SymbolIndex\",\"inPackage\":\"com.jamesward.sbtmcp\"}")
    println("[sbt-mcp] press Ctrl-C to stop.")

    val latch = new CountDownLatch(1)
    Runtime.getRuntime.addShutdownHook(new Thread(() => { handle.close(); latch.countDown() }))
    latch.await()
  }
}
