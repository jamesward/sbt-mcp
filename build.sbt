sbtPlugin := true
enablePlugins(SbtPlugin)

organization := "com.jamesward"
name         := "sbt-mcp"
homepage     := Some(uri("https://github.com/jamesward/sbt-mcp"))
licenses     := List("Apache-2.0" -> uri("https://www.apache.org/licenses/LICENSE-2.0"))

developers := List(
  Developer(
    "jamesward",
    "James Ward",
    "james@jamesward.com",
    uri("https://jamesward.com")
  )
)

versionScheme := Some("semver-spec")

javacOptions ++= Seq("-source", "17", "-target", "17")
scalacOptions ++= Seq("-release", "17", "-Werror")

libraryDependencies ++= Seq(
  "com.jamesward" %% "zio-http-mcp" % "0.5.3",
  "ch.epfl.scala" %% "tasty-query"  % "1.8.0",
  "org.slf4j"      % "slf4j-simple" % "2.0.18" % Test,
)

scriptedLaunchOpts ++= Seq(
  s"-Dplugin.version=${version.value}",
)
scriptedBufferLog := false
