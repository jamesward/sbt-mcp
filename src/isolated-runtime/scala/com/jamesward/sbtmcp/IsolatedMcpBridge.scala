package com.jamesward.sbtmcp

import java.util.function.{ BiFunction, Function, Supplier }
import scala.jdk.CollectionConverters.*

/** JDK-only boundary loaded in the isolated Scala 3.9 MCP runtime. */

  private final case class StartResult(handle: Object, error: Throwable)
object IsolatedMcpBridge:
  def start(
      host: String,
      port: Int,
      implementationVersion: String,
      runCommand: Function[String, String],
      refresh: Supplier[String],
      listTasks: Supplier[java.util.List[String]],
      globSearch: BiFunction[String, String, String],
      inspectSymbol: Function[String, String],
      locateSymbol: Function[String, String],
      docsUrl: String,
  ): Object =
    try StartResult(McpServerRuntimeImpl.start(
      host,
      port,
      implementationVersion,
      command => runCommand.apply(command),
      () => Option(refresh.get()),
      () => listTasks.get().asScala.toList.flatMap: encoded =>
        encoded.indexOf('\u0000') match
          case -1        => Nil
          case separator => (encoded.substring(0, separator) -> encoded.substring(separator + 1)) :: Nil,
      (query, inPackage) => globSearch.apply(query, inPackage.orNull),
      symbol => inspectSymbol.apply(symbol),
      symbol => locateSymbol.apply(symbol),
      Option(docsUrl),
    ), null)
    catch
      case failure: McpServerRuntimeImpl.StartupFailure =>
        StartResult(failure.childHandle, failure.getCause)

  def startHandle(result: Object): Object =
    result.asInstanceOf[StartResult].handle

  def startError(result: Object): Throwable =
    result.asInstanceOf[StartResult].error

  def host(handle: Object): String =
    handle.asInstanceOf[McpServerRuntimeImpl.Handle].host

  def port(handle: Object): Int =
    handle.asInstanceOf[McpServerRuntimeImpl.Handle].port

  def close(handle: Object): Unit =
    handle.asInstanceOf[McpServerRuntimeImpl.Handle].close()

  def awaitClosed(handle: Object, timeoutMillis: Long): Boolean =
    handle.asInstanceOf[McpServerRuntimeImpl.Handle].awaitClosed(timeoutMillis)
end IsolatedMcpBridge
