/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.Attacks;
import io.argos.server.testing.fakeagent.BatchKind;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.grpc.Status;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-SEC-11 (18 §8 gzip-bomb, 12 §3.3 class 1, SEC-11): the whole server runs as a child JVM (the
 * boot jar {@code server/build/libs/argos-server-*.jar} when it is built, otherwise {@code
 * ServerApplication} from the test class path), a fake agent sends 1 MB of gzip that inflates to 1
 * GiB, and the server refuses it before decoding with {@code RESOURCE_EXHAUSTED message_size}. The
 * resident set of the server may grow by at most 200 MB: the largest resident set during the attack
 * (five bombs one after the other and four at once) minus the resident set right before it, after a
 * full GC of the server JVM ({@code jcmd <pid> GC.run}). Linux resets {@code VmHWM} through {@code
 * /proc/<pid>/clear_refs} before the attack; Windows samples {@code WorkingSet64} every 50 ms
 * ({@link RssProbe}). A peak-to-peak comparison would hide growth below the start-up peak.
 *
 * <p>Numbers from a shared laptop are a reference only (INVALID as a measurement).
 */
@Tag("req:SEC-11")
@Tag("slow")
class GzipBombSecurityTest {

  /** 18 §10 T-SEC-11: peak RSS growth at most 200 MB. */
  static final long MAX_GROWTH_BYTES = 200L * 1024 * 1024;

  @TempDir static Path work;

  private static ServerTestEnvironment env;
  private static Process server;
  private static Path serverLog;
  private static String launchMode;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    int http = freePort();
    int management = freePort();
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Xmx1g");
    command.add("-Djava.io.tmpdir=" + work.resolve("tmp"));
    command.add("-DLOG_PATH=" + work.resolve("logs"));
    Path bootJar = bootJar();
    if (bootJar != null) {
      launchMode = "boot jar " + bootJar.getFileName();
      command.add("-jar");
      command.add(bootJar.toString());
    } else {
      launchMode = "ServerApplication from the test class path (boot jar not built)";
      command.add("-cp");
      command.add(System.getProperty("java.class.path"));
      command.add("io.argos.server.ServerApplication");
    }
    Files.createDirectories(work.resolve("tmp"));
    for (Map.Entry<String, String> e : env.springProperties().entrySet()) {
      command.add("--" + e.getKey() + "=" + e.getValue());
    }
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + http);
    command.add("--management.server.address=127.0.0.1");
    command.add("--management.server.port=" + management);
    command.add("--argos.web.public-url=http://127.0.0.1:" + http);
    // argos.wal.dir: the environment's private WAL directory (a second --argos.wal.dir would be
    // joined with a comma by Spring's command line source).
    serverLog = work.resolve("server-stdout.log");
    server =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(serverLog.toFile())
            .start();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
    while (!accepts(env.collectorPort())) {
      if (!server.isAlive() || System.nanoTime() > deadline) {
        throw new IllegalStateException(
            "The server did not open the gRPC port; last output:\n" + tail(serverLog));
      }
      TimeUnit.MILLISECONDS.sleep(500);
    }
  }

  @AfterAll
  static void stop() throws Exception {
    if (server != null) {
      server.destroy();
      if (!server.waitFor(30, TimeUnit.SECONDS)) {
        server.destroyForcibly();
      }
    }
    if (env != null) {
      env.close();
    }
  }

  @Test
  @DisplayName(
      "T-SEC-11 a 1 MB -> 1 GiB gzip bomb is RESOURCE_EXHAUSTED before decoding; peak RSS grows by at most 200 MB")
  void gzipBomb() throws Exception {
    String app = "bomb-app";
    String token;
    try (Connection c = env.connect(PgAccount.APP)) {
      token = AgentTokens.issue(c, AgentTokens.ensureApp(c, app, "prod")).token();
    }
    try (FakeAgent normal =
            FakeAgent.builder(
                    AgentChannels.tls(
                        "localhost", env.collectorPort(), env.collectorCaCertificate()))
                .ownsChannel(true)
                .token(token)
                .appName(app)
                .instanceName("normal")
                .build();
        FakeAgent bomber =
            FakeAgent.builder(
                    AgentChannels.tls(
                        "localhost",
                        env.collectorPort(),
                        env.collectorCaCertificate(),
                        AgentChannels.Options.defaults().withPreCompressedGzip()))
                .ownsChannel(true)
                .token(token)
                .appName(app)
                .instanceName("bomber")
                .compression(FakeAgent.Compression.NONE)
                .build()) {
      assertThat(normal.connect().accepted()).isTrue();
      assertThat(bomber.connect().accepted()).isTrue();
      // Warm-up: ordinary batches, gzip included (AUTO above 1 KiB), so that the baseline holds the
      // decode, WAL and pipeline paths.
      for (int i = 0; i < 20; i++) {
        List<io.argos.proto.v1.TransactionRecord> records = new ArrayList<>();
        for (int r = 0; r < 200; r++) {
          records.add(normal.records().transaction().build());
        }
        CallResult ok = normal.sendTransactions(normal.batches().transactions(records));
        assertThat(ok.ackStatus())
            .as(ok.toString())
            .isIn(AckStatus.ACK_OK, AckStatus.ACK_PARTIAL_DROP);
      }
      byte[] bomb = Attacks.gzipBomb(Attacks.GZIP_BOMB_INFLATED_BYTES);
      // Baseline: a full GC in the server JVM (not in this test JVM), then the resident set right
      // before the attack.
      String gc = gcServer(server.pid());
      TimeUnit.SECONDS.sleep(2);
      long peakBefore = peakRss(server.pid());
      try (RssProbe probe = RssProbe.start(server.pid())) {
        long currentBefore = probe.beginAttack();

        List<CallResult> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
          results.add(bomb(bomber, bomb));
        }
        List<CompletableFuture<CallResult>> concurrent = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
          concurrent.add(CompletableFuture.supplyAsync(() -> bomb(bomber, bomb)));
        }
        for (CompletableFuture<CallResult> f : concurrent) {
          results.add(f.get(120, TimeUnit.SECONDS));
        }
        TimeUnit.SECONDS.sleep(2);
        long maxDuring = probe.endAttack();
        long currentAfter = currentRss(server.pid());

        assertThat(results).hasSize(9);
        for (CallResult r : results) {
          assertThat(r.code()).as(r.toString()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
          assertThat(r.rejectReason()).isEqualTo("message_size");
        }
        CallResult still =
            normal.sendTransactions(
                normal.batches().transactions(List.of(normal.records().transaction().build())));
        assertThat(still.ok()).as("the server still serves after the attack").isTrue();
        long growth = maxDuring - currentBefore;
        System.out.printf(
            Locale.ROOT,
            "T-SEC-11 reference (shared laptop, INVALID as a measurement; %s, %s): bomb %d bytes ->"
                + " %d bytes inflated; method: %s, baseline %s; process peak before the attack %d"
                + " MiB, current before %d MiB, max during %d MiB (%d samples), growth %d MiB (limit"
                + " 200 MiB); current after %d MiB%n",
            launchMode,
            System.getProperty("os.name"),
            bomb.length,
            Attacks.GZIP_BOMB_INFLATED_BYTES,
            probe.method(),
            gc,
            peakBefore >> 20,
            currentBefore >> 20,
            maxDuring >> 20,
            probe.samples(),
            growth >> 20,
            currentAfter >> 20);
        assertThat(growth)
            .as("max RSS during the attack minus the RSS right before it, bytes")
            .isLessThanOrEqualTo(MAX_GROWTH_BYTES);
      }
    }
  }

  /**
   * {@code jcmd <pid> GC.run} with the server's own JDK (the child runs {@code java.home}'s {@code
   * java}).
   *
   * @return a description of the baseline step for the output
   */
  static String gcServer(long pid) throws IOException, InterruptedException {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    Path jcmd = Paths.get(System.getProperty("java.home"), "bin", windows ? "jcmd.exe" : "jcmd");
    Process p =
        new ProcessBuilder(jcmd.toString(), Long.toString(pid), "GC.run")
            .redirectErrorStream(true)
            .start();
    String out;
    try (InputStream in = p.getInputStream()) {
      out = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
    }
    assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("jcmd GC.run finished").isTrue();
    assertThat(p.exitValue()).as("jcmd %d GC.run: %s", pid, out).isZero();
    return "jcmd GC.run in the server JVM";
  }

  /**
   * The resident set of the server during the attack. Linux: {@code VmHWM} reset by writing {@code
   * 5} to {@code /proc/<pid>/clear_refs} right before the attack and read after it, together with
   * {@code VmRSS} sampled every 50 ms by a thread of this JVM. Windows: {@code PeakWorkingSet64}
   * cannot be reset, so one resident PowerShell process prints {@code WorkingSet64} every 50 ms and
   * the maximum of the attack window is taken (no process per sample).
   */
  abstract static class RssProbe implements AutoCloseable {
    static final long INTERVAL_MS = 50;

    final long pid;
    final AtomicLong max = new AtomicLong();
    final AtomicLong last = new AtomicLong(-1);
    final AtomicLong count = new AtomicLong();
    volatile boolean measuring;

    RssProbe(long pid) {
      this.pid = pid;
    }

    static RssProbe start(long pid) throws IOException, InterruptedException {
      boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
      RssProbe probe = windows ? new WindowsProbe(pid) : new LinuxProbe(pid);
      probe.open();
      return probe;
    }

    void sample(long bytes) {
      last.set(bytes);
      if (measuring) {
        count.incrementAndGet();
        max.accumulateAndGet(bytes, Math::max);
      }
    }

    abstract void open() throws IOException, InterruptedException;

    /** Starts the attack window; returns the resident set right before it. */
    abstract long beginAttack() throws IOException, InterruptedException;

    /** Ends the attack window; returns the largest resident set seen in it. */
    abstract long endAttack() throws IOException, InterruptedException;

    abstract String method();

    long samples() {
      return count.get();
    }

    /** Stops the sampling (never throws). */
    @Override
    public abstract void close();
  }

  /** Linux: {@code clear_refs} reset of {@code VmHWM} plus a sampling thread. */
  static final class LinuxProbe extends RssProbe {
    private Thread sampler;
    private volatile boolean running = true;
    private boolean reset;

    LinuxProbe(long pid) {
      super(pid);
    }

    @Override
    void open() {
      sampler =
          Thread.ofPlatform()
              .daemon()
              .name("rss-sampler")
              .start(
                  () -> {
                    while (running) {
                      try {
                        sample(currentRss(pid));
                        TimeUnit.MILLISECONDS.sleep(INTERVAL_MS);
                      } catch (InterruptedException e) {
                        return;
                      } catch (IOException | RuntimeException e) {
                        return;
                      }
                    }
                  });
    }

    @Override
    long beginAttack() throws IOException, InterruptedException {
      try {
        Files.writeString(Paths.get("/proc", Long.toString(pid), "clear_refs"), "5");
        reset = true;
      } catch (IOException e) {
        reset = false;
      }
      long current = currentRss(pid);
      max.set(current);
      measuring = true;
      return current;
    }

    @Override
    long endAttack() throws IOException, InterruptedException {
      measuring = false;
      long sampled = max.get();
      return reset ? Math.max(sampled, peakRss(pid)) : sampled;
    }

    @Override
    String method() {
      return reset
          ? "VmHWM reset via /proc/<pid>/clear_refs before the attack, max with VmRSS sampled every"
              + " 50 ms"
          : "VmRSS sampled every 50 ms (clear_refs not writable)";
    }

    @Override
    public void close() {
      running = false;
      if (sampler != null) {
        sampler.interrupt();
      }
    }
  }

  /** Windows: one resident PowerShell printing {@code WorkingSet64} every 50 ms. */
  static final class WindowsProbe extends RssProbe {
    private Process process;
    private Thread reader;

    WindowsProbe(long pid) {
      super(pid);
    }

    @Override
    void open() throws IOException, InterruptedException {
      String script =
          "$p = Get-Process -Id "
              + pid
              + "; while ($true) { $p.Refresh(); [Console]::Out.WriteLine($p.WorkingSet64);"
              + " [Console]::Out.Flush(); Start-Sleep -Milliseconds "
              + INTERVAL_MS
              + " }";
      process =
          new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
              .redirectErrorStream(true)
              .start();
      reader =
          Thread.ofPlatform()
              .daemon()
              .name("rss-sampler")
              .start(
                  () -> {
                    try (java.io.BufferedReader lines =
                        new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                process.getInputStream(), StandardCharsets.UTF_8))) {
                      String line;
                      while ((line = lines.readLine()) != null) {
                        String value = line.strip();
                        if (value.matches("\\d+")) {
                          sample(Long.parseLong(value));
                        }
                      }
                    } catch (IOException e) {
                      // the sampler was stopped
                    }
                  });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (last.get() < 0) {
        assertThat(process.isAlive() && System.nanoTime() < deadline)
            .as("the PowerShell sampler prints WorkingSet64")
            .isTrue();
        TimeUnit.MILLISECONDS.sleep(20);
      }
    }

    @Override
    long beginAttack() throws InterruptedException {
      TimeUnit.MILLISECONDS.sleep(3 * INTERVAL_MS);
      long current = last.get();
      max.set(current);
      measuring = true;
      return current;
    }

    @Override
    long endAttack() throws InterruptedException {
      TimeUnit.MILLISECONDS.sleep(3 * INTERVAL_MS);
      measuring = false;
      return max.get();
    }

    @Override
    String method() {
      return "WorkingSet64 sampled every 50 ms by one resident PowerShell process";
    }

    @Override
    public void close() {
      if (process != null) {
        process.destroyForcibly();
      }
      if (reader != null) {
        reader.interrupt();
      }
    }
  }

  private static CallResult bomb(FakeAgent bomber, byte[] bomb) {
    return bomber.sendRaw(
        BatchKind.TRANSACTIONS,
        bomb,
        FakeAgent.CallSpec.defaults()
            .withCompression(FakeAgent.Compression.GZIP)
            .withDeadline(Duration.ofSeconds(60)));
  }

  /** Peak resident set of {@code pid} in bytes. */
  static long peakRss(long pid) throws IOException, InterruptedException {
    return rss(pid, true);
  }

  /** Current resident set of {@code pid} in bytes. */
  static long currentRss(long pid) throws IOException, InterruptedException {
    return rss(pid, false);
  }

  private static long rss(long pid, boolean peak) throws IOException, InterruptedException {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("win")) {
      String property = peak ? "PeakWorkingSet64" : "WorkingSet64";
      Process p =
          new ProcessBuilder(
                  "powershell",
                  "-NoProfile",
                  "-Command",
                  "(Get-Process -Id " + pid + ")." + property)
              .redirectErrorStream(true)
              .start();
      String out;
      try (InputStream in = p.getInputStream()) {
        out = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
      }
      p.waitFor(30, TimeUnit.SECONDS);
      return Long.parseLong(out);
    }
    String key = peak ? "VmHWM:" : "VmRSS:";
    for (String line : Files.readAllLines(Paths.get("/proc", Long.toString(pid), "status"))) {
      if (line.startsWith(key)) {
        String value = line.substring(key.length()).strip();
        int space = value.indexOf(' ');
        return Long.parseLong(space < 0 ? value : value.substring(0, space)) * 1024;
      }
    }
    throw new IllegalStateException(key + " not found for " + pid);
  }

  private static Path bootJar() throws IOException {
    Path libs =
        Paths.get(System.getProperty("argos.repositoryRoot", ".."), "server", "build", "libs");
    if (!Files.isDirectory(libs)) {
      return null;
    }
    try (DirectoryStream<Path> jars = Files.newDirectoryStream(libs, "argos-server-*.jar")) {
      for (Path jar : jars) {
        // The executable jar has no classifier: not -plain, -test-fixtures, -sources, -javadoc.
        if (jar.getFileName()
            .toString()
            .matches("argos-server-\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?\\.jar")) {
          return jar;
        }
      }
    }
    return null;
  }

  private static boolean accepts(int port) {
    try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
      return s.isConnected();
    } catch (IOException e) {
      return false;
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static String tail(Path log) {
    try {
      List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
      return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    } catch (IOException e) {
      return "(no output: " + e.getMessage() + ")";
    }
  }
}
