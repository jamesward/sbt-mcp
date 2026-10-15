package harness

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.client.McpClient
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

/** Checks the structured build tools' advertised schemas, and calls `projects` over HTTP. */
object BuildToolsHarness:
  private val buildTools =
    Set("compile", "test", "run", "clean", "publish-local", "projects", "test-names", "main-classes", "dependencies", "show")

  def main(args: Array[String]): Unit =
    val program =
      ZIO.scoped {
        for
          client   <- McpClient.connect("http://127.0.0.1:5093/")
          tools    <- client.listTools
          // The command loop is busy running this harness: `projects` reads settings, so it still answers.
          projects <- client.callTool("projects", Json.Obj())
          badArgs  <- client.callTool("compile", Json.Obj("configuration" -> Json.Str("nope")))
        yield (tools, projects, badArgs)
      }.retry(Schedule.recurs(25) && Schedule.spaced(200.millis))

    val (tools, projects, badArgs) = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(program.provide(Client.default)).getOrThrow()
    }

    val byName = tools.map(t => t.name.toString -> t).toMap
    assert(buildTools.subsetOf(byName.keySet), s"missing build tools; got: ${byName.keySet}")
    assert(Set("sbt-task", "list-tasks").subsetOf(byName.keySet), s"sbt-task/list-tasks must remain; got: ${byName.keySet}")

    def properties(schema: Json.Obj): Set[String] =
      schema.get("properties").flatMap(_.asObject).map(_.fields.map(_._1).toSet).getOrElse(Set.empty)

    buildTools.foreach { name =>
      val tool = byName(name)
      assert(tool.description.exists(_.length > 40), s"$name: missing description")
      val output = tool.outputSchema.getOrElse(throw AssertionError(s"$name: no outputSchema"))
      assert(output.get("type").flatMap(_.asString).contains("object"), s"$name: outputSchema is not an object: $output")
      assert(tool.inputSchema.get("type").flatMap(_.asString).contains("object"), s"$name: inputSchema is not an object")
    }

    val compileOut = properties(byName("compile").outputSchema.get)
    assert(Set("success", "problems", "errorCount", "log").subsetOf(compileOut), s"compile output: $compileOut")
    val testOut = properties(byName("test").outputSchema.get)
    assert(Set("summary", "failures", "problems").subsetOf(testOut), s"test output: $testOut")
    assert(properties(byName("test").inputSchema) == Set("project", "tests", "full", "frameworkArgs"),
      s"test input: ${byName("test").inputSchema.toJson}")
    val compileConfig = byName("compile").inputSchema.toJson
    assert(compileConfig.contains("\"enum\":[\"compile\",\"test\"]"), s"compile configuration enum: $compileConfig")
    assert(byName("projects").annotations.flatMap(_.readOnlyHint).contains(true), "projects should be read-only")
    assert(byName("clean").annotations.flatMap(_.destructiveHint).contains(true), "clean should be destructive")

    assert(!projects.isError.contains(true), s"projects failed: ${projects.content}")
    val structured = projects.structuredContent.flatMap(_.asObject).getOrElse(throw AssertionError(s"no structuredContent: $projects"))
    assert(structured.get("currentProject").flatMap(_.asString).contains("root"), s"projects: $structured")
    val ids = structured.get("projects").flatMap(_.asArray).toList.flatten.flatMap(_.asObject).flatMap(_.get("id")).flatMap(_.asString)
    assert(Set("root", "core", "app", "harness").subsetOf(ids.toSet), s"projects ids: $ids")
    // Absent optional fields are omitted (a `null` would not match the advertised schema).
    assert(!structured.toJson.contains("null"), s"projects structuredContent has nulls: ${structured.toJson}")
    val text = projects.content.collect { case ToolContent.Text(t, _) => t }.mkString
    assert(text.fromJson[Json.Obj].toOption.contains(structured), s"text content should mirror structuredContent: $text")

    assert(badArgs.isError.contains(true), s"invalid arguments should be rejected: $badArgs")

    println(s"mcpBuildToolsHarness OK: ${buildTools.size} build tools with schemas; projects=${ids.mkString(",")}")
