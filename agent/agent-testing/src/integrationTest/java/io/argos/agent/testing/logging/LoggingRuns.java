/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Assumptions;

/**
 * Runs of the WO-18 logging suite (package {@code logging}): the shop jar apps of {@code
 * -Pagent.e2e} (Logback through SLF4J, {@code GET /api/mdc-log}) and the Spring-free logging
 * fixtures (Log4j 2, Log4j 1.2, reload4j; {@code GET /log}), each with the release agent jar and,
 * where a test compares ids, a TLS {@link FakeCollector}. Every run gets a directory of its own
 * under one temporary root, removed when the run closes.
 */
final class LoggingRuns {
  static final Duration WAIT = Duration.ofSeconds(30);

  /** Shop jar apps (Logback, 18 §4.1). */
  static final List<String> SHOP_JAR_APPS = List.of("shop-boot2", "shop-boot3", "shop-boot4");

  /** {@code argos.trace_id=<32 hex>} as Logback's {@code %X} (no key) prints it. */
  static final Pattern LOGBACK_TRACE = Pattern.compile("argos\\.trace_id=([0-9a-f]{32})");

  static final Pattern LOGBACK_TX = Pattern.compile("argos\\.tx_id=([0-9a-f]{16})");

  /** The user id salt of the collectors of this suite (16 §6.3). */
  static final byte[] SALT = randomSalt();

  private LoggingRuns() {}

  /** One app on one JDK. */
  record Combo(String app, int jdk) {
    @Override
    public String toString() {
      return app + " on JDK " + jdk;
    }
  }

  /** The shop jar combinations of {@code -Pagent.e2e} (skips the caller without one). */
  static List<Combo> shopCombos() {
    List<Combo> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1)) {
      String[] parts = item.strip().split("@", -1);
      if (parts.length == 2 && SHOP_JAR_APPS.contains(parts[0])) {
        combos.add(new Combo(parts[0], Integer.parseInt(parts[1])));
      }
    }
    Assumptions.assumeFalse(
        combos.isEmpty(),
        "no shop jar app (" + SHOP_JAR_APPS + ") in -Pagent.e2e, e.g. shop-boot3@21,shop-boot2@8");
    return combos;
  }

  /**
   * The logging fixture jar of {@code flavor} ({@code log4j2}, {@code log4j1}, {@code reload4j}).
   */
  static SampleApp fixture(String flavor) {
    return SampleApp.fromArtifact(Path.of(required("argos.test.loggingFixture." + flavor)));
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  /** A started app with its collector, ext stub and directory. */
  static final class Run implements AutoCloseable {
    final String name;
    final RunningApp app;
    final FakeCollector collector;
    private final ExtStub extStub;
    private final Path root;

    private Run(String name, RunningApp app, FakeCollector collector, ExtStub extStub, Path root) {
      this.name = name;
      this.app = app;
      this.collector = collector;
      this.extStub = extStub;
      this.root = root;
    }

    /**
     * Starts {@code app} on {@code jdk} with the release agent jar and {@code settings}; with a TLS
     * fake collector (salt {@link #SALT}) when {@code collector} is true, else the JSONL exporter
     * of the harness defaults. {@code args} are program arguments, {@code jvmOptions} JVM options.
     */
    static Run start(
        String name,
        SampleApp app,
        int jdk,
        Map<String, String> settings,
        boolean collector,
        List<String> args,
        List<String> jvmOptions)
        throws Exception {
      Path root = Files.createTempDirectory("wo18-logging-");
      ExtStub extStub =
          ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
      FakeCollector fake = null;
      try {
        AppLauncher launcher =
            AppLauncher.of(app, Jdk.of(jdk))
                .withEnv("SAMPLE_EXT_URL", extStub.baseUri().toString())
                .withAgent(
                    Path.of(required("argos.test.releaseAgentJar")), new LinkedHashMap<>(settings))
                .workDir(root.resolve(name));
        if (!args.isEmpty()) {
          launcher.args(args.toArray(new String[0]));
        }
        if (!jvmOptions.isEmpty()) {
          launcher.jvmOptions(jvmOptions.toArray(new String[0]));
        }
        if (collector) {
          fake = FakeCollector.builder().tls(true).userIdSalt(SALT).start();
          launcher.collector(fake);
        }
        RunningApp running = launcher.start();
        System.out.println(
            "[wo18-logging-run] "
                + name
                + ": "
                + app.name()
                + " on JDK "
                + jdk
                + ", ready "
                + running.spawnToReadyMillis()
                + " ms"
                + (fake != null ? ", collector " + fake.address() : ", JSONL"));
        return new Run(name, running, fake, extStub, root);
      } catch (Exception | Error e) {
        if (fake != null) {
          fake.close();
        }
        extStub.close();
        throw e;
      }
    }

    List<TransactionRecord> transactions(Predicate<TransactionRecord> filter) {
      return app.collected().transactions().stream().filter(filter).toList();
    }

    /**
     * Sends {@code GET path} and returns the one new transaction of {@code transactionPath} it
     * produced (requests are sent one at a time by the callers).
     */
    TransactionRecord request(String path, String transactionPath) throws Exception {
      Predicate<TransactionRecord> filter = tx -> transactionPath.equals(tx.path());
      int before = transactions(filter).size();
      HttpResult result = app.get(path);
      assertThat(result.status()).as("GET %s: %s", path, result.body()).isEqualTo(200);
      app.collected().awaitTransactions(filter, before + 1, WAIT);
      List<TransactionRecord> matching = transactions(filter);
      return matching.get(matching.size() - 1);
    }

    /** The first standard output line that contains {@code text}, waiting for it. */
    String awaitStdout(String text) throws InterruptedException {
      long deadline = System.nanoTime() + WAIT.toNanos();
      while (true) {
        Optional<String> line =
            app.stdoutLines().stream().filter(l -> l.contains(text)).findFirst();
        if (line.isPresent()) {
          return line.get();
        }
        assertThat(System.nanoTime() < deadline)
            .as("a standard output line of %s with %s", name, text)
            .isTrue();
        Thread.sleep(50);
      }
    }

    @Override
    public void close() {
      try {
        app.close();
      } catch (Exception e) {
        // best effort
      }
      if (collector != null) {
        collector.close();
      }
      extStub.close();
      deleteTree(root);
    }
  }

  /** A fresh tag for one request ({@code [A-Za-z0-9-]}, printed in its log line). */
  static String tag(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 13);
  }

  /** The 16 lower-case hex digits of a record's decimal {@code tx_id}. */
  static String txHex(TransactionRecord tx) {
    String hex = Long.toHexString(Long.parseUnsignedLong(tx.txId()));
    return "0".repeat(16 - hex.length()) + hex;
  }

  /** The value of {@code pattern}'s first group in {@code line}, or null. */
  static String group(Pattern pattern, String line) {
    Matcher matcher = pattern.matcher(line);
    return matcher.find() ? matcher.group(1) : null;
  }

  /** {@code capture.user-id.mode: hashed} of {@code userId} with {@link #SALT} (16 §6.3). */
  static String hashed(String userId) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SALT, "HmacSHA256"));
    byte[] digest = mac.doFinal(userId.getBytes(StandardCharsets.UTF_8));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
  }

  private static byte[] randomSalt() {
    byte[] salt = new byte[32];
    new SecureRandom().nextBytes(salt);
    return salt;
  }

  static void deleteTree(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var files = Files.walk(root)) {
      for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          // left behind (Windows may still hold a file)
        }
      }
    } catch (IOException e) {
      // nothing more to do
    }
  }
}
