package com.jamesward.sbtmcp

import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.{ Files, Path, StandardCopyOption }
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.{ BiFunction, Function, Supplier }

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Parent-loader facade for the fully isolated Scala 3.9 MCP/ZIO runtime. */

  private final case class OwnedStartupFailure(cause: Throwable) extends RuntimeException(cause)
object McpServerRuntime:

  final class Handle private[sbtmcp] (
      val host: String,
      val port: Int,
      loader: URLClassLoader,
      bridge: Object,
      childHandle: Object,
      extractedDirectory: Path,
  ) extends AutoCloseable:

    private[sbtmcp] val isIsolated: Boolean =
      loader.getParent eq ClassLoader.getPlatformClassLoader
    private val closed         = AtomicBoolean(false)
    private val cleanupStarted = AtomicBoolean(false)

    override def close(): Unit =
      if closed.compareAndSet(false, true) then
        var failure: Option[Throwable] = None
        try withContextLoader(loader) { invoke(bridge, "close", childHandle) }
        catch case error: Throwable => failure = Some(error)
        finally
          val terminated =
            try withContextLoader(loader) {
              invoke(bridge, "awaitClosed", childHandle, Long.box(0L)).asInstanceOf[java.lang.Boolean].booleanValue()
            }
            catch case _: Throwable => false
          if terminated then cleanup()
          else
            val cleanupThread = Thread(
              () =>
                try
                  var terminated = false
                  while !terminated do
                    terminated = withContextLoader(loader) {
                      invoke(bridge, "awaitClosed", childHandle, Long.box(30000L))
                        .asInstanceOf[java.lang.Boolean]
                        .booleanValue()
                    }
                  cleanup()
                catch case _: Throwable => (),
              "sbt-mcp-runtime-cleanup",
            )
            cleanupThread.setDaemon(true)
            cleanupThread.start()
        failure.foreach(throw _)

    private def cleanup(): Unit =
      if cleanupStarted.compareAndSet(false, true) then
        try loader.close()
        finally deleteRecursively(extractedDirectory)

  def start(
      host: String,
      port: Int,
      runCommand: String => String,
      refresh: () => Option[String],
      listTasks: () => List[(String, String)],
      docsUrl: Option[String],
  ): Handle =
    val (loader, directory) = runtimeLoader()
    try
      withContextLoader(loader) {
        val bridgeClass = Class.forName("com.jamesward.sbtmcp.IsolatedMcpBridge$", true, loader)
        val bridge      = bridgeClass.getField("MODULE$").get(null)
        val startResult = invoke(
          bridge,
          "start",
          host,
          Int.box(port),
          McpBuildInfo.version,
          new Function[String, String]:
            def apply(command: String): String = runCommand(command)
          ,
          new Supplier[String]:
            def get(): String = refresh().orNull
          ,
          new Supplier[java.util.List[String]]:
            def get(): java.util.List[String] =
              listTasks().map { case (name, description) => s"$name\u0000$description" }.asJava
          ,
          new BiFunction[String, String, String]:
            def apply(query: String, inPackage: String): String = globSearch(query, Option(inPackage))
          ,
          new Function[String, String]:
            def apply(symbol: String): String = inspect(symbol)
          ,
          new Function[String, String]:
            def apply(symbol: String): String = location(symbol)
          ,
          docsUrl.orNull,
        )
        val childHandle = invoke(bridge, "startHandle", startResult)
        val startupError = invoke(bridge, "startError", startResult).asInstanceOf[Throwable]
        if startupError != null then
          Handle(host, port, loader, bridge, childHandle, directory).close()
          throw OwnedStartupFailure(startupError)
        val childHost = invoke(bridge, "host", childHandle).asInstanceOf[String]
        val childPort = invoke(bridge, "port", childHandle).asInstanceOf[Integer].intValue()
        Handle(childHost, childPort, loader, bridge, childHandle, directory)
      }
    catch
      case OwnedStartupFailure(cause) => throw cause
      case error: Throwable =>
        loader.close()
        deleteRecursively(directory)
        throw unwrap(error)

  private def globSearch(query: String, inPackage: Option[String]): String =
    SymbolIndexState.globSearchResult(query, inPackage) match
      case SymbolIndexState.QueryResult.Unavailable(message) => message
      case SymbolIndexState.QueryResult.Available(hits) =>
        if hits.isEmpty then s"no symbols matching '$query'"
        else hits.map(hit => s"${hit.kind} ${hit.fqn}").mkString("\n")

  private def inspect(symbol: String): String =
    SymbolIndexState.inspectResult(symbol) match
      case SymbolIndexState.QueryResult.Unavailable(message) => message
      case SymbolIndexState.QueryResult.Available(result) => result.getOrElse(s"symbol not found: $symbol")

  private def location(symbol: String): String =
    SymbolIndexState.locationResult(symbol) match
      case SymbolIndexState.QueryResult.Unavailable(message) => message
      case SymbolIndexState.QueryResult.Available(result) => result.getOrElse(s"no source location for $symbol")

  private def runtimeLoader(): (URLClassLoader, Path) =
    val directory = Files.createTempDirectory("sbt-mcp-runtime-")
    directory.toFile.deleteOnExit()
    try
      val index = Option(getClass.getResourceAsStream("/sbt-mcp-runtime/classpath.index")).getOrElse {
        throw IllegalStateException("embedded MCP runtime classpath index is missing")
      }
      val names = Using.resource(index)(stream => new String(stream.readAllBytes()).linesIterator.filter(_.nonEmpty).toList)
      val jars = names.map { name =>
        val resource = s"/sbt-mcp-runtime/$name"
        val stream = Option(getClass.getResourceAsStream(resource)).getOrElse {
          throw IllegalStateException(s"embedded MCP runtime jar is missing: $resource")
        }
        val output = directory.resolve(name)
        output.toFile.deleteOnExit()
        Using.resource(stream)(input => Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING))
        output
      }
      (URLClassLoader(jars.map(_.toUri.toURL).toArray, ClassLoader.getPlatformClassLoader), directory)
    catch
      case error: Throwable =>
        deleteRecursively(directory)
        throw error

  private def invoke(target: Object, name: String, args: Object*): Object =
    val method = target.getClass.getMethods.find(method => method.getName == name && method.getParameterCount == args.size)
      .getOrElse(throw NoSuchMethodException(s"${target.getClass.getName}.$name/${args.size}"))
    try method.invoke(target, args*)
    catch case error: InvocationTargetException => throw unwrap(error)

  private def unwrap(error: Throwable): Throwable = error match
    case invocation: InvocationTargetException if invocation.getCause != null => invocation.getCause
    case other => other

  private def withContextLoader[A](loader: ClassLoader)(operation: => A): A =
    val thread   = Thread.currentThread()
    val previous = thread.getContextClassLoader
    thread.setContextClassLoader(loader)
    try operation
    finally thread.setContextClassLoader(previous)

  private def deleteRecursively(directory: Path): Unit =
    if Files.exists(directory) then
      Using.resource(Files.walk(directory)) { paths =>
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists)
      }
end McpServerRuntime
