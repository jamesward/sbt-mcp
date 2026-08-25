ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))

// Get symbols, add a new symbol, get symbols again — the new symbol must appear.
commands += Command.command("checkIncrementalSymbols") { state =>
  import com.jamesward.sbtmcp.{ SymbolIndex, SymbolIndexState }

  // List ALL symbols in `example` (wildcard) — the same "list the project symbols" flow an agent uses.
  def listExampleSymbols(): List[String] = {
    com.jamesward.sbtmcp.SbtMcpPlugin.refreshFromState(state)
    SymbolIndexState.context match {
      case Some(ctx0) =>
        given tastyquery.Contexts.Context = ctx0
        SymbolIndex.globSearch("*", Some("example")).map(_.fqn)
      case None => Nil
    }
  }

  // 1) initial listing — the base symbol is present, NewThing is not
  val before = listExampleSymbols()
  assert(before.exists(_.contains("example.Base")), s"wildcard listing should include Base, got: $before")
  assert(!before.exists(_.contains("NewThing")), s"NewThing should not exist before it is added, got: $before")

  // 2) add a brand-new symbol
  sbt.io.IO.write(
    new java.io.File("src/main/scala/example/NewThing.scala"),
    "package example\n\nclass NewThing:\n  def hello: String = \"hi\"\n"
  )

  // 3) list again — NewThing must now show up without knowing its name in advance
  val after = listExampleSymbols()
  assert(
    after.exists(_.contains("example.NewThing")),
    s"NewThing should appear in the listing after being added, got: $after"
  )

  state.log.info(s"checkIncrementalSymbols OK: before=$before after=$after")
  state
}
