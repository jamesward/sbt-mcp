// sbt-mcp stdio launcher. Written by sbt-mcp's `mcpInstallStdio` task: re-run that
// task to update it rather than editing this file.
//
// An MCP client launches this as a stdio server from the build's root directory:
//
//     java .sbt-mcp/SbtMcpStdio.java [--start=eager|lazy|never]
//
// It needs only a JDK 17+ (the `java` source launcher compiles it in memory). It
// connects to the build's sbt-mcp server on a Unix domain socket that only this user
// can open, which speaks the same framing as stdio (one JSON-RPC message per line),
// and relays messages both ways. When sbt isn't running it starts it with
// `sbt --client mcpStatus`: sbt's own background server, the one later `sbt` commands
// connect to, which keeps running after this process exits. Until sbt-mcp is up,
// `initialize`, `server/discover` and `tools/list` are answered from
// `.sbt-mcp/snapshot.json`, so the client connects (and lists the built-in tools)
// right away; other requests wait for sbt. When the live tool list differs from what
// the client was given (e.g. the proxied documentation tools appear), the client is
// told with `notifications/tools/list_changed`. A `reload` restarts sbt-mcp: requests
// waiting to be sent then wait for it, and the launcher reconnects.
//
//   --start=eager  (default) start sbt as soon as this launcher starts
//   --start=lazy   start sbt on the first request that needs it
//   --start=never  never start sbt; wait for one started some other way
//
// stdout carries MCP messages only. Diagnostics go to stderr and to
// project/target/sbt-mcp/stdio.log; sbt's startup output goes to
// project/target/sbt-mcp/boot.log.

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SbtMcpStdio {

  static final String MODERN = "2026-07-28";
  static final List<String> LEGACY = List.of("2025-11-25", "2025-06-18", "2025-03-26");
  static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
  static final String META_SUBSCRIPTION = "io.modelcontextprotocol/subscriptionId";
  /** How long a request waits for sbt-mcp (a first start may download a lot). */
  static final Duration START_WAIT = Duration.ofMinutes(15);
  /** The id prefix of the launcher's own requests (tool list checks); never forwarded. */
  static final String OWN_ID = "sbt-mcp-stdio:";

  final Path base;
  final Path runtimeDir;
  final Path serverFile;
  final String start;
  final Map<String, Object> snapshot;
  final PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);

  // ---- State, guarded by `this` ----

  /** A request waiting to be sent, or sent and waiting for its response. */
  static final class Pending {
    final Object id;
    final String method;
    final boolean modern;
    final String line;
    final long deadline;
    boolean sent = false;

    Pending(Object id, String method, boolean modern, String line) {
      this.id = id;
      this.method = method;
      this.modern = modern;
      this.line = line;
      this.deadline = System.nanoTime() + START_WAIT.toNanos();
    }
  }

  /** Client requests relayed to sbt-mcp, by canonical JSON id, in arrival order. */
  final Map<String, Pending> pending = new LinkedHashMap<>();
  /** Open `subscriptions/listen` requests, by canonical JSON id (the value is the id itself). */
  final Map<String, Object> subscriptions = new LinkedHashMap<>();
  /** The live connection to sbt-mcp, or null. */
  SocketChannel connection = null;
  boolean legacyClient = false;
  /** The last tool list given to the client and the era it was asked in, to detect changes. */
  String lastTools = null;
  boolean lastToolsModern = false;
  int ownRequests = 0;

  // sbt startup.
  Thread booting = null;
  long bootFinishedAt = 0;
  String bootFailure = null;

  SbtMcpStdio(Path base, String start) {
    this.base = base;
    this.runtimeDir = base.resolve("project").resolve("target").resolve("sbt-mcp");
    this.serverFile = runtimeDir.resolve("server.json");
    this.start = start;
    this.snapshot = readSnapshot(base.resolve(".sbt-mcp").resolve("snapshot.json"));
  }

  public static void main(String[] args) throws Exception {
    Path base = Path.of("").toAbsolutePath();
    String start = "eager";
    for (String arg : args) {
      if (arg.startsWith("--start=")) start = arg.substring("--start=".length());
      else if (arg.startsWith("--project-dir=")) base = Path.of(arg.substring("--project-dir=".length())).toAbsolutePath();
      else {
        System.err.println("sbt-mcp-stdio: unknown argument " + arg);
        System.exit(2);
      }
    }
    if (!List.of("eager", "lazy", "never").contains(start)) {
      System.err.println("sbt-mcp-stdio: --start must be eager, lazy or never");
      System.exit(2);
    }
    new SbtMcpStdio(base, start).run();
  }

  void run() throws IOException {
    log("start: dir=" + base + " start=" + start + " java=" + System.getProperty("java.version")
        + (snapshot == null ? " (no .sbt-mcp/snapshot.json: requests wait for sbt)" : ""));
    if (!connect() && start.equals("eager")) ensureStarted();
    daemon("sbt-mcp-stdio-monitor", this::monitor);
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) continue;
      try {
        handle(line);
      } catch (RuntimeException error) {
        log("failed to handle a message: " + error);
      }
    }
    // The client closed stdin: shut down. sbt keeps running for later sessions.
    log("stdin closed; exiting");
    out.flush();
    System.exit(0);
  }

  // ---- Messages from the client ----

  void handle(String line) {
    Object parsed;
    try {
      parsed = Json.parse(line);
    } catch (RuntimeException error) {
      send(error(null, -32700, "Parse error: " + error.getMessage()));
      return;
    }
    Map<String, Object> msg = obj(parsed);
    if (msg == null) {
      send(error(null, -32600, "Invalid Request: expected a JSON-RPC object"));
      return;
    }
    Object method = msg.get("method");
    if (!(method instanceof String)) return; // a response: servers here send no requests
    Map<String, Object> params = obj(msg.get("params"));
    if (!msg.containsKey("id")) {
      notification((String) method, params, line);
      return;
    }
    Object id = msg.get("id");
    boolean modern = isModern((String) method, params);
    synchronized (this) {
      if (method.equals("initialize")) legacyClient = true;
    }
    switch ((String) method) {
      case "ping":
        send(result(id, new LinkedHashMap<>()));
        return;
      case "subscriptions/listen":
        listen(id, params);
        return;
      default:
    }
    if (connection() == null && snapshot != null) {
      Map<String, Object> answer = offlineAnswer((String) method, params, modern);
      if (answer != null) {
        if (start.equals("lazy")) ensureStarted();
        answer.put("id", id);
        send(answer);
        return;
      }
    }
    relay(new Pending(id, (String) method, modern, Json.write(msg)));
  }

  void notification(String method, Map<String, Object> params, String line) {
    if (method.equals("notifications/cancelled") && params != null) {
      String key = Json.write(params.get("requestId"));
      synchronized (this) {
        if (subscriptions.remove(key) != null) return;
        Pending p = pending.remove(key);
        // Not yet sent: nothing to tell sbt-mcp. Either way no response may reach the client.
        if (p == null || !p.sent) return;
      }
    }
    SocketChannel c = connection();
    if (c != null) write(c, line); // e.g. notifications/initialized; dropped while sbt is down
  }

  static boolean isModern(String method, Map<String, Object> params) {
    if (method.equals("server/discover") || method.equals("subscriptions/listen")) return true;
    return metaVersion(params) != null;
  }

  /** The protocol version a request declares in its `_meta`, or null. */
  static Object metaVersion(Map<String, Object> params) {
    Map<String, Object> meta = params == null ? null : obj(params.get("_meta"));
    return meta == null ? null : meta.get(META_VERSION);
  }

  /** What the snapshot can answer while sbt-mcp is not running, or null. */
  Map<String, Object> offlineAnswer(String method, Map<String, Object> params, boolean modern) {
    Map<String, Object> era = obj(snapshot.get(modern ? "modern" : "legacy"));
    if (era == null) return null;
    if (modern) {
      Object requested = metaVersion(params);
      if (requested != null && !MODERN.equals(requested)) {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Object> supported = new ArrayList<>();
        supported.add(MODERN);
        supported.addAll(LEGACY);
        data.put("supported", supported);
        data.put("requested", requested);
        Map<String, Object> e = error(null, -32022, "Unsupported protocol version: " + requested);
        obj(e.get("error")).put("data", data);
        return e;
      }
    }
    switch (method) {
      case "initialize": {
        if (modern) return null;
        Map<String, Object> init = copy(obj(era.get("initialize")));
        if (init == null) return null;
        Object requested = params == null ? null : params.get("protocolVersion");
        init.put("protocolVersion", LEGACY.contains(requested) ? requested : LEGACY.get(0));
        announceListChanged(init);
        return result(null, init);
      }
      case "server/discover": {
        Map<String, Object> discover = copy(obj(era.get("server/discover")));
        if (discover == null) return null;
        announceListChanged(discover);
        return result(null, discover);
      }
      case "tools/list": {
        Map<String, Object> tools = copy(obj(era.get("tools/list")));
        if (tools == null) return null;
        rememberTools(tools, modern);
        return result(null, tools);
      }
      case "logging/setLevel":
        return modern ? null : result(null, new LinkedHashMap<>());
      default:
        return null;
    }
  }

  /** This launcher sends `notifications/tools/list_changed`; say so in the capabilities. */
  static void announceListChanged(Map<String, Object> result) {
    Map<String, Object> caps = obj(result.get("capabilities"));
    if (caps == null) return;
    Map<String, Object> tools = obj(caps.get("tools"));
    if (tools == null) {
      tools = new LinkedHashMap<>();
      caps.put("tools", tools);
    }
    tools.put("listChanged", Boolean.TRUE);
  }

  synchronized void rememberTools(Map<String, Object> result, boolean modern) {
    Object tools = result.get("tools");
    if (tools != null) {
      lastTools = Json.write(tools);
      lastToolsModern = modern;
    }
  }

  /** `subscriptions/listen` is served here: the only notification is a changed tool list. */
  void listen(Object id, Map<String, Object> params) {
    Map<String, Object> requested = params == null ? null : obj(params.get("notifications"));
    Map<String, Object> agreed = new LinkedHashMap<>();
    boolean tools = requested != null && Boolean.TRUE.equals(requested.get("toolsListChanged"));
    if (tools) agreed.put("toolsListChanged", Boolean.TRUE);
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put(META_SUBSCRIPTION, id);
    Map<String, Object> ack = new LinkedHashMap<>();
    ack.put("_meta", meta);
    ack.put("notifications", agreed);
    if (tools) synchronized (this) {
      subscriptions.put(Json.write(id), id);
    }
    send(notification("notifications/subscriptions/acknowledged", ack));
  }

  // ---- Relaying ----

  /** Queue a request for sbt-mcp, and send it now if connected. */
  void relay(Pending p) {
    SocketChannel c;
    synchronized (this) {
      pending.put(Json.write(p.id), p);
      c = connection;
      if (c != null) p.sent = true;
    }
    if (c != null) write(c, p.line);
    else if (!start.equals("never")) ensureStarted();
  }

  /** A message from sbt-mcp: a response, or a notification for an in-flight request. */
  void fromServer(String line) {
    Map<String, Object> msg;
    try {
      msg = obj(Json.parse(line));
    } catch (RuntimeException notJson) {
      log("sbt-mcp sent a line that is not JSON: " + (line.length() > 200 ? line.substring(0, 200) : line));
      return;
    }
    if (msg == null) return;
    Object id = msg.get("id");
    if (id instanceof String && ((String) id).startsWith(OWN_ID)) {
      toolsChecked(msg);
      return;
    }
    if (msg.containsKey("method")) {
      // Request-scoped notifications (progress, log messages): drop those for cancelled requests.
      Map<String, Object> params = obj(msg.get("params"));
      Object token = params == null ? null : params.get("progressToken");
      if (token == null || !droppedProgress(token)) send(msg);
      return;
    }
    Pending p;
    synchronized (this) {
      p = pending.remove(Json.write(id));
    }
    if (p == null) return; // cancelled: nothing more may reach the client
    Map<String, Object> result = obj(msg.get("result"));
    if (result != null) {
      if (p.method.equals("initialize") || p.method.equals("server/discover")) announceListChanged(result);
      if (p.method.equals("tools/list")) rememberTools(result, p.modern);
    }
    send(msg);
  }

  /** Whether a progress token belongs to no request still waiting (so it was cancelled). */
  synchronized boolean droppedProgress(Object token) {
    String t = Json.write(token);
    for (Pending p : pending.values()) {
      Map<String, Object> params = obj(obj(Json.parse(p.line)).get("params"));
      Map<String, Object> meta = params == null ? null : obj(params.get("_meta"));
      if (meta != null && t.equals(Json.write(meta.get("progressToken")))) return false;
    }
    return true;
  }

  /** The connection ended (sbt reloaded or stopped): fail what was sent; keep the rest queued. */
  void disconnected(SocketChannel c, String reason) {
    List<Pending> lost = new ArrayList<>();
    synchronized (this) {
      if (connection != c) return;
      connection = null;
      for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
        Pending p = it.next();
        if (p.sent) {
          lost.add(p);
          it.remove();
        }
      }
    }
    closeQuietly(c);
    log("disconnected from sbt-mcp (" + reason + ")" + (lost.isEmpty() ? "" : "; " + lost.size() + " request(s) lost"));
    for (Pending p : lost)
      send(error(p.id, -32000, "the connection to sbt-mcp was lost (" + reason + "), most likely because sbt "
          + "reloaded the build mid-request (a reload restarts the sbt-mcp server). The command may or may not have "
          + "completed: check its effect, and run it again if needed."));
  }

  synchronized SocketChannel connection() {
    return connection;
  }

  /**
   * Connect to sbt-mcp if it's advertised and not connected yet, then send what is
   * queued. Returns whether a connection is up.
   */
  boolean connect() {
    synchronized (this) {
      if (connection != null) return true;
    }
    Path socket = advertisedSocket();
    if (socket == null) return false;
    SocketChannel c;
    try {
      c = SocketChannel.open(StandardProtocolFamily.UNIX);
      c.connect(UnixDomainSocketAddress.of(socket));
    } catch (IOException | RuntimeException unavailable) {
      return false; // e.g. sbt-mcp is restarting during a reload
    }
    List<String> queued = new ArrayList<>();
    synchronized (this) {
      if (connection != null) {
        closeQuietly(c);
        return true;
      }
      connection = c;
      for (Pending p : pending.values()) {
        p.sent = true;
        queued.add(p.line);
      }
    }
    log("connected to sbt-mcp at " + socket);
    daemon("sbt-mcp-stdio-reader", () -> read(c));
    for (String line : queued) write(c, line);
    checkTools(c);
    return true;
  }

  void read(SocketChannel c) {
    String reason = "end of stream";
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(c), StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) if (!line.isBlank()) fromServer(line);
    } catch (IOException error) {
      reason = error.toString();
    }
    disconnected(c, reason);
  }

  void write(SocketChannel c, String line) {
    ByteBuffer bytes = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
    try {
      synchronized (c) {
        while (bytes.hasRemaining()) c.write(bytes);
      }
    } catch (IOException error) {
      disconnected(c, error.toString());
    }
  }

  /** Ask sbt-mcp for its tool list, to compare with the one the client was given. */
  void checkTools(SocketChannel c) {
    boolean modern;
    synchronized (this) {
      if (lastTools == null) return;
      modern = lastToolsModern;
      ownRequests++;
    }
    Map<String, Object> params = new LinkedHashMap<>();
    if (modern) {
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put(META_VERSION, MODERN);
      meta.put("io.modelcontextprotocol/clientCapabilities", new LinkedHashMap<>());
      params.put("_meta", meta);
    }
    Map<String, Object> msg = new LinkedHashMap<>();
    msg.put("jsonrpc", "2.0");
    msg.put("id", OWN_ID + "tools-" + ownRequests);
    msg.put("method", "tools/list");
    msg.put("params", params);
    write(c, Json.write(msg));
  }

  /** The answer to [[checkTools]]: tell the client when its tool list is out of date. */
  void toolsChecked(Map<String, Object> msg) {
    Map<String, Object> result = obj(msg.get("result"));
    if (result == null || result.get("tools") == null) return;
    String live = Json.write(result.get("tools"));
    List<Object> subs;
    boolean legacy;
    synchronized (this) {
      if (lastTools == null || live.equals(lastTools)) return;
      lastTools = live;
      subs = new ArrayList<>(subscriptions.values());
      legacy = legacyClient;
    }
    log("the tool list changed; notifying the client");
    if (legacy) send(notification("notifications/tools/list_changed", null));
    for (Object subscription : subs) {
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put(META_SUBSCRIPTION, subscription);
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("_meta", meta);
      send(notification("notifications/tools/list_changed", p));
    }
  }

  /**
   * Reconnect when sbt-mcp (re)appears, and fail requests that waited too long or whose
   * wait can't end (sbt failed to start).
   */
  void monitor() {
    while (true) {
      sleep(250);
      boolean waiting;
      synchronized (this) {
        waiting = !pending.isEmpty();
      }
      if (connect()) continue;
      List<Pending> expired = new ArrayList<>();
      String failure;
      synchronized (this) {
        failure = booting == null ? bootFailure : null;
        long now = System.nanoTime();
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
          Pending p = it.next();
          if (!p.sent && (failure != null || now > p.deadline)) {
            expired.add(p);
            it.remove();
          }
        }
      }
      for (Pending p : expired)
        send(error(p.id, -32000, failure != null ? failure
            : "timed out after " + START_WAIT.toMinutes() + " minutes waiting for sbt-mcp; see " + runtimeDir.resolve("boot.log")));
      if (waiting && !start.equals("never")) ensureStarted();
      if (!waiting) sleep(1750); // idle: look for a server every 2 s
    }
  }

  // ---- The sbt server ----

  /** The live sbt-mcp server's socket from project/target/sbt-mcp/server.json, or null. */
  Path advertisedSocket() {
    try {
      if (!Files.isRegularFile(serverFile)) return null;
      Map<String, Object> info = obj(Json.parse(Files.readString(serverFile, StandardCharsets.UTF_8)));
      Object socket = info == null ? null : info.get("socket");
      Object pid = info == null ? null : info.get("pid");
      if (!(socket instanceof String) || !(pid instanceof Json.Num)) return null;
      boolean alive = ProcessHandle.of(Long.parseLong(((Json.Num) pid).raw)).map(ProcessHandle::isAlive).orElse(false);
      return alive ? Path.of((String) socket) : null;
    } catch (IOException | RuntimeException unreadable) {
      return null; // e.g. read while sbt writes it
    }
  }

  /**
   * Start sbt's background server (if it isn't running) with `sbt --client mcpStatus`.
   * The client connects to a running server, or starts one, and exits; the server keeps
   * running. Attempts are spaced out so a build that can't start isn't restarted in a loop.
   */
  synchronized void ensureStarted() {
    if (start.equals("never") || booting != null || connection != null) return;
    if (bootFailure != null && System.currentTimeMillis() - bootFinishedAt < 30_000) return;
    bootFailure = null;
    booting = daemon("sbt-mcp-stdio-boot", this::boot);
  }

  void boot() {
    String failure = null;
    try {
      Files.createDirectories(runtimeDir);
      Path bootLog = runtimeDir.resolve("boot.log");
      int code = runSbt("--client", bootLog);
      if (code != 0 && advertisedSocket() == null) {
        log("`sbt --client` exited with " + code + "; trying the JVM client");
        code = runSbt("--jvm-client", bootLog);
      }
      // mcpStatus runs after the build loaded, so an enabled server is listening by now.
      for (int i = 0; i < 40 && !connect(); i++) sleep(250);
      if (connection() == null)
        failure = "sbt-mcp is not running after starting sbt (exit code " + code + "). Is `ThisBuild / mcpEnabled := true` "
            + "set in this build? Last lines of " + bootLog + ":\n" + tail(bootLog, 25);
    } catch (IOException error) {
      failure = "could not start sbt: " + error;
    } finally {
      synchronized (this) {
        booting = null;
        bootFinishedAt = System.currentTimeMillis();
        bootFailure = failure;
      }
      if (failure != null) log(failure);
    }
  }

  int runSbt(String clientFlag, Path bootLog) throws IOException {
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    List<String> command = new ArrayList<>();
    if (windows) {
      command.add("cmd");
      command.add("/c");
      command.add(Files.isRegularFile(base.resolve("sbt.bat")) ? "sbt.bat" : "sbt");
    } else {
      Path script = base.resolve("sbt");
      if (Files.isRegularFile(script)) {
        command.add("bash");
        command.add(script.toString());
      } else command.add("sbt");
    }
    command.add(clientFlag);
    command.add("mcpStatus");
    log("starting sbt: " + String.join(" ", command) + " (output: " + bootLog + ")");
    ProcessBuilder builder = new ProcessBuilder(command)
        .directory(base.toFile())
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(bootLog.toFile()))
        .redirectInput(ProcessBuilder.Redirect.from(new File(windows ? "NUL" : "/dev/null")));
    try {
      return builder.start().waitFor();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  static String tail(Path file, int lines) {
    try {
      List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
      return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()))
          .replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
    } catch (IOException | RuntimeException unreadable) {
      return "(unreadable: " + unreadable + ")";
    }
  }

  // ---- Output and helpers ----

  void send(Object message) {
    String line = Json.write(message);
    synchronized (out) {
      out.print(line);
      out.print('\n');
      out.flush();
    }
  }

  static Map<String, Object> result(Object id, Object result) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("jsonrpc", "2.0");
    m.put("id", id);
    m.put("result", result);
    return m;
  }

  static Map<String, Object> error(Object id, int code, String message) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("code", new Json.Num(Integer.toString(code)));
    e.put("message", message);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("jsonrpc", "2.0");
    m.put("id", id);
    m.put("error", e);
    return m;
  }

  static Map<String, Object> notification(String method, Object params) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("jsonrpc", "2.0");
    m.put("method", method);
    if (params != null) m.put("params", params);
    return m;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> obj(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : null;
  }

  static Map<String, Object> copy(Map<String, Object> value) {
    return value == null ? null : obj(Json.parse(Json.write(value)));
  }

  static Map<String, Object> readSnapshot(Path file) {
    try {
      return Files.isRegularFile(file) ? obj(Json.parse(Files.readString(file, StandardCharsets.UTF_8))) : null;
    } catch (IOException | RuntimeException unreadable) {
      System.err.println("sbt-mcp-stdio: ignoring unreadable " + file + ": " + unreadable);
      return null;
    }
  }

  void log(String message) {
    String line = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " sbt-mcp-stdio: " + message;
    System.err.println(line);
    try {
      Files.createDirectories(runtimeDir);
      Files.writeString(runtimeDir.resolve("stdio.log"), line + System.lineSeparator(), StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // stderr has it
    }
  }

  static Thread daemon(String name, Runnable body) {
    Thread t = new Thread(body, name);
    t.setDaemon(true);
    t.start();
    return t;
  }

  static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  static void closeQuietly(SocketChannel c) {
    try {
      c.close();
    } catch (IOException ignored) {
      // already closed
    }
  }

  /**
   * Minimal JSON: objects are LinkedHashMaps, arrays Lists, numbers [[Num]] (their
   * literal text, so ids and values round-trip unchanged), null is [[NULL]].
   */
  static final class Json {
    static final Object NULL = new Object() {
      @Override
      public String toString() {
        return "null";
      }
    };

    record Num(String raw) {}

    private final String s;
    private int i = 0;

    private Json(String s) {
      this.s = s;
    }

    static Object parse(String text) {
      Json p = new Json(text);
      p.ws();
      Object v = p.value();
      p.ws();
      if (p.i != p.s.length()) throw p.fail("trailing characters");
      return v;
    }

    private RuntimeException fail(String what) {
      return new IllegalArgumentException(what + " at offset " + i);
    }

    private void ws() {
      while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    private Object value() {
      if (i >= s.length()) throw fail("unexpected end");
      char c = s.charAt(i);
      switch (c) {
        case '{': {
          i++;
          Map<String, Object> m = new LinkedHashMap<>();
          ws();
          if (peek('}')) return m;
          while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw fail("expected a key");
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            if (peek('}')) return m;
            expect(',');
          }
        }
        case '[': {
          i++;
          List<Object> l = new ArrayList<>();
          ws();
          if (peek(']')) return l;
          while (true) {
            ws();
            l.add(value());
            ws();
            if (peek(']')) return l;
            expect(',');
          }
        }
        case '"':
          return string();
        case 't':
          word("true");
          return Boolean.TRUE;
        case 'f':
          word("false");
          return Boolean.FALSE;
        case 'n':
          word("null");
          return NULL;
        default: {
          int start = i;
          while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
          if (start == i) throw fail("unexpected character '" + c + "'");
          String raw = s.substring(start, i);
          if (!raw.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw fail("bad number " + raw);
          return new Num(raw);
        }
      }
    }

    private boolean peek(char c) {
      if (i < s.length() && s.charAt(i) == c) {
        i++;
        return true;
      }
      return false;
    }

    private void expect(char c) {
      if (!peek(c)) throw fail("expected '" + c + "'");
    }

    private void word(String w) {
      if (!s.startsWith(w, i)) throw fail("expected " + w);
      i += w.length();
    }

    private String string() {
      i++; // opening quote
      StringBuilder b = new StringBuilder();
      while (true) {
        if (i >= s.length()) throw fail("unterminated string");
        char c = s.charAt(i++);
        if (c == '"') return b.toString();
        if (c != '\\') {
          b.append(c);
          continue;
        }
        if (i >= s.length()) throw fail("unterminated escape");
        char e = s.charAt(i++);
        switch (e) {
          case '"', '\\', '/' -> b.append(e);
          case 'b' -> b.append('\b');
          case 'f' -> b.append('\f');
          case 'n' -> b.append('\n');
          case 'r' -> b.append('\r');
          case 't' -> b.append('\t');
          case 'u' -> {
            if (i + 4 > s.length()) throw fail("bad unicode escape");
            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
          }
          default -> throw fail("bad escape \\" + e);
        }
      }
    }

    /** Compact, single-line JSON (newline-delimited framing forbids embedded newlines). */
    static String write(Object value) {
      StringBuilder b = new StringBuilder();
      write(b, value);
      return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
      if (v == null || v == NULL) b.append("null");
      else if (v instanceof String) quote(b, (String) v);
      else if (v instanceof Boolean) b.append(v);
      else if (v instanceof Num) b.append(((Num) v).raw);
      else if (v instanceof Number) b.append(v);
      else if (v instanceof Map) {
        b.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
          if (!first) b.append(',');
          first = false;
          quote(b, String.valueOf(e.getKey()));
          b.append(':');
          write(b, e.getValue());
        }
        b.append('}');
      } else if (v instanceof List) {
        b.append('[');
        boolean first = true;
        for (Object e : (List<?>) v) {
          if (!first) b.append(',');
          first = false;
          write(b, e);
        }
        b.append(']');
      } else quote(b, v.toString());
    }

    private static void quote(StringBuilder b, String s) {
      b.append('"');
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
          case '"' -> b.append("\\\"");
          case '\\' -> b.append("\\\\");
          case '\n' -> b.append("\\n");
          case '\r' -> b.append("\\r");
          case '\t' -> b.append("\\t");
          default -> {
            if (c < 0x20 || c == '\u2028' || c == '\u2029') b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
          }
        }
      }
      b.append('"');
    }
  }
}
