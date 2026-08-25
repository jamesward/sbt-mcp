// Consume the sbt-mcp plugin directly FROM SOURCE — no publishLocal needed.
// The meta-build depends on the plugin project two directories up (the repo root).
lazy val root = (project in file("."))
  .dependsOn(RootProject(file("../..")))
