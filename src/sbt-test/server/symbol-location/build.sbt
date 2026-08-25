ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))

// Validate we can get the source location (path:line) of a symbol.
commands += Command.command("checkSymbolLocation") { state =>
  import com.jamesward.sbtmcp.{ SymbolIndex, SymbolIndexState }

  com.jamesward.sbtmcp.SbtMcpPlugin.refreshFromState(state)

  val loc: Option[String] = SymbolIndexState.context match {
    case Some(ctx0) =>
      given tastyquery.Contexts.Context = ctx0
      SymbolIndex.location("example.Located")
    case None => None
  }

  assert(loc.isDefined, "expected a source location for example.Located")
  val l = loc.get
  assert(l.contains("Located.scala"), s"location should reference Located.scala, got: $l")
  assert(l.matches(""".*:\d+"""), s"location should end with :<line>, got: $l")

  state.log.info(s"checkSymbolLocation OK: example.Located -> $l")
  state
}
