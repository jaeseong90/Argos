/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.custom;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.ConfigApplyOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.agent.testing.log.AgentLogAssert;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The custom module in a running shop app (WO-18, 10 §6.10, F-A14, SEC-25, ADR-0010 C-235 (2)),
 * with the release agent jar over gRPC to a TLS {@link FakeCollector}. {@code
 * SAMPLE_DRIVER_WRAPPER=true} puts the third-party style driver wrapper {@code example.jdbcwrapper}
 * (outside {@code io.argos.}, so a custom target) under the pool. One app per {@code
 * shop-boot3}/{@code shop-boot2} item of {@code -Pagent.e2e}:
 *
 * <ol>
 *   <li>local items: {@code example.jdbcwrapper.Wrapped*#prepare*} (active), {@code
 *       java.lang.String#*} (forbidden: ignored with a WARN, the others still work), {@code
 *       org.springframework.**#argosNoSuchMethod} (more than 500 classes at class load: disabled,
 *       WARN, self-diagnostics), {@code example.jdbcwrapper.*PreparedStatement#execute*|SQL} (a
 *       step type suffix of the earlier format: ignored alone with a WARN, ADR-0010 C-237 (5));
 *   <li>{@code GET /api/orders/1}: METHOD steps {@code example.jdbcwrapper.WrappedConnection
 *       #prepareStatement}, never nested in a step of the same name (no proxy double step);
 *   <li>a remote push with one forbidden item, and one with a step type suffix: the whole key
 *       {@code FORBIDDEN_PATTERN}, the items in force keep producing steps;
 *   <li>an allowed push without the item: retransformed, no more such steps;
 *   <li>U-20: self-diagnostics show the inferred {@code instrumentation.app-packages}.
 * </ol>
 */
@Tag("req:F-A14")
@Tag("req:SEC-25")
class CustomMethodStepsTest {
  private static final String KEY = "instrumentation.custom-methods";
  private static final String PREPARE = "example.jdbcwrapper.WrappedConnection#prepareStatement";
  private static final String SUFFIXED = "example.jdbcwrapper.*PreparedStatement#execute*|SQL";
  private static final String PATH = "/api/orders/1";
  private static final Duration WAIT = Duration.ofSeconds(25);
  private static final String DEFAULT_ALLOW =
      "sampling.**, instrumentation.disabled, instrumentation.ignore-*, log.level, enabled,"
          + " transaction.slow-stack.*, capture.error-message-max-length, capture.stack-depth,"
          + " instrumentation.app-packages, async.step, active.snapshot-interval-seconds,"
          + " metrics.interval-seconds, transaction.max-duration-ms";

  @TempDir Path work;

  /** The {@code shop-boot3} and {@code shop-boot2} items of {@code -Pagent.e2e}. */
  static Stream<String> combos() {
    List<String> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1)) {
      String text = item.strip();
      if (text.startsWith("shop-boot3@") || text.startsWith("shop-boot2@")) {
        combos.add(text);
      }
    }
    if (combos.isEmpty()) {
      combos.add("none");
    }
    return combos.stream();
  }

  @ParameterizedTest(
      name =
          "T-SEC-25 T-AG-046 {0}: custom METHOD steps, forbidden and step-type-suffix local items"
              + " ignored + WARN, pushed forbidden or suffixed item rejects the key, removal"
              + " retransformed, >500 classes disabled")
  @MethodSource("combos")
  void customMethodsInTheShop(String combo) throws Exception {
    Assumptions.assumeFalse(combo.equals("none"), "no shop-boot3/shop-boot2 item in -Pagent.e2e");
    String[] parts = combo.split("@", -1);
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      AppLauncher launcher =
          AppLauncher.of(SampleApp.named(parts[0]), Jdk.of(Integer.parseInt(parts[1])))
              .workDir(work.resolve("app"))
              .readyTimeout(Duration.ofMinutes(4))
              .withAgent(Path.of(required("argos.test.releaseAgentJar")))
              .withEnv("SAMPLE_DRIVER_WRAPPER", "true")
              .agentConfig("sampling.profile.normal-rate", "1.0")
              .agentConfig(
                  KEY,
                  "[example.jdbcwrapper.Wrapped*#prepare*, java.lang.String#*,"
                      + " org.springframework.**#argosNoSuchMethod, "
                      + SUFFIXED
                      + "]")
              .agentConfig("remote-config.allow", "[" + DEFAULT_ALLOW + ", " + KEY + "]");
      launcher.collector(collector);
      RunningApp app = launcher.start(Duration.ofMinutes(4));
      try {
        awaitEstablished(app, collector);

        // 1-2: local items, steps, no double step.
        TransactionRecord first = request(app);
        List<TransactionRecord.StepRecord> prepares = steps(first, PREPARE);
        assertThat(prepares).as("METHOD steps of %s in %s", PREPARE, first).isNotEmpty();
        assertNoDoubleStep(first);
        assertThat(first.steps("METHOD"))
            .extracting(TransactionRecord.StepRecord::name)
            .allSatisfy(name -> assertThat(name).doesNotContain("$$").doesNotContain("$Proxy"));
        assertThat(first.steps("METHOD"))
            .as("the item with a step type suffix records nothing (C-237 (5))")
            .extracting(TransactionRecord.StepRecord::name)
            .noneSatisfy(name -> assertThat(name).contains("PreparedStatement#execute"));
        await(
            () ->
                app.agentLog()
                    .contains("instrumentation.custom-methods item 3 disabled: it matches more"),
            Duration.ofSeconds(10));
        assertThat(app.agentLog())
            .contains(
                "local configuration: instrumentation.custom-methods item 2 ignored: the class"
                    + " pattern starts with the forbidden prefix java.")
            .contains(
                "instrumentation.custom-methods item 3 disabled: it matches more than 500 classes")
            .contains(
                "local configuration: instrumentation.custom-methods item 4 ignored: a step type"
                    + " suffix is not supported (custom methods always record METHOD steps)")
            .doesNotContain("java.lang.String#")
            .doesNotContain("argosNoSuchMethod")
            .doesNotContain("PreparedStatement#execute*");
        SelfDiagnostics start = selfDiagnostics(app, collector);
        assertThat(extra(start, "custom_methods_items_ignored")).isEqualTo(2);
        assertThat(extra(start, "custom_methods_items_disabled")).isEqualTo(1);
        // U-20: the inferred application packages (Start-Class of the Spring Boot jar).
        Object packages = start.member("extras.app_packages").orElse(null);
        Object source = start.member("extras.app_packages_source").orElse(null);
        System.out.println(
            "[wo18-u20] app=" + combo + " packages=" + packages + " source=" + source + " (agent)");
        assertThat(packages).isEqualTo("io.argos.sample");
        assertThat(source).isEqualTo("start_class");
        assertThat(start.member("effective_config.instrumentation.app-packages.value"))
            .contains("[io.argos.sample]");

        // 3: a pushed forbidden item rejects the whole key; the items in force keep working.
        ConfigApplyOutcome forbidden =
            push(
                collector,
                Map.of(
                    KEY,
                    "[example.jdbcwrapper.Wrapped*#prepare*, jakarta.servlet.http.HttpServlet"
                        + "#service]"));
        assertThat(forbidden.appliedKeys()).isEmpty();
        assertThat(forbidden.rejected()).containsEntry(KEY, "FORBIDDEN_PATTERN");
        assertThat(forbidden.message(KEY))
            .isEqualTo("forbidden_pattern: item 2 starts with the forbidden prefix jakarta.");
        assertThat(forbidden.retransformedClasses()).isZero();
        assertThat(steps(request(app), PREPARE))
            .as("items in force after the rejection")
            .isNotEmpty();

        // 3b: a pushed item with a step type suffix rejects the whole key the same way (C-237 (5)).
        ConfigApplyOutcome suffixed =
            push(
                collector,
                Map.of(KEY, "[example.jdbcwrapper.Wrapped*#prepare*, " + SUFFIXED + "]"));
        assertThat(suffixed.appliedKeys()).isEmpty();
        assertThat(suffixed.rejected()).containsEntry(KEY, "FORBIDDEN_PATTERN");
        assertThat(suffixed.message(KEY))
            .isEqualTo(
                "step_type_suffix: item 2 has a step type suffix (custom methods always record"
                    + " METHOD steps)");
        assertThat(suffixed.retransformedClasses()).isZero();
        assertThat(steps(request(app), PREPARE))
            .as("items in force after the step type suffix rejection")
            .isNotEmpty();

        // 4: an allowed push without the item: its classes are retransformed, no more steps.
        ConfigApplyOutcome removed = push(collector, Map.of(KEY, "[example.none.Nothing#none]"));
        assertThat(removed.appliedKeys()).containsExactly(KEY);
        assertThat(removed.retransformedClasses())
            .as("the wrapper classes the removed item matched")
            .isPositive();
        TransactionRecord after = request(app);
        assertThat(steps(after, PREPARE)).as("after the removal: %s", after).isEmpty();
        assertThat(after.steps("SQL")).as("the JDBC steps stay").isNotEmpty();
        System.out.println(
            "[wo18-custom] "
                + combo
                + " prepare_steps="
                + prepares.size()
                + " retransformed_on_removal="
                + removed.retransformedClasses());
        AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors();
      } finally {
        app.close();
        keepEvidence("custom-" + parts[0] + "-jdk" + parts[1], app);
      }
    }
  }

  /** The METHOD steps named {@code name}. */
  private static List<TransactionRecord.StepRecord> steps(TransactionRecord tx, String name) {
    return tx.steps("METHOD").stream().filter(s -> name.equals(s.name())).toList();
  }

  /** No METHOD step is the child of a METHOD step with the same name (a proxy double step). */
  private static void assertNoDoubleStep(TransactionRecord tx) {
    Map<Integer, TransactionRecord.StepRecord> bySeq = new HashMap<>();
    for (TransactionRecord.StepRecord step : tx.steps()) {
      bySeq.put(step.seq(), step);
    }
    for (TransactionRecord.StepRecord step : tx.steps("METHOD")) {
      TransactionRecord.StepRecord parent = step.parent() == null ? null : bySeq.get(step.parent());
      if (parent != null && "METHOD".equals(parent.type())) {
        assertThat(parent.name()).as("parent of %s", step).isNotEqualTo(step.name());
      }
    }
  }

  /** {@code GET /api/orders/1} (200) and its one new transaction with every step. */
  private static TransactionRecord request(RunningApp app) throws Exception {
    Predicate<TransactionRecord> filter = tx -> PATH.equals(tx.path());
    int before = app.collected().transactions().stream().filter(filter).toList().size();
    assertThat(app.get(PATH).status()).as("GET %s", PATH).isEqualTo(200);
    app.collected().awaitTransactions(filter, before + 1, WAIT);
    Thread.sleep(500);
    List<TransactionRecord> matching =
        app.collected().awaitTransactions(filter, before + 1, Duration.ofSeconds(2));
    assertThat(matching.size()).as("transactions of %s", PATH).isGreaterThan(before);
    return matching.get(matching.size() - 1);
  }

  private static void awaitEstablished(RunningApp app, FakeCollector collector)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (collector.openSessionCount() == 0) {
      assertThat(System.nanoTime() < deadline && app.isAlive())
          .as("collector session within 60 s\n%s", app.describe())
          .isTrue();
      Thread.sleep(100);
    }
  }

  private static ConfigApplyOutcome push(FakeCollector collector, Map<String, String> values)
      throws Exception {
    return collector.pushConfig(values).get(30, TimeUnit.SECONDS);
  }

  private static SelfDiagnostics selfDiagnostics(RunningApp app, FakeCollector collector)
      throws InterruptedException {
    SelfDiagnostics result = app.selfDiagnostics(collector);
    for (int i = 0; i < 5 && !result.available(); i++) {
      Thread.sleep(1_200L);
      result = app.selfDiagnostics(collector);
    }
    assertThat(result.available()).as("SELF_DIAGNOSTICS: %s", result.reason()).isTrue();
    return result;
  }

  private static long extra(SelfDiagnostics diagnostics, String name) {
    Object value = diagnostics.member("extras." + name).orElse(null);
    return value instanceof Number number ? number.longValue() : -1L;
  }

  private static void await(java.util.function.BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline && !condition.getAsBoolean()) {
      Thread.sleep(100);
    }
  }

  private static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  /** Writes the agent log and stdout to {@code build/reports/agent-e2e/wo18-<name>-*}. */
  private static void keepEvidence(String name, RunningApp app) {
    String reportDir = System.getProperty("argos.test.reportDir");
    if (reportDir == null || reportDir.isBlank()) {
      return;
    }
    try {
      Path dir = Files.createDirectories(Path.of(reportDir));
      Files.writeString(
          dir.resolve("wo18-" + name + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
      Files.write(
          dir.resolve("wo18-" + name + "-stdout.log"), app.stdoutLines(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.out.println("[wo18-custom] cannot keep the logs of " + name + ": " + e);
    }
  }
}
