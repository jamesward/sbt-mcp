package com.jamesward.sbtmcp

import zio.*

import java.io.{ InputStream, OutputStream }
import java.net.{ StandardProtocolFamily, UnixDomainSocketAddress }
import java.nio.ByteBuffer
import java.nio.channels.{ ServerSocketChannel, SocketChannel }
import java.nio.file.{ Files, Path }
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Listens on a Unix domain socket and serves each connection with `serve` (the
 * stdio transport's framing: newline-delimited JSON-RPC both ways), until the scope
 * closes. The socket is only reachable by this user: its directory is created
 * owner-only (0700) and the socket itself is made 0600, like sbt's own server socket.
 */
object SocketListener:

  def serve(path: Path, serve: (InputStream, OutputStream) => Task[Unit]): ZIO[Scope, Throwable, Unit] =
    for
      server      <- ZIO.acquireRelease(ZIO.attemptBlocking(bind(path)))(server =>
                       ZIO.succeed {
                         try server.close() catch case NonFatal(_) => ()
                         try Files.deleteIfExists(path) catch case NonFatal(_) => ()
                       }
                     )
      connections <- ZIO.succeed(ConcurrentHashMap.newKeySet[SocketChannel]())
      // Closing a connection unblocks the thread reading it, which ends its serve.
      _           <- ZIO.addFinalizer(ZIO.succeed(connections.asScala.foreach(c => try c.close() catch case NonFatal(_) => ())))
      _           <- accept(server, connections, serve).forkScoped
    yield ()

  private def accept(
      server: ServerSocketChannel,
      connections: java.util.Set[SocketChannel],
      serve: (InputStream, OutputStream) => Task[Unit],
  ): Task[Unit] =
    // Interrupting a thread blocked in `accept` doesn't wake it: closing the channel does.
    ZIO.attemptBlockingCancelable(server.accept())(ZIO.succeed(try server.close() catch case NonFatal(_) => ())).flatMap { connection =>
      connections.add(connection)
      serve(ChannelInput(connection), ChannelOutput(connection))
        .catchAllCause(cause => ZIO.logDebugCause("sbt-mcp: a socket connection ended with an error", cause))
        .ensuring(ZIO.succeed {
          connections.remove(connection)
          try connection.close() catch case NonFatal(_) => ()
        })
        .forkDaemon
        .unit
    }.forever.catchSome { case _: java.nio.channels.ClosedChannelException => ZIO.unit } // closed on shutdown


  /**
   * Streams over the channel's own `read`/`write`, which may run on different threads
   * at once. (`Channels.newInputStream`/`newOutputStream` share a lock on some JDKs, so
   * a read blocked waiting for the next request would stall writing a response.)
   */
  private final class ChannelInput(channel: SocketChannel) extends InputStream:
    override def read(): Int =
      val one = new Array[Byte](1)
      if read(one, 0, 1) < 0 then -1 else one(0) & 0xff
    override def read(b: Array[Byte], off: Int, len: Int): Int =
      if len == 0 then 0 else channel.read(ByteBuffer.wrap(b, off, len))
    override def close(): Unit = channel.close()

  private final class ChannelOutput(channel: SocketChannel) extends OutputStream:
    override def write(b: Int): Unit = write(Array(b.toByte), 0, 1)
    override def write(b: Array[Byte], off: Int, len: Int): Unit =
      val buffer = ByteBuffer.wrap(b, off, len)
      while buffer.hasRemaining do channel.write(buffer)
    override def close(): Unit = channel.close()

  /**
   * Bind `path`, replacing a socket file left behind by a server that's gone (a
   * killed sbt). A socket that still accepts connections belongs to a running server
   * and is never taken over.
   */
  private def bind(path: Path): ServerSocketChannel =
    val directory = path.toAbsolutePath.getParent
    Files.createDirectories(directory)
    ownerOnly(directory, "rwx------")
    if Files.exists(path) then
      val live =
        try
          val probe = SocketChannel.open(StandardProtocolFamily.UNIX)
          try { probe.connect(UnixDomainSocketAddress.of(path)); true }
          finally probe.close()
        catch case NonFatal(_) => false
      if live then throw IllegalStateException(s"another sbt-mcp server is listening on $path")
      Files.delete(path)
    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    try
      server.bind(UnixDomainSocketAddress.of(path))
      ownerOnly(path, "rw-------")
      server
    catch
      case error: Throwable =>
        server.close()
        throw error

  private def ownerOnly(path: Path, permissions: String): Unit =
    try Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
    catch case _: UnsupportedOperationException => () // Windows: the user's profile directory protects it
