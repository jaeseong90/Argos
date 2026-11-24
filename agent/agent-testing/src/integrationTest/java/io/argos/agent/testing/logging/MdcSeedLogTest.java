/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.logging;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.logging.LoggingRuns.Combo;
import io.argos.agent.testing.logging.LoggingRuns.Run;
import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.ShopCatalog;
import io.argos.sample.common.seed.Seeds;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-26 (SEC-26, SEC-32, 18 §10), the MDC path of WO-18 (ADR-0010 C-122: the request path is
 * {@code servlet.SeedLogTest}, the batch path {@code batch.BatchSeedLogTest}): the first shop jar
 * app of {@code -Pagent.e2e} at the default INFO level with the logging module on and {@code
 * capture.user-id.source: mdc:user} ({@code plain}) gets 10,000 requests: every seed request of the
 * shop catalog ({@code seed.*}: PII in query strings, headers and messages, XSS and CSV payloads),
 * {@code /api/mdc-log} with seed values in the {@code user} MDC key (plain, after {@code
 * MDC.clear()}, from the app executor), and ordinary traffic. Then the agent log has no ERROR, no
 * {@value Seeds#SENTINEL} or {@code ARGOSSEED} and none of the seed values; the seed user ids
 * reached the records (the path read them) but never the log.
 */
@Tag("req:SEC-26")
@Tag("req:SEC-32")
class MdcSeedLogTest {
  static final int REQUESTS = 10_000;

  @Test
  @DisplayName(
      "T-SEC-26 MDC path: 10,000 INFO requests incl. the seed requests and seed user ids in the"
          + " user MDC key (mdc:user plain) leave no sentinel, seed value or ERROR in the agent log")
  void seedTrafficThroughTheMdc() throws Exception {
    Combo combo = LoggingRuns.shopCombos().get(0);
    String name = "wo18-seedlog-" + combo.app() + "-jdk" + combo.jdk();
    Map<String, String> settings =
        Map.of("capture.user-id.mode", "plain", "capture.user-id.source", "mdc:user");
    try (Run run =
        Run.start(
            name,
            SampleApp.named(combo.app()),
            combo.jdk(),
            settings,
            false,
            List.of(),
            List.of())) {
      // The seed requests of the shop catalog, one at a time, with their expected status.
      int seedEntries = 0;
      for (CatalogEntry entry : ShopCatalog.entries()) {
        if (!entry.id().startsWith("seed.") || entry.requires() != null) {
          continue;
        }
        HttpResult result = run.app.call(entry.method(), entry.path(), entry.headers());
        assertThat(result.status())
            .as("%s %s", entry.id(), entry.path())
            .isEqualTo(entry.expectedStatus());
        seedEntries++;
      }
      List<String> paths = new ArrayList<>();
      List<Map<String, String>> headers = new ArrayList<>();
      assertThat(seedEntries).as("seed entries of the shop catalog").isGreaterThan(40);
      List<String> seedUsers = new ArrayList<>(List.of(Seeds.USER_ID, Seeds.EMAIL, Seeds.PHONE));
      seedUsers.addAll(Seeds.XSS);
      seedUsers.addAll(Seeds.CSV);
      String[] variants = {"", "&clear=true", "&async=true"};
      int n = 0;
      for (String user : seedUsers) {
        for (String variant : variants) {
          paths.add(
              "/api/mdc-log?tag=seed-"
                  + n++
                  + variant
                  + "&user="
                  + URLEncoder.encode(user, StandardCharsets.UTF_8));
          headers.add(Map.of());
        }
      }
      int fixed = paths.size();
      for (int i = seedEntries + fixed; i < REQUESTS; i++) {
        paths.add(i % 2 == 0 ? "/api/mdc-log?tag=n" + i + variants[i % 3] : "/api/orders/1");
        headers.add(Map.of());
      }
      assertThat(seedEntries + paths.size()).isEqualTo(REQUESTS);

      AtomicInteger failed = new AtomicInteger();
      Map<Integer, Integer> statuses = send(run, paths, headers, failed);
      assertThat(failed.get()).as("requests that failed to complete").isZero();
      System.out.println(
          "[wo18-seedlog] "
              + name
              + ": "
              + REQUESTS
              + " requests ("
              + seedEntries
              + " seed entries, "
              + fixed
              + " seed user ids in the MDC), statuses "
              + statuses);

      List<String> recordedUsers =
          run.transactions(tx -> tx.userId() != null).stream().map(tx -> tx.userId()).toList();
      assertThat(recordedUsers)
          .as("the mdc:user source read the seed user ids into the records")
          .contains(Seeds.USER_ID);

      String log = run.app.agentLog();
      assertThatAgentLog(log)
          .hasStartBanner()
          .hasNoErrors()
          .doesNotContainSeedSentinel()
          .doesNotContainText("ARGOSSEED");
      List<String> lines = log.lines().toList();
      Map<String, String> values = new LinkedHashMap<>();
      values.put("the e-mail seed", Seeds.EMAIL);
      values.put("the phone seed", Seeds.PHONE);
      values.put("the phone pattern", Seeds.PHONE_PATTERN);
      values.put("the resident number pattern", Seeds.RESIDENT_NUMBER_PATTERN);
      values.put("the card pattern", Seeds.CARD_PATTERN);
      values.put("the plain seed user id", Seeds.USER_ID);
      values.put("the JWT-shaped seed", Seeds.JWT_LIKE);
      values.put("the access-key seed", Seeds.ACCESS_KEY_LIKE);
      for (int i = 0; i < Seeds.XSS.size(); i++) {
        values.put("the XSS seed " + i, Seeds.XSS.get(i));
      }
      for (Map.Entry<String, String> value : values.entrySet()) {
        for (int i = 0; i < lines.size(); i++) {
          if (lines.get(i).contains(value.getValue())) {
            throw new AssertionError(
                "the agent log of "
                    + name
                    + " contains "
                    + value.getKey()
                    + " (not printed) on line "
                    + (i + 1));
          }
        }
      }
    }
  }

  /** Sends every request (8 at a time); returns the count per status. */
  private static Map<Integer, Integer> send(
      Run run, List<String> paths, List<Map<String, String>> headers, AtomicInteger failed)
      throws Exception {
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    Map<Integer, Integer> statuses = new java.util.concurrent.ConcurrentSkipListMap<>();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < paths.size(); i++) {
        String path = paths.get(i);
        Map<String, String> requestHeaders = headers.get(i);
        futures.add(
            pool.submit(
                () -> {
                  try {
                    HttpRequest.Builder builder =
                        HttpRequest.newBuilder(URI.create(run.app.baseUrl() + path))
                            .timeout(Duration.ofSeconds(30))
                            .GET();
                    requestHeaders.forEach(builder::header);
                    HttpResponse<Void> response =
                        client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
                    statuses.merge(response.statusCode(), 1, Integer::sum);
                  } catch (Exception e) {
                    failed.incrementAndGet();
                  }
                }));
      }
      for (Future<?> future : futures) {
        future.get(5, TimeUnit.MINUTES);
      }
    } finally {
      pool.shutdownNow();
    }
    return statuses;
  }
}
