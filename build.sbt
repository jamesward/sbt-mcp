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
versionScheme := Some("semver-spec")

javacOptions ++= Seq("-source", "17", "-target", "17")
scalacOptions ++= Seq("-release", "17", "-Werror")

libraryDependencies ++= Seq(
  "com.jamesward" %% "zio-http-mcp" % "0.5.3",
  "ch.epfl.scala" %% "tasty-query"  % "1.8.0",
  "org.slf4j"      % "slf4j-simple" % "2.0.18" % Test,
  "dev.zio"       %% "zio-test"     % "2.1.26" % Test,
  "dev.zio"       %% "zio-test-sbt" % "2.1.26" % Test,
)

// Normal builds use the Maven Central release; `-Dlocal` substitutes the
// sibling source project through `evalTestDeps` above.
libraryDependencies ++= {
  if (useLocalZioEvals) Seq.empty
  else Seq("com.jamesward" %% "zio-evals" % "0.0.1" % Test)
}

scriptedLaunchOpts ++= Seq(
  s"-Dplugin.version=${version.value}",
)
scriptedBufferLog := false
