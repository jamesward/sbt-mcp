package com.jamesward.sbtmcp

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{ AtomicMoveNotSupportedException, Files, Path, StandardCopyOption }
import java.nio.file.attribute.PosixFilePermissions

import scala.util.Using
import scala.util.control.NonFatal

/**
 * The stdio launcher: `.sbt-mcp/SbtMcpStdio.java`, a dependency-free Java program an
 * MCP client starts as a stdio server. It connects to this build's sbt-mcp server on
 * a user-only Unix domain socket that speaks the stdio framing (newline-delimited
 * JSON-RPC), relays messages both ways, and starts sbt when it isn't running. It finds
 * the socket through `project/target/sbt-mcp/server.json` (written while the server
 * runs) and answers from `.sbt-mcp/snapshot.json` until sbt is up. Both `.sbt-mcp`
 * files are written by `mcpInstallStdio` and meant to be committed.
 */
private[sbtmcp] object McpStdioLauncher {
  final val Directory    = ".sbt-mcp"
  final val LauncherName = "SbtMcpStdio.java"
  final val SnapshotName = "snapshot.json"

  def launcherFile(base: File): File = new File(new File(base, Directory), LauncherName)
  def snapshotFile(base: File): File = new File(new File(base, Directory), SnapshotName)

  /** Where the running server advertises itself; under `project/target`, so never committed. */
  def serverFile(base: File): File = new File(new File(new File(new File(base, "project"), "target"), "sbt-mcp"), "server.json")

  /** The launcher's source, as shipped in this plugin. */
  lazy val launcherSource: String = {
    val stream = Option(getClass.getResourceAsStream(s"/sbt-mcp-stdio/$LauncherName")).getOrElse {
      throw new IllegalStateException(s"embedded $LauncherName is missing")
    }
    Using.resource(stream)(s => new String(s.readAllBytes(), StandardCharsets.UTF_8))
  }

  /** Write (or update) the launcher and snapshot. Returns the files whose content changed. */
  def install(base: File, snapshot: String): List[File] =
    List(launcherFile(base) -> launcherSource, snapshotFile(base) -> snapshot).flatMap { case (file, content) =>
      if (read(file).contains(content)) None
      else {
        writeAtomically(file.toPath, content, shared = true)
        Some(file)
      }
    }

  def isInstalled(base: File): Boolean = launcherFile(base).isFile

  /**
   * The installed files that differ from what this plugin version would write (e.g. after
   * upgrading the plugin). `snapshot` is only computed when the launcher is installed.
   */
  def outdated(base: File, snapshot: => String): List[File] =
    if (!isInstalled(base)) Nil
    else
      List(launcherFile(base) -> (() => launcherSource), snapshotFile(base) -> (() => snapshot)).collect {
        case (file, expected) if !read(file).contains(expected()) => file
      }

  /**
   * The server's socket: under sbt's global base (`~/.sbt/2` or `~/.config/sbt/2`),
   * named for the build directory, like sbt's own server socket. Socket paths are
   * limited to about 100 bytes, so a long one falls back to the temp directory.
   */
  def socketPath(globalBase: File, buildBase: File): Path = {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
      .digest(buildBase.getCanonicalPath.getBytes(StandardCharsets.UTF_8))
    val hash      = digest.take(8).map(b => f"${b & 0xff}%02x").mkString
    val preferred = globalBase.toPath.toAbsolutePath.resolve("sbt-mcp").resolve(hash).resolve("mcp.sock")
    if (preferred.toString.getBytes(StandardCharsets.UTF_8).length <= MaxSocketPath) preferred
    else {
      val user = sys.props.getOrElse("user.name", "user").replaceAll("[^A-Za-z0-9._-]", "_")
      Path.of(sys.props("java.io.tmpdir")).toAbsolutePath.resolve(s"sbt-mcp-$user").resolve(s"$hash.sock")
    }
  }

  private final val MaxSocketPath = 100

  def writeServerFile(base: File, socket: Path, url: Option[String], pid: Long, version: String): Unit = {
    val urlField = url.fold("")(u => s""","url":${jsonString(u)}""")
    val json =
      s"""{"socket":${jsonString(socket.toString)}$urlField,"pid":$pid,"version":${jsonString(version)}}""" + "\n"
    writeAtomically(serverFile(base).toPath, json, shared = false)
  }

  def deleteServerFile(base: File): Unit =
    try { Files.deleteIfExists(serverFile(base).toPath); () }
    catch { case NonFatal(_) => () }

  /** MCP client configuration that starts the launcher (Kiro, Claude Code and Cursor share this shape). */
  def clientConfig(serverName: String, timeoutMillis: Long): String =
    s"""{
       |  "mcpServers": {
       |    ${jsonString(serverName)}: {
       |      "type": "stdio",
       |      "command": "java",
       |      "args": ["$Directory/$LauncherName"],
       |      "timeout": $timeoutMillis
       |    }
       |  }
       |}""".stripMargin

  private def read(file: File): Option[String] =
    if (!file.isFile) None
    else
      try Some(new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8))
      catch { case NonFatal(_) => None }

  /** `shared` files (committed ones) are made world-readable; temp files start owner-only. */
  private def writeAtomically(target: Path, content: String, shared: Boolean): Unit = {
    Files.createDirectories(target.getParent)
    val temp = Files.createTempFile(target.getParent, target.getFileName.toString, ".tmp")
    try {
      Files.write(temp, content.getBytes(StandardCharsets.UTF_8))
      if (shared)
        try Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--"))
        catch { case _: UnsupportedOperationException => () }
      try Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      catch { case _: AtomicMoveNotSupportedException => Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
    } finally Files.deleteIfExists(temp)
  }

  private[sbtmcp] def jsonString(value: String): String = {
    val b = new StringBuilder("\"")
    value.foreach {
      case '"'          => b ++= "\\\""
      case '\\'         => b ++= "\\\\"
      case '\n'         => b ++= "\\n"
      case '\r'         => b ++= "\\r"
      case '\t'         => b ++= "\\t"
      case c if c < ' ' => b ++= f"\\u${c.toInt}%04x"
      case c            => b += c
    }
    (b += '"').toString
  }
}
