scalaVersion := "3.8.4"

lazy val root = (project in file("."))
  .settings(
    name := "stdio-launcher-check",
    ThisBuild / mcpEnabled     := true,
    // This fixture intentionally exercises the server even when scripted runs in CI.
    ThisBuild / mcpDisableInCI := false,
    ThisBuild / mcpDocsUrl     := None,
  )

// While the server runs, project/target/sbt-mcp/server.json names its socket and this JVM.
// Without mcpPort there is no HTTP endpoint: the user-only socket is the only way in.
TaskKey[Unit]("checkServerFile") := Def.uncached {
  import java.nio.file.{ Files, Path }
  import java.nio.file.attribute.PosixFilePermissions
  val json   = IO.read(baseDirectory.value / "project" / "target" / "sbt-mcp" / "server.json")
  val pid    = ProcessHandle.current().pid()
  assert(json.contains(s"\"pid\":$pid,"), s"server.json does not name this JVM ($pid): $json")
  assert(!json.contains("\"url\""), s"server.json advertises HTTP without mcpPort: $json")
  val socket = Path.of("\"socket\":\"([^\"]+)\"".r.findFirstMatchIn(json).getOrElse(sys.error(s"no socket: $json")).group(1))
  val perms  = PosixFilePermissions.toString(Files.getPosixFilePermissions(socket))
  assert(perms == "rw-------", s"$socket is $perms; it must be owner-only")
  val http =
    try { new java.net.Socket("127.0.0.1", 5010).close(); true }
    catch { case _: java.io.IOException => false }
  assert(!http, "something listens on 127.0.0.1:5010 (the old default port) without mcpPort")

  // One MCP exchange over the socket, in the stdio framing. tools/list doesn't need the
  // command loop (which this task is holding).
  val channel = java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(socket))
  try {
    val request = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}\n"
    channel.write(java.nio.ByteBuffer.wrap(request.getBytes("UTF-8")))
    val reader = new java.io.BufferedReader(new java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(channel), "UTF-8"))
    val line = reader.readLine()
    assert(line != null && line.contains("\"id\":7") && line.contains("\"sbt-task\"") && line.contains("\"compile\""),
      s"unexpected tools/list response: ${Option(line).map(_.take(300))}")
  } finally channel.close()
  streams.value.log.info(s"checkServerFile OK: $socket")
}

TaskKey[Unit]("checkInstalled") := Def.uncached {
  import java.nio.file.Files
  import java.nio.file.attribute.PosixFilePermissions
  val dir      = baseDirectory.value / ".sbt-mcp"
  val launcher = IO.read(dir / "SbtMcpStdio.java")
  val snapshot = IO.read(dir / "snapshot.json")
  assert(launcher.contains("public final class SbtMcpStdio"), "the launcher was not written")
  Seq("\"legacy\"", "\"initialize\"", "\"modern\"", "\"server/discover\"", "\"tools/list\"", "\"sbt-task\"", "\"compile\"")
    .foreach(key => assert(snapshot.contains(key), s"snapshot.json lacks $key"))
  assert(!snapshot.contains("\"stale\""), "snapshot.json was not updated")
  Seq(dir / "SbtMcpStdio.java", dir / "snapshot.json").foreach { f =>
    val perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath))
    assert(perms == "rw-r--r--", s"$f is $perms; committed files must be readable by all")
  }
}

// Assert a line was logged (the global log file collects every task's and sbt-mcp's output).
InputKey[Unit]("checkLogged") := {
  val expected = Def.spaceDelimited("<text>").parsed.mkString(" ").stripPrefix("\"").stripSuffix("\"")
  val log      = IO.read(state.value.globalLogging.backing.file)
  assert(log.contains(expected), s"the log does not contain: $expected")
}
