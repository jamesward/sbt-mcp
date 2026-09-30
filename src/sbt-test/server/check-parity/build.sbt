import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

// Differential ("oracle") test for the `check` tool: every corpus case is checked
// with `check` (both as an unsaved overlay and from disk) AND compiled with the
// real `compile`, and the two sets of positioned diagnostics must be identical.
// The matrix spans the oldest supported Scala (3.3.1, which the check bridge is
// compiled against), the 3.3 LTS head, several minors up to 3.9, and strict /
// future-source option sets on both ends.

lazy val strictOptions = Seq("-Werror", "-Wunused:all", "-Yexplicit-nulls", "-deprecation", "-feature")

lazy val v331 = (project in file("v331")).settings(scalaVersion := "3.3.1")
lazy val v338 = (project in file("v338")).settings(scalaVersion := "3.3.8")
lazy val v352 = (project in file("v352")).settings(scalaVersion := "3.5.2")
lazy val v374 = (project in file("v374")).settings(scalaVersion := "3.7.4")
lazy val v384 = (project in file("v384")).settings(scalaVersion := "3.8.4")
lazy val v390 = (project in file("v390")).settings(scalaVersion := "3.9.0")
lazy val strict338 = (project in file("strict338")).settings(
  scalaVersion := "3.3.8",
  scalacOptions ++= strictOptions :+ "-Ysafe-init",
)
lazy val strict390 = (project in file("strict390")).settings(
  scalaVersion := "3.9.0",
  scalacOptions ++= strictOptions :+ "-Wsafe-init",
)
lazy val future390 = (project in file("future390")).settings(
  scalaVersion := "3.9.0",
  scalacOptions ++= Seq("-source:future", "-explain", "-deprecation"),
)

lazy val root = (project in file("."))
  .settings(scalaVersion := "3.9.0")
  .aggregate(v331, v338, v352, v374, v384, v390, strict338, strict390, future390)

// Each compile runs against the loop's ORIGINAL state and the command returns it:
// a failed compile consumes the state's `onFailure` handler, which must not leak
// back into the loop (the same rule as the plugin's own `mcpExec`).
commands += Command.single("checkParity") { (state, module) =>
  val base      = Project.extract(state).structure.root
  val root      = java.nio.file.Paths.get(base)
  val corpusDir = root.resolve("corpus")
  val caseDir   = root.resolve(s"$module/src/main/scala/corpus")
  Files.createDirectories(caseDir)
  val cases = Files.list(corpusDir).toArray.map(_.asInstanceOf[Path]).sortBy(_.getFileName.toString).toList

  val failures = List.newBuilder[String]
  val timings  = List.newBuilder[(String, Long, Long, Long)]

  cases.foreach { corpusFile =>
    val caseName = corpusFile.getFileName.toString.stripSuffix(".scala")
    val fileName = "Case" + caseName.takeWhile(_ != '-') + ".scala"
    val target   = caseDir.resolve(fileName)
    val text     = new String(Files.readAllBytes(corpusFile), StandardCharsets.UTF_8)
    val relative = root.relativize(target).toString

    // 1) unsaved overlay: the file does not exist on disk yet
    val overlay = com.jamesward.sbtmcp.SbtMcpPlugin.checkResultOnLoop(state, List(relative), Some(text))
    // 2) the same content from disk
    Files.write(target, text.getBytes(StandardCharsets.UTF_8))
    val disk = com.jamesward.sbtmcp.SbtMcpPlugin.checkResultOnLoop(state, List(relative))
    // 3) the oracle: a real incremental compile of the module
    val started = System.nanoTime()
    val (_, compiled, output) = sbt.McpInProcess.runOnLoop(state, s"$module/compile")
    val compileMillis = (System.nanoTime() - started) / 1000000
    Files.delete(target)

    val expected       = ParityOracle.compileKeys(output)
    val compileFailed  = compiled.isLeft
    def compare(label: String, result: Either[String, com.jamesward.sbtmcp.ModuleCheck.Result]): Unit =
      result match {
        case Left(error) => failures += s"$module $caseName [$label]: check refused: $error"
        case Right(r) =>
          val actual = ParityOracle.checkKeys(r)
          if (actual != expected || r.failed != compileFailed)
            failures += (
              s"$module $caseName [$label]: check vs compile differ\n" +
                s"    compile failed=$compileFailed: ${expected.toList.sortBy(_.toString).mkString(", ")}\n" +
                s"    check   failed=${r.failed}: ${actual.toList.sortBy(_.toString).mkString(", ")}\n" +
                s"    missing from check: ${(expected -- actual).mkString(", ")}\n" +
                s"    extra in check:     ${(actual -- expected).mkString(", ")}\n" +
                r.diagnostics.map(d => s"      check> ${d.severity} ${d.line}:${d.column} ${d.message.linesIterator.nextOption().getOrElse("")}").mkString("\n") +
                "\n" + output.linesIterator.filter(_.startsWith("[")).take(40).map("      compile> " + _).mkString("\n")
            )
      }
    compare("overlay", overlay)
    compare("disk", disk)
    timings += ((caseName, overlay.map(_.totalMillis).getOrElse(-1L), disk.map(_.totalMillis).getOrElse(-1L), compileMillis))
    state.log.info(
      s"checkParity $module $caseName: compile failed=$compileFailed diagnostics=${expected.size} " +
        s"check=${disk.map(_.totalMillis).getOrElse(-1L)}ms compile=${compileMillis}ms"
    )
  }

  // Leave the module clean (removes the last case's classes).
  val (_, cleanResult, cleanOutput) = sbt.McpInProcess.runOnLoop(state, s"$module/compile")
  assert(cleanResult.isRight, s"$module should compile after the corpus cases are removed:\n$cleanOutput")

  val t = timings.result()
  val checkMedian   = t.map(_._3).sorted.apply(t.size / 2)
  val compileMedian = t.map(_._4).sorted.apply(t.size / 2)
  state.log.info(s"checkParity $module timing: median check ${checkMedian}ms vs median compile ${compileMedian}ms over ${t.size} cases")

  val problems = failures.result()
  if (problems.nonEmpty) {
    problems.foreach(p => state.log.error(p))
    sys.error(s"checkParity $module: ${problems.size} mismatch(es) between check and compile")
  }
  state.log.info(s"checkParity $module OK: ${cases.size} cases agree with compile (overlay and disk)")
  state
}
