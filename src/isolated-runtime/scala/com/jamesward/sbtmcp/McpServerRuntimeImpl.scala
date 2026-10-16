package com.jamesward.sbtmcp

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.McpInput.given
import com.jamesward.ziohttp.mcp.McpOutput.given
import com.jamesward.ziohttp.mcp.McpError.given
import com.jamesward.ziohttp.mcp.client.{McpClient, McpClientConfig}
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.json.*
import zio.json.ast.Json
import zio.schema.{ DeriveSchema, Schema }

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ Await, ExecutionContext, TimeoutException }
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * Builds the MCP server (three tools) and serves it over zio-http on a daemon
 * fiber bound to loopback. One instance per sbt server JVM — see [[SbtMcpPlugin]].
 */
object McpServerRuntimeImpl {

  private val ShutdownWait = FiniteDuration(2, TimeUnit.SECONDS)
  private val StartupWait  = FiniteDuration(5, TimeUnit.SECONDS)

  /**
   * Opaque handle used by the plugin to stop the server on `onUnload`. `socket` is the
   * Unix domain socket served with the stdio framing, `port` the HTTP port (or -1).
   */
  final case class Handle(
      host: String,
      port: Int,
      socket: Option[java.nio.file.Path],
      private val shutdown: () => Unit,
      private val awaitTermination: Long => Boolean,
  ) {
    def close(): Unit = shutdown()
    def awaitClosed(timeoutMillis: Long): Boolean = awaitTermination(timeoutMillis)
  }

  // ---- Tool argument schemas (drive MCP inputSchema generation) ----
  final case class SbtTaskArgs(command: String)

  final class StartupFailure(val childHandle: Handle, cause: Throwable) extends RuntimeException(cause)
  object SbtTaskArgs { given Schema[SbtTaskArgs] = DeriveSchema.gen[SbtTaskArgs] }

  final case class GlobSearchArgs(query: String, inPackage: Option[String] = None)
  object GlobSearchArgs { given Schema[GlobSearchArgs] = DeriveSchema.gen[GlobSearchArgs] }

  final case class InspectArgs(symbol: String)
  object InspectArgs { given Schema[InspectArgs] = DeriveSchema.gen[InspectArgs] }

  final case class ListTasksArgs(all: Boolean = false, task: Option[String] = None)
  object ListTasksArgs { given Schema[ListTasksArgs] = DeriveSchema.gen[ListTasksArgs] }

  final case class CheckArgs(files: List[String], content: Option[String] = None, scope: Option[String] = None)
  object CheckArgs { given Schema[CheckArgs] = DeriveSchema.gen[CheckArgs] }

  /**
   * Serve the tools on `socket` (the stdio transport's framing, one connection per
   * client, see [[SocketListener]]) and, when `port` is positive, over Streamable HTTP
   * on `host:port`. At least one of the two must be given.
   */
  def start(
      socket: Option[java.nio.file.Path],
      host: String,
      port: Int,
      implementationVersion: String,
      runCommand: String => String,
      refresh: () => Option[String],
      listTasks: () => List[(String, String)],
      globSearch: (String, Option[String]) => String,
      inspectSymbol: String => String,
      locateSymbol: String => String,
      check: (List[String], Option[String], Option[String]) => String,
      buildTool: (String, Map[String, List[String]]) => String,
      docsUrl: Option[String],
  ): Handle = {
    // sbt 2.0.x puts slf4j-api 1.7.x in a parent classloader without a binding.
    // Netty's default logger discovery probes SLF4J first, which prints the noisy
    // StaticLoggerBinder warning before falling back. A binding added by this plugin
    // (or a scripted meta-build) lives in a child classloader and is therefore
    // invisible to that parent API. Select JUL explicitly before zio-http starts so
    // Netty never performs the broken SLF4J probe. ZIO's own server logging remains
    // controlled by the quiet runtime below.
    io.netty.util.internal.logging.InternalLoggerFactory.setDefaultFactory(
      io.netty.util.internal.logging.JdkLoggerFactory.INSTANCE
    )

    val base: McpServer[Any] = builtIns(
      implementationVersion, runCommand, refresh, listTasks, globSearch, inspectSymbol, locateSymbol, check, buildTool,
    )
    // Proxy an upstream MCP server (javadocs.dev by default): its tools are merged
    // into tools/list, and any tools/call not matching our built-ins is forwarded.
    val withProxy: McpServer[Client] = docsUrl.filter(_.trim.nonEmpty) match {
      case Some(u) => base.toolSource(new ProxyToolSource(u.trim))
      case None    => base
    }
    serve(socket, host, port, withProxy.mountedAt("/"))
  }

  /**
   * The answers the stdio launcher (`SbtMcpStdio.java`) gives while sbt is not
   * running yet: `initialize` and `tools/list` for legacy clients, `server/discover`
   * and `tools/list` for 2026-07-28 clients. They are the results of real requests
   * to the built-in tools' server (no docs proxy, so no network and a stable
   * listing), made in-process. The tool definitions don't depend on the build, so
   * the snapshot only changes with the plugin version.
   */
  def snapshot(implementationVersion: String): String = {
    val unavailable = "not available"
    val server = builtIns(
      implementationVersion,
      _ => unavailable,
      () => None,
      () => Nil,
      (_, _) => unavailable,
      _ => unavailable,
      _ => unavailable,
      (_, _, _) => unavailable,
      (_, _) => unavailable,
    ).mountedAt("/")
    val routes = server.statelessRoutes
    val client = Json.Obj("name" -> Json.Str("sbt-mcp-stdio"), "version" -> Json.Str(implementationVersion))
    val modernMeta = Json.Obj(
      "_meta" -> Json.Obj(
        McpMeta.ProtocolVersion    -> Json.Str(ProtocolVersion.latest.wire),
        McpMeta.ClientCapabilities -> Json.Obj(),
        McpMeta.ClientInfo         -> client,
      )
    )
    def result(method: String, params: Json.Obj): ZIO[Any, Throwable, Json] = {
      val body = Json.Obj(
        "jsonrpc" -> Json.Str("2.0"),
        "id"      -> Json.Num(1),
        "method"  -> Json.Str(method),
        "params"  -> params,
      )
      val request = Request
        .post(URL.root, Body.fromString(body.toString))
        .addHeader(Header.ContentType(MediaType.application.json))
        .addHeader(Header.Accept(MediaType.application.json, MediaType.text.`event-stream`))
      ZIO.scoped(for
        response <- routes.runZIO(request)
        text     <- response.body.asString
        // A JSON body, or SSE `data:` lines (the last one carries the response).
        json = text.linesIterator.map(_.trim).filter(_.startsWith("data:")).map(_.drop(5).trim).toList.lastOption
                 .getOrElse(text)
        parsed <- ZIO.fromEither(Json.decoder.decodeJson(json)).mapError(RuntimeException(_))
        out    <- ZIO.fromOption(parsed.asObject.flatMap(_.get("result")))
                    .orElseFail(RuntimeException(s"$method: no result in $text"))
      yield out)
    }
    val effect =
      for
        initialize <- result(
                        "initialize",
                        Json.Obj(
                          "protocolVersion" -> Json.Str(ProtocolVersion.latestLegacy.wire),
                          "capabilities"    -> Json.Obj(),
                          "clientInfo"      -> client,
                        ),
                      )
        legacyTools <- result("tools/list", Json.Obj())
        discover    <- result("server/discover", modernMeta)
        modernTools <- result("tools/list", modernMeta)
      yield Json.Obj(
        "format"  -> Json.Num(1),
        "version" -> Json.Str(implementationVersion),
        "legacy"  -> Json.Obj("initialize" -> initialize, "tools/list" -> legacyTools),
        "modern"  -> Json.Obj("server/discover" -> discover, "tools/list" -> modernTools),
      )
    val runtime = Unsafe.unsafe { implicit u => Runtime.unsafe.fromLayer(Runtime.removeDefaultLoggers) }
    try
      Unsafe.unsafe { implicit u =>
        runtime.unsafe.run(effect).getOrThrowFiberFailure()
      }.toJsonPretty + "\n"
    finally Unsafe.unsafe { implicit u => runtime.unsafe.shutdown() }
  }

  private def builtIns(
      implementationVersion: String,
      runCommand: String => String,
      refresh: () => Option[String],
      listTasks: () => List[(String, String)],
      globSearch: (String, Option[String]) => String,
      inspectSymbol: String => String,
      locateSymbol: String => String,
      check: (List[String], Option[String], Option[String]) => String,
      buildTool: (String, Map[String, List[String]]) => String,
  ): McpServer[Any] = {
    val core: McpServer[Any] =
      McpServer("sbt-mcp", implementationVersion)
        .instructions(
          "Tools for driving a Scala/sbt build. Structured build tools return data with an output schema: " +
            "`compile`, `test`, `run`, `clean`, `publish-local`, `projects`, `test-names`, `main-classes`, " +
            "`dependencies` and `show`. `sbt-task` runs any other sbt command (and `list-tasks` lists the tasks). " +
            "Search/inspect Scala 3 symbols read from TASTy, and `check` Scala 3 sources quickly without compiling. " +
            "The symbol index refreshes automatically before each query. " +
            "Documentation tools are proxied from javadocs.dev."
        )
        .tool(sbtTaskTool(runCommand))
        .tool(listTasksTool(runCommand, listTasks))
        .tool(globSearchTool(refresh, globSearch))
        .tool(inspectTool(refresh, inspectSymbol))
        .tool(locationTool(refresh, locateSymbol))
        .tool(checkTool(check))
    BuildTools.all(buildTool).foldLeft(core)(_.tool(_))
  }

  private def serve(socket: Option[java.nio.file.Path], host: String, port: Int, server: McpServer[Client]): Handle = {
    require(socket.isDefined || port > 0, "neither a socket nor an HTTP port to serve on")
    // Embedded sbt servers are frequently torn down and rebound during `reload`.
    // Use zio-http's fast Netty shutdown profile so event-loop finalizers do not
    // impose the library defaults (2-second quiet period / 15-second timeout).
    val serverLayer =
      ZLayer.make[Server](
        ZLayer.succeed(
          Server.Config.default
            .binding(host, port)
            .gracefulShutdownTimeout(1.second)
        ),
        ZLayer.succeed(NettyConfig.defaultWithFastShutdown),
        Server.customized,
      )

    // Silence the embedded server's ZIO INFO chatter ("Starting the server...",
    // "Server started", per-request "MCP tools/call" …) that would otherwise print
    // to the sbt console. This must be applied at the RUNTIME level (not via
    // `.provide` on the serve effect), so that zio-http's request-handler fibers —
    // which run under this runtime — inherit it too. WARNING/ERROR are kept so
    // genuine failures still surface.
    val quietLogging =
      Runtime.removeDefaultLoggers >>> Runtime.addLogger(
        zio.ZLogger.default.map(Predef.println).filterLogLevel(_ >= zio.LogLevel.Warning)
      )

    val runtime = Unsafe.unsafe { implicit u => Runtime.unsafe.fromLayer(quietLogging) }
    val started = scala.concurrent.Promise[Unit]()
    val serving =
      ZIO.scoped {
        for
          client <- Client.default.build
          _      <- ZIO.when(port > 0)(
                      serverLayer.build.flatMap(http => Server.install(server.statelessRoutes).provideEnvironment(http ++ client))
                    )
          _      <- ZIO.foreachDiscard(socket)(path => SocketListener.serve(path, server.serveStream(_, _).provideEnvironment(client)))
          _      <- ZIO.succeed(started.trySuccess(()))
          _      <- ZIO.never
        yield ()
      }.tapErrorCause(cause => ZIO.succeed(started.tryFailure(cause.squash)).unit)
    val fiber = Unsafe.unsafe { implicit u => runtime.unsafe.fork(serving) }
    val closed        = AtomicBoolean(false)
    val runtimeClosed = AtomicBoolean(false)
    val terminated    = CountDownLatch(1)

    def shutdownRuntime(): Unit =
      if runtimeClosed.compareAndSet(false, true) then
        try Unsafe.unsafe { implicit u => runtime.unsafe.shutdown() }
        finally terminated.countDown()

    def awaitTermination(timeoutMillis: Long): Boolean =
      try terminated.await(timeoutMillis, TimeUnit.MILLISECONDS)
      catch
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          false

    def shutdown(): Unit =
      if closed.compareAndSet(false, true) then
        // `Fiber.interrupt` waits for every uninterruptible finalizer. Netty or
        // another third-party finalizer must never be allowed to freeze sbt's
        // synchronous onUnload hook, so start interruption asynchronously and
        // put a hard JVM-side bound around the wait. Interruption continues in
        // the background if that bound is exceeded.
        val interrupted = Unsafe.unsafe { implicit u => runtime.unsafe.runToFuture(fiber.interrupt) }
        interrupted.onComplete(_ => shutdownRuntime())(using ExecutionContext.parasitic)
        try Await.ready(interrupted, McpServerRuntimeImpl.ShutdownWait)
        catch
          case _: TimeoutException => ()
          case _: InterruptedException =>
            Thread.currentThread().interrupt()
        ()

    val handle = Handle(host, if port > 0 then port else -1, socket, () => shutdown(), awaitTermination)
    try
      // Do not publish a handle or log successful startup until zio-http has
      // installed the routes and bound the listener. Bind failures now surface
      // synchronously to the plugin's existing startup error handling.
      Await.result(started.future, McpServerRuntimeImpl.StartupWait)
      handle
    catch
      case error: InterruptedException =>
        Thread.currentThread().interrupt()
        shutdown()
        throw StartupFailure(handle, error)
      case NonFatal(error) =>
        shutdown()
        throw StartupFailure(handle, error)
  }

  // ---- Tools ----

  private def sbtTaskTool(runCommand: String => String): McpToolHandler =
    McpTool("sbt-task")
      .description(
        "Run any sbt command/task in this build and return its status plus the captured " +
          "console output (compiler warnings and errors included). The command is queued onto " +
          "sbt's command loop (in-process — no separate sbt server connection) and runs there, " +
          "so it works even while a `~` watch is active (it runs between the watch's file-change " +
          "triggers, serialized with it). Every result includes elapsed milliseconds and either " +
          "captured console output or an explicit `captured output: empty` marker. Status is " +
          "`[ok] <cmd>` or `[error] <cmd>: …`. Prefer the structured tools (`compile`, `test`, `run`, " +
          "`clean`, `publish-local`, `show`, …) where one fits: they return diagnostics and test results " +
          "as data. Use this for anything else, e.g. `reload`, `set …`, `scalafmtAll`, command chains (`a; b`)."
      )
      // Running arbitrary sbt tasks can mutate the project and the world.
      .annotations(destructive = OptBool.True, openWorld = OptBool.True)
      .handle[Any, Throwable, SbtTaskArgs, String] { (args: SbtTaskArgs) =>
        ZIO.attemptBlocking(runCommand(args.command))
      }

  private def listTasksTool(
      runCommand: String => String,
      listTasks: () => List[(String, String)],
  ): McpToolHandler =
    McpTool("list-tasks")
      .description(
        "List the sbt tasks and settings defined in the loaded build, with each key's " +
          "description. The bulk listing is read directly from the build structure IN-PROCESS " +
          "(no sbt command is executed).\n" +
          "Parameters:\n" +
          "  - all (boolean, default false): include keys that have no description " +
          "(otherwise only described keys are shown, like sbt's `tasks`).\n" +
          "  - task (string, optional): return detailed help for a single task — its " +
          "description and argument/parameter usage via `help <task>` — instead of the list. " +
          "Example: {\"task\":\"testOnly\"}."
      )
      .annotations(readOnly = OptBool.True)
      .handle[Any, Throwable, ListTasksArgs, String] { (args: ListTasksArgs) =>
        args.task match {
          case Some(t) if t.trim.nonEmpty =>
            // Rich per-task usage/params come from sbt's own `help`, run in-process.
            ZIO.attemptBlocking(runCommand(s"help ${t.trim}"))
          case _ =>
            ZIO.attempt {
              val all = listTasks()
              if (all.isEmpty) "task list unavailable (build not loaded yet)"
              else {
                val shown = if (args.all) all else all.filter(_._2.nonEmpty)
                shown
                  .sortBy(_._1)
                  .map { case (name, desc) => if (desc.isEmpty) name else f"$name%-30s $desc" }
                  .mkString("\n")
              }
            }
        }
      }

  private def globSearchTool(
      refresh: () => Option[String],
      globSearch: (String, Option[String]) => String,
  ): McpToolHandler =
    McpTool("glob-search")
      .description(
        "Search Scala 3 symbols by unqualified name in the active project and its transitive " +
          "aggregated subprojects (TASTy). Dependency jars remain available for type resolution " +
          "and exact inspect/location lookup, but are not enumerated by glob-search. " +
          "Set `inPackage` to restrict to a package (recommended — searching the whole classpath is " +
          "slow). Use `query`=\"*\" (or empty) to LIST ALL symbols in that package — e.g. " +
          "{\"query\":\"*\",\"inPackage\":\"com.example\"} lists everything in com.example. Returns " +
          "each match's kind and fully-qualified name. The index refreshes automatically first."
      )
      .annotations(readOnly = OptBool.True)
      .handle[Any, Throwable, GlobSearchArgs, String] { (args: GlobSearchArgs) =>
        ZIO.attempt {
          val note    = refresh()
          val wildcard = { val q = args.query.trim; q.isEmpty || q == "*" }
          val body =
            if (wildcard && args.inPackage.forall(_.trim.isEmpty))
              "to list all symbols, provide `inPackage` (e.g. {\"query\":\"*\",\"inPackage\":\"com.example\"})"
            else globSearch(args.query, args.inPackage)
          withNote(body, note)
        }
      }

  private def inspectTool(refresh: () => Option[String], inspectSymbol: String => String): McpToolHandler =
    McpTool("inspect")
      .description(
        "Inspect a Scala 3 symbol by fully-qualified name: lists members and (approximate) signatures. " +
          "Works for classes, objects and traits."
      )
      .annotations(readOnly = OptBool.True)
      .handle[Any, Throwable, InspectArgs, String] { (args: InspectArgs) =>
        ZIO.attempt {
          val note = refresh()
          val body = inspectSymbol(args.symbol)
          withNote(body, note)
        }
      }

  private def locationTool(refresh: () => Option[String], locateSymbol: String => String): McpToolHandler =
    McpTool("symbol-location")
      .description(
        "Return the source location (as `path:line`) of a Scala 3 symbol — a class, object, " +
          "trait, or a top-level/static term — read from its defining tree in TASTy."
      )
      .annotations(readOnly = OptBool.True)
      .handle[Any, Throwable, InspectArgs, String] { (args: InspectArgs) =>
        ZIO.attempt {
          val note = refresh()
          val body = locateSymbol(args.symbol)
          withNote(body, note)
        }
      }

  private def checkTool(check: (List[String], Option[String], Option[String]) => String): McpToolHandler =
    McpTool("check")
      .description(
        "Fast syntax + type check of Scala 3 source files WITHOUT compiling (no bytecode, no " +
          "incremental-compile bookkeeping, does not wait behind a running build or `~` watch). Runs " +
          "the project's own Scala compiler, warm and in-process, through every checking phase " +
          "(parser, typer, macro/inline expansion, override/abstract-member checks, pattern-match " +
          "exhaustivity, unused warnings) with the module's classpath and scalacOptions. Use it after " +
          "each edit for IDE-speed feedback; run `sbt-task` `compile`/`test` before finishing.\n" +
          "Parameters:\n" +
          "  - files (string list): source paths, absolute or relative to the build root. All must belong " +
          "to the same module (project + Compile/Test).\n" +
          "  - content (string, optional): unsaved content to check instead of the file on disk (exactly " +
          "one file).\n" +
          "  - scope (optional): \"files\" (default) checks the given files plus any sources of the module " +
          "changed since its last compile; \"module\" re-checks every source of the module (finds breakage " +
          "in files that USE a changed API).\n" +
          "Returns `[ok]`/`[error]`, error/warning counts, and diagnostics as `path:line:column: severity: " +
          "message`. Other modules are seen as of their last compile."
      )
      .annotations(readOnly = OptBool.True)
      .handle[Any, Throwable, CheckArgs, String] { (args: CheckArgs) =>
        ZIO.attemptBlocking(check(args.files, args.content, args.scope))
      }

  /** Append an optional warning note (e.g. the project doesn't compile) to a tool result. */
  private def withNote(body: String, note: Option[String]): String =
    note.fold(body)(n => s"$body\n(note: $n)")

  /**
   * A [[McpToolSource]] that proxies an upstream MCP server (e.g. javadocs.dev). Its
   * tools are merged into `tools/list`, and a `tools/call` for any name not matched by
   * our built-in tools is forwarded to the upstream. Each request opens a scoped MCP
   * session over the shared zio-http [[Client]] supplied to the server routes. An
   * unreachable upstream degrades gracefully to an empty tool list / an `isError`
   * result rather than failing our server.
   */
  private val docsProxyClientInfo = Implementation("sbt-mcp-docs-proxy", "1.0.0")

  private final class ProxyToolSource(url: String) extends McpToolSource[Client] {
    def listTools(ctx: McpToolContext): ZIO[Client, Nothing, Chunk[ToolDefinition]] =
      ZIO
        .scoped(McpClient.connect(McpClientConfig(url, clientInfo = docsProxyClientInfo)).flatMap(_.listTools))
        .catchAll(e =>
          ZIO.logWarning(s"sbt-mcp: docs proxy ($url) listTools failed: $e").as(Chunk.empty)
        )

    // Every call opens its own upstream session, so a transient failure (for example an
    // `initialize` rejected while the upstream restarts, seen as JSON-RPC -32601 during
    // parallel lookups) is retried on a fresh session before it's reported.
    def callTool(name: ToolName, args: Option[Json.Obj], ctx: McpToolContext): ZIO[Client, Nothing, CallToolResult] =
      ZIO
        .scoped(McpClient.connect(McpClientConfig(url, clientInfo = docsProxyClientInfo)).flatMap(_.callTool(name.value, args.getOrElse(Json.Obj()))))
        .retry(Schedule.exponential(500.millis) && Schedule.recurs(2))
        .catchAll(e =>
          ZIO.logWarning(s"sbt-mcp: docs proxy ($url) callTool '${name.value}' failed: $e").as(
            CallToolResult(
              content = Chunk(ToolContent.text(s"proxy to $url failed for '${name.value}': $e")),
              isError = Some(true),
            )
          )
        )
  }
}
