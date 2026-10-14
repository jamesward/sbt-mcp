package com.jamesward.sbtmcp

import sbt.*
import sbt.Keys.*
import sbt.internal.util.{ AttributeKey, KeyTag }
import sbt.internal.util.complete.Parser

import scala.util.control.NonFatal

/**
 * The structured build tools (`compile`, `test`, `run`, `clean`, `publish-local`,
 * `projects`, `test-names`, `main-classes`, `dependencies`, `show`).
 *
 * Each tool takes simple, validated arguments and returns a JSON object (the MCP
 * `structuredContent`), whose shape is the tool's `outputSchema` declared in the
 * isolated runtime (`McpServerRuntimeImpl`). Actions run as sbt command lines through
 * the same in-process path as `sbt-task` ([[SbtMcpPlugin.runForTool]]), so they keep
 * sbt's aggregation and log capture, while [[McpRunRecorder]] collects compiler
 * diagnostics and test events as data. Queries run tasks directly with
 * `Extracted.runTask`. Everything except `projects` must run ON the command loop.
 *
 * Results are `Right(json)` or `Left(message)` for a request error (unknown project,
 * a task that failed to evaluate, …). A message never starts with `{`, which is how
 * the isolated side tells the two apart across the string-only bridge.
 */
private[sbtmcp] object McpBuildTools {

  type Args = Map[String, List[String]]

  val Names: Set[String] =
    Set("compile", "test", "run", "clean", "publish-local", "projects", "test-names", "main-classes", "dependencies", "show")

  /** Tools that only read settings, so they can answer from a captured State off the loop. */
  val SettingsOnly: Set[String] = Set("projects")

  private final val MaxLogChars     = 20000
  private final val MaxProblems     = 500
  private final val MaxValueChars   = 20000
  private final val MaxValueElements = 2000

  /** Dispatch a tool. Call ON the command loop (or, for [[SettingsOnly]] tools, anywhere). */
  def run(state: State, tool: String, args: Args): Either[String, String] =
    try
      tool match {
        case "compile"       => compileTool(state, args)
        case "test"          => testTool(state, args)
        case "run"           => runTool(state, args)
        case "clean"         => cleanTool(state, args)
        case "publish-local" => publishLocalTool(state, args)
        case "projects"      => Right(projectsTool(state))
        case "test-names"    => testNamesTool(state, args)
        case "main-classes"  => mainClassesTool(state, args)
        case "dependencies"  => dependenciesTool(state, args)
        case "show"          => showTool(state, args)
        case other           => Left(s"unknown build tool: $other")
      }
    catch { case NonFatal(e) => Left(s"$tool: ${rootCause(e)}") }

  // ---- argument helpers ----

  private def one(args: Args, name: String): Option[String] =
    args.get(name).flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)

  private def many(args: Args, name: String): List[String] =
    args.getOrElse(name, Nil).map(_.trim).filter(_.nonEmpty)

  private def flag(args: Args, name: String): Boolean =
    one(args, name).exists(_.equalsIgnoreCase("true"))

  /** The project named by `project`, or the session's current project. Only known ids are accepted. */
  private def targetRef(extracted: Extracted, args: Args, tool: String): Either[String, ProjectRef] =
    one(args, "project") match {
      case None => Right(extracted.currentRef)
      case Some(id) =>
        val refs = extracted.structure.allProjectRefs
        refs.filter(_.project == id).sortBy(ref => if (ref.build == extracted.structure.root) 0 else 1).headOption
          .toRight(s"$tool: unknown project '$id' (known: ${refs.map(_.project).distinct.sorted.mkString(", ")})")
    }

  /** Command-line prefix selecting `ref` (empty for the session's current project, so it keeps aggregating). */
  private def commandPrefix(extracted: Extracted, args: Args, ref: ProjectRef): String =
    if (one(args, "project").isEmpty) ""
    else if (ref.build == extracted.structure.root) s"${ref.project}/"
    else s"{${ref.build}}${ref.project}/"

  private def configurationArg(args: Args, allowed: List[String], default: String, tool: String): Either[String, String] = {
    val value = one(args, "configuration").map(_.toLowerCase).getOrElse(default)
    Either.cond(allowed.contains(value), value, s"$tool: configuration must be one of ${allowed.mkString(", ")} (got '$value')")
  }

  /** Quote an argument for sbt's `spaceDelimited` parser, so spaces, `;` and quotes survive. */
  private[sbtmcp] def quoteArg(arg: String): String =
    "\"" + arg.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c    => c.toString
    } + "\""

  private val ClassName   = "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*".r
  private val TestPattern = "[^\\s;\"{}]+".r

  // ---- actions (command lines on the loop) ----

  private final case class ActionResult(
      command: String,
      result: Either[String, Unit],
      log: String,
      elapsedMillis: Long,
      snapshot: McpRunRecorder.Snapshot,
  ) {
    def success: Boolean = result.isRight
  }

  private def action(state: State, command: String): ActionResult = {
    val started = System.nanoTime()
    val ((_, result, log), snapshot) = McpRunRecorder.recording(SbtMcpPlugin.runForTool(state, command))
    ActionResult(command, result, log, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), snapshot)
  }

  /** The fields every action returns: command, status, timing, diagnostics, and the captured log. */
  private def actionFields(state: State, r: ActionResult): Seq[(String, String)] = {
    val root     = buildRoot(state)
    val problems = r.snapshot.problems
    val (logText, truncated) =
      if (r.log.length <= MaxLogChars) (r.log, false)
      else (s"... (${r.log.length - MaxLogChars} earlier characters omitted)\n" + r.log.takeRight(MaxLogChars), true)
    Seq(
      "command"      -> Js.str(r.command),
      "success"      -> Js.bool(r.success),
      "elapsedMs"    -> r.elapsedMillis.toString,
      "errorCount"   -> problems.count(_.severity == "error").toString,
      "warningCount" -> problems.count(_.severity == "warning").toString,
      "problems"     -> Js.arr(problems.take(MaxProblems).map(problemJson(root, _))),
      "log"          -> Js.str(logText),
      "logTruncated" -> Js.bool(truncated),
    ) ++ r.result.left.toOption.map(m => "error" -> Js.str(m))
  }

  private def problemJson(root: java.nio.file.Path, p: McpRunRecorder.Problem): String =
    Js.obj(
      Seq("severity" -> Js.str(p.severity)) ++
        p.file.map(f => "file" -> Js.str(displayPath(root, f))) ++
        p.line.map(l => "line" -> l.toString) ++
        p.column.map(c => "column" -> c.toString) ++
        Seq("message" -> Js.str(p.message))*
    )

  /** A source path relative to the build root when it is inside it (Zinc reports `${BASE}/…` ids). */
  private[sbtmcp] def displayPath(root: java.nio.file.Path, file: String): String = {
    val expanded = if (file.startsWith("${BASE}")) root.toString + file.stripPrefix("${BASE}") else file
    try {
      val path = java.nio.file.Paths.get(expanded).toAbsolutePath.normalize
      if (path.startsWith(root)) root.relativize(path).toString.replace('\\', '/') else path.toString
    } catch { case NonFatal(_) => expanded }
  }

  private def compileTool(state: State, args: Args): Either[String, String] = {
    val extracted = Project.extract(state)
    for {
      ref           <- targetRef(extracted, args, "compile")
      configuration <- configurationArg(args, List("compile", "test"), "compile", "compile")
    } yield {
      val task = if (configuration == "test") "Test/compile" else "compile"
      Js.obj(actionFields(state, action(state, commandPrefix(extracted, args, ref) + task))*)
    }
  }

  private def testTool(state: State, args: Args): Either[String, String] = {
    val extracted     = Project.extract(state)
    val tests         = many(args, "tests")
    val frameworkArgs = many(args, "frameworkArgs")
    for {
      ref <- targetRef(extracted, args, "test")
      _ <- tests.find(t => !TestPattern.matches(t))
             .map(t => s"test: invalid test name or pattern '$t' (no whitespace, quotes, braces or ';')").toLeft(())
    } yield {
      val extra = if (frameworkArgs.isEmpty) "" else " -- " + frameworkArgs.map(quoteArg).mkString(" ")
      val task =
        if (tests.nonEmpty) s"testOnly ${tests.mkString(" ")}$extra"
        else if (flag(args, "full")) (if (extra.isEmpty) "testFull" else s"testOnly$extra")
        else if (extra.nonEmpty) s"testOnly$extra"
        else "test"
      val r     = action(state, commandPrefix(extracted, args, ref) + task)
      val cases = r.snapshot.tests
      def count(statuses: String*) = cases.count(c => statuses.contains(c.status))
      val failures = cases.filter(c => c.status == "failed" || c.status == "error").map { c =>
        Js.obj(
          Seq("suite" -> Js.str(c.suite)) ++ c.test.map(t => "test" -> Js.str(t)) ++
            Seq("status" -> Js.str(c.status)) ++ c.message.map(m => "message" -> Js.str(m)) ++
            c.durationMillis.map(d => "durationMs" -> d.toString)*
        )
      }
      val summary = Js.obj(
        "total"   -> cases.size.toString,
        "passed"  -> count("passed").toString,
        "failed"  -> count("failed").toString,
        "errors"  -> count("error").toString,
        "skipped" -> count("skipped", "ignored", "canceled", "pending").toString,
        "suites"  -> cases.map(_.suite).distinct.size.toString,
      )
      val suiteErrors = r.snapshot.suiteErrors.map { case (suite, message) =>
        Js.obj("suite" -> Js.str(suite), "message" -> Js.str(message))
      }
      Js.obj(
        actionFields(state, r) ++ Seq(
          "summary"     -> summary,
          "failures"    -> Js.arr(failures),
          "suiteErrors" -> Js.arr(suiteErrors),
        )*
      )
    }
  }

  private def runTool(state: State, args: Args): Either[String, String] = {
    val extracted = Project.extract(state)
    val mainClass = one(args, "mainClass")
    for {
      ref <- targetRef(extracted, args, "run")
      _   <- mainClass.filterNot(ClassName.matches).map(m => s"run: invalid main class '$m'").toLeft(())
    } yield {
      val programArgs = args.getOrElse("args", Nil).map(quoteArg)
      val task        = (mainClass.fold("run")(m => s"runMain $m") :: programArgs).mkString(" ")
      // `run` never aggregates, so the current project is the one that runs.
      Js.obj(actionFields(state, action(state, commandPrefix(extracted, args, ref) + task))*)
    }
  }

  private def cleanTool(state: State, args: Args): Either[String, String] = {
    val extracted = Project.extract(state)
    targetRef(extracted, args, "clean").map { ref =>
      Js.obj(actionFields(state, action(state, commandPrefix(extracted, args, ref) + "clean"))*)
    }
  }

  private def publishLocalTool(state: State, args: Args): Either[String, String] = {
    val extracted = Project.extract(state)
    targetRef(extracted, args, "publish-local").map { ref =>
      val r = action(state, commandPrefix(extracted, args, ref) + "publishLocal")
      val published =
        if (!r.success) Vector.empty
        else
          aggregates(extracted, ref).flatMap { candidate =>
            val skipped =
              try extracted.runTask(candidate / publishLocal / skip, state)._2
              catch { case NonFatal(_) => false }
            if (skipped) None
            else extracted.getOpt(candidate / projectID).map { module =>
              val crossed =
                try CrossVersion(module, extracted.getOpt(candidate / scalaModuleInfo).flatten).fold(module.name)(_(module.name))
                catch { case NonFatal(_) => module.name }
              Js.obj(
                "project"      -> Js.str(candidate.project),
                "organization" -> Js.str(module.organization),
                "name"         -> Js.str(crossed),
                "version"      -> Js.str(module.revision),
              )
            }
          }
      Js.obj(actionFields(state, r) :+ ("published" -> Js.arr(published))*)
    }
  }

  // ---- queries ----

  private def projectsTool(state: State): String = {
    val extracted = Project.extract(state)
    val structure = extracted.structure
    val root      = buildRoot(state)
    val rootRef   = ProjectRef(structure.root, structure.rootProject(structure.root))
    val refs      = structure.allProjectRefs.sortBy(ref => (if (ref.build == structure.root) 0 else 1, ref.build.toString, ref.project))
    val projects = refs.map { ref =>
      val project = structure.extra.projectFor(ref)
      val deps = project.dependencies.map { dep =>
        Js.obj(Seq("project" -> Js.str(dep.project.project)) ++ dep.configuration.map(c => "configuration" -> Js.str(c))*)
      }
      Js.obj(
        Seq(
          "id"      -> Js.str(ref.project),
          "base"    -> Js.str(displayPath(root, project.base.getAbsolutePath)),
          "current" -> Js.bool(ref == extracted.currentRef),
          "root"    -> Js.bool(ref == rootRef),
        ) ++
          Option.when(ref.build != structure.root)("build" -> Js.str(ref.build.toString)) ++
          extracted.getOpt(ref / name).map(v => "name" -> Js.str(v)) ++
          extracted.getOpt(ref / organization).map(v => "organization" -> Js.str(v)) ++
          extracted.getOpt(ref / version).map(v => "version" -> Js.str(v)) ++
          extracted.getOpt(ref / scalaVersion).map(v => "scalaVersion" -> Js.str(v)) ++
          Seq(
            "aggregates" -> Js.arr(project.aggregate.map(a => Js.str(a.project))),
            "dependsOn"  -> Js.arr(deps),
          )*
      )
    }
    Js.obj("currentProject" -> Js.str(extracted.currentRef.project), "projects" -> Js.arr(projects))
  }

  /** `ref` followed by its transitive aggregates, once each. */
  private def aggregates(extracted: Extracted, ref: ProjectRef): Vector[ProjectRef] = {
    val seen = scala.collection.mutable.LinkedHashSet.empty[ProjectRef]
    def visit(r: ProjectRef): Unit =
      if (seen.add(r)) extracted.structure.extra.projectFor(r).aggregate.foreach(visit)
    visit(ref)
    seen.toVector
  }

  /**
   * Run a query task, turning a failure into a request error that carries the compiler
   * diagnostics recorded while it ran (most query failures are compile failures).
   */
  private def query[A](state: State, tool: String)(body: => A): Either[String, A] = {
    val (result, snapshot) = McpRunRecorder.recording {
      try Right(body)
      catch { case NonFatal(e) => Left(rootCause(e)) }
    }
    result.left.map { message =>
      val root   = buildRoot(state)
      val errors = snapshot.problems.filter(_.severity == "error").take(20).map { p =>
        val where = p.file.map(f => displayPath(root, f) + p.line.fold("")(l => s":$l") + p.column.fold("")(c => s":$c"))
        s"  ${where.fold("")(_ + ": ")}${p.message.linesIterator.nextOption().getOrElse("")}"
      }
      val hint = if (errors.nonEmpty) "\ncompile errors (run the `compile` tool for full diagnostics):\n" + errors.mkString("\n") else ""
      s"$tool: $message$hint"
    }
  }

  private def testNamesTool(state: State, args: Args): Either[String, String] = {
    val extracted = Project.extract(state)
    for {
      ref <- targetRef(extracted, args, "test-names")
      perProject <- query(state, "test-names") {
        aggregates(extracted, ref).map(r => r -> extracted.runTask(r / Test / definedTestNames, state)._2.sorted)
      }
    } yield Js.obj(
      "projects" -> Js.arr(perProject.map { case (r, names) =>
        Js.obj("project" -> Js.str(r.project), "tests" -> Js.arr(names.map(Js.str)))
      })
    )
  }

  private def mainClassesTool(state: State, args: Args): Either[String, String] = {
    val extracted = Project.extract(state)
    for {
      ref <- targetRef(extracted, args, "main-classes")
      perProject <- query(state, "main-classes") {
        aggregates(extracted, ref).map { r =>
          val discovered = extracted.runTask(r / Compile / discoveredMainClasses, state)._2.sorted
          val selected   = extracted.runTask(r / Compile / run / mainClass, state)._2
          (r, discovered, selected)
        }
      }
    } yield Js.obj(
      "projects" -> Js.arr(perProject.map { case (r, discovered, selected) =>
        Js.obj(
          Seq("project" -> Js.str(r.project), "mainClasses" -> Js.arr(discovered.map(Js.str))) ++
            selected.map(m => "defaultMainClass" -> Js.str(m))*
        )
      })
    )
  }

  private def dependenciesTool(state: State, args: Args): Either[String, String] = {
    val extracted = Project.extract(state)
    for {
      ref           <- targetRef(extracted, args, "dependencies")
      configuration <- configurationArg(args, List("compile", "runtime", "test", "provided"), "compile", "dependencies")
      report        <- query(state, "dependencies")(extracted.runTask(ref / update, state)._2)
    } yield {
      val declared = extracted.getOpt(ref / libraryDependencies).getOrElse(Nil)
      def isDirect(m: ModuleID) =
        declared.exists(d => d.organization == m.organization && (d.name == m.name || m.name.startsWith(d.name + "_")))
      def module(m: ModuleID, extra: Seq[(String, String)] = Nil) =
        Js.obj(
          Seq("organization" -> Js.str(m.organization), "name" -> Js.str(m.name), "revision" -> Js.str(m.revision)) ++ extra*
        )
      val configReport = report.configuration(ConfigRef(configuration))
      val resolved = configReport.toVector.flatMap(_.modules).filterNot(_.evicted).map { mr =>
        module(mr.module, Seq("direct" -> Js.bool(isDirect(mr.module))))
      }
      val evicted = configReport.toVector.flatMap(_.details).flatMap(_.modules).filter(_.evicted).map(mr => module(mr.module)).distinct
      Js.obj(
        "project"       -> Js.str(ref.project),
        "configuration" -> Js.str(configuration),
        "declared" -> Js.arr(declared.map(d => module(d, d.configurations.map(c => "configurations" -> Js.str(c)).toSeq))),
        "resolved" -> Js.arr(resolved),
        "evicted"  -> Js.arr(evicted),
      )
    }
  }

  private def showTool(state: State, args: Args): Either[String, String] = {
    val extracted = Project.extract(state)
    for {
      keyText <- one(args, "key").toRight("show: provide a `key`, e.g. \"scalaVersion\" or \"core / Test / sourceDirectories\"")
      parsed <- Parser.parse(keyText, sbt.internal.Act.scopedKeyParser(extracted))
                  .left.map(m => s"show: cannot resolve key '$keyText':\n$m")
      shown = Def.showRelativeKey2(extracted.currentRef).show(parsed)
      (kind, value) <- parsed.key.tag match {
        case KeyTag.Setting(_) =>
          extracted.structure.data.get(parsed.scope, parsed.key).toRight(s"show: $shown is not defined").map(v => ("setting", v: Any))
        case KeyTag.Task(_) | KeyTag.SeqTask(_) =>
          query(state, "show") {
            val key = Scoped.scopedTask(parsed.scope, parsed.key.asInstanceOf[AttributeKey[Task[Any]]])
            ("task", extracted.runTask(key, state)._2: Any)
          }
        case KeyTag.InputTask(_) =>
          Left(s"show: $shown is an input task; run it with `sbt-task` (it needs command-line arguments)")
      }
    } yield {
      val rendered = String.valueOf(value)
      Js.obj(
        "key"       -> Js.str(shown),
        "kind"      -> Js.str(kind),
        "valueType" -> Js.str(parsed.key.tag.typeArg.getName),
        "value"     -> Js.str(if (rendered.length <= MaxValueChars) rendered else rendered.take(MaxValueChars) + " …"),
        "json"      -> toJson(value, depth = 0),
      )
    }
  }

  /** A best-effort JSON rendering of a task/setting value (collections, options, files, primitives). */
  private[sbtmcp] def toJson(value: Any, depth: Int): String =
    if (depth > 8) Js.str(String.valueOf(value))
    else
      value match {
        case null                         => "null"
        case ()                           => "null"
        case s: String                    => Js.str(s)
        case b: Boolean                   => Js.bool(b)
        case n: (Int | Long | Short | Byte) => n.toString
        case d: Double if !d.isNaN && !d.isInfinite => d.toString
        case f: Float if !f.isNaN && !f.isInfinite  => f.toString
        case f: java.io.File              => Js.str(f.getPath)
        case p: java.nio.file.Path        => Js.str(p.toString)
        case v: xsbti.VirtualFileRef      => Js.str(v.id)
        case a: Attributed[?]             => toJson(a.data, depth + 1)
        case o: Option[?]                 => o.fold("null")(toJson(_, depth + 1))
        case m: scala.collection.Map[?, ?] if m.keys.forall(_.isInstanceOf[String]) =>
          Js.obj(m.toSeq.take(MaxValueElements).map { case (k, v) => k.toString -> toJson(v, depth + 1) }*)
        case it: Iterable[?]              => Js.arr(it.iterator.take(MaxValueElements).map(toJson(_, depth + 1)).toSeq)
        case arr: Array[?]                => Js.arr(arr.iterator.take(MaxValueElements).map(toJson(_, depth + 1)).toSeq)
        case other                        => Js.str(String.valueOf(other))
      }

  // ---- shared ----

  private def buildRoot(state: State): java.nio.file.Path =
    java.nio.file.Paths.get(Project.extract(state).structure.root).toAbsolutePath.normalize

  private def rootCause(error: Throwable): String = {
    val causes = error match {
      case incomplete: Incomplete => Incomplete.allExceptions(incomplete).toList
      case other                  => List(other)
    }
    causes
      .map(e => Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).toList.last)
      .map(e => Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getName))
      .distinct
      .mkString("; ")
  }

  /** Minimal JSON writer: values are already-rendered JSON fragments. */
  private[sbtmcp] object Js {
    def str(s: String): String = {
      val sb = new StringBuilder(s.length + 2)
      sb.append('"')
      s.foreach {
        case '"'          => sb.append("\\\"")
        case '\\'         => sb.append("\\\\")
        case '\n'         => sb.append("\\n")
        case '\r'         => sb.append("\\r")
        case '\t'         => sb.append("\\t")
        case c if c < ' ' => sb.append(f"\\u${c.toInt}%04x")
        case c            => sb.append(c)
      }
      sb.append('"').toString
    }
    def bool(b: Boolean): String                 = if (b) "true" else "false"
    def arr(items: Iterable[String]): String     = items.mkString("[", ",", "]")
    def obj(fields: (String, String)*): String   = fields.map { case (k, v) => s"${str(k)}:$v" }.mkString("{", ",", "}")
  }
}
