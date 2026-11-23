/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.AgentEventInfo;
import io.argos.agent.testing.collector.CommandOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.RunningApp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The command round trip against a {@link FakeCollector} (WO-17, 10 §12.1, 11 §8.2, SEC-24 agent
 * side): one shop-boot3 (first {@code shop-boot3@N} of {@code -Pagent.e2e}, platform threads) with
 * the release agent jar and an {@code argos-agent.yml}, kept for all tests of the class, in order:
 *
 * <ol>
 *   <li>all five command types answer {@code OK} with their content type; {@code THREAD_STACK} of
 *       the {@code tx_id} found in the dump; {@code CONFIG_RELOAD} after a yml edit applies a
 *       runtime key, lists a start-only key as {@code restart_required} and sends {@code AgentEvent
 *       CONFIG_FILE_RELOADED};
 *   <li>a dump of 80 blocked request threads is larger than one 256 KiB chunk and is joined;
 *   <li>cooldowns: two {@code THREAD_DUMP}s at once give one {@code COOLDOWN}, {@code ENV_DUMP} and
 *       {@code CONFIG_RELOAD} again within 60 s give {@code COOLDOWN};
 *   <li>invalid {@code THREAD_STACK} arguments and a transaction that is not running are {@code
 *       FAILED} with the reason only; unknown type numbers are {@code REJECTED} (closed list);
 *   <li>the TTL runs from the agent's receipt: a command whose {@code issued_at_ms} is two minutes
 *       old with {@code ttl_s} 1 still runs ({@code EXPIRED} for a command that waited longer than
 *       its TTL on the agent is shown by the dispatcher's unit test, see the WO-17 report).
 * </ol>
 *
 * The agent log never contains a frame, a thread name of the app or the collector token.
 */
@Tag("req:F-A12")
@Tag("req:SEC-24")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommandRoundTripTest {
  private Path work;
  private FakeCollector collector;
  private RunningApp app;
  private ExecutorService requests;
  private long lastDumpNanos;

  @BeforeAll
  void start() throws Exception {
    List<Integer> jdks = CommandRuns.shopJdks();
    Assumptions.assumeFalse(jdks.isEmpty(), "no shop-boot3@<jdk> in -Pagent.e2e");
    int jdk = jdks.get(0);
    work = Files.createTempDirectory("wo17-roundtrip-");
    requests = Executors.newFixedThreadPool(96);
    collector = FakeCollector.builder().tls(true).start();
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("sampling.profile.normal-rate", "1.0");
    app =
        CommandRuns.shop(work.resolve("app"), jdk, collector, settings)
            .agentConfigFile(true)
            .start(Duration.ofMinutes(4));
    CommandRuns.awaitEstablished(app, collector);
    assertThat(app.get("/api/orders/1").status()).isEqualTo(200);
  }

  @AfterAll
  void stop() throws Exception {
    try {
      if (app != null) {
        String log = app.agentLog();
        app.close();
        CommandRuns.keepEvidence("roundtrip-" + app.jdk(), app);
        assertThat(log).as("frames are never logged").doesNotContain("ThreadDumpTarget");
        assertThat(log).as("the token is never logged").doesNotContain(collector.token());
      }
    } finally {
      if (collector != null) {
        collector.close();
      }
      if (requests != null) {
        requests.shutdownNow();
      }
    }
  }

  private void awaitDumpCooldown() throws InterruptedException {
    long waitMs =
        CommandRuns.DUMP_COOLDOWN_MS
            - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastDumpNanos);
    if (lastDumpNanos != 0 && waitMs > 0) {
      Thread.sleep(waitMs);
    }
  }

  private CommandOutcome dump() throws Exception {
    awaitDumpCooldown();
    CommandOutcome outcome = CommandRuns.run(collector, "THREAD_DUMP", Map.of());
    lastDumpNanos = System.nanoTime();
    return outcome;
  }

  /** A dump that shows {@code expected} threads blocked in the dump-me frame (two tries). */
  private CommandOutcome dumpWithBlocked(int expected) throws Exception {
    CommandOutcome outcome = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      Thread.sleep(attempt == 0 ? 1_500L : 0L);
      outcome = dump();
      assertThat(outcome.status()).as("THREAD_DUMP: %s", outcome.error()).isEqualTo("OK");
      if (CommandRuns.threadsAt(CommandRuns.json(outcome), CommandRuns.BLOCK_FRAME).size()
          >= expected) {
        return outcome;
      }
    }
    return outcome;
  }

  @Test
  @Order(1)
  @DisplayName(
      "WO-17 command round trip: THREAD_DUMP, THREAD_STACK, SELF_DIAGNOSTICS, ENV_DUMP and"
          + " CONFIG_RELOAD answer OK with their content types; reload applies and reports"
          + " restart_required, then AgentEvent CONFIG_FILE_RELOADED")
  void allFiveTypes() throws Exception {
    CompletableFuture<HttpResult> blocked =
        CommandRuns.async(app, CommandRuns.DUMP_ME + "?ms=12000", requests);

    CommandOutcome dump = dumpWithBlocked(1);
    Map<String, Object> dumpJson = CommandRuns.json(dump);
    assertThat(dump.truncated()).isFalse();
    List<Map<String, Object>> at = CommandRuns.threadsAt(dumpJson, CommandRuns.BLOCK_FRAME);
    assertThat(at).as("the blocked request thread in the dump").hasSize(1);
    Map<String, Object> requestThread = at.get(0);
    assertThat(requestThread.get("virtual")).isEqualTo(false);
    assertThat(requestThread.get("tx_id")).as("tx_id of the running request").isNotNull();
    String txId = (String) requestThread.get("tx_id");
    assertThat(txId).matches("[0-9]{1,20}");
    System.out.println(
        "[wo17-roundtrip] THREAD_DUMP threads="
            + CommandRuns.threads(dumpJson).size()
            + " bytes="
            + dump.payload().size()
            + " source="
            + dumpJson.get("virtual_threads_source")
            + " durationMs="
            + dump.durationMs());

    CommandOutcome stack = CommandRuns.run(collector, "THREAD_STACK", Map.of("tx_id", txId));
    assertThat(stack.status()).as("THREAD_STACK: %s", stack.error()).isEqualTo("OK");
    List<Map<String, Object>> one = CommandRuns.threads(CommandRuns.json(stack));
    assertThat(one).hasSize(1);
    assertThat(one.get(0).get("id")).isEqualTo(requestThread.get("id"));
    assertThat(one.get(0).get("tx_id")).isEqualTo(txId);
    assertThat(CommandRuns.indexOf(CommandRuns.frames(one.get(0)), CommandRuns.BLOCK_FRAME))
        .isNotNegative();

    CommandOutcome self = CommandRuns.run(collector, "SELF_DIAGNOSTICS", Map.of());
    assertThat(self.status()).as("SELF_DIAGNOSTICS: %s", self.error()).isEqualTo("OK");
    Map<String, Object> selfJson = CommandRuns.json(self);
    assertThat(selfJson).containsKeys("format", "counters", "modules", "agent", "effective_config");
    assertThat(self.payloadText()).doesNotContain(collector.token());

    CommandOutcome env = CommandRuns.run(collector, "ENV_DUMP", Map.of());
    assertThat(env.status()).as("ENV_DUMP: %s", env.error()).isEqualTo("OK");
    assertThat(env.contentType()).startsWith("text/plain");
    assertThat(env.payloadText())
        .contains("# env (")
        .contains("# properties (")
        .contains("# args (");
    assertThat(env.payloadText()).doesNotContain(collector.token());

    // CONFIG_RELOAD after a yml edit: a key applied at run time and a key read only at start.
    Path yml = app.workDir().resolve("argos-agent.yml");
    assertThat(yml).exists();
    Files.writeString(
        yml,
        "\ntransaction:\n  max-duration-ms: '123000'\nbuffer:\n  max-age-seconds: '45'\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);
    int eventsBefore = collector.agentEvents("CONFIG_FILE_RELOADED").size();
    CommandOutcome reload = CommandRuns.run(collector, "CONFIG_RELOAD", Map.of());
    assertThat(reload.status()).as("CONFIG_RELOAD: %s", reload.error()).isEqualTo("OK");
    Map<String, Object> reloadJson = CommandRuns.json(reload);
    System.out.println("[wo17-roundtrip] CONFIG_RELOAD " + reload.payloadText());
    assertThat(String.valueOf(reloadJson.get("applied_keys")))
        .contains("transaction.max-duration-ms");
    assertThat(String.valueOf(reloadJson.get("restart_required")))
        .contains("buffer.max-age-seconds");
    assertThat(reloadJson.get("effective_config_hash")).isInstanceOf(String.class);
    List<AgentEventInfo> events =
        collector.awaitAgentEvents(
            "CONFIG_FILE_RELOADED", eventsBefore + 1, Duration.ofSeconds(10));
    assertThat(events).hasSizeGreaterThan(eventsBefore);

    assertThat(blocked.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
  }

  @Test
  @Order(2)
  @DisplayName(
      "WO-17 a thread dump larger than one 256 KiB chunk arrives in several chunks and is joined"
          + " into one valid JSON document")
  void multiChunkDump() throws Exception {
    int blockedRequests = 80;
    List<CompletableFuture<HttpResult>> blocked = new ArrayList<>();
    for (int i = 0; i < blockedRequests; i++) {
      blocked.add(CommandRuns.async(app, CommandRuns.DUMP_ME + "?ms=15000", requests));
    }
    CommandOutcome dump = dumpWithBlocked(blockedRequests);
    System.out.println(
        "[wo17-roundtrip] multi-chunk THREAD_DUMP chunks="
            + dump.chunks()
            + " bytes="
            + dump.payload().size()
            + " truncated="
            + dump.truncated()
            + " durationMs="
            + dump.durationMs());
    assertThat(dump.payload().size()).isGreaterThan(256 * 1024);
    assertThat(dump.chunks()).isGreaterThanOrEqualTo(2);
    Map<String, Object> json = CommandRuns.json(dump);
    assertThat(CommandRuns.threadsAt(json, CommandRuns.BLOCK_FRAME))
        .hasSizeGreaterThanOrEqualTo(blockedRequests);
    assertThat(dump.payload().size()).isLessThanOrEqualTo(1024 * 1024);
    for (CompletableFuture<HttpResult> request : blocked) {
      assertThat(request.get(60, TimeUnit.SECONDS).status()).isEqualTo(200);
    }
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SEC-24 cooldowns: two THREAD_DUMPs at once give OK and COOLDOWN; ENV_DUMP and"
          + " CONFIG_RELOAD again within 60 s give COOLDOWN")
  void cooldowns() throws Exception {
    awaitDumpCooldown();
    CompletableFuture<CommandOutcome> first = collector.command("THREAD_DUMP", Map.of());
    CompletableFuture<CommandOutcome> second = collector.command("THREAD_DUMP", Map.of());
    lastDumpNanos = System.nanoTime();
    List<String> statuses =
        List.of(
            first.get(30, TimeUnit.SECONDS).status(), second.get(30, TimeUnit.SECONDS).status());
    assertThat(statuses).containsExactlyInAnyOrder("OK", "COOLDOWN");
    CommandOutcome cooled = first.get().status().equals("COOLDOWN") ? first.get() : second.get();
    assertThat(cooled.payload().isEmpty()).isTrue();

    CommandOutcome env = CommandRuns.run(collector, "ENV_DUMP", Map.of());
    assertThat(env.status()).as("ENV_DUMP within 60 s of the last").isEqualTo("COOLDOWN");
    CommandOutcome reload = CommandRuns.run(collector, "CONFIG_RELOAD", Map.of());
    assertThat(reload.status()).as("CONFIG_RELOAD within 60 s of the last").isEqualTo("COOLDOWN");
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-SEC-24 THREAD_STACK with invalid arguments or a finished transaction is FAILED with the"
          + " reason only; unknown command type numbers are REJECTED")
  void failuresAndClosedList() throws Exception {
    CommandOutcome both =
        CommandRuns.run(collector, "THREAD_STACK", Map.of("tx_id", "1", "thread_id", "2"));
    assertThat(both.status()).isEqualTo("FAILED");
    assertThat(both.error()).contains("invalid arguments").doesNotContain("\tat ");
    Thread.sleep(1_100L);
    CommandOutcome gone = CommandRuns.run(collector, "THREAD_STACK", Map.of("tx_id", "1"));
    assertThat(gone.status()).isEqualTo("FAILED");
    assertThat(gone.error()).contains("transaction not running").doesNotContain("\tat ");

    for (int number : new int[] {0, 6, 9, 99}) {
      CommandOutcome unknown = collector.commandRaw(number, Map.of()).get(30, TimeUnit.SECONDS);
      assertThat(unknown.status()).as("type number %d", number).isEqualTo("REJECTED");
      assertThat(unknown.payload().isEmpty()).isTrue();
    }
  }

  @Test
  @Order(5)
  @DisplayName(
      "WO-17 the TTL runs from the agent's receipt: an old issued_at_ms with ttl_s 1 still runs")
  void ttlFromReceipt() throws Exception {
    CommandOutcome old =
        collector
            .command("SELF_DIAGNOSTICS", Map.of(), 1, System.currentTimeMillis() - 120_000L)
            .get(30, TimeUnit.SECONDS);
    assertThat(old.status()).as("SELF_DIAGNOSTICS: %s", old.error()).isEqualTo("OK");
  }
}
