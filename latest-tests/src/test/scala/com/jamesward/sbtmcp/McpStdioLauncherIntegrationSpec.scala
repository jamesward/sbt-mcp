package com.jamesward.sbtmcp

import io.modelcontextprotocol.client.McpClient as JavaMcpClient
import io.modelcontextprotocol.client.transport.{ ServerParameters, StdioClientTransport }
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema as JMcpSchema
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.io.{ BufferedReader, InputStreamReader, OutputStreamWriter }
import java.net.{ StandardProtocolFamily, UnixDomainSocketAddress }
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, LinkedBlockingQueue, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/**
 * The stdio launcher (`SbtMcpStdio.java`, run with the `java` source launcher like an MCP
 * client does) against a real sbt-mcp server runtime on its Unix domain socket. The
 * server is "sbt" here: tests advertise it in `project/target/sbt-mcp/server.json` the
 * way the plugin does, and a fake `sbt` script stands in for the sbt launcher where
 * startup is under test.
 */
object McpStdioLauncherIntegrationSpec extends ZIOSpecDefault:

  private val Modern = "2026-07-28"

  /** A build directory with the launcher installed (optionally with an edited snapshot). */
  private def project(editSnapshot: Json.Obj => Json.Obj = identity): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking {
      val dir      = Files.createTempDirectory("sbt-mcp-stdio-it-")
      val snapshot = McpServerRuntime.snapshot().fromJson[Json.Obj].fold(e => throw RuntimeException(e), identity)
      McpStdioLauncher.install(dir.toFile, editSnapshot(snapshot).toJsonPretty)
      dir
    }) { dir =>
      ZIO.attemptBlocking {
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      }.ignore
    }

  /** The build's socket, as the plugin computes it (a short global base keeps it bindable). */
  private def socketOf(base: Path): Path = McpStdioLauncher.socketPath(base.resolve("g").toFile, base.toFile)

  private def command(base: Path, start: String): List[String] =
    List("java", s"$base/.sbt-mcp/SbtMcpStdio.java", s"--project-dir=$base", s"--start=$start")

  /** Start sbt-mcp on the build's socket (no HTTP) and advertise it like the plugin does. */
  private def serve(base: Path, runCommand: String => String): ZIO[Scope, Throwable, McpServerRuntime.Handle] =
    ZIO.acquireRelease(ZIO.attemptBlocking {
      val handle = McpServerRuntime.start(
        "127.0.0.1", 0, runCommand, () => None, () => Nil, docsUrl = None, socket = Some(socketOf(base)), http = false,
      )
      McpStdioLauncher.writeServerFile(base.toFile, socketOf(base), None, ProcessHandle.current().pid(), "test")
      handle
    })(handle => ZIO.attemptBlocking { McpStdioLauncher.deleteServerFile(base.toFile); handle.close() }.ignore)

  /** The launcher as a subprocess, spoken to in raw newline-delimited JSON-RPC. */
  final class Launcher(process: Process):
    private val lines  = LinkedBlockingQueue[Json.Obj]()
    private val stderr = CopyOnWriteArrayList[String]()
    private val writer = OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8)
    daemon {
      val in = BufferedReader(InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
      Iterator.continually(in.readLine()).takeWhile(_ != null).foreach { line =>
        // Every stdout line must be one JSON-RPC message.
        lines.put(line.fromJson[Json.Obj].fold(e => Json.Obj("unparseable" -> Json.Str(s"$e: $line")), identity))
      }
    }
    daemon {
      val err = BufferedReader(InputStreamReader(process.getErrorStream, StandardCharsets.UTF_8))
      Iterator.continually(err.readLine()).takeWhile(_ != null).foreach(stderr.add)
    }

    def send(message: String): Unit = synchronized { writer.write(message + "\n"); writer.flush() }

    /** The next message matching `p` (others are kept in `skipped`), failing after `seconds`. */
    val skipped = CopyOnWriteArrayList[Json.Obj]()
    def next(seconds: Long)(p: Json.Obj => Boolean): Json.Obj =
      val deadline = java.lang.System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
      skipped.asScala.find(p).map { m => skipped.remove(m); m }.getOrElse {
        var found: Option[Json.Obj] = None
        while found.isEmpty do
          val left = deadline - java.lang.System.nanoTime()
          val m    = if left <= 0 then null else lines.poll(left, TimeUnit.NANOSECONDS)
          if m == null then throw AssertionError(s"no matching message in ${seconds}s; stderr:\n${stderr.asScala.mkString("\n")}")
          if p(m) then found = Some(m) else skipped.add(m)
        found.get
      }

    def response(id: Json, seconds: Long = 60): Json.Obj = next(seconds)(m => m.get("id").contains(id) && m.get("method").isEmpty)

    /** Messages that arrive within `millis`, without waiting for any in particular. */
    def drain(millis: Long): List[Json.Obj] =
      Thread.sleep(millis)
      val out = java.util.ArrayList[Json.Obj]()
      lines.drainTo(out)
      val all = skipped.asScala.toList ++ out.asScala.toList
      skipped.clear()
      all

    def log: String = stderr.asScala.mkString("\n")

    /** Close stdin: the launcher must exit promptly (the stdio transport's shutdown). */
    def close(): Boolean =
      writer.close()
      process.waitFor(15, TimeUnit.SECONDS)

    def kill(): Unit = process.destroyForcibly()

  private def daemon(body: => Unit): Unit =
    val t = Thread(() => body, "launcher-reader")
    t.setDaemon(true)
    t.start()

  private def launch(base: Path, start: String): ZIO[Scope, Throwable, Launcher] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Launcher(ProcessBuilder(command(base, start)*).start())))(l =>
      ZIO.attemptBlocking(l.kill()).ignore
    )

  private def meta: String =
    s""""_meta":{"io.modelcontextprotocol/protocolVersion":"$Modern","io.modelcontextprotocol/clientCapabilities":{}}"""

  private def obj(json: Json.Obj, path: String*): Option[Json] =
    path.foldLeft(Option[Json](json))((j, key) => j.flatMap(_.asObject).flatMap(_.get(key)))

  private def text(result: Json.Obj): String = result.toJson

  /** Replace a key's value (`Json.Obj.add` deep-merges, which would keep removed array elements). */
  private def put(json: Json.Obj, key: String, value: Json): Json.Obj =
    Json.Obj(json.fields.map((k, v) => if k == key then k -> value else k -> v))

  /** A snapshot without one tool in an era's list, so the live list differs from it. */
  private def without(era: String, tool: String): Json.Obj => Json.Obj = snapshot =>
    val section = snapshot.get(era).flatMap(_.asObject).get
    val tools   = section.get("tools/list").flatMap(_.asObject).get
    val kept    = tools.get("tools").flatMap(_.asArray).get.filterNot(t => obj(t.asObject.get, "name").contains(Json.Str(tool)))
    put(snapshot, era, put(section, "tools/list", put(tools, "tools", Json.Arr(kept))))

  def spec = suite("McpStdioLauncherIntegrationSpec")(
    test("the server's socket is owner-only, takes over a stale socket file, and refuses a live one") {
      ZIO.scoped {
        for
          base   <- project()
          socket  = socketOf(base)
          // A socket file left behind by a killed server.
          _      <- ZIO.attemptBlocking {
                      Files.createDirectories(socket.getParent)
                      val stale = java.nio.channels.ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                      stale.bind(UnixDomainSocketAddress.of(socket))
                      stale.close()
                    }
          _      <- serve(base, c => s"[ok] $c")
          perms  <- ZIO.attemptBlocking(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)))
          dir    <- ZIO.attemptBlocking(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket.getParent)))
          second <- ZIO.attempt(McpServerRuntime.start(
                      "127.0.0.1", 0, c => c, () => None, () => Nil, docsUrl = None, socket = Some(socket), http = false,
                    )).exit
          // No HTTP port without mcpPort: the socket is the only way in.
          raw    <- ZIO.attemptBlocking {
                      val c = SocketChannel.open(UnixDomainSocketAddress.of(socket))
                      try
                        val out = java.nio.channels.Channels.newOutputStream(c)
                        out.write("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"sbt-task","arguments":{"command":"x"}}}""".getBytes ++ "\n".getBytes)
                        out.flush()
                        BufferedReader(InputStreamReader(java.nio.channels.Channels.newInputStream(c))).readLine()
                      finally c.close()
                    }
        yield assertTrue(
          perms == "rw-------",
          dir == "rwx------",
          second.isFailure,
          raw.contains("[ok] x"),
        )
      }
    },
    test("a real stdio client connects before sbt runs, then calls tools and learns the changed tool list") {
      ZIO.scoped {
        for
          base     <- project(without("legacy", "show"))
          changed   = LinkedBlockingQueue[Int]()
          transport = StdioClientTransport(
                        ServerParameters.builder("java").args(command(base, "never").drop(1).asJava).build(),
                        McpJsonDefaults.getMapper(),
                      )
          client   <- ZIO.acquireRelease(ZIO.attemptBlocking(
                        JavaMcpClient.sync(transport)
                          .requestTimeout(java.time.Duration.ofSeconds(60))
                          .initializationTimeout(java.time.Duration.ofSeconds(60))
                          .clientInfo(JMcpSchema.Implementation.builder("stdio-it", "1.0.0").build())
                          .toolsChangeConsumer(tools => changed.put(tools.size))
                          .build()
                      ))(c => ZIO.attemptBlocking(c.closeGracefully()).ignore)
          init     <- ZIO.attemptBlocking(client.initialize())
          offline  <- ZIO.attemptBlocking(client.listTools().tools().asScala.map(_.name()).toList)
          // No sbt yet: the call waits for it.
          call     <- ZIO.attemptBlocking(
                        client.callTool(
                          JMcpSchema.CallToolRequest.builder("sbt-task")
                            .arguments(java.util.Map.of[String, Object]("command", "about")).build()
                        )
                      ).fork
          _        <- ZIO.sleep(3.seconds)
          waiting  <- call.poll
          _        <- serve(base, command => s"[ok] $command")
          result   <- call.join
          output    = result.content().asScala.collect { case t: JMcpSchema.TextContent => t.text() }.mkString
          live     <- ZIO.attemptBlocking(Option(changed.poll(30, TimeUnit.SECONDS)))
        yield assertTrue(
          init.serverInfo().name() == "sbt-mcp",
          init.capabilities().tools().listChanged() == true,
          offline.size == 15,
          !offline.contains("show"),
          waiting.isEmpty,
          output.startsWith("[ok] about"),
          live.contains(16),
        )
      }
    },
    test("2026-07-28: discover, subscriptions, a changed list, concurrency and cancellation") {
      val started = CountDownLatch(1)
      val release = CountDownLatch(1)
      val calls   = AtomicInteger(0)
      val runCommand: String => String = command =>
        calls.incrementAndGet()
        if command == "block" then { started.countDown(); release.await(60, TimeUnit.SECONDS) }
        s"[ok] $command"
      ZIO.scoped {
        for
          base     <- project(without("modern", "check"))
          launcher <- launch(base, "never")
          result   <- ZIO.attemptBlocking {
                        // Offline: an unsupported version is refused the modern way, discover and tools/list answer.
                        launcher.send("""{"jsonrpc":"2.0","id":"v","method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2099-01-01"}}}""")
                        val unsupported = launcher.response(Json.Str("v"))
                        launcher.send(s"""{"jsonrpc":"2.0","id":"d","method":"server/discover","params":{$meta}}""")
                        val discover = launcher.response(Json.Str("d"))
                        launcher.send(s"""{"jsonrpc":"2.0","id":"s","method":"subscriptions/listen","params":{$meta,"notifications":{"toolsListChanged":true,"resourcesListChanged":true}}}""")
                        val ack = launcher.next(30)(_.get("method").contains(Json.Str("notifications/subscriptions/acknowledged")))
                        launcher.send(s"""{"jsonrpc":"2.0","id":"t","method":"tools/list","params":{$meta}}""")
                        val offlineTools = launcher.response(Json.Str("t"))
                        (unsupported, discover, ack, offlineTools)
                      }
          (unsupported, discover, ack, offlineTools) = result
          _        <- serve(base, runCommand)
          changed  <- ZIO.attemptBlocking(launcher.next(30)(_.get("method").contains(Json.Str("notifications/tools/list_changed"))))
          online   <- ZIO.attemptBlocking {
                        launcher.send(s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{$meta,"name":"sbt-task","arguments":{"command":"compile"}}}""")
                        val called = launcher.response(Json.Num(1))
                        launcher.send(s"""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{$meta,"name":"ünknown tool","arguments":{}}}""")
                        val unknown = launcher.response(Json.Num(2))
                        // A blocked call doesn't hold up others; once cancelled, its response never arrives.
                        launcher.send(s"""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{$meta,"name":"sbt-task","arguments":{"command":"block"}}}""")
                        started.await(30, TimeUnit.SECONDS)
                        launcher.send(s"""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{$meta,"name":"sbt-task","arguments":{"command":"meanwhile"}}}""")
                        val meanwhile = launcher.response(Json.Num(4))
                        launcher.send("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":3,"reason":"test"}}""")
                        Thread.sleep(300)
                        release.countDown()
                        val late = launcher.drain(1500).filter(_.get("id").contains(Json.Num(3)))
                        (called, unknown, meanwhile, late)
                      }
          (called, unknown, meanwhile, late) = online
          exited   <- ZIO.attemptBlocking(launcher.close())
        yield assertTrue(
          obj(unsupported, "error", "code").contains(Json.Num(-32022)),
          text(unsupported).contains("2026-07-28"),
          obj(discover, "result", "capabilities", "tools", "listChanged").contains(Json.Bool(true)),
          obj(discover, "result", "supportedVersions").exists(_.toJson.contains(Modern)),
          obj(ack, "params", "_meta", "io.modelcontextprotocol/subscriptionId").contains(Json.Str("s")),
          obj(ack, "params", "notifications").contains(Json.Obj("toolsListChanged" -> Json.Bool(true))),
          !text(offlineTools).contains("\"check\""),
          obj(changed, "params", "_meta", "io.modelcontextprotocol/subscriptionId").contains(Json.Str("s")),
          text(called).contains("[ok] compile"),
          obj(called, "result", "resultType").contains(Json.Str("complete")),
          text(unknown).contains("nknown"),
          text(meanwhile).contains("[ok] meanwhile"),
          late.isEmpty,
          calls.get() == 3,
          exited,
        )
      }
    },
    test("a reload: the in-flight call is reported lost, the next one waits and is delivered once") {
      val calls   = AtomicInteger(0)
      val started = CountDownLatch(1)
      val run: String => String = c =>
        calls.incrementAndGet()
        if c == "reload" then { started.countDown(); Thread.sleep(60000) }
        s"[ok] $c"
      ZIO.scoped {
        for
          base     <- project()
          launcher <- launch(base, "never")
          first    <- ZIO.scoped(serve(base, run) *> ZIO.attemptBlocking {
                        launcher.send("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"sbt-task","arguments":{"command":"one"}}}""")
                        val one = launcher.response(Json.Num(1))
                        launcher.send("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"sbt-task","arguments":{"command":"reload"}}}""")
                        started.await(30, TimeUnit.SECONDS)
                        one
                      }) // the server stops while `reload` runs, like an sbt reload
          lost     <- ZIO.attemptBlocking(launcher.response(Json.Num(2)))
          // The server is down (server.json deleted, socket closed): this call waits.
          _        <- ZIO.attemptBlocking(launcher.send("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"sbt-task","arguments":{"command":"three"}}}"""))
          _        <- ZIO.sleep(3.seconds)
          early    <- ZIO.attemptBlocking(launcher.drain(0))
          _        <- serve(base, run)
          third    <- ZIO.attemptBlocking(launcher.response(Json.Num(3)))
        yield assertTrue(
          text(first).contains("[ok] one"),
          obj(lost, "error", "message").exists(_.toJson.contains("reloaded")),
          early.isEmpty,
          text(third).contains("[ok] three"),
          calls.get() == 3,
        )
      }
    },
    test("starts sbt with `sbt --client mcpStatus` when no server is running") {
      ZIO.scoped {
        for
          base     <- project()
          socket    = socketOf(base)
          _        <- ZIO.acquireRelease(ZIO.attempt(McpServerRuntime.start(
                        "127.0.0.1", 0, c => s"[ok] $c", () => None, () => Nil, docsUrl = None, socket = Some(socket), http = false,
                      )))(h => ZIO.attemptBlocking(h.close()).ignore)
          // A stand-in for the sbt launcher: record its arguments, then advertise the server like sbt-mcp does.
          _        <- ZIO.attemptBlocking(Files.writeString(
                        base.resolve("sbt"),
                        s"""#!/usr/bin/env bash
                           |echo "$$@" >> "$base/sbt-args.txt"
                           |mkdir -p "$base/project/target/sbt-mcp"
                           |echo '{"socket":"$socket","pid":${ProcessHandle.current().pid()},"version":"test"}' > "$base/project/target/sbt-mcp/server.json"
                           |""".stripMargin,
                      ))
          launcher <- launch(base, "eager")
          called   <- ZIO.attemptBlocking {
                        launcher.send("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"sbt-task","arguments":{"command":"hello"}}}""")
                        launcher.response(Json.Num(1))
                      }
          args     <- ZIO.attemptBlocking(Files.readString(base.resolve("sbt-args.txt")))
        yield assertTrue(text(called).contains("[ok] hello"), args == "--client mcpStatus\n")
      }
    },
    test("explains why sbt-mcp isn't running when sbt starts without it, and still answers from the snapshot") {
      ZIO.scoped {
        for
          base     <- project()
          _        <- ZIO.attemptBlocking(Files.writeString(
                        base.resolve("sbt"),
                        s"""#!/usr/bin/env bash
                           |echo "$$@" >> "$base/sbt-args.txt"
                           |echo "[info] sbt-mcp: not running (set \\`ThisBuild / mcpEnabled := true\\` to enable)"
                           |exit 1
                           |""".stripMargin,
                      ))
          launcher <- launch(base, "eager")
          answers  <- ZIO.attemptBlocking {
                        launcher.send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"x","version":"1"}}}""")
                        val init = launcher.response(Json.Num(1))
                        launcher.send("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"compile","arguments":{}}}""")
                        (init, launcher.response(Json.Num(2), seconds = 90))
                      }
          (init, failed) = answers
          args     <- ZIO.attemptBlocking(Files.readString(base.resolve("sbt-args.txt")))
        yield assertTrue(
          obj(init, "result", "protocolVersion").contains(Json.Str("2025-03-26")),
          obj(init, "result", "serverInfo", "version").contains(Json.Str(McpBuildInfo.version)),
          obj(failed, "error").isDefined,
          text(failed).contains("mcpEnabled := true"),
          text(failed).contains("not running (set"),
          // The native client failed, so the JVM client was tried too.
          args == "--client mcpStatus\n--jvm-client mcpStatus\n",
        )
      }
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
