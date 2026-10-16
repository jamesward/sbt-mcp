package com.jamesward.sbtmcp

import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

object McpStdioLauncherSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("sbt-mcp-stdio-spec-"))) { dir =>
      ZIO.attemptBlocking {
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      }.orDie
    }

  private def read(path: Path): String = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  def spec = suite("McpStdioLauncherSpec")(
    test("install writes the launcher and snapshot once, and reports outdated files") {
      ZIO.scoped {
        for
          dir      <- tempDir
          base      = dir.toFile
          first    <- ZIO.attemptBlocking(McpStdioLauncher.install(base, "{\"a\":1}\n"))
          launcher <- ZIO.attemptBlocking(read(dir.resolve(".sbt-mcp/SbtMcpStdio.java")))
          snapshot <- ZIO.attemptBlocking(read(dir.resolve(".sbt-mcp/snapshot.json")))
          again    <- ZIO.attemptBlocking(McpStdioLauncher.install(base, "{\"a\":1}\n"))
          upToDate <- ZIO.attemptBlocking(McpStdioLauncher.outdated(base, "{\"a\":1}\n"))
          stale    <- ZIO.attemptBlocking(McpStdioLauncher.outdated(base, "{\"a\":2}\n"))
          _        <- ZIO.attemptBlocking(Files.writeString(McpStdioLauncher.launcherFile(base).toPath, "// edited"))
          edited   <- ZIO.attemptBlocking(McpStdioLauncher.outdated(base, "{\"a\":1}\n"))
          perms    <- ZIO.attemptBlocking(
                        java.nio.file.attribute.PosixFilePermissions.toString(
                          Files.getPosixFilePermissions(McpStdioLauncher.snapshotFile(base).toPath)
                        )
                      )
        yield assertTrue(
          first == List(McpStdioLauncher.launcherFile(base), McpStdioLauncher.snapshotFile(base)),
          launcher == McpStdioLauncher.launcherSource,
          snapshot == "{\"a\":1}\n",
          again.isEmpty,
          upToDate.isEmpty,
          stale == List(McpStdioLauncher.snapshotFile(base)),
          edited == List(McpStdioLauncher.launcherFile(base)),
          // Committed files must be readable by everyone (temp files start owner-only).
          perms == "rw-r--r--",
        )
      }
    },
    test("nothing is outdated (and no snapshot is computed) when the launcher isn't installed") {
      ZIO.scoped {
        for
          dir      <- tempDir
          outdated <- ZIO.attemptBlocking(McpStdioLauncher.outdated(dir.toFile, throw AssertionError("computed")))
        yield assertTrue(outdated.isEmpty)
      }
    },
    test("server.json advertises the socket, the HTTP URL when enabled, and the pid; it is deleted on stop") {
      ZIO.scoped {
        for
          dir     <- tempDir
          base     = dir.toFile
          _       <- ZIO.attemptBlocking(McpStdioLauncher.writeServerFile(base, Path.of("/s/mcp.sock"), None, 42L, "1"))
          noHttp  <- ZIO.attemptBlocking(read(dir.resolve("project/target/sbt-mcp/server.json")))
          _       <- ZIO.attemptBlocking(
                       McpStdioLauncher.writeServerFile(base, Path.of("/s/mcp.sock"), Some("http://127.0.0.1:5010/"), 42L, "1.2\"3")
                     )
          written <- ZIO.attemptBlocking(read(dir.resolve("project/target/sbt-mcp/server.json")))
          _       <- ZIO.attemptBlocking(McpStdioLauncher.deleteServerFile(base))
          _       <- ZIO.attemptBlocking(McpStdioLauncher.deleteServerFile(base)) // idempotent
          gone    <- ZIO.attemptBlocking(!Files.exists(dir.resolve("project/target/sbt-mcp/server.json")))
        yield assertTrue(
          noHttp == "{\"socket\":\"/s/mcp.sock\",\"pid\":42,\"version\":\"1\"}\n",
          written == "{\"socket\":\"/s/mcp.sock\",\"url\":\"http://127.0.0.1:5010/\",\"pid\":42,\"version\":\"1.2\\\"3\"}\n",
          gone,
        )
      }
    },
    test("the socket is per build, under sbt's global base, and short enough to bind") {
      ZIO.scoped {
        for
          dir    <- tempDir
          global  = dir.resolve("global").toFile
          a       = McpStdioLauncher.socketPath(global, dir.resolve("a").toFile)
          a2      = McpStdioLauncher.socketPath(global, dir.resolve("x/../a").toFile)
          b       = McpStdioLauncher.socketPath(global, dir.resolve("b").toFile)
          long    = McpStdioLauncher.socketPath(dir.resolve("g" * 120).toFile, dir.resolve("a").toFile)
        yield assertTrue(
          a.startsWith(global.toPath.resolve("sbt-mcp")),
          a.getFileName.toString == "mcp.sock",
          a == a2, // the same build, however its path is spelled
          a != b,
          !long.startsWith(dir),
          long.toString.length <= 100,
          long.getFileName.toString.endsWith(".sock"),
        )
      }
    },
    test("the shipped launcher is the stdio launcher, needing nothing but the JDK") {
      val source  = McpStdioLauncher.launcherSource
      val imports = source.linesIterator.filter(_.startsWith("import ")).toList
      assertTrue(
        source.contains("public final class SbtMcpStdio"),
        source.contains("\"--client\""),
        source.contains("mcpStatus"),
        source.contains("server.json"),
        source.contains("UnixDomainSocketAddress"),
        !source.contains("HttpClient"),
        imports.nonEmpty,
        imports.forall(_.startsWith("import java.")),
      )
    },
    test("client config starts the launcher with java and the full command timeout") {
      val config = McpStdioLauncher.clientConfig("sbt-mcp-x\"y", SbtMcpPlugin.RecommendedClientTimeout.toMillis)
      assertTrue(
        config.contains("\"sbt-mcp-x\\\"y\": {"),
        config.contains("\"type\": \"stdio\""),
        config.contains("\"command\": \"java\""),
        config.contains("\"args\": [\".sbt-mcp/SbtMcpStdio.java\"]"),
        config.contains(s"\"timeout\": ${30.minutes.toMillis}"),
      )
    },
    test("JSON strings are escaped") {
      assertTrue(McpStdioLauncher.jsonString("a\"b\\c\nd\u0001") == "\"a\\\"b\\\\c\\nd\\u0001\"")
    },
    test("mcpInstall recommends the stdio launcher; the HTTP alternative needs mcpPort") {
      val off = SbtMcpPlugin.installInstructions("sbt-mcp-x", enabled = true)
      val on  = SbtMcpPlugin.installInstructions("sbt-mcp-x", enabled = true, port = Some(5011))
      assertTrue(
        off.contains("`./sbt mcpInstallStdio`"),
        off.contains("\"args\": [\".sbt-mcp/SbtMcpStdio.java\"]"),
        off.contains("only this user can open"),
        off.contains("ThisBuild / mcpPort := Some(5010)"),
        off.contains("\"type\": \"http\""),
        on.contains("This build serves HTTP at http://127.0.0.1:5011/"),
        on.contains("\"url\": \"http://127.0.0.1:5011/\""),
        !on.contains("ThisBuild / mcpPort := Some("),
      )
    },
  )
