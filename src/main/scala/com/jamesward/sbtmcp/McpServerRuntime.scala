package com.jamesward.sbtmcp

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.McpInput.given
import com.jamesward.ziohttp.mcp.McpOutput.given
import com.jamesward.ziohttp.mcp.McpError.given
import com.jamesward.ziohttp.mcp.client.McpClient
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.schema.{ DeriveSchema, Schema }

/**
 * Builds the MCP server (three tools) and serves it over zio-http on a daemon
 * fiber bound to loopback. One instance per sbt server JVM — see [[SbtMcpPlugin]].
 */
object McpServerRuntime {

  /** Opaque handle used by the plugin to stop the server on `onUnload`. */
  final case class Handle(host: String, port: Int, private val shutdown: () => Unit) {
    def close(): Unit = shutdown()
  }

  // ---- Tool argument schemas (drive MCP inputSchema generation) ----
  final case class SbtTaskArgs(command: String)
  object SbtTaskArgs { given Schema[SbtTaskArgs] = DeriveSchema.gen[SbtTaskArgs] }

  final case class GlobSearchArgs(query: String, inPackage: Option[String] = None)
  object GlobSearchArgs { given Schema[GlobSearchArgs] = DeriveSchema.gen[GlobSearchArgs] }

  final case class InspectArgs(symbol: String)
  object InspectArgs { given Schema[InspectArgs] = DeriveSchema.gen[InspectArgs] }

  final case class ListTasksArgs(all: Boolean = false, task: Option[String] = None)
  object ListTasksArgs { given Schema[ListTasksArgs] = DeriveSchema.gen[ListTasksArgs] }

  def start(
      host: String,
      port: Int,
      runCommand: String => String,
      refresh: () => Option[String],
      listTasks: () => List[(String, String)],
      docsUrl: Option[String],
  ): Handle = {
    val base: McpServer[Any] =
      McpServer("sbt-mcp", "0.1.0")
        .instructions(
          "Tools for driving a Scala/sbt build: run sbt tasks, and search/inspect Scala 3 " +
            "symbols read from TASTy. The symbol index refreshes automatically before each query. " +
            "Documentation tools are proxied from javadocs.dev."
        )
        .tool(sbtTaskTool(runCommand))
        .tool(listTasksTool(runCommand, listTasks))
        .tool(globSearchTool(refresh))
        .tool(inspectTool(refresh))
        .tool(locationTool(refresh))
    // Proxy an upstream MCP server (javadocs.dev by default): its tools are merged
    // into tools/list, and any tools/call not matching our built-ins is forwarded.
    val withProxy = docsUrl.filter(_.trim.nonEmpty) match {
      case Some(u) => base.toolSource(new ProxyToolSource(u.trim))
      case None    => base
    }
    val server: McpServer[Any] = withProxy.mountedAt("/")

    val routes: Routes[Any, Response] = server.statelessRoutes

    // Bind to loopback explicitly; do NOT expose on 0.0.0.0.
    val serverLayer =
      ZLayer.succeed(Server.Config.default.binding(host, port)) >>> Server.live

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
    val fiber =
      Unsafe.unsafe { implicit u =>
        runtime.unsafe.fork(Server.serve(routes).provide(serverLayer))
      }

    Handle(
      host,
      port,
      () => Unsafe.unsafe { implicit u => runtime.unsafe.run(fiber.interrupt).getOrThrow(); () },
    )
  }

  // ---- Tools ----

  private def sbtTaskTool(runCommand: String => String): McpToolHandler =
    McpTool("sbt-task")
      .description(
        "Run an sbt command/task in this build and return its status plus the captured " +
          "console output (compiler warnings and errors included). The command is queued onto " +
          "sbt's command loop (in-process — no separate sbt server connection) and runs there, " +
          "so it works even while a `~` watch is active (it runs between the watch's file-change " +
          "triggers, serialized with it). Result is `[ok] <cmd>` or `[error] <cmd>: …` followed " +
          "by the output. Examples: `compile`, `test`, `myProject/compile`, " +
          "`testOnly com.example.MySpec`, `clean`."
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

  private def globSearchTool(refresh: () => Option[String]): McpToolHandler =
    McpTool("glob-search")
      .description(
        "Search Scala 3 symbols by unqualified name across the active project's classpath (TASTy). " +
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
            else
              SymbolIndexState.context match {
                case None => notReady
                case Some(ctx0) =>
                  given tastyquery.Contexts.Context = ctx0
                  val hits = SymbolIndex.globSearch(args.query, args.inPackage)
                  if (hits.isEmpty) s"no symbols matching '${args.query}'"
                  else hits.map(h => s"${h.kind} ${h.fqn}").mkString("\n")
              }
          withNote(body, note)
        }
      }

  private def inspectTool(refresh: () => Option[String]): McpToolHandler =
    McpTool("inspect")
      .description(
        "Inspect a Scala 3 symbol by fully-qualified name: lists members and (approximate) signatures. " +
          "Works for classes, objects and traits."
      )
      .annotations(readOnly = OptBool.True)
      .handle[Any, Throwable, InspectArgs, String] { (args: InspectArgs) =>
        ZIO.attempt {
          val note = refresh()
          val body = SymbolIndexState.context match {
            case None => notReady
            case Some(ctx0) =>
              given tastyquery.Contexts.Context = ctx0
              SymbolIndex.inspect(args.symbol).getOrElse(s"symbol not found: ${args.symbol}")
          }
          withNote(body, note)
        }
      }

  private def locationTool(refresh: () => Option[String]): McpToolHandler =
    McpTool("symbol-location")
      .description(
        "Return the source location (as `path:line`) of a Scala 3 symbol — a class, object, " +
          "trait, or a top-level/static term — read from its defining tree in TASTy."
      )
      .annotations(readOnly = OptBool.True)
      .handle[Any, Throwable, InspectArgs, String] { (args: InspectArgs) =>
        ZIO.attempt {
          val note = refresh()
          val body = SymbolIndexState.context match {
            case None => notReady
            case Some(ctx0) =>
              given tastyquery.Contexts.Context = ctx0
              SymbolIndex.location(args.symbol).getOrElse(s"no source location for ${args.symbol}")
          }
          withNote(body, note)
        }
      }

  /** Append an optional warning note (e.g. the project doesn't compile) to a tool result. */
  private def withNote(body: String, note: Option[String]): String =
    note.fold(body)(n => s"$body\n(note: $n)")

  /**
   * A [[McpToolSource]] that proxies an upstream MCP server (e.g. javadocs.dev). Its
   * tools are merged into `tools/list`, and a `tools/call` for any name not matched by
   * our built-in tools is forwarded to the upstream. We connect per request (via a
   * fresh zio-http [[Client]]) and degrade gracefully: an unreachable upstream yields
   * an empty tool list / an `isError` result rather than failing our server.
   */
  private final class ProxyToolSource(url: String) extends McpToolSource[Any] {
    def listTools(ctx: McpToolContext): ZIO[Any, Nothing, Chunk[ToolDefinition]] =
      ZIO
        .scoped(McpClient.connect(url).flatMap(_.listTools))
        .provide(Client.default)
        .catchAll(e =>
          ZIO.logWarning(s"sbt-mcp: docs proxy ($url) listTools failed: $e").as(Chunk.empty)
        )

    def callTool(name: ToolName, args: Option[Json.Obj], ctx: McpToolContext): ZIO[Any, Nothing, CallToolResult] =
      ZIO
        .scoped(McpClient.connect(url).flatMap(_.callTool(name.value, args.getOrElse(Json.Obj()))))
        .provide(Client.default)
        .catchAll(e =>
          ZIO.logWarning(s"sbt-mcp: docs proxy ($url) callTool '${name.value}' failed: $e").as(
            CallToolResult(
              content = Chunk(ToolContent.text(s"proxy to $url failed for '${name.value}': $e")),
              isError = Some(true),
            )
          )
        )
  }

  private val notReady =
    "symbol index not available yet — compile the project, then retry (the index refreshes automatically)."
}
