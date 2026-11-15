/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.JdkLoggerFactory;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The {@link FakeCollector} as its own process, for the overhead bench ({@code --exporter grpc}, 18
 * §7) and manual runs: {@code java -jar
 * agent/agent-testing/build/fake-collector/fake-collector.jar} (Gradle {@code
 * :agent:agent-testing:fakeCollectorJar}). Development-only, never shipped. It keeps running totals
 * per batch kind only ({@link FakeCollector.Builder#retainBatches(boolean)} off), prints them every
 * {@code --report-every} seconds and at the end, and follows the same 11 §16 contract as in the
 * tests (sessions, deduplication, clock, no caps or quotas).
 *
 * <pre>
 * --port N                  listen port (default 0: a free port; see --ready-file)
 * --bind HOST               listen address (default 127.0.0.1)
 * --tls                     TLS with a throw-away self-signed certificate (localhost, 127.0.0.1)
 * --tls-dir DIR             where the key store and ca.pem go (default: java.io.tmpdir)
 * --token-file FILE         write the collector's test token there (owner-only) and accept only it
 *                           (the agent reads it with server.token-file); without it any well-formed
 *                           argos_ token is accepted
 * --heartbeat-interval-s N  ServerHello.heartbeat_interval_s (default 30)
 * --report-every S          print the totals every S seconds (default 10, 0: only at the end)
 * --duration S              stop after S seconds (default 0: until the process is stopped)
 * --ready-file FILE         write "host:port" to connect to (127.0.0.1 for a wildcard --bind) and,
 *                           with --tls, the CA path, once listening
 * --summary FILE            write the final totals as JSON
 * </pre>
 *
 * <p>Output goes to stdout: one {@code fake-collector listening ...} line, then {@code
 * fake-collector t=...} report lines. The token is never printed.
 */
public final class FakeCollectorMain {
  private static final Set<String> WILDCARDS = Set.of("0.0.0.0", "::", "[::]");

  private FakeCollectorMain() {}

  /** Parsed command line. */
  record Options(
      int port,
      String bind,
      boolean tls,
      Path tlsDir,
      Path tokenFile,
      int heartbeatIntervalS,
      int reportEverySeconds,
      int durationSeconds,
      Path readyFile,
      Path summaryFile) {}

  /** A bad command line. */
  static final class UsageException extends Exception {
    private static final long serialVersionUID = 1L;

    UsageException(String message) {
      super(message);
    }
  }

  public static void main(String[] args) throws Exception {
    // Netty would log through SLF4J (on the class path for Testcontainers) and print its NOP
    // notice.
    InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);
    Options options;
    try {
      options = parse(args);
    } catch (UsageException e) {
      System.err.println("fake-collector: " + e.getMessage());
      System.exit(2);
      return;
    }
    run(options, System.out);
    // After --duration (or during shutdown, where this blocks until the hooks are done): no
    // library thread may keep the process alive.
    System.exit(0);
  }

  static Options parse(String[] args) throws UsageException {
    int port = 0;
    String bind = "127.0.0.1";
    boolean tls = false;
    Path tlsDir = null;
    Path tokenFile = null;
    int heartbeat = FakeCollector.DEFAULT_HEARTBEAT_INTERVAL_S;
    int reportEvery = 10;
    int duration = 0;
    Path readyFile = null;
    Path summary = null;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case "--tls" -> tls = true;
        case "--port" -> port = number(args, ++i, arg, 0, 65_535);
        case "--bind" -> bind = value(args, ++i, arg);
        case "--tls-dir" -> tlsDir = Path.of(value(args, ++i, arg));
        case "--token-file" -> tokenFile = Path.of(value(args, ++i, arg));
        case "--heartbeat-interval-s" -> heartbeat = number(args, ++i, arg, 1, 3_600);
        case "--report-every" -> reportEvery = number(args, ++i, arg, 0, 86_400);
        case "--duration" -> duration = number(args, ++i, arg, 0, 7 * 86_400);
        case "--ready-file" -> readyFile = Path.of(value(args, ++i, arg));
        case "--summary" -> summary = Path.of(value(args, ++i, arg));
        default -> throw new UsageException("unknown option " + arg + " (see the class comment)");
      }
    }
    return new Options(
        port, bind, tls, tlsDir, tokenFile, heartbeat, reportEvery, duration, readyFile, summary);
  }

  private static String value(String[] args, int index, String option) throws UsageException {
    if (index >= args.length || args[index].startsWith("--")) {
      throw new UsageException(option + " needs a value");
    }
    return args[index];
  }

  private static int number(String[] args, int index, String option, int min, int max)
      throws UsageException {
    String text = value(args, index, option);
    try {
      int value = Integer.parseInt(text);
      if (value < min || value > max) {
        throw new UsageException(option + " must be in [" + min + ", " + max + "]: " + text);
      }
      return value;
    } catch (NumberFormatException e) {
      throw new UsageException(option + " needs a number: " + text);
    }
  }

  /** Runs until {@code --duration} passes or the JVM shuts down. */
  static void run(Options options, PrintStream out) throws IOException, InterruptedException {
    FakeCollector.Builder builder =
        FakeCollector.builder()
            .tls(options.tls())
            .bindAddress(options.bind())
            .port(options.port())
            .heartbeatIntervalS(options.heartbeatIntervalS())
            .retainBatches(false);
    if (options.tlsDir() != null) {
      builder.tlsDirectory(options.tlsDir());
    }
    FakeCollector collector = builder.start();
    long started = System.nanoTime();
    CountDownLatch stopped = new CountDownLatch(1);
    Thread hook =
        new Thread(
            () -> {
              finish(collector, options, out, started);
              stopped.countDown();
            },
            "fake-collector-shutdown");
    try {
      if (options.tokenFile() != null) {
        writePrivate(options.tokenFile(), collector.token() + "\n");
        collector.faults().requireToken(collector.token());
      }
      String address = connectHost(options.bind()) + ":" + collector.port();
      Path pem = collector.trustPem();
      out.println(
          "fake-collector listening on "
              + options.bind()
              + ":"
              + collector.port()
              + " tls="
              + collector.tls()
              + (pem != null ? " ca-file=" + pem : "")
              + " token="
              + (options.tokenFile() != null ? "file:" + options.tokenFile() : "any")
              + " protocol="
              + collector.protocolName());
      out.flush();
      if (options.readyFile() != null) {
        writeAtomically(options.readyFile(), address + "\n" + (pem != null ? pem + "\n" : ""));
      }
      Runtime.getRuntime().addShutdownHook(hook);
    } catch (IOException | RuntimeException e) {
      collector.close();
      throw e;
    }
    long deadline =
        options.durationSeconds() > 0
            ? started + TimeUnit.SECONDS.toNanos(options.durationSeconds())
            : Long.MAX_VALUE;
    long every = TimeUnit.SECONDS.toNanos(options.reportEverySeconds());
    boolean reporting = every > 0;
    long nextReport = reporting ? started + every : deadline;
    while (System.nanoTime() < deadline) {
      long sleep = Math.min(deadline, nextReport) - System.nanoTime();
      if (sleep > 0 && stopped.await(sleep, TimeUnit.NANOSECONDS)) {
        return;
      }
      if (reporting && System.nanoTime() >= nextReport) {
        out.println(report(collector, started));
        out.flush();
        nextReport += every;
      }
    }
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException e) {
      // already shutting down: the hook finishes
      stopped.await();
      return;
    }
    finish(collector, options, out, started);
  }

  /** The host to connect to for a listen address: a wildcard is not a place to connect to. */
  static String connectHost(String bind) {
    return WILDCARDS.contains(bind) ? "127.0.0.1" : bind;
  }

  private static synchronized void finish(
      FakeCollector collector, Options options, PrintStream out, long started) {
    out.println(report(collector, started) + " final");
    out.flush();
    if (options.summaryFile() != null) {
      try {
        writeAtomically(options.summaryFile(), summaryJson(collector, started));
      } catch (IOException e) {
        System.err.println("fake-collector: cannot write the summary: " + e.getMessage());
      }
    }
    collector.close();
  }

  /** One report line: elapsed time, sessions, and per kind batches/items/bytes/acked/dup/failed. */
  static String report(FakeCollector collector, long startedNanos) {
    StringBuilder line =
        new StringBuilder("fake-collector t=")
            .append(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos))
            .append("s sessions=")
            .append(collector.acceptedSessionCount())
            .append(" open=")
            .append(collector.openSessionCount())
            .append(" reconnects=")
            .append(collector.reconnectCount())
            .append(" heartbeats=")
            .append(collector.heartbeats().size())
            .append(" max_in_flight=")
            .append(collector.maxConcurrentUnaryCalls());
    for (Map.Entry<BatchKind, KindCounts> entry : collector.counts().entrySet()) {
      KindCounts c = entry.getValue();
      line.append(" | ")
          .append(entry.getKey().name().toLowerCase(Locale.ROOT))
          .append(" batches=")
          .append(c.batches())
          .append(" items=")
          .append(c.items())
          .append(" bytes=")
          .append(c.bytes())
          .append(" acked=")
          .append(c.acked())
          .append(" dup=")
          .append(c.duplicates())
          .append(" failed=")
          .append(c.failed());
    }
    return line.toString();
  }

  static String summaryJson(FakeCollector collector, long startedNanos) {
    List<String> kinds = new ArrayList<>();
    for (Map.Entry<BatchKind, KindCounts> entry : collector.counts().entrySet()) {
      KindCounts c = entry.getValue();
      kinds.add(
          String.format(
              Locale.ROOT,
              "\"%s\":{\"batches\":%d,\"items\":%d,\"bytes\":%d,\"acked\":%d,\"acked_items\":%d,"
                  + "\"duplicates\":%d,\"failed\":%d}",
              entry.getKey().name(),
              c.batches(),
              c.items(),
              c.bytes(),
              c.acked(),
              c.ackedItems(),
              c.duplicates(),
              c.failed()));
    }
    return String.format(
        Locale.ROOT,
        "{\"protocol\":\"%s\",\"seconds\":%d,\"sessions\":%d,\"reconnects\":%d,\"heartbeats\":%d,"
            + "\"max_in_flight\":%d,\"kinds\":{%s}}%n",
        collector.protocolName().replace("\"", "'"),
        TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos),
        collector.acceptedSessionCount(),
        collector.reconnectCount(),
        collector.heartbeats().size(),
        collector.maxConcurrentUnaryCalls(),
        String.join(",", kinds));
  }

  /** Writes {@code text} readable by the owner only where the file system has POSIX permissions. */
  static void writePrivate(Path file, String text) throws IOException {
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.deleteIfExists(file);
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else {
      Files.createFile(file);
    }
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  private static void writeAtomically(Path file, String text) throws IOException {
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(temporary, text, StandardCharsets.UTF_8);
    Files.move(
        temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
}
