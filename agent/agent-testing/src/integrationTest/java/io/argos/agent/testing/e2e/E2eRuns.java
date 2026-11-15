/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.FileExporterReader;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.launcher.TempWatch;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared code of the sample-app end-to-end test classes ({@link SampleAppAgentTest}, {@link
 * FailSafeE2eTest}, {@link LoadLeakE2eTest}, {@link WarAppAgentTest}): the {@code
 * -Pagent.e2e=<app>@<jdk>,...} combinations (default {@code shop-boot3@21}), the release agent jar,
 * the in-process ext-stub and the launcher of one run. The classes are split by requirement because
 * JUnit gives a dynamic test the tags of its factory and class only (test/README.md §5); together
 * they start each app exactly as often as the single WO-02 class did.
 */
final class E2eRuns {
  static final Duration EXPORT_WAIT = Duration.ofSeconds(15);

  private E2eRuns() {}

  /** One app on one JDK. */
  record Combo(String app, int jdk) {
    static Combo parse(String text) {
      String[] parts = text.strip().split("@", -1);
      if (parts.length != 2) {
        throw new IllegalArgumentException("expected <app>@<jdk>, got " + text);
      }
      return new Combo(parts[0], Integer.parseInt(parts[1]));
    }

    @Override
    public String toString() {
      return app + " on JDK " + jdk;
    }
  }

  /** Every combination of {@code -Pagent.e2e}. */
  static List<Combo> combos() {
    List<Combo> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1)) {
      if (!item.isBlank()) {
        combos.add(Combo.parse(item));
      }
    }
    return combos;
  }

  /** The combinations of jar apps (Spring Boot and executable jars). */
  static List<Combo> jarCombos() {
    return combos().stream().filter(combo -> !isWar(combo.app())).toList();
  }

  /** The combinations of WAR apps (standalone Tomcat). */
  static List<Combo> warCombos() {
    return combos().stream().filter(combo -> isWar(combo.app())).toList();
  }

  /** A WAR app per its launch descriptor; an app not built for this run counts as a jar app. */
  static boolean isWar(String app) {
    String path = System.getProperty("argos.test.sampleApp." + app);
    return path != null && !path.isBlank() && SampleApp.named(app).isWar();
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  static Path releaseJar() {
    return Path.of(required("argos.test.releaseAgentJar"));
  }

  /** The in-process target of the apps' outgoing calls (WO-02 P-6). */
  static ExtStub startExtStub() throws IOException {
    return ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
  }

  /**
   * The launcher of every run: ext-stub, the given work directory. The T-BLD-006 baseline and the
   * agent run use run names of the same length: on Windows the JDK binds the wakeup pipe of every
   * NIO selector to an AF_UNIX socket file in %TEMP% (socket_<n>, deleted at once) only when that
   * path fits the AF_UNIX limit (106 bytes), so the temp path length decides whether the app
   * creates such files at all. {@link TempWatch#runDirectory} keeps the length out of the range
   * where the random digits of n decide it (a temp path of 90 bytes failed T-BLD-006 twice: only
   * 8-digit names fit there, and the agent run happened to get one).
   */
  static AppLauncher launcher(Path work, ExtStub extStub, Combo combo, String runName) {
    SampleApp app = SampleApp.named(combo.app());
    return AppLauncher.of(app, Jdk.of(combo.jdk()))
        .withEnv("SAMPLE_EXT_URL", extStub.baseUri().toString())
        .workDir(TempWatch.runDirectory(work, combo.app() + "-jdk" + combo.jdk() + runName));
  }

  /** The requests of the P-6 part (also the T-BLD-006 baseline workload). */
  static void appRequests(RunningApp running) throws IOException, InterruptedException {
    // The request set of SampleAppAgentTest.run(), in the same order, so that both runs do the
    // same work.
    assertThat(running.get("/api/orders/1").status()).isEqualTo(200);
    assertThat(running.get("/api/not-found-x").status()).isEqualTo(404);
    assertThat(running.get("/api/forward").status()).isEqualTo(200);
    assertThat(running.get("/api/error-500").status()).isEqualTo(500);
    assertThat(running.get("/api/send-error").status()).isEqualTo(503);
    assertThat(running.get("/static/app.js").status()).isEqualTo(200);
    assertThat(
            running
                .call(
                    "OPTIONS",
                    "/api/orders",
                    Map.of(
                        "Origin", "http://localhost:5173",
                        "Access-Control-Request-Method", "GET"))
                .status())
        .isEqualTo(200);
    assertThat(running.get("/api/pay/1?client=hurl").status()).isEqualTo(200);
    assertThat(running.get("/api/pay/1?client=hurl&status=500").status()).isEqualTo(502);
    assertThat(running.get("/api/pay/1?client=hurl&refuse=1").status()).isEqualTo(503);
    assertThat(running.get("/api/async/executor").status()).isEqualTo(200);
  }

  static TransactionRecord single(FileExporterReader transactions, String path) {
    List<TransactionRecord> matching = transactions.read(FileExporterReader.path(path));
    assertThat(matching).as("transactions of %s", path).hasSize(1);
    return matching.get(0);
  }

  /** An integer field of a flat JSON body ({@code "name":123}). */
  static int jsonInt(String body, String name) {
    Matcher matcher =
        Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(\\d+)").matcher(body);
    assertThat(matcher.find()).as("field %s in %s", name, body).isTrue();
    return Integer.parseInt(matcher.group(1));
  }

  /** The module list of the agent's start banner ({@code modules=[a,b]}). */
  static List<String> modules(String log) {
    Matcher matcher = Pattern.compile("modules=\\[([^\\]]*)\\]").matcher(log);
    return matcher.find() ? Arrays.asList(matcher.group(1).split(",", -1)) : List.of();
  }
}
