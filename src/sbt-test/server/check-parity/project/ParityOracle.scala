import sbt.*

object ParityOracle {
  // A positioned diagnostic, comparable between `check` and `compile`.
  final case class Key(file: String, line: Int, column: Int, severity: String, code: Option[Int]) {
    override def toString = s"$file:$line:$column $severity${code.fold("")(c => s" E$c")}"
  }

  // sbt/dotty batch format: `[error] -- [E007] Type Mismatch Error: /abs/File.scala:4:15 ----`
  private val Header = """^\[(error|warn)\] -- (?:\[E(\d+)\] )?.*?(?:Error|Warning): (.+?):(\d+):(\d+)[\s-]*$""".r

  def compileKeys(output: String): Set[Key] =
    output.linesIterator.collect {
      case Header(level, code, path, line, column) =>
        Key(
          java.nio.file.Paths.get(path).getFileName.toString,
          line.toInt,
          column.toInt,
          if (level == "error") "error" else "warning",
          Option(code).map(_.toInt),
        )
    }.toSet

  def checkKeys(result: com.jamesward.sbtmcp.ModuleCheck.Result): Set[Key] =
    result.diagnostics.collect {
      case d if d.path.isDefined && d.line > 0 && (d.isError || d.isWarning) =>
        Key(d.path.get.getFileName.toString, d.line, d.column, d.severity, d.errorNumber)
    }.toSet

}
