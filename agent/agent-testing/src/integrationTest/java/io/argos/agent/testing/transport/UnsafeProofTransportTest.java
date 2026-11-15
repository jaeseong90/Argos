/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.jvm.UnsafeScanner;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.log.AgentLogAssert;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The proof that ADR-0009 C-74 asks of every line of {@code test/agent/unsafe-allowlist.txt}, on
 * the real transport paths: connect with TLS, send (gzip with the jar apps of {@code -Pagent.e2e}),
 * reconnect, shutdown, on every JDK of {@code -Pagent.attach.jdks}:
 *
 * <ol>
 *   <li>the agent's relocated Netty setting turns Unsafe off ({@code netty unsafe false} in the
 *       agent log: {@code PlatformDependent.hasUnsafe()} of the shaded Netty);
 *   <li>JDK 25 in its default mode (warn) prints no Unsafe warning line;
 *   <li>{@code --sun-misc-unsafe-memory-access=deny} works end to end (the harness preset on 25);
 *   <li>the class-load record names the classes: every loaded class that references {@code
 *       sun.misc.Unsafe} is allow-listed, and (with all five JDKs) every allow-listed class loaded.
 * </ol>
 *
 * Classes that never load are removed from the jar
 * (AgentShading.TRANSPORT_UNSAFE_EXCLUDED_CLASSES): a removed class that the transport needs would
 * show as {@code NoClassDefFoundError} in the agent log, which fails here.
 */
@Tag("req:F-A35")
@Tag("req:C-74")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnsafeProofTransportTest {
  private static final List<Integer> ALL_JDKS = List.of(8, 11, 17, 21, 25);

  /** {@code [Loaded x from y]} (JDK 8) or {@code [0.1s][info][class,load] x source: y}. */
  private static final Pattern LOADED_8 = Pattern.compile("^\\[Loaded (\\S+) .*");

  private static final Pattern LOADED_9 = Pattern.compile("^\\[.*class,load.*\\] (\\S+) .*");

  private final Set<String> loadedEverywhere = new TreeSet<>();
  private final Set<Integer> covered = new TreeSet<>();

  @TempDir(cleanup = CleanupMode.NEVER)
  Path work;

  // After each test method (a @TestFactory's dynamic tests have all run by then): JUnit gives every
  // method its own directory, so deleting only after all of them left the earlier ones behind.
  @AfterEach
  void deleteRun() {
    TransportRuns.deleteRun(work);
  }

  private static Set<String> allowlist() {
    return UnsafeScanner.readAllowlist(
        Path.of(TransportRuns.required("argos.test.unsafeAllowlist")));
  }

  private static Set<String> unsafeReferencing() {
    return UnsafeScanner.scan(TransportRuns.releaseJar());
  }

  /** The class-load record of a run ({@code -verbose:class} on every JDK writes to stdout). */
  static Set<String> loadedClasses(List<String> stdout) {
    Set<String> loaded = new TreeSet<>();
    for (String line : stdout) {
      Matcher matcher = LOADED_8.matcher(line);
      if (!matcher.matches()) {
        matcher = LOADED_9.matcher(line);
      }
      if (matcher.matches()) {
        loaded.add(matcher.group(1));
      }
    }
    return loaded;
  }

  private void assertProof(int jdk, String agentLog, Set<String> loaded, String stderr) {
    assertThat(agentLog).as("the transport started on JDK %d", jdk).contains("netty unsafe false");
    assertThat(agentLog)
        .as("no class the jar left out is needed")
        .doesNotContain("NoClassDefFoundError")
        .doesNotContain("ClassNotFoundException")
        .doesNotContain("NoSuchMethodError");
    AgentLogAssert.assertThatAgentLog(agentLog).hasNoErrors();
    assertThat(stderr)
        .as("no Unsafe warning from the JVM")
        .doesNotContain("sun.misc.Unsafe")
        .doesNotContain("terminally deprecated");
    Set<String> referencing = unsafeReferencing();
    Set<String> loadedReferencing = new TreeSet<>(loaded);
    loadedReferencing.retainAll(referencing);
    Set<String> notAllowed = new TreeSet<>(loadedReferencing);
    notAllowed.removeAll(allowlist());
    assertThat(notAllowed)
        .as("classes that load and reference Unsafe outside the allow-list (JDK %d)", jdk)
        .isEmpty();
    synchronized (loadedEverywhere) {
      loadedEverywhere.addAll(loadedReferencing);
      covered.add(jdk);
    }
    System.out.println(
        "[c-74] JDK "
            + jdk
            + ": "
            + loaded.stream().filter(c -> c.startsWith("io.argos.agent.")).count()
            + " agent classes loaded, Unsafe-referencing and loaded: "
            + loadedReferencing);
  }

  @TestFactory
  Stream<DynamicTest> trivialAppOnEveryJdk() {
    return TransportRuns.attachJdks().stream()
        .map(
            jdk ->
                DynamicTest.dynamicTest(
                    "T-BLD-004a JDK "
                        + jdk
                        + (jdk >= 25 ? " deny" : "")
                        + ": connect (TLS), send, reconnect, shutdown with Unsafe off; every loaded"
                        + " Unsafe-referencing class is allow-listed",
                    () -> connectSendReconnectShutdown(jdk)));
  }

  private void connectSendReconnectShutdown(int jdk) throws Exception {
    try (FakeCollector collector =
        FakeCollector.builder()
            .tls(true)
            .tlsDirectory(work.resolve("ca-" + jdk))
            .heartbeatIntervalS(1)
            .start()) {
      AppLauncher launcher =
          TransportRuns.trivial(work.resolve("jdk" + jdk), jdk, collector, "9000")
              .agentConfig("sampling.profile.normal-rate", "1.0")
              .withJvmOptions("-verbose:class");
      CompletableFuture<ExitedApp> running =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return TransportRuns.runToExit(launcher);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      assertThat(collector.awaitSessions(1, Duration.ofSeconds(60))).isNotEmpty();
      assertThat(collector.awaitBatches(BatchKind.TRANSACTIONS, 1, Duration.ofSeconds(20)))
          .isNotEmpty();
      // The stream and the connection end: the reconnect path of the session and of the channel.
      collector.faults().closeStream();
      assertThat(collector.awaitSessions(2, Duration.ofSeconds(20))).hasSizeGreaterThanOrEqualTo(2);
      ExitedApp run = running.get();
      assertThat(collector.byes()).as(run.agentLog()).hasSize(1);
      assertProof(jdk, run.agentLog(), loadedClasses(run.stdout().lines().toList()), run.stderr());
    }
  }

  @TestFactory
  Stream<DynamicTest> jarAppsWithGzip() {
    List<String> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "").split(",", -1)) {
      if (item.contains("@") && !SampleApp.named(TransportRuns.appOf(item.strip())).isWar()) {
        combos.add(item.strip());
      }
    }
    return combos.stream()
        .map(
            combo ->
                DynamicTest.dynamicTest(
                    "T-BLD-004a "
                        + combo
                        + ": gzip batches with Unsafe off; every loaded Unsafe-referencing class"
                        + " is allow-listed",
                    () -> gzipBatches(combo)));
  }

  private void gzipBatches(String combo) throws Exception {
    int jdk = TransportRuns.jdkOf(combo);
    try (FakeCollector collector =
            FakeCollector.builder()
                .tls(true)
                .tlsDirectory(work.resolve("ca-" + combo.replace('@', '-')))
                .start();
        RunningApp app =
            AppLauncher.of(SampleApp.named(TransportRuns.appOf(combo)), Jdk.of(jdk))
                .withAgent(TransportRuns.releaseJar())
                .collector(collector)
                .withJvmOptions("-verbose:class")
                .workDir(work.resolve(combo.replace('@', '-')))
                .start(Duration.ofMinutes(4))) {
      app.load(100, Duration.ofSeconds(5), "/api/orders/1");
      assertThat(
              TransportRuns.await(
                  () ->
                      collector.batches(BatchKind.TRANSACTIONS).stream()
                          .mapToInt(ReceivedBatch::bytes)
                          .anyMatch(bytes -> bytes > 1024),
                  Duration.ofSeconds(20)))
          .as("a batch above 1 KiB, sent gzip compressed (11 §2); %s", collector.counts())
          .isTrue();
      List<String> stdout = app.stdoutLines();
      Set<String> loaded = loadedClasses(stdout);
      assertThat(loaded)
          .as("the gzip code of gRPC ran")
          .contains("io.argos.agent.shaded.io.grpc.Codec$Gzip");
      assertProof(jdk, app.agentLog(), loaded, String.join("\n", app.stderrLines()));
    }
  }

  @Test
  @DisplayName(
      "T-BLD-004a JDK 25 default mode (warn): 0 Unsafe warning lines from connect to shutdown")
  void jdk25WarnMode() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        TransportRuns.attachJdks().contains(25), "JDK 25 is not in -Pagent.attach.jdks");
    try (FakeCollector collector =
        FakeCollector.builder().tls(true).tlsDirectory(work.resolve("ca-warn")).start()) {
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                      work.resolve("jdk25-warn"), 25, collector, TransportRuns.STAY_ALIVE_MILLIS)
                  // After the harness preset (deny): the last value wins, warn is the JDK default.
                  .withJvmOptions("--sun-misc-unsafe-memory-access=warn"));
      assertThat(run.command()).contains("--sun-misc-unsafe-memory-access=warn");
      assertThat(collector.seqs(BatchKind.TRANSACTIONS)).as(run.agentLog()).isNotEmpty();
      assertThat(run.stderr())
          .doesNotContain("sun.misc.Unsafe")
          .doesNotContain("terminally deprecated");
      assertThat(run.stdout()).doesNotContain("sun.misc.Unsafe");
      assertThat(run.agentLog()).contains("netty unsafe false");
    }
  }

  @Test
  @DisplayName("every allow-list line has a reason and names a class of the jar that uses Unsafe")
  void allowlistLinesHaveReasons() throws Exception {
    Path file = Path.of(TransportRuns.required("argos.test.unsafeAllowlist"));
    Set<String> referencing = unsafeReferencing();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      String entry = line.strip();
      if (entry.isEmpty() || entry.startsWith("#")) {
        continue;
      }
      assertThat(entry).as("reason comment (C-74)").containsPattern("\\S+\\s+#\\s*\\S+");
      String name = entry.substring(0, entry.indexOf('#')).strip();
      assertThat(referencing).as("still needed: %s", name).contains(name);
    }
    assertThat(allowlist()).as("the jar's other Unsafe users are gone").containsAll(referencing);
  }

  /**
   * With all five JDKs covered, an allow-listed class that never loaded should have been removed.
   */
  @AfterAll
  void everyAllowedClassLoaded() {
    if (!covered.containsAll(ALL_JDKS)) {
      System.out.println(
          "[c-74] covered JDKs "
              + covered
              + " of "
              + ALL_JDKS
              + ": the 'every allow-listed class loads' check needs all of them");
      return;
    }
    Set<String> neverLoaded = new TreeSet<>(allowlist());
    neverLoaded.removeAll(loadedEverywhere);
    System.out.println("[c-74] allow-listed, never loaded on " + covered + ": " + neverLoaded);
    assertThat(neverLoaded)
        .as(
            "allow-list lines whose class never loaded on %s: remove them from the jar"
                + " (AgentShading.TRANSPORT_UNSAFE_EXCLUDED_CLASSES, C-74)",
            Arrays.toString(ALL_JDKS.toArray()))
        .isEmpty();
  }
}
