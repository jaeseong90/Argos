/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.httpclient;

import static io.argos.agent.testing.httpclient.HttpClientStepsTest.assertStep;
import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.httpclient.HttpClientRuns.Call;
import io.argos.agent.testing.httpclient.HttpClientRuns.Combo;
import io.argos.agent.testing.httpclient.HttpClientRuns.Profile;
import io.argos.agent.testing.httpclient.HttpClientRuns.Run;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.SampleApp;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T-AG-055 (F-A33, DSN-10-14): Spring WebClient on Reactor Netty in a servlet app propagates at the
 * point the application waits, and a WebFlux server app gets metrics only.
 *
 * <ul>
 *   <li>shop-boot3 and shop-boot4 ({@code -Pagent.e2e}): {@code mode=block}, {@code future} ({@code
 *       toFuture().get()}) and {@code subscribe} (callbacks, the app waits on them) each give
 *       exactly one HTTP step in the request's transaction and a {@code traceparent} with its trace
 *       id at ext-stub; a read timeout in {@code future} and {@code subscribe} mode is TIMEOUT.
 *   <li>The WebFlux fixture (shop-boot3's {@code
 *       io.argos.sample.boot3.http.webflux.WebFluxFixture}: a functional WebFlux server on Reactor
 *       Netty without servlets, started from the unpacked shop-boot3 jar) with the agent: requests,
 *       including one that makes a WebClient call on the event loop, give no transaction and no
 *       propagation header, while the agent's session is established; no ERROR in the agent log.
 *       The metric batches themselves come with WO-16 (5-second collector, MetricBatch).
 * </ul>
 */
@Tag("req:F-A33")
@ExtendWith(HttpClientRuns.class)
class WebClientModesTest {

  static final String FIXTURE_MAIN = "io.argos.sample.boot3.http.webflux.WebFluxFixture";

  @TestFactory
  @DisplayName("T-AG-055 WebClient block / future / subscribe and the WebFlux app (DSN-10-14)")
  Stream<DynamicNode> modes() {
    List<DynamicNode> nodes = new ArrayList<>();
    for (Combo combo : HttpClientRuns.combosOf("shop-boot3", "shop-boot4")) {
      List<DynamicNode> tests = new ArrayList<>();
      for (String mode : List.of("block", "future", "subscribe")) {
        tests.add(
            DynamicTest.dynamicTest(
                "T-AG-055 "
                    + combo
                    + " webclient mode="
                    + mode
                    + ": one HTTP step in the request's transaction, traceparent"
                    + (mode.equals("block") ? "" : ", read timeout TIMEOUT"),
                () -> mode(HttpClientRuns.run(combo, Profile.DEFAULT), mode)));
      }
      if (combo.app().equals("shop-boot3")) {
        tests.add(
            DynamicTest.dynamicTest(
                "T-AG-055 "
                    + combo
                    + " WebFlux fixture: no transaction and no propagation header, session and"
                    + " metrics only",
                () -> webFluxApp(combo)));
      }
      nodes.add(DynamicContainer.dynamicContainer(combo.toString(), tests.stream()));
    }
    return nodes.stream();
  }

  static void mode(Run run, String mode) throws Exception {
    long id = HttpClientRuns.nextId();
    Call ok = run.call("/api/pay/" + id + "?client=webclient&mode=" + mode, 200);
    assertThat(ok.body()).containsEntry("outcome", "OK").containsEntry("mode", mode);
    assertStep(ok.onlyHttpStep("webclient " + mode), "FAILURE_NONE", 200, "webclient " + mode);
    assertThat(run.stubHeaders("/pay/" + id).get("traceparent"))
        .as("traceparent of the WebClient call in mode %s", mode)
        .isNotNull()
        .startsWith("00-" + ok.tx().traceId() + "-");
    if (!mode.equals("block")) {
      Call timeout =
          run.call(
              "/api/pay/"
                  + HttpClientRuns.nextId()
                  + "?client=webclient&mode="
                  + mode
                  + "&timeout=1",
              504);
      assertStep(
          timeout.onlyHttpStep("webclient " + mode + " timeout"),
          "TIMEOUT",
          null,
          "webclient " + mode + " timeout");
    }
    HttpClientStepsTest.record(
        run.name + " webclient " + mode,
        "OK "
            + ok.httpSteps().get(0).elapsedMs()
            + " ms"
            + (mode.equals("block") ? "" : ", TIMEOUT"));
  }

  static void webFluxApp(Combo combo) throws Exception {
    Path root = HttpClientRuns.newWorkRoot("wo14-webflux-");
    try {
      SampleApp fixture = unpackFixture(SampleApp.named("shop-boot3"), root.resolve("app"));
      Map<String, String> settings = new LinkedHashMap<>();
      settings.put("sampling.profile.normal-rate", "1.0");
      settings.put("propagation.argos-state.hosts", HttpClientRuns.HOST);
      try (Run run =
          HttpClientRuns.start(
              "webflux-fixture-jdk" + combo.jdk(),
              fixture,
              combo.jdk(),
              settings,
              Map.of(),
              root.resolve("runs"))) {
        for (int i = 0; i < 5; i++) {
          HttpResult hello = run.app.get("/api/flux/hello");
          assertThat(hello.status()).as(hello.body()).isEqualTo(200);
        }
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
          long id = HttpClientRuns.nextId();
          ids.add(id);
          HttpResult call = run.app.get("/api/flux/call/" + id);
          assertThat(call.status()).as(call.body()).isEqualTo(200);
          assertThat(call.body()).contains("\"upstreamStatus\":200");
        }
        for (long id : ids) {
          assertThat(run.stubHeaders("/flux/" + id))
              .as("no propagation header without a transaction (WebFlux server, metrics only)")
              .doesNotContainKeys("traceparent", "tracestate");
        }
        assertThat(run.collector.awaitSessions(1, Duration.ofSeconds(30)))
            .as("the agent connected")
            .isNotEmpty();
        // The metric side of "metrics only" needs the 5-second collector and the MetricBatch
        // transport of WO-16 (agent-core metric package); until then the agent sends no metric
        // batch for any app, so only the session is checked here (WO-14 integration).
        List<Collected.MetricPoint> metrics = run.collected().metrics().orElse(List.of());
        // Batches leave every 100 ms (sender.flush-interval-ms): 3 s is ample for a transaction.
        Thread.sleep(3000);
        assertThat(run.collected().transactions())
            .as("T-AG-055: a WebFlux server app gives no transaction (metrics only)")
            .isEmpty();
        assertThatAgentLog(run.app.agentLog()).hasStartBanner().hasNoErrors();
        HttpClientStepsTest.record(
            run.name,
            "0 transactions after 8 requests, "
                + metrics.size()
                + " metric samples, no traceparent on 3 WebClient calls");
      }
    } finally {
      HttpClientRuns.deleteTree(root);
    }
  }

  /**
   * Unpacks {@code BOOT-INF/classes} and {@code BOOT-INF/lib} of the shop-boot3 Boot jar into
   * {@code dir} and writes a plain launcher jar whose manifest starts {@link #FIXTURE_MAIN} with
   * that class path, so the harness starts the fixture as a {@code jar} app ({@code java -jar},
   * readiness on {@code /}). No artifact beyond shop-boot3's own.
   */
  static SampleApp unpackFixture(SampleApp boot3, Path dir) throws IOException {
    Path classes = Files.createDirectories(dir.resolve("classes"));
    Path lib = Files.createDirectories(dir.resolve("lib"));
    List<String> classPath = new ArrayList<>();
    classPath.add("classes/");
    try (JarFile jar = new JarFile(boot3.artifact().toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        Path target;
        if (name.startsWith("BOOT-INF/classes/")) {
          target = classes.resolve(name.substring("BOOT-INF/classes/".length())).normalize();
          if (!target.startsWith(classes)) {
            throw new IOException("entry outside the target directory: " + name);
          }
        } else if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
          String file = name.substring("BOOT-INF/lib/".length());
          if (file.contains("/") || file.contains("\\")) {
            throw new IOException("unexpected nested library: " + name);
          }
          target = lib.resolve(file);
          classPath.add("lib/" + file.replace(" ", "%20"));
        } else {
          continue;
        }
        if (entry.isDirectory()) {
          Files.createDirectories(target);
          continue;
        }
        Files.createDirectories(target.getParent());
        try (InputStream in = jar.getInputStream(entry)) {
          Files.copy(in, target);
        }
      }
    }
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    attributes.put(Attributes.Name.MAIN_CLASS, FIXTURE_MAIN);
    attributes.put(Attributes.Name.CLASS_PATH, String.join(" ", classPath));
    Path launcher = dir.resolve("webflux-fixture.jar");
    try (OutputStream out = Files.newOutputStream(launcher);
        JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
      jarOut.flush();
    }
    return new SampleApp("webflux-fixture", launcher, "jar", boot3.runJdk(), "none", List.of());
  }
}
