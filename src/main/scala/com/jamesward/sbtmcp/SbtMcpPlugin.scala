package com.jamesward.sbtmcp

import sbt.*
import sbt.Keys.*

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.concurrent.{ Await, Promise, TimeoutException }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * sbt-mcp — an opt-in (default OFF) MCP server embedded in the sbt JVM.
 *
 * It exposes these tools over MCP (see [[McpServerRuntime]]):
 *   - `sbt-task`   : run an sbt command/task in the running sbt server
 *   - `list-tasks` : list the build's tasks/settings
 *   - `glob-search`: search Scala 3 symbols by name (tasty-query backed)
 *   - `inspect`    : list a symbol's members/signatures (tasty-query backed)
 *   - `symbol-location`: a symbol's source location (tasty-query backed)
 *   - `check`      : fast compile-free type check with the project's own compiler
 *
 * STUB STATUS: this is a scaffold. Its isolated runtime uses zio-http-mcp 0.8.3 /
 * an isolated symbol runtime with tasty-query 1.8.0 / 1.9.0 readers, and
 * encodes the intended architecture; exact library call sites should be validated
 * by a compile (some tasty-query flag/signature rendering is intentionally
 * simplified — see the isolated symbol runtime sources).
 *
 * ==Single server in a multi-module build==
 * sbt runs ONE server JVM per build, shared by every subproject and every
 * connected client. We must therefore start exactly one MCP server for the
 * whole build, not one per module. This is guaranteed three ways:
 *   1. The lifecycle hooks live in [[globalSettings]] on `Global / onLoad` /
 *      `Global / onUnload`, which fire once per build load/unload — NOT once per
 *      aggregated project.
 *   2. `mcpEnabled` / `mcpDisableInCI` / `mcpPort` / `mcpHost` are global settings,
 *      so there is a
 *      single source of truth for whether/where the server runs.
 *   3. [[serverHandle]] is a process-global `AtomicReference` and [[maybeStartServer]]
 *      uses an atomic compare-and-set gate, so even if `onLoad` were invoked
 *      more than once (e.g. a `reload`), only the first call binds a port; the
 *      rest are no-ops.
 */
object SbtMcpPlugin extends AutoPlugin {
  override def requires = sbt.plugins.JvmPlugin
  override def trigger  = allRequirements

  object autoImport {
    val mcpEnabled = settingKey[Boolean]("Start the embedded MCP server on sbt load (default false).")
    val mcpDisableInCI = settingKey[Boolean](
      "Do not start the embedded MCP server in CI or Heroku builds (default true)."
    )
    val mcpPort = settingKey[Int]("Loopback port for the embedded MCP server (default 5010).")
    val mcpHost = settingKey[String]("Interface to bind; keep on loopback (default 127.0.0.1).")
    val mcpDocsUrl = settingKey[Option[String]](
      "Upstream MCP server whose tools are proxied and merged with ours (default javadocs.dev). Set None to disable."
    )
    val mcpStatus  = taskKey[Unit]("Print embedded MCP server status.")
    val mcpInstall = taskKey[Unit](
      "Print instructions for an AI agent to register this sbt-mcp server in the local MCP client config and AGENTS.md."
    )
  }
  import autoImport.*

  // One sbt server JVM => at most one MCP server. Both are process-global.
  private val serverHandle = new AtomicReference[Option[McpServerRuntime.Handle]](None)
  private val starting     = new AtomicBoolean(false)

  // Latest build State, captured on each (re)load. Read off-thread by the
  // `list-tasks` tool to enumerate tasks/settings straight from the build
  // structure (in-process), instead of asking the sbt server. State only changes
  // across reloads, so an onLoad-captured value is current between reloads.
  private val currentState = new AtomicReference[Option[State]](None)

  // In-flight `sbt-task` requests, keyed by a generated execId. The `mcpExec`
  // command (run on the loop) looks up the command line + completes the promise
  // with the full result text (status + captured compiler output).
  private val pending =
    new ConcurrentHashMap[String, (String, Promise[String])]()

  // In-flight automatic index-refresh requests, keyed by execId. The completed
  // value is an optional warning note (e.g. the project doesn't currently compile).
  private val pendingRefresh = new ConcurrentHashMap[String, Promise[Option[String]]]()
  private final val RefreshCmd = "mcpRefreshIndexInternal"

  // In-flight `check` module-configuration refreshes, keyed by execId. The loop-side
  // command computes the module's classpath/options/sources and completes the promise.
  private val pendingCheckConfig =
    new ConcurrentHashMap[String, (ProjectRef, Configuration, Promise[Either[String, ModuleCheck.ModuleConfig]])]()
  private final val CheckConfigCmd = "mcpCheckConfigInternal"

  // Last computed configuration per (project id, configuration name), used when the
  // command loop is busy (e.g. mid-compile) so `check` never has to wait behind it.
  private val checkConfigs = new ConcurrentHashMap[(String, String), ModuleCheck.ModuleConfig]()

  // Kept as named values so the generated client configuration and the server-side
  // wait can be compared directly in fast regression tests.
  private[sbtmcp] final val CommandWait              = 30.minutes
  private[sbtmcp] final val RecommendedClientTimeout = CommandWait

  override lazy val globalSettings: Seq[Setting[?]] = Seq(
    mcpEnabled     := false,
    mcpDisableInCI := true,
    mcpPort        := 5010,
    mcpHost        := "127.0.0.1",
    mcpDocsUrl     := Some("https://www.javadocs.dev/mcp"),
    // `mcpExec <id>` runs a queued sbt-task ON the command loop (so it is
    // serialized with any `~` watch), then completes the waiting MCP request.
    commands += mcpExecCommand,
    // `mcpRefreshIndexInternal <id>` recomputes the symbol index on the loop.
    commands += mcpRefreshCommand,
    // `mcpCheckConfigInternal <id>` computes a module's `check` configuration on the loop.
    commands += mcpCheckConfigCommand,
    onLoad := { (s: State) =>
      val s1 = onLoad.value(s) // run the previously-registered onLoad first
      currentState.set(Some(s1))
      maybeStartServer(s1)
      s1
    },
    onUnload := { (s: State) =>
      stopServer(s.log)
      checkConfigs.clear()
      currentState.set(None)
      onUnload.value(s)
    },
  )

  /**
   * Command that executes one queued `sbt-task` request on the command loop and
   * completes its promise. Runs on the loop thread, so it is serialized with a
   * `~` watch (it runs between triggers) — no concurrent build execution.
   */
  private def mcpExecCommand: Command =
    Command.single("mcpExec") { (state, id) =>
      Option(pending.remove(id)) match {
        case None => state
        case Some((commandLine, promise)) =>
          val startedAtNanos = System.nanoTime()
          val (_, result, output) = sbt.McpInProcess.runOnLoop(state, commandLine)
          val elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
          val text = commandResultText(commandLine, result, output, elapsedMillis)
          promise.trySuccess(text)
          // Return the loop's ORIGINAL state: the sub-command ran for its effects
          // (compile/test results are on disk), but we must not leak its command-flow
          // mutations (a failed command consumes onFailure / rewrites remainingCommands)
          // back into the loop.
          state
      }
    }

  private[sbtmcp] def commandResultText(
      commandLine: String,
      result: Either[String, Unit],
      output: String,
      elapsedMillis: Long = 0L,
  ): String = {
    val status = result match {
      case Right(_)  => s"[ok] $commandLine"
      case Left(msg) => s"[error] $commandLine: $msg"
    }
    val evidence = s"elapsed: $elapsedMillis ms"
    if (output.isEmpty) s"$status\n$evidence\ncaptured output: empty"
    else s"$status\n$evidence\n$output"
  }

  /**
   * Command that recomputes the symbol index ON the command loop and completes the
   * waiting refresh request. Runs [[refreshFromState]] against the live State.
   */
  private def mcpRefreshCommand: Command =
    Command.single(RefreshCmd) { (state, id) =>
      val note: Option[String] =
        try { refreshFromState(state); None }
        catch {
          case scala.util.control.NonFatal(_) =>
            Some("the project does not currently compile — symbols reflect the last successful compile")
        }
      Option(pendingRefresh.remove(id)).foreach(_.trySuccess(note))
      state
    }

  /**
   * Recompute the current project's classpath and (re)index its symbols. MUST be
   * called ON the command loop (it runs the task engine): from the internal refresh
   * command, or directly from a build's own command. For an aggregating project,
   * traverses all transitive aggregates and merges their `fullClasspathAsJars`
   * results into the active index; a leaf project still indexes only itself. The
   * content-hashed jars form the fingerprint that [[SymbolIndexState]] uses to skip
   * rebuilding the tasty-query context when nothing changed.
   */
  def refreshFromState(state: State): Unit = {
    val extracted = Project.extract(state)
    val converter = extracted.get(fileConverter)
    val classpath    = scala.collection.mutable.LinkedHashMap.empty[java.nio.file.Path, String]
    val searchEntries = scala.collection.mutable.LinkedHashSet.empty[java.nio.file.Path]
    var taskState = state

    val refs = aggregateProjectRefs(extracted)
    val targetScalaVersion = refs
      .map(ref => extracted.get(ref / scalaVersion))
      .maxBy { version =>
        val numbers = version.split("[.-]").iterator.take(3).map(_.toIntOption.getOrElse(0)).toVector.padTo(3, 0)
        (numbers(0), numbers(1), numbers(2))
      }

    refs.foreach { ref =>
      val (nextState, cp) = extracted.runTask(ref / Compile / fullClasspathAsJars, taskState)
      taskState = nextState
      cp.headOption.foreach(attributed => searchEntries += converter.toPath(attributed.data))
      cp.foreach { attributed =>
        val path = converter.toPath(attributed.data)
        classpath.getOrElseUpdate(path, attributed.data.contentHashStr)
      }
    }

    val entries = classpath.keysIterator.toList
    val fingerprint = classpath.iterator.map { case (path, hash) => s"$path\u0000$hash" }.toVector
    SymbolIndexState.update(
      extracted.currentRef.project,
      entries,
      fingerprint,
      targetScalaVersion,
      searchEntries.toList,
    )
  }

  /** Current project followed by all of its transitive aggregates, once each. */
  private[sbtmcp] def aggregateProjectRefs(extracted: Extracted): Vector[ProjectRef] = {
    val seen = scala.collection.mutable.LinkedHashSet.empty[ProjectRef]

    def visit(ref: ProjectRef): Unit =
      if (seen.add(ref))
        extracted.structure.extra.projectFor(ref).aggregate.foreach(visit)

    visit(extracted.currentRef)
    seen.toVector
  }

  /**
   * Automatically bring the symbol index up to date before a symbol query. Enqueues
   * [[refreshFromState]] onto the command loop and waits for it, so it is serialized
   * with any `~` watch (running between triggers). If the loop is already busy — e.g.
   * this originates from inside another command — it is SKIPPED (the current index is
   * used) to avoid a deadlock. Returns an optional warning note to surface to the
   * caller (e.g. the project currently fails to compile, so the index is stale).
   */
  private def refreshIndex(): Option[String] = {
    if (sbt.McpInProcess.isBusy || currentState.get.isEmpty) return None
    val id      = "mcp-refresh-" + UUID.randomUUID().toString
    val promise = Promise[Option[String]]()
    pendingRefresh.put(id, promise)
    if (!sbt.McpInProcess.enqueue(s"$RefreshCmd $id", id)) { pendingRefresh.remove(id); None }
    else
      try Await.result(promise.future, 10.minutes)
      catch { case _: TimeoutException => pendingRefresh.remove(id); None }
  }

  // ---------------------------------------------------------------------------
  // `check`: fast compile-free validation of Scala 3 sources
  // ---------------------------------------------------------------------------

  /** Command that computes one module's `check` configuration ON the command loop. */
  private def mcpCheckConfigCommand: Command =
    Command.single(CheckConfigCmd) { (state, id) =>
      Option(pendingCheckConfig.remove(id)).foreach { case (ref, configuration, promise) =>
        val result =
          try Right(checkConfigFromState(state, ref, configuration))
          catch { case scala.util.control.NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString)) }
        promise.trySuccess(result)
      }
      state
    }

  /**
   * Compute everything `check` needs for `ref / configuration`. MUST run on the
   * command loop (it evaluates tasks). Upstream modules are compiled if needed (via
   * `dependencyClasspath`) so their latest API is visible; the module itself is NOT
   * compiled: its last compiled classes are used, and its changed sources are
   * re-checked from source (see [[ModuleCheck.changedSources]]).
   */
  def checkConfigFromState(state: State, ref: ProjectRef, configuration: Configuration): ModuleCheck.ModuleConfig = {
    val extracted = Project.extract(state)
    val converter = extracted.get(fileConverter)
    var taskState = state
    def run[A](key: TaskKey[A]): A =
      try {
        val (next, value) = extracted.runTask(key, taskState)
        taskState = next
        value
      } catch {
        case scala.util.control.NonFatal(e) =>
          throw new RuntimeException(s"${ref.project}/${configuration.name}/${key.key.label} failed: ${rootCause(e)}", e)
      }

    val scope         = ref / configuration
    val version       = extracted.get(scope / scalaVersion)
    val compilerJars  = run(scope / scalaInstance).allJars.toList.map(_.toPath)
    val options       = run(scope / scalacOptions).toList
    val ownClasses    = extracted.get(scope / classDirectory).toPath
    val (dependencies, notes) =
      try (run(scope / dependencyClasspath).map(a => converter.toPath(a.data)).toList, Nil)
      catch {
        case scala.util.control.NonFatal(_) =>
          // An upstream module does not compile: fall back to its last compiled classes.
          val external = run(scope / externalDependencyClasspath).map(a => converter.toPath(a.data)).toList
          val upstream = extracted.get(buildDependencies).classpathTransitiveRefs(ref)
          val ownMain  = if (configuration == Test) List(extracted.get(ref / Compile / classDirectory).toPath) else Nil
          val upstreamClasses = upstream.map(r => extracted.get(r / Compile / classDirectory).toPath).toList
          (
            ownMain ++ upstreamClasses ++ external,
            List("an upstream module does not currently compile; checked against its last compiled classes"),
          )
      }
    val sourceFiles = run(scope / sources).map(_.toPath.toAbsolutePath.normalize).toList
    // `previousCompile` depends on upstream compilation in sbt 2, so it fails while an
    // upstream module is broken: keep the stamps from the last refresh, or (none yet)
    // treat every source as changed, which is correct, only slower.
    val recorded =
      try
        run(scope / previousCompile).analysis.toScala.map { analysis =>
          analysis.readStamps.getAllSourceStamps.asScala.iterator.map { case (file, stamp) =>
            converter.toPath(file).toAbsolutePath.normalize -> stamp.writeStamp
          }.toMap
        }
      catch {
        case scala.util.control.NonFatal(_) =>
          Option(checkConfigs.get((ref.project, configuration.name))).flatMap(_.recordedStamps)
      }
    val module = ModuleCheck.ModuleConfig(
      projectId = ref.project,
      configuration = configuration.name,
      scalaVersion = version,
      compilerJars = compilerJars,
      classpath = (ownClasses :: dependencies).distinct,
      scalacOptions = options,
      sources = sourceFiles,
      recordedStamps = recorded,
      currentStamp = currentSourceStamp,
      notes = notes,
    )
    checkConfigs.put((ref.project, configuration.name), module)
    module
  }

  /** The most specific message of a (possibly `Incomplete`) task failure. */
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

  /** A source's current Zinc stamp, in the same kind as the recorded `previous` one. */
  private def currentSourceStamp(path: java.nio.file.Path, previous: String): Option[String] =
    if (previous.startsWith("farm(")) Some(sbt.internal.inc.Stamper.forFarmHashP(path).writeStamp)
    else if (previous.startsWith("lastModified(")) Some(sbt.internal.inc.Stamper.forLastModifiedP(path).writeStamp)
    else if (previous.startsWith("hash("))
      Some(sbt.internal.inc.Stamper.forContentHash(sbt.internal.inc.PlainVirtualFile(path)).writeStamp)
    else None

  /**
   * The (project, configuration) whose source directories contain `path`, by the
   * most specific directory. Settings only, so it is safe off the command loop.
   */
  private[sbtmcp] def ownerOf(extracted: Extracted, path: java.nio.file.Path): Option[(ProjectRef, Configuration)] = {
    val candidates = for {
      ref           <- extracted.structure.allProjectRefs
      configuration <- List(Compile, Test)
      directories = extracted.getOpt(ref / configuration / unmanagedSourceDirectories).getOrElse(Nil) ++
        extracted.getOpt(ref / configuration / managedSourceDirectories).getOrElse(Nil)
      directory <- directories.map(_.toPath.toAbsolutePath.normalize)
      if path.startsWith(directory)
    } yield (directory.getNameCount, ref, configuration)
    candidates.sortBy(-_._1).headOption.map { case (_, ref, configuration) => (ref, configuration) }
  }

  /** The build root: relative `check` paths resolve against it and output is shown relative to it. */
  private def buildRoot(extracted: Extracted): java.nio.file.Path =
    java.nio.file.Paths.get(extracted.structure.root).toAbsolutePath.normalize

  /**
   * Validate and resolve a `check` request. `configFor` supplies the module
   * configuration (computed on the loop, or from the cache).
   */
  private[sbtmcp] def checkRequest(
      state: State,
      files: List[String],
      content: Option[String],
      scope: Option[String],
      configFor: (ProjectRef, Configuration) => Either[String, ModuleCheck.ModuleConfig],
  ): Either[String, ModuleCheck.Result] = {
    val extracted = Project.extract(state)
    val root      = buildRoot(extracted)
    val paths     = files.map(_.trim).filter(_.nonEmpty).map(f => root.resolve(f).toAbsolutePath.normalize).distinct
    for {
      parsedScope <- ModuleCheck.Scope.parse(scope)
      _ <- Either.cond(paths.nonEmpty, (), "check: provide at least one file in `files`")
      _ <- Either.cond(content.isEmpty || paths.size == 1, (), "check: `content` can only be used with exactly one file")
      _ <- paths.find(p => !p.toString.endsWith(".scala")).map(p => s"check: only .scala files can be checked: $p").toLeft(())
      _ <- (if (content.isEmpty) paths.find(p => !java.nio.file.Files.isRegularFile(p)) else None)
             .map(p => s"check: file not found: $p").toLeft(())
      owners = paths.map(p => p -> ownerOf(extracted, p))
      _ <- owners.collectFirst { case (p, None) => s"check: $p is not in a source directory of any project" }.toLeft(())
      distinct = owners.flatMap(_._2).distinct
      _ <- Either.cond(
             distinct.size == 1,
             (),
             "check: files belong to different modules (" +
               distinct.map { case (r, c) => s"${r.project}/${c.name}" }.mkString(", ") +
               "); check each module separately",
           )
      (ref, configuration) = distinct.head
      version = extracted.get(ref / configuration / scalaVersion)
      _ <- Either.cond(isSupportedScala(version), (), s"check: ${ref.project} uses Scala $version; check supports Scala 3.3 and later")
      config <- configFor(ref, configuration)
      overlays = content.map(text => Map(paths.head -> text)).getOrElse(Map.empty)
      checked <-
        try Right(ModuleCheck.check(config, paths, overlays, parsedScope))
        catch { case scala.util.control.NonFatal(e) => Left(s"check: compiler failed: ${Option(e.getMessage).getOrElse(e.toString)}") }
    } yield checked
  }

  private def renderCheck(state: State, result: Either[String, ModuleCheck.Result]): String =
    result.map(r => ModuleCheck.render(r, buildRoot(Project.extract(state)))).merge

  private[sbtmcp] def isSupportedScala(version: String): Boolean = {
    val parts = version.split("[.-]")
    (parts.headOption.flatMap(_.toIntOption), parts.lift(1).flatMap(_.toIntOption)) match {
      case (Some(3), Some(minor)) => minor >= 3
      case (Some(major), _)       => major > 3
      case _                      => false
    }
  }

  /**
   * Run a `check` request ON the command loop (e.g. from a build's own command);
   * the module configuration is computed directly.
   */
  def checkOnLoop(state: State, files: List[String], content: Option[String] = None, scope: Option[String] = None): String =
    renderCheck(state, checkResultOnLoop(state, files, content, scope))

  /** Structured form of [[checkOnLoop]]: the diagnostics, or a request error. */
  def checkResultOnLoop(
      state: State,
      files: List[String],
      content: Option[String] = None,
      scope: Option[String] = None,
  ): Either[String, ModuleCheck.Result] =
    checkRequest(
      state,
      files,
      content,
      scope,
      (ref, configuration) =>
        try Right(checkConfigFromState(state, ref, configuration))
        catch { case scala.util.control.NonFatal(e) => Left(s"check: could not load module settings: ${e.getMessage}") },
    )

  /**
   * The `check` tool entry point (MCP handler threads). The compiler runs here, off
   * the command loop. The module configuration is refreshed on the loop when it is
   * idle; while it is busy (a compile or `~` watch is running), the last computed
   * configuration is used so a check never queues behind a build.
   */
  private def runCheck(files: List[String], content: Option[String], scope: Option[String]): String =
    currentState.get match {
      case None => "check: build not loaded yet"
      case Some(state) =>
        renderCheck(state, checkRequest(state, files, content, scope, (ref, configuration) => {
          val cached = Option(checkConfigs.get((ref.project, configuration.name)))
          cached match {
            case Some(config) if sbt.McpInProcess.isBusy =>
              Right(config.copy(notes = config.notes :+ "sbt is busy; used the module settings from its last refresh"))
            case _ =>
              refreshCheckConfig(ref, configuration) match {
                case Right(config) => Right(config)
                case Left(error)   => cached.toRight(error)
              }
          }
        }))
    }

  private def refreshCheckConfig(ref: ProjectRef, configuration: Configuration): Either[String, ModuleCheck.ModuleConfig] = {
    val id      = "mcp-check-" + UUID.randomUUID().toString
    val promise = Promise[Either[String, ModuleCheck.ModuleConfig]]()
    pendingCheckConfig.put(id, (ref, configuration, promise))
    if (!sbt.McpInProcess.enqueue(s"$CheckConfigCmd $id", id)) {
      pendingCheckConfig.remove(id)
      Left("check: no sbt channel available yet (is an sbt session attached?)")
    } else
      try Await.result(promise.future, CommandWait)
      catch {
        case _: TimeoutException =>
          pendingCheckConfig.remove(id)
          Left("check: timed out waiting for sbt to load the module settings")
      }
  }

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    // Opt out of sbt 2.x action caching: this task has side effects / reads
    // process-global state, so a cached (skipped) re-run would print nothing.
    mcpStatus := Def.uncached {
      val log         = streams.value.log
      val enabled     = mcpEnabled.value
      val disableInCI = mcpDisableInCI.value
      serverHandle.get match {
        case Some(h) => log.info(s"sbt-mcp: running at http://${h.host}:${h.port}/")
        case None if enabled && disableInCI && isAutomatedBuild(sys.env) =>
          log.info(
            "sbt-mcp: not running (disabled in CI or a Heroku build; set `Global / mcpDisableInCI := false` to override)"
          )
        case None if enabled => log.info("sbt-mcp: not running (server startup failed or has not completed)")
        case None            => log.info("sbt-mcp: not running (set `Global / mcpEnabled := true` to enable)")
      }
    },
    // The MCP server is a single process-global instance shared by the whole build,
    // so its status is a build-wide fact, not a per-project one. Without this, running
    // `mcpStatus` on an aggregating root would aggregate to every subproject and print
    // the same line once per module. Disabling aggregation makes it print exactly once.
    mcpStatus / aggregate := false,
    // Agent-facing onboarding: print how to register this server in the local MCP
    // client config and what AGENTS.md guidance to add. Emitted via the task logger
    // (streams.log) so the text is captured in the global log delta and returned to
    // an agent that invokes it through the `sbt-task` MCP tool. Side-effecting +
    // reads global settings, so opt out of action caching like `mcpStatus`.
    mcpInstall := Def.uncached {
      val log        = streams.value.log
      val host       = mcpHost.value
      val port       = mcpPort.value
      val enabled    = mcpEnabled.value
      val projName   = name.value
      val serverName = if (projName.startsWith("sbt-mcp")) projName else s"sbt-mcp-$projName"
      val url        = s"http://$host:$port/"
      installInstructions(serverName, url, enabled, port).linesIterator.foreach(line => log.info(line))
    },
    // Single build-wide server (see mcpStatus): print the guidance once, not per module.
    mcpInstall / aggregate := false,
  )

  /**
   * Start the single MCP server iff enabled and not already running. The
   * `starting` CAS gate makes this idempotent and race-free, so a multi-module
   * build (or a repeated `onLoad`) never binds the port twice.
   */
  private def maybeStartServer(state: State): Unit = {
    if (serverHandle.get.isDefined) return
    val extracted   = Project.extract(state)
    val enabled     = extracted.getOpt(mcpEnabled).getOrElse(false)
    val disableInCI = extracted.getOpt(mcpDisableInCI).getOrElse(true)
    if (!shouldStartServer(enabled, disableInCI, sys.env)) {
      if (enabled && disableInCI && isAutomatedBuild(sys.env))
        state.log.info(
          "sbt-mcp: MCP server disabled in CI or a Heroku build (set `Global / mcpDisableInCI := false` to override)"
        )
      return
    }
    if (!starting.compareAndSet(false, true)) return // another thread is starting it
    try {
      if (serverHandle.get.isEmpty) {
        val host   = extracted.getOpt(mcpHost).getOrElse("127.0.0.1")
        val port   = extracted.getOpt(mcpPort).getOrElse(5010)
        val docsUrl = extracted.getOpt(mcpDocsUrl).flatten
        val handle = McpServerRuntime.start(
          host,
          port,
          runCommand,
          () => refreshIndex(),
          () => taskInfos(currentState.get),
          docsUrl,
          (files, content, scope) => runCheck(files, content, scope),
        )
        serverHandle.set(Some(handle))
        state.log.info(
          s"""sbt-mcp: MCP server started at http://$host:$port/
             |sbt-mcp: Setup in an AI agent with this prompt:
             |follow the instructions available via `./sbt mcpInstall` to setup sbt-mcp
             |""".stripMargin)
      }
    } catch {
      case scala.util.control.NonFatal(e) =>
        state.log.error(s"sbt-mcp: failed to start MCP server: ${e.getMessage}")
    } finally {
      starting.set(false)
    }
  }

  private[sbtmcp] def shouldStartServer(
      enabled: Boolean,
      disableInCI: Boolean,
      environment: Map[String, String],
  ): Boolean =
    enabled && !(disableInCI && isAutomatedBuild(environment))

  private final val CiEnvironmentVariable                  = "CI"
  private final val HerokuSourceVersionEnvironmentVariable = "SOURCE_VERSION"

  private def isAutomatedBuild(environment: Map[String, String]): Boolean =
    environment.get(CiEnvironmentVariable).exists(isTruthy) ||
      environment.get(HerokuSourceVersionEnvironmentVariable).exists(_.trim.nonEmpty)

  private def isTruthy(rawValue: String): Boolean = {
    val value = rawValue.trim
    value.nonEmpty && value != "0" && !value.equalsIgnoreCase("false")
  }

  /**
   * Build the agent-facing onboarding text printed by the `mcpInstall` task: how to
   * register this server in the local MCP client config, and the AGENTS.md usage
   * guidance an agent should adopt. `serverName` defaults to `sbt-mcp-<project name>`
   * (rename freely); `url` is derived from the configured `mcpHost`/`mcpPort`.
   */
  private[sbtmcp] def installInstructions(serverName: String, url: String, enabled: Boolean, port: Int): String = {
    val enableBlock =
      if (enabled)
        s"The server is enabled; once sbt is loaded it listens at $url."
      else
        s"""The MCP server is NOT enabled yet. Enable it by adding to build.sbt (or a
           |git-ignored local dev override):
           |
           |    Global / mcpEnabled := true
           |    Global / mcpPort    := $port
           |
           |then `reload` sbt so the server starts.""".stripMargin

    s"""|=== sbt-mcp install instructions (for an AI agent) ===
        |
        |1) Enable and locate the server.
        |
        |$enableBlock
        |
        |IMPORTANT: `mcpInstall` only prints guidance. It does not inspect or edit any
        |existing config. Before adding the snippet below, check for the exact server
        |key or another key with the same URL, then reconcile its name, type, URL,
        |timeout, and disabled fields manually.
        |
        |`mcpInstall` does not daemonize sbt. A one-shot `./sbt mcpInstall` process
        |exits after printing these instructions and its MCP server stops. Start a
        |long-lived sbt session and keep it loaded; that session must remain running
        |while the MCP client uses the server.
        |
        |2) Register the server in the local project's MCP client config. Add it to the
        |   config file your agent reads (create the file if missing):
        |     - Kiro:        .kiro/settings/mcp.json
        |     - Claude Code: .mcp.json
        |     - Cursor:      .cursor/mcp.json
        |
        |    {
        |      "mcpServers": {
        |        "$serverName": {
        |          "type": "http",
        |          "url": "$url",
        |          "timeout": ${RecommendedClientTimeout.toMillis},
        |          "disabled": false
        |        }
        |      }
        |    }
        |
        |   After starting the long-lived sbt session, RECONNECT / re-init the MCP client
        |   so it picks up the tool list (clients fetch tools once at connect time).
        |
        |   For plain direct capture without supershell / formatted logger sequences, use:
        |
        |       ./sbt --server --no-colors --supershell=false mcpInstall
        |
        |3) Add this guidance to the project's AGENTS.md (create it if missing):
        |
        |## Build, Test & Dev Workflow
        |
        |- Use the MCP server named `$serverName` for ALL sbt interactions. Run
        |  commands/tasks through its `sbt-task` tool; use `list-tasks` to discover
        |  tasks/settings or get per-task help. Do not invoke `sbt`, a shell, or a
        |  separate sbt client when the MCP tool is available. If the MCP server is
        |  unavailable, state that clearly and use a direct CLI fallback only when
        |  necessary. Separate multiple sbt commands with `;`.
        |
        |- After editing Scala 3 sources, validate them with the `check` tool of
        |  `$serverName` (pass the edited files; it is a fast, compile-free type
        |  check). Use `"scope":"module"` after changing an API. Run `compile`/`test`
        |  through `sbt-task` before declaring the work done.
        |
        |- Use `$serverName` for Scala/classpath symbol work: `glob-search` to
        |  find/list symbols, `inspect` for members/signatures, and `symbol-location`
        |  for source locations. Prefer these over text search, dependency-jar
        |  inspection, or guessed APIs whenever the question is about Scala symbols.
        |
        |- JavaDoc/ScalaDoc lookups are available through the same server via its
        |  proxied javadocs.dev tools.
        |
        |=== end sbt-mcp install instructions ===""".stripMargin
  }

  private def stopServer(log: Logger): Unit =
    serverHandle.getAndSet(None).foreach { h =>
      try h.close()
      catch { case scala.util.control.NonFatal(_) => () }
      log.info("sbt-mcp: MCP server stopped")
    }
    SymbolIndexState.shutdown()
    ModuleCheck.shutdown()

  /**
   * Enumerate the build's task/setting keys with their descriptions, read directly
   * from the resolved build structure (`structure.index.keyMap`) — no sbt command
   * executed. This is the in-process alternative to scraping the sbt server's
   * `tasks` output; it powers the `list-tasks` MCP tool's bulk listing.
   */
  /**
   * Run an sbt command line for the `sbt-task` tool. The command is appended to
   * sbt's command loop (via [[sbt.McpInProcess.enqueue]]) so it runs ON the loop
   * thread — serialized with any `~` watch, exactly like a command typed at the
   * shell. We then block until the loop's `mcpExec` completes our promise.
   *
   * This is safe during a `~` watch: sbt's watch waits on a separate UI thread and
   * leaves the command loop free between triggers, so the queued command runs then.
   */
  private def runCommand(commandLine: String): String =
    currentState.get match {
      case None => "sbt-task: build not loaded yet"
      case Some(_) =>
        val id      = "mcp-" + UUID.randomUUID().toString
        val promise = Promise[String]()
        pending.put(id, (commandLine, promise))
        if (!sbt.McpInProcess.enqueue(s"mcpExec $id", id)) {
          pending.remove(id)
          "sbt-task: no sbt channel available yet (is an sbt session attached?)"
        } else {
          try Await.result(promise.future, CommandWait)
          catch {
            case _: TimeoutException =>
              pending.remove(id)
              s"[timeout] $commandLine (still queued behind a long-running command?)"
          }
        }
    }

  private def taskInfos(stateOpt: Option[State]): List[(String, String)] =
    stateOpt match {
      case None => Nil
      case Some(state) =>
        try {
          val keyMap = Project.extract(state).structure.index.keyMap
          keyMap.values.toList
            .map(k => (k.label, k.description.getOrElse("")))
            .distinct
        } catch { case scala.util.control.NonFatal(_) => Nil }
    }
}
