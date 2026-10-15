package sbt

/**
 * sbt keys that sbt-mcp needs but sbt declares `private[sbt]`. Lives in `package sbt`
 * (like [[McpInProcess]]) to re-export them.
 */
object McpKeys {

  /** The compiler reporter; sbt-mcp wraps it to record diagnostics for its structured `compile`/`test` tools. */
  val compilerReporter: TaskKey[xsbti.Reporter] = Keys.compilerReporter
}
