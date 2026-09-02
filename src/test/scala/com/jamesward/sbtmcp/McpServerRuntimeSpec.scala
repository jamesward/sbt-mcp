package com.jamesward.sbtmcp

import com.jamesward.ziohttp.mcp.client.McpClient
import zio.*
import zio.http.Client
import zio.json.ast.Json
import zio.test.*

import java.net.{ InetSocketAddress, ServerSocket, Socket }
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }

object McpServerRuntimeSpec extends ZIOSpecDefault:

  private val Host = "127.0.0.1"

  private def freePort: Task[Int] =
    ZIO.attemptBlocking {
      val socket = ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()
    }

  private def awaitListening(port: Int): Task[Unit] =
    ZIO.attemptBlocking {
      val deadline = java.lang.System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
      var ready    = false
      var failure: Option[Throwable] = None
      while !ready && java.lang.System.nanoTime() < deadline do
        val socket = Socket()
        try
          socket.connect(InetSocketAddress(Host, port), 100)
          ready = true
        catch
          case error: Throwable =>
            failure = Some(error)
            Thread.sleep(25)
        finally socket.close()
      if !ready then
        throw RuntimeException(s"MCP server did not listen on $Host:$port", failure.orNull)
    }

  /**
   * Run close on a daemon thread so a regression in the imperative lifecycle
   * boundary fails this test instead of hanging the entire test JVM.
   */
  private def closeWithin(handle: McpServerRuntime.Handle): Task[Unit] =
    ZIO.attemptBlocking {
      val done    = CountDownLatch(1)
      val failure = AtomicReference[Throwable]()
      val thread = Thread(
        () =>
          try handle.close()
          catch case error: Throwable => failure.set(error)
          finally done.countDown(),
        "mcp-server-close-regression",
      )
      thread.setDaemon(true)
      thread.start()
      if !done.await(3, TimeUnit.SECONDS) then
        throw AssertionError("MCP server close did not complete within 3 seconds")
      Option(failure.get()).foreach(throw _)
    }

  private def assertPortReusable(port: Int): Task[Unit] =
    ZIO.attemptBlocking {
      val socket = ServerSocket()
      try
        socket.setReuseAddress(false)
        socket.bind(InetSocketAddress(Host, port))
      finally socket.close()
    }

  private def startServer(
      port: Int,
      runCommand: String => String = command => s"[ok] $command",
  ): Task[McpServerRuntime.Handle] =
    ZIO.attempt(McpServerRuntime.start(
      host       = Host,
      port       = port,
      runCommand = runCommand,
      refresh    = () => None,
      listTasks  = () => Nil,
      docsUrl    = None,
    ))

  def spec = suite("McpServerRuntimeSpec")(
    test("close is bounded, idempotent, and releases the listening port") {
      for
        port   <- freePort
        handle <- startServer(port)
        result <- (for
                    _ <- awaitListening(port)
                    _ <- closeWithin(handle)
                    _ <- closeWithin(handle)
                    _ <- assertPortReusable(port)
                    replacement <- startServer(port)
                    _ <- (awaitListening(port) *> closeWithin(replacement))
                           .ensuring(closeWithin(replacement).ignore)
                  yield assertCompletes)
                    .ensuring(closeWithin(handle).ignore)
      yield result
    },
    test("start reports a port bind failure") {
      ZIO.acquireRelease(ZIO.attemptBlocking(ServerSocket(0)))(socket => ZIO.attemptBlocking(socket.close()).orDie)
        .flatMap(socket => startServer(socket.getLocalPort).exit)
        .map(exit => assertTrue(exit.isFailure))
    },
    test("issue 1: a task outliving the old scaled client timeout returns exactly once") {
      val executions = AtomicInteger(0)
      val runCommand: String => String = command => {
        executions.incrementAndGet()
        Thread.sleep(150)
        s"[ok] $command"
      }

      for
        port   <- freePort
        handle <- startServer(port, runCommand)
        result <- ZIO
                    .scoped {
                      McpClient.connect(s"http://$Host:$port/").flatMap { client =>
                        client
                          .callTool("sbt-task", Json.Obj("command" -> Json.Str("slow")))
                          .timeout(750.millis)
                          .map(response => assertTrue(response.isDefined, executions.get() == 1))
                      }
                    }
                    .provide(Client.default)
                    .ensuring(closeWithin(handle).ignore)
      yield result
    },
    test("issue 3: initialize exposes the generated artifact version") {
      for
        port   <- freePort
        handle <- startServer(port)
        result <- ZIO
                    .scoped {
                      McpClient.connect(s"http://$Host:$port/").map { client =>
                        assertTrue(
                          client.serverInfo.name == "sbt-mcp",
                          client.serverInfo.version == McpBuildInfo.version,
                        )
                      }
                    }
                    .provide(Client.default)
                    .ensuring(closeWithin(handle).ignore)
      yield result
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
