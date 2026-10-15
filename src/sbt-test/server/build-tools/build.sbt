ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "com.example"
ThisBuild / version      := "0.1.0"
// Keep `publish-local` inside the test directory instead of the user's ~/.ivy2/local
// (sbt sets `ivyPaths` per project, so a ThisBuild value would be ignored).
lazy val localIvy = ivyPaths := IvyPaths(baseDirectory.value.toString, Some(((ThisBuild / baseDirectory).value / "ivy-home").toString))

ThisBuild / mcpEnabled     := true
// This fixture intentionally exercises the server even when scripted runs in CI.
ThisBuild / mcpDisableInCI := false
ThisBuild / mcpPort        := 5093
ThisBuild / mcpHost        := "127.0.0.1"
ThisBuild / mcpDocsUrl     := None

lazy val root = (project in file("."))
  .aggregate(core, app)
  .settings(name := "build-tools-test", publish / skip := true, localIvy)

lazy val core = project
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test"     % "2.1.26" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.26" % Test,
    ),
    Test / fork := true,
    localIvy,
  )

lazy val app = project
  .dependsOn(core)
  .settings(run / fork := true, publish / skip := true, localIvy)

lazy val harness = (project in file("harness"))
  .settings(
    publish / skip := true,
    scalaVersion := "3.9.0",
    Compile / run / fork := true,
    libraryDependencies += "com.jamesward" %% "zio-http-mcp" % "0.8.3",
  )

// ---- helpers: call a structured tool exactly as its MCP handler does, on the loop ----

def tool(state: State, name: String, args: (String, List[String])*): String =
  com.jamesward.sbtmcp.SbtMcpPlugin.buildToolOnLoop(state, name, args.toMap)

def json(name: String, out: String): String = {
  assert(out.startsWith("{"), s"$name: expected a JSON result, got:\n$out")
  // Well-formed JSON (sjson-new ships with sbt).
  sjsonnew.support.scalajson.unsafe.Parser.parseFromString(out).get
  out
}

def has(name: String, out: String, fragments: String*): Unit =
  fragments.foreach(f => assert(out.contains(f), s"$name: missing $f in:\n$out"))

def hasNot(name: String, out: String, fragments: String*): Unit =
  fragments.foreach(f => assert(!out.contains(f), s"$name: unexpected $f in:\n$out"))

commands += Command.command("mcpBuildToolsOk") { state =>
  val projects = json("projects", tool(state, "projects"))
  has("projects", projects, "\"currentProject\":\"root\"", "\"id\":\"core\"", "\"id\":\"app\"", "\"base\":\"core\"",
    "\"aggregates\":[\"core\",\"app\"]", "\"dependsOn\":[{\"project\":\"core\"", "\"organization\":\"com.example\"",
    "\"scalaVersion\":\"3.8.4\"")

  val compiled = json("compile", tool(state, "compile"))
  has("compile", compiled, "\"command\":\"compile\"", "\"success\":true", "\"errorCount\":0", "\"elapsedMs\":")

  val testCompiled = json("compile test", tool(state, "compile", "project" -> List("core"), "configuration" -> List("test")))
  has("compile test", testCompiled, "\"command\":\"core/Test/compile\"", "\"success\":true")

  val testNames = json("test-names", tool(state, "test-names"))
  has("test-names", testNames, "{\"project\":\"core\",\"tests\":[\"core.FailingSpec\",\"core.GreeterSpec\"]}")

  val mains = json("main-classes", tool(state, "main-classes", "project" -> List("app")))
  has("main-classes", mains, "\"mainClasses\":[\"app.Main\"]", "\"defaultMainClass\":\"app.Main\"")

  // Arguments with spaces, `;` and quotes reach the program verbatim.
  val ran = json("run", tool(state, "run", "project" -> List("app"), "args" -> List("a b", "c;d", "say \"hi\"")))
  has("run", ran, "\"command\":\"app/run \\\"a b\\\" \\\"c;d\\\" \\\"say \\\\\\\"hi\\\\\\\"\\\"\"", "\"success\":true",
    "\"output\":\"Hello, app args=a b|c;d|say \\\"hi\\\"\"")

  // A project without a main class is a failed run that says why.
  val noMain = tool(state, "run", "project" -> List("core"))
  assert(noMain.startsWith("{") && noMain.contains("\"success\":false") && noMain.contains("no main class to run"), noMain)

  val passing = json("test passing", tool(state, "test", "project" -> List("core"), "tests" -> List("core.GreeterSpec")))
  has("test passing", passing, "\"command\":\"core/testOnly core.GreeterSpec\"", "\"success\":true",
    "\"passed\":2", "\"failed\":0", "\"failures\":[]")

  val full = json("test full", tool(state, "test", "project" -> List("core"), "full" -> List("true")))
  has("test full", full, "\"command\":\"core/testFull\"", "\"success\":false", "\"failed\":1", "\"passed\":3",
    "\"suite\":\"core.FailingSpec\"", "\"test\":\"compares words\"", "\"status\":\"failed\"", "was not equal to")
  hasNot("test full", full, "ForkError", "PersistedException")

  val deps = json("dependencies", tool(state, "dependencies", "project" -> List("core"), "configuration" -> List("test")))
  has("dependencies", deps, "\"configuration\":\"test\"",
    "{\"organization\":\"dev.zio\",\"name\":\"zio-test\",\"revision\":\"2.1.26\",\"configurations\":\"test\"}",
    "{\"organization\":\"dev.zio\",\"name\":\"zio-test_3\",\"revision\":\"2.1.26\",\"direct\":true}",
    "\"name\":\"zio_3\"", "\"direct\":false")

  val setting = json("show setting", tool(state, "show", "key" -> List("core / Test / fork")))
  has("show setting", setting, "\"key\":\"core / Test / fork\"", "\"kind\":\"setting\"", "\"value\":\"true\"", "\"json\":true")

  val dirs = json("show dirs", tool(state, "show", "key" -> List("core / Compile / unmanagedSourceDirectories")))
  has("show dirs", dirs, "\"kind\":\"setting\"", "\"json\":[\"", "core/src/main/scala")

  val task = json("show task", tool(state, "show", "key" -> List("core / Test / definedTestNames")))
  has("show task", task, "\"kind\":\"task\"", "\"json\":[\"core.FailingSpec\",\"core.GreeterSpec\"]")

  val published = json("publish-local", tool(state, "publish-local"))
  has("publish-local", published, "\"command\":\"publishLocal\"", "\"success\":true",
    "\"published\":[{\"project\":\"core\",\"organization\":\"com.example\",\"name\":\"core_3\",\"version\":\"0.1.0\"}]")

  val cleaned = json("clean", tool(state, "clean", "project" -> List("app")))
  has("clean", cleaned, "\"command\":\"app/clean\"", "\"success\":true")

  // Request errors are plain messages (never JSON), so MCP reports them with isError.
  val errors = List(
    tool(state, "compile", "project" -> List("nope"))               -> "compile: unknown project 'nope'",
    tool(state, "compile", "configuration" -> List("it"))           -> "compile: configuration must be one of",
    tool(state, "test", "tests" -> List("a b"))                     -> "test: invalid test name or pattern 'a b'",
    tool(state, "run", "mainClass" -> List("not a class"))          -> "run: invalid main class",
    tool(state, "show", "key" -> List("noSuchKeyAnywhere"))         -> "show: cannot resolve key 'noSuchKeyAnywhere'",
    tool(state, "show", "key" -> List("core / testOnly"))           -> "is an input task",
  )
  errors.foreach { case (out, expected) =>
    assert(!out.startsWith("{") && out.contains(expected), s"expected error containing '$expected', got:\n$out")
  }

  state.log.info("mcpBuildToolsOk OK")
  state
}

commands += Command.command("mcpBuildToolsBroken") { state =>
  val compiled = json("compile broken", tool(state, "compile", "project" -> List("core")))
  has("compile broken", compiled, "\"command\":\"core/compile\"", "\"success\":false", "\"errorCount\":1",
    "\"severity\":\"error\"", "\"file\":\"core/src/main/scala/core/Broken.scala\"", "\"line\":4", "\"column\":19",
    "Found:    (\\\"not a number\\\" : String)", "\"error\":")
  hasNot("compile broken", compiled, "\\u001b")

  // sbt 2 caches the failed compile and replays it without the reporter: the problems still come back.
  val again = json("compile broken again", tool(state, "compile", "project" -> List("core")))
  has("compile broken again", again, "\"success\":false", "\"errorCount\":1",
    "\"file\":\"core/src/main/scala/core/Broken.scala\"", "\"line\":4", "\"column\":19")

  val tested = json("test broken", tool(state, "test", "project" -> List("core")))
  has("test broken", tested, "\"success\":false", "\"errorCount\":1", "\"total\":0",
    "\"file\":\"core/src/main/scala/core/Broken.scala\"")

  val names = tool(state, "test-names", "project" -> List("core"))
  assert(!names.startsWith("{"), s"test-names should fail on a broken build:\n$names")
  has("test-names broken", names, "test-names:", "compile errors", "core/src/main/scala/core/Broken.scala:4:19")

  state.log.info("mcpBuildToolsBroken OK")
  state
}
