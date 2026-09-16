// Pass `-Dlocal` to sbt to substitute the released zio-evals dependency with
// the sibling checkout at `../zio-evals` for co-development. The dependency is
// TEST-scoped (`test->compile`) because only the eval integration test uses it;
// zio-evals never enters the published sbt plugin.
val useLocalSubprojects = sys.props.get("local").isDefined
val zioEvalsDir         = file("../zio-evals")
val useLocalZioEvals    = useLocalSubprojects && zioEvalsDir.exists()
val evalTestDeps: Seq[ClasspathDep[ProjectReference]] =
  if (useLocalZioEvals)
    Seq(RootProject(zioEvalsDir) % "test->compile")
  else
    Seq.empty

// A minimal root project is kept only for the structural bits that cannot be
// expressed as top-level settings: enabling SbtPlugin and the `-Dlocal`
// zio-evals project dependency. All ordinary settings are flat, below.
lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .dependsOn(evalTestDeps *)

// Dependency-only project: its resolved TASTy Query 1.9 jar is embedded into
// the plugin, but none of its Scala 3.9 classes enter the plugin classpath.
lazy val tastyQuery39Assets = (project in file("reader-assets"))
  .settings(
    publish / skip := true,
    scalaVersion := "3.9.0",
    libraryDependencies := Seq("ch.epfl.scala" %% "tasty-query" % "1.9.0"),
  )

// The MCP/ZIO runtime is isolated from sbt's Scala 3.8 classloader. It compiles
// with Scala 3.9 against zio-http-mcp 0.7.0 and is embedded with its full runtime
// dependency closure; only a JDK callback bridge is visible to the sbt plugin.
lazy val isolatedMcpRuntime = (project in file("isolated-runtime"))
  .settings(
    publish / skip := true,
    scalaVersion := "3.9.0",
    Compile / unmanagedSourceDirectories += (root / baseDirectory).value / "src" / "isolated-runtime" / "scala",
    libraryDependencies += "com.jamesward" %% "zio-http-mcp" % "0.7.0",
  )


// TASTy-linked symbol implementation. Its classes and TASTy Query 1.8 are
// embedded resources and never enter the sbt plugin's production classpath.
lazy val isolatedSymbolRuntime = (project in file("isolated-symbol-runtime"))
  .settings(
    publish / skip := true,
    scalaVersion := "3.8.4",
    Compile / unmanagedSourceDirectories += (root / baseDirectory).value / "src" / "isolated-symbol" / "scala",
    Test / unmanagedSourceDirectories += (root / baseDirectory).value / "src" / "isolated-symbol-test" / "scala",
    libraryDependencies ++= Seq(
      "ch.epfl.scala" %% "tasty-query"  % "1.8.0",
      "dev.zio"       %% "zio-test"     % "2.1.26" % Test,
      "dev.zio"       %% "zio-test-sbt" % "2.1.26" % Test,
    ),
  )
organization := "com.jamesward"
name         := "sbt-mcp"
homepage     := Some(uri("https://github.com/jamesward/sbt-mcp"))
licenses     := List("Apache-2.0" -> uri("https://www.apache.org/licenses/LICENSE-2.0"))
developers := List(
  Developer(
    "jamesward",
    "James Ward",
    "james@jamesward.com",
    uri("https://jamesward.com"),
  )
)
tastyQuery39Assets / name := "sbt-mcp-tasty-query-assets"
isolatedMcpRuntime / name := "sbt-mcp-isolated-runtime"
isolatedSymbolRuntime / name := "sbt-mcp-isolated-symbol-runtime"
versionScheme := Some("semver-spec")


// Generate the runtime MCP identity from the same sbt `version` used to publish
// the plugin, avoiding a second hard-coded implementation version.
root / Compile / sourceGenerators += Def.task {
  val output = (Compile / sourceManaged).value / "com" / "jamesward" / "sbtmcp" / "McpBuildInfo.scala"
  val escapedVersion = version.value.replace("\\", "\\\\").replace("\"", "\\\"")
  IO.write(
    output,
    s"""package com.jamesward.sbtmcp
       |
       |private[sbtmcp] object McpBuildInfo:
       |  final val version: String = "$escapedVersion"
       |""".stripMargin,
  )
  Seq(output)
}.taskValue
javacOptions ++= Seq("-source", "17", "-target", "17")

// TASTy Query follows the TASTy minor version and its 1.9.0 artifact is itself
// compiled with Scala 3.9, so it cannot be linked into sbt 2.0's Scala 3.8
// plugin classloader. Embed the asset project's resolved jar as a resource for
// the isolated symbol-index classloader.
root / Compile / resourceGenerators += Def.task {
  val jars = (tastyQuery39Assets / Compile / update).value.matching(
    artifactFilter(name = "tasty-query_3", extension = "jar")
  )
  val source = jars.headOption.getOrElse(sys.error("tasty-query 1.9.0 jar was not resolved"))
  val output = (Compile / resourceManaged).value / "sbt-mcp-readers" / "tasty-query-1.9.0.jar"
  IO.copyFile(source, output)
  Seq(output)
}.taskValue
scalacOptions ++= Seq("-release", "17", "-Werror")

root / Compile / resourceGenerators += Def.task {
  val converter = fileConverter.value
  val runtimeJar = converter.toPath((isolatedMcpRuntime / Compile / packageBin).value).toFile
  val dependencies = (isolatedMcpRuntime / Runtime / dependencyClasspath).value
    .map(attributed => converter.toPath(attributed.data).toFile)
  val jars = (runtimeJar +: dependencies).distinct
  val outputDir = (Compile / resourceManaged).value / "sbt-mcp-runtime"
  IO.delete(outputDir)
  val copied = jars.zipWithIndex.map { case (source, index) =>
    val output = outputDir / f"$index%03d-${source.getName}"
    IO.copyFile(source, output)
    output
  }
  val classpathIndex = outputDir / "classpath.index"
  IO.write(classpathIndex, copied.map(_.getName).mkString("\n") + "\n")
  copied :+ classpathIndex
}.taskValue

root / Compile / resourceGenerators += Def.task {
  val converter = fileConverter.value
  val runtimeJar = converter.toPath((isolatedSymbolRuntime / Compile / packageBin).value).toFile
  val readerJar = (isolatedSymbolRuntime / Runtime / dependencyClasspath).value
    .map(attributed => converter.toPath(attributed.data).toFile)
    .find(_.getName.startsWith("tasty-query_3-1.8.0"))
    .getOrElse(sys.error("tasty-query 1.8.0 jar was not resolved"))
  val outputDir = (Compile / resourceManaged).value / "sbt-mcp-symbol"
  IO.delete(outputDir)
  val runtimeOutput = outputDir / "symbol-runtime.jar"
  val readerOutput  = outputDir / "tasty-query-1.8.0.jar"
  IO.copyFile(runtimeJar, runtimeOutput)
  IO.copyFile(readerJar, readerOutput)
  Seq(runtimeOutput, readerOutput)
}.taskValue

root / libraryDependencies ++= Seq(
  "com.jamesward" %% "zio-http-mcp" % "0.5.3" % Test,
  "dev.zio"       %% "zio-test"     % "2.1.26" % Test,
  "dev.zio"       %% "zio-test-sbt" % "2.1.26" % Test,
)

// Normal builds use the Maven Central release; `-Dlocal` substitutes the
// sibling source project through `evalTestDeps` above.
root / libraryDependencies ++= {
  if (useLocalZioEvals) Seq.empty
  else Seq("com.jamesward" %% "zio-evals" % "0.0.2" % Test)
}

scriptedLaunchOpts ++= Seq(
  s"-Dplugin.version=${version.value}",
)
scriptedBufferLog := false
