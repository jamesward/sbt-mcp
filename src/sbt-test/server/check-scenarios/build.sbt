import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import com.jamesward.sbtmcp.{ ModuleCheck, SbtMcpPlugin }

// End-to-end scenarios for the `check` tool on a realistic multi-module build:
// dependsOn, a quoted macro from an upstream module, the Test configuration with a
// test-only library, changed siblings, scope=module, compiler-session invalidation,
// upstream auto-compile / fallback, Scala 2 and request validation, plus the MCP
// tool itself over HTTP (see harness/).
ThisBuild / scalaVersion := "3.9.0"

Global / mcpEnabled     := true
// This fixture intentionally exercises the server even when scripted runs in CI.
Global / mcpDisableInCI := false
Global / mcpPort        := 5097
Global / mcpHost        := "127.0.0.1"
Global / mcpDocsUrl     := None

lazy val core   = (project in file("core"))
lazy val macros = (project in file("macros"))
lazy val app = (project in file("app"))
  .dependsOn(core, macros)
  .settings(libraryDependencies += "org.scalameta" %% "munit" % "1.1.0" % Test)
lazy val legacy = (project in file("legacy")).settings(scalaVersion := "2.13.16")

lazy val root = (project in file(".")).aggregate(core, macros, app, legacy)

lazy val harness = (project in file("harness"))
  .settings(
    publish / skip := true,
    Compile / run / fork := true,
    libraryDependencies += "com.jamesward" %% "zio-http-mcp" % "0.8.3",
  )

// ---- helpers ----------------------------------------------------------------

def rootOf(state: State): Path = java.nio.file.Paths.get(Project.extract(state).structure.root)
def read(state: State, rel: String): String =
  new String(Files.readAllBytes(rootOf(state).resolve(rel)), StandardCharsets.UTF_8)
def write(state: State, rel: String, text: String): Unit = {
  val path = rootOf(state).resolve(rel)
  Files.createDirectories(path.getParent)
  Files.write(path, text.getBytes(StandardCharsets.UTF_8))
}
def delete(state: State, rel: String): Unit = Files.deleteIfExists(rootOf(state).resolve(rel))

def check(state: State, files: List[String], content: Option[String] = None, scope: Option[String] = None) =
  SbtMcpPlugin.checkResultOnLoop(state, files, content, scope)

def ok(label: String, result: Either[String, ModuleCheck.Result]): ModuleCheck.Result = result match {
  case Left(error) => sys.error(s"$label: expected a check result, got: $error")
  case Right(r) =>
    assert(!r.failed && r.diagnostics.isEmpty, s"$label: expected a clean check, got:\n${r.diagnostics.mkString("\n")}")
    r
}

def failsAt(label: String, result: Either[String, ModuleCheck.Result], file: String, line: Int, fragment: String): ModuleCheck.Result =
  result match {
    case Left(error) => sys.error(s"$label: expected a check result, got: $error")
    case Right(r) =>
      assert(r.failed, s"$label: expected the check to fail, got:\n${r.diagnostics.mkString("\n")}")
      assert(
        r.errors.exists(d => d.path.exists(_.getFileName.toString == file) && d.line == line && d.message.contains(fragment)),
        s"$label: expected an error at $file:$line containing '$fragment', got:\n${r.diagnostics.mkString("\n")}",
      )
      r
  }

def refused(label: String, result: Either[String, ModuleCheck.Result], fragment: String): Unit = result match {
  case Left(error) => assert(error.contains(fragment), s"$label: expected refusal containing '$fragment', got: $error")
  case Right(r)    => sys.error(s"$label: expected a refusal, got a result:\n${r.diagnostics.mkString("\n")}")
}

// Returns the given state (not the post-compile one): a failed compile consumes the
// state's `onFailure` handler, which must not leak back into the loop.
def compileModule(state: State, module: String): (State, Either[String, Unit], String) = {
  val (_, result, output) = sbt.McpInProcess.runOnLoop(state, s"$module/compile")
  (state, result, output)
}

val Probe = "app/src/main/scala/app/Probe.scala"

// ---- scenarios --------------------------------------------------------------

commands += Command.command("checkDependsOnAndMacros") { state =>
  // dependsOn: upstream API is visible; a missing member is reported precisely.
  ok("dependsOn ok", check(state, List(Probe), Some(
    """package app
      |
      |object Probe:
      |  def v: String = core.CoreApi.greet("x")
      |""".stripMargin)))
  failsAt("dependsOn missing member", check(state, List(Probe), Some(
    """package app
      |
      |object Probe:
      |  def v: String = core.CoreApi.nope
      |""".stripMargin)), "Probe.scala", 4, "nope")

  // A quoted macro defined in an upstream module runs during the check, and its
  // error is reported at the CALL SITE (like sbt), not inside the macro.
  ok("macro ok", check(state, List(Probe), Some(
    """package app
      |
      |object Probe:
      |  def v: String = macros.Macros.nonEmpty("fine")
      |""".stripMargin)))
  val macroFailure =
    """package app
      |
      |object Probe:
      |  def ok: Int = 1
      |  def v: String = macros.Macros.nonEmpty("")
      |""".stripMargin
  val checked = failsAt("macro error", check(state, List(Probe), Some(macroFailure)), "Probe.scala", 5, "must not be empty")

  // Same answer as the real compiler, including the column.
  write(state, Probe, macroFailure)
  val (s1, compiled, output) = compileModule(state, "app")
  delete(state, Probe)
  assert(compiled.isLeft, s"compile should fail on the macro error:\n$output")
  val macroError = checked.errors.find(_.message.contains("must not be empty")).get
  assert(
    output.contains(s"Probe.scala:${macroError.line}:${macroError.column}"),
    s"check reported ${macroError.line}:${macroError.column}; compile said:\n$output",
  )
  val (s2, restored, restoredOutput) = compileModule(s1, "app")
  assert(restored.isRight, restoredOutput)
  s2.log.info("checkDependsOnAndMacros OK")
  s2
}

commands += Command.command("checkTestConfiguration") { state =>
  // A test source is checked against the Test classpath (munit + main classes)...
  val suite = ok("test sources", check(state, List("app/src/test/scala/app/AppSuite.scala")))
  assert(suite.config.configuration == "test", s"expected the Test configuration, got ${suite.config.label}")
  // ...while main sources must NOT see test-only libraries.
  val main = failsAt("main cannot see munit", check(state, List(Probe), Some(
    """package app
      |
      |object Probe:
      |  val suite: munit.FunSuite = null
      |""".stripMargin)), "Probe.scala", 4, "munit")
  assert(main.config.configuration == "compile", s"expected Compile, got ${main.config.label}")
  // Test sources may use a test-only API.
  failsAt("test error", check(state, List("app/src/test/scala/app/AppSuite.scala"), Some(
    """package app
      |
      |class AppSuite extends munit.FunSuite:
      |  test("run") {
      |    assertEquals(App.run, 42)
      |  }
      |""".stripMargin)), "AppSuite.scala", 5, "")
  state.log.info("checkTestConfiguration OK")
  state
}

commands += Command.command("checkChangedSiblings") { state =>
  val helper   = "app/src/main/scala/app/Helper.scala"
  val original = read(state, helper)
  val usesAdded =
    """package app
      |
      |object Probe:
      |  def v: Int = Helper.added
      |""".stripMargin
  try {
    // Nothing edited since the last compile: only the requested file is checked.
    val clean = ok("no changes", check(state, List(Probe), Some("package app\n\nobject Probe\n")))
    assert(clean.changedSiblings.isEmpty, s"expected no changed siblings, got ${clean.changedSiblings}")

    // Helper edited on disk but NOT compiled: its stale classes would not have
    // `added`, so the edited source is checked alongside the requested file.
    write(state, helper, "package app\n\nobject Helper:\n  def base: Int = 1\n  def added: Int = 2\n")
    val sibling = ok("changed sibling", check(state, List(Probe), Some(usesAdded)))
    assert(sibling.changedSiblings.map(_.getFileName.toString) == List("Helper.scala"), s"got ${sibling.changedSiblings}")

    // An error in the changed sibling is reported too (compile would fail as well).
    write(state, helper, "package app\n\nobject Helper:\n  def base: Int = 1\n  def added: Int = \"two\"\n")
    failsAt("broken sibling", check(state, List(Probe), Some(usesAdded)), "Helper.scala", 5, "Required: Int")

    // Restoring the content makes it unchanged again (content stamps, not mtimes).
    write(state, helper, original)
    val restored = ok("restored", check(state, List(Probe), Some("package app\n\nobject Probe\n")))
    assert(restored.changedSiblings.isEmpty, s"restored Helper must not count as changed: ${restored.changedSiblings}")
  } finally write(state, helper, original)
  state.log.info("checkChangedSiblings OK")
  state
}

commands += Command.command("checkModuleScope") { state =>
  val api = "app/src/main/scala/app/Api.scala"
  val renamed = "package app\n\nobject Api:\n  def renamedValue: Int = 1\n"
  // Default scope: only the edited file (+ changed siblings). User.scala still uses
  // `Api.value` but is not re-checked, exactly the documented trade-off.
  ok("files scope", check(state, List(api), Some(renamed)))
  // Module scope re-checks every source of the module and finds the breakage.
  val module = failsAt("module scope", check(state, List(api), Some(renamed), Some("module")), "User.scala", 4, "value")
  assert(module.changedSiblings.isEmpty)
  state.log.info("checkModuleScope OK")
  state
}

commands += Command.command("checkInvalidation") { state =>
  val helper   = "app/src/main/scala/app/Helper.scala"
  val original = read(state, helper)
  val probe    = "package app\n\nobject Probe:\n  def v: Int = Helper.compiledLater\n"
  ok("warm up", check(state, List(Probe), Some("package app\n\nobject Probe\n")))
  assert(ok("warm", check(state, List(Probe), Some("package app\n\nobject Probe\n"))).reusedCompiler)

  // Change the module's classes with a real compile: the next check must see the
  // new classes (a fresh compiler context per check), and the compiler itself must
  // stay loaded and JIT-warm (no reload penalty after every compile).
  write(state, helper, "package app\n\nobject Helper:\n  def base: Int = 1\n  def compiledLater: Int = 3\n")
  val (s1, compiled, output) = compileModule(state, "app")
  assert(compiled.isRight, output)
  val after = ok("after compile", check(s1, List(Probe), Some(probe)))
  assert(after.changedSiblings.isEmpty, s"Helper was compiled; it must not be re-checked from source: ${after.changedSiblings}")
  assert(after.reusedCompiler, "a compile must not force the compiler to be reloaded")

  // An unsaved overlay never leaks into a later check: Probe's overlay defined
  // `Leak`, but a later check of another file must not see it.
  ok("overlay defining Leak", check(s1, List(Probe), Some("package app\n\nobject Probe\nobject Leak:\n  def x = 1\n")))
  failsAt("no leak", check(s1, List("app/src/main/scala/app/Other.scala"), Some(
    "package app\n\nobject Other:\n  def y: Int = Leak.x\n")), "Other.scala", 4, "Leak")

  write(s1, helper, original)
  val (s2, restored, restoredOutput) = compileModule(s1, "app")
  assert(restored.isRight, restoredOutput)
  s2.log.info("checkInvalidation OK")
  s2
}

commands += Command.command("checkUpstream") { state =>
  val coreApi  = "core/src/main/scala/core/CoreApi.scala"
  val original = read(state, coreApi)
  var current  = state
  try {
    // Upstream edited but not compiled: refreshing the module settings compiles it
    // (dependencyClasspath), so the new API is visible to the downstream check.
    write(state, coreApi, original + "  def fresh: Int = 5\n")
    ok("upstream auto-compiled", check(state, List(Probe), Some(
      "package app\n\nobject Probe:\n  def v: Int = core.CoreApi.fresh\n")))

    // Upstream broken: fall back to its last compiled classes, with a note.
    write(state, coreApi, original + "  def broken: Int = \"x\"\n")
    val fallback = ok("upstream broken", check(state, List(Probe), Some(
      "package app\n\nobject Probe:\n  def v: Int = core.CoreApi.fresh\n")))
    assert(
      fallback.config.notes.exists(_.contains("upstream module does not currently compile")),
      s"expected an upstream note, got ${fallback.config.notes}",
    )
  } finally {
    write(state, coreApi, original)
    val (s1, restored, output) = compileModule(state, "core")
    assert(restored.isRight, output)
    current = s1
  }
  current.log.info("checkUpstream OK")
  current
}

commands += Command.command("checkRefusals") { state =>
  refused("scala 2", check(state, List("legacy/src/main/scala/legacy/Legacy.scala")), "supports Scala 3.3 and later")
  refused("no files", check(state, Nil), "at least one file")
  refused("not scala", check(state, List("build.sbt")), "only .scala files")
  refused("missing", check(state, List("app/src/main/scala/app/Missing.scala")), "file not found")
  write(state, "Outside.scala", "object Outside\n")
  try refused("outside", check(state, List("Outside.scala")), "not in a source directory")
  finally delete(state, "Outside.scala")
  refused(
    "two modules",
    check(state, List("app/src/main/scala/app/App.scala", "core/src/main/scala/core/CoreApi.scala")),
    "different modules",
  )
  refused(
    "content with two files",
    check(state, List("app/src/main/scala/app/App.scala", "app/src/main/scala/app/Api.scala"), Some("x")),
    "exactly one file",
  )
  refused("bad scope", check(state, List("app/src/main/scala/app/App.scala"), None, Some("everything")), "unknown scope")
  // Multiple files of one module are checked together in one run.
  ok("two files together", check(state, List("app/src/main/scala/app/App.scala", "app/src/main/scala/app/Api.scala")))
  state.log.info("checkRefusals OK")
  state
}

commands += Command.command("checkRendering") { state =>
  val text = SbtMcpPlugin.checkOnLoop(state, List(Probe), Some(
    "package app\n\nobject Probe:\n  val x: Int = \"s\"\n"))
  val lines = text.linesIterator.toList
  assert(lines.head.startsWith("[error] check app/compile (Scala 3.9.0): 1 error, 0 warnings"), text)
  assert(lines(1).startsWith("elapsed: "), text)
  assert(lines(2) == "checked: app/src/main/scala/app/Probe.scala", text)
  assert(text.contains("app/src/main/scala/app/Probe.scala:4:15: error [E007]: Found:"), text)
  assert(text.contains("  val x: Int = \"s\"\n                 ^"), text)
  val clean = SbtMcpPlugin.checkOnLoop(state, List(Probe), Some("package app\n\nobject Probe\n"))
  assert(clean.startsWith("[ok] check app/compile (Scala 3.9.0): 0 errors, 0 warnings"), clean)
  state.log.info(s"checkRendering OK:\n$text")
  state
}

// Warm check vs a real incremental compile of the same one-file edit.
commands += Command.command("checkPerformance") { state =>
  def median(xs: Seq[Long]) = xs.sorted.apply(xs.size / 2)
  // Every iteration uses a never-seen-before edit so sbt 2's content-addressed
  // compile cache cannot replay an earlier compile.
  val nonce = System.nanoTime()
  val body  = (i: Int) => s"package app\n\nobject Probe:\n  def v: Long = Helper.base + ${nonce}L + $i\n"
  ok("warm up", check(state, List(Probe), Some(body(0))))
  val warm = (1 to 7).map(i => ok("warm", check(state, List(Probe), Some(body(i)))).totalMillis)
  var s = state
  val compile = (1 to 5).map { i =>
    write(s, Probe, body(100 + i))
    val started = System.nanoTime()
    val (next, result, output) = compileModule(s, "app")
    assert(result.isRight, output)
    s = next
    (System.nanoTime() - started) / 1000000
  }
  delete(s, Probe)
  val (s2, _, _) = compileModule(s, "app")
  s2.log.info(
    s"checkPerformance: warm check median ${median(warm)}ms ${warm.mkString("[", ",", "]")}; " +
      s"incremental compile median ${median(compile)}ms ${compile.mkString("[", ",", "]")}"
  )
  assert(median(warm) < median(compile), s"a warm check (${median(warm)}ms) must beat compile (${median(compile)}ms)")
  s2
}

// Seed the cached module settings the MCP tool uses while the loop is busy running
// the forked harness (the busy path: no queueing behind the running command).
commands += Command.command("checkPrimeForHarness") { state =>
  SbtMcpPlugin.checkConfigFromState(state, ProjectRef(Project.extract(state).structure.root, "app"), Compile)
  state.log.info("checkPrimeForHarness OK")
  state
}
