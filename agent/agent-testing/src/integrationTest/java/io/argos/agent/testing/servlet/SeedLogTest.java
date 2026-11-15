/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.servlet;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static io.argos.agent.testing.servlet.ServletRuns.path;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.servlet.ServletRuns.Combo;
import io.argos.agent.testing.servlet.ServletRuns.Profile;
import io.argos.agent.testing.servlet.ServletRuns.Run;
import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.ShopCatalog;
import io.argos.sample.common.seed.Seeds;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T-SEC-26b (SEC-26, SEC-32, 18 §4.3, 10 §14, 16 §6.3), agent side of the request path: a partial
 * check of T-SEC-26 (ADR-0010 C-122; 18 §10 defines T-SEC-26 as no sentinel in the agent log after
 * 10,000 INFO requests; the storage and server-log scans are WO-46, the MDC and batch paths WO-18).
 *
 * <p>The seed requests of {@code test/seed/seed-traffic.sh} (every {@code seed.*} entry of the shop
 * catalog: e-mail, phone, resident and card numbers in query strings, headers and exception
 * messages, user ids, spoofed {@code X-Forwarded-For}, token-shaped headers, XSS and CSV payloads
 * in paths, query strings and headers) and one request that feeds seed values to every capture
 * input (session and {@code X-User-Id} user id, the whitelisted {@code X-Test-Header}, {@code
 * X-Request-Id}, {@code X-Forwarded-For}, the query string) run in three agent configurations, so
 * that every capture path of 16 §6.1-§6.4 reads seed values:
 *
 * <ul>
 *   <li>{@code DEFAULTS} (every shop jar app): the privacy defaults record none of them.
 *   <li>{@code CAPTURE} (every shop jar app, TLS FakeCollector with the user id salt {@link
 *       ServletRuns#SALT}): the record carries the hashed seed user id, the header and the request
 *       id.
 *   <li>{@code MODES} (first shop jar app): the record carries the plain seed user id and the full
 *       query string with the seed phone numbers.
 * </ul>
 *
 * <p>Then the INFO agent log of the run (every request the suite sent to it) has no ERROR, no
 * {@value Seeds#SENTINEL} or {@code ARGOSSEED} (the access-key seed has no underscore), none of the
 * seed PII values and their sentinel-free patterns, no user id in plain or hashed form, no header
 * or request id value, and nothing of the salt (SEC-32: the salt lives in memory only and is never
 * logged; hex, Base64, Base64url, Java array text). The application's console carries the app's own
 * seed output (exception messages) but none of the values only the agent knows: the salt, the
 * hashed user ids, the header and request id values. Failure messages name the value and the line,
 * never the value itself.
 */
@Tag("req:SEC-26")
@Tag("req:SEC-32")
@ExtendWith(ServletRuns.class)
class SeedLogTest {
  /** The sentinel of the access-key seed ({@code AKIAARGOSSEED0000001}, sample-apps README). */
  static final String SENTINEL_WITHOUT_UNDERSCORE = "ARGOSSEED";

  /** Value of the whitelisted {@code X-Test-Header} (CAPTURE) that only this test knows. */
  static final String HEADER_VALUE = "hdr-wo12-" + randomHex();

  /** Value of the request id header {@code X-Request-Id} (CAPTURE) that only this test knows. */
  static final String REQUEST_ID = "rid-wo12-" + randomHex();

  /** The query string of the capture request: a phone seed and the sentinel-free phone shape. */
  static final String QUERY = "page=0&phone=" + Seeds.PHONE + "&mobile=" + Seeds.PHONE_PATTERN;

  @TestFactory
  @DisplayName(
      "T-SEC-26b seed requests leave no PII, user id, header value or salt in the agent log"
          + " (shop jar apps)")
  Stream<DynamicTest> seedTraffic() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : ServletRuns.jarCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-SEC-26b "
                  + combo
                  + " privacy defaults: seed requests (PII, tokens, XSS, CSV) leave no ARGOS_SEED"
                  + " or ARGOSSEED and no seed value in the INFO agent log",
              () -> seeds(ServletRuns.run(combo, Profile.DEFAULTS))));
    }
    // Profile-major order: the DEFAULTS runs of every combination first, then CAPTURE (fewer
    // restarts of the shared runs, ServletRuns.MAX_ALIVE).
    for (Combo combo : ServletRuns.jarCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-SEC-26b "
                  + combo
                  + " widened over gRPC with a salt: the hashed session user id, header and request"
                  + " id read seed values; no seed, user id, header value or salt in the agent log"
                  + " (SEC-32)",
              () -> seeds(ServletRuns.run(combo, Profile.CAPTURE))));
    }
    for (Combo combo : ServletRuns.firstJarCombo()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-SEC-26b "
                  + combo
                  + ": query string full and the plain header user id read seed values; none of"
                  + " them in the agent log",
              () -> seeds(ServletRuns.run(combo, Profile.MODES))));
    }
    return tests.stream();
  }

  static void seeds(Run run) throws Exception {
    int before = run.settle();
    int calls = 0;
    for (CatalogEntry entry : ShopCatalog.entries()) {
      if (!entry.id().startsWith("seed.") || entry.requires() != null) {
        continue;
      }
      HttpResult result = run.call(entry.method(), entry.path(), entry.headers());
      assertThat(result.status())
          .as("%s %s", entry.id(), entry.path())
          .isEqualTo(entry.expectedStatus());
      calls++;
    }
    assertThat(calls).as("seed entries of the shop catalog").isGreaterThan(40);
    run.newTransactions(tx -> true, before, calls);
    TransactionRecord captured = captureInputs(run);

    String log = run.app.agentLog();
    assertThat(log).as("agent log of %s", run.name).isNotEmpty();
    assertThatAgentLog(log)
        .hasStartBanner()
        .hasNoErrors()
        .doesNotContainSeedSentinel()
        .doesNotContainText(SENTINEL_WITHOUT_UNDERSCORE);
    List<String> logLines = log.lines().toList();
    for (Map.Entry<String, String> value : seedValues().entrySet()) {
      assertAbsent("the agent log of " + run.name, logLines, value.getKey(), value.getValue());
    }
    List<String> console = new ArrayList<>(run.app.stdoutLines());
    console.addAll(run.app.stderrLines());
    Map<String, String> agentOnly = agentOnlyValues();
    for (Map.Entry<String, String> value : agentOnly.entrySet()) {
      assertAbsent("the agent log of " + run.name, logLines, value.getKey(), value.getValue());
      assertAbsent("the console of " + run.name, console, value.getKey(), value.getValue());
    }
    System.out.println(
        "[wo12] T-SEC-26b "
            + run.name
            + ": "
            + calls
            + " seed requests and the capture request (user id "
            + (captured.userId() == null ? "none" : "recorded")
            + ", "
            + captured.headers().size()
            + " header(s), request id "
            + (captured.requestId() == null ? "none" : "recorded")
            + "); agent log clean of the sentinels, "
            + seedValues().size()
            + " seed values and "
            + agentOnly.size()
            + " agent-only values (console too)");
  }

  /**
   * Feeds seed values to every capture input: {@code GET /seed/user} stores the seed user id in a
   * new session (its cookie is kept), then {@code GET /api/orders?<QUERY>} carries the cookie, the
   * seed user id in {@code X-User-Id}, the whitelisted header, the request id and a seed {@code
   * X-Forwarded-For}. Returns the second request's record after checking that the profile's capture
   * modes read what they should (16 §6.1-§6.4).
   */
  static TransactionRecord captureInputs(Run run) throws Exception {
    HttpResult login = run.call("GET", "/seed/user", Map.of("X-User-Id", Seeds.USER_ID));
    assertThat(login.status()).as("GET /seed/user").isEqualTo(200);
    String cookie = CaptureTest.sessionCookie(login);
    assertThat(cookie).as("JSESSIONID of %s", login.headers()).isNotNull();
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Cookie", cookie);
    headers.put("X-User-Id", Seeds.USER_ID);
    headers.put("X-Test-Header", HEADER_VALUE);
    headers.put("Accept-Language", "ko-KR");
    headers.put("X-Request-Id", REQUEST_ID);
    headers.put("X-Forwarded-For", Seeds.FORWARDED_FOR + ", 127.0.0.1");
    TransactionRecord tx = CaptureTest.request(run, "/api/orders?" + QUERY, headers);
    switch (run.profile) {
      case DEFAULTS -> {
        assertThat(tx.userId()).as("user id off: %s", tx).isNull();
        assertThat(tx.headers()).as("no header whitelist: %s", tx).isEmpty();
        assertThat(tx.requestId()).as("no request id header: %s", tx).isNull();
        assertThat(tx.path()).as("query string off: %s", tx).isEqualTo("/api/orders");
      }
      case CAPTURE -> {
        assertThat(tx.userId())
            .as("hashed session user id with the collector's salt: %s", tx)
            .isEqualTo(CaptureTest.hashed(Seeds.USER_ID));
        assertThat(tx.headers()).as(tx.toString()).containsEntry("X-Test-Header", HEADER_VALUE);
        assertThat(tx.requestId()).as(tx.toString()).isEqualTo(REQUEST_ID);
        assertThat(tx.path()).as("query string whitelist: %s", tx).isEqualTo("/api/orders?page=0");
      }
      case MODES -> {
        assertThat(tx.userId()).as("plain user id from X-User-Id: %s", tx).isEqualTo(Seeds.USER_ID);
        assertThat(tx.path()).as("query string full: %s", tx).isEqualTo("/api/orders?" + QUERY);
      }
      default -> throw new IllegalStateException("no capture expectations for " + run.profile);
    }
    assertThat(run.transactions(path("/seed/user"))).isNotEmpty();
    return tx;
  }

  /** Seed PII and secrets of 18 §4.3 (sentinel or not) by what they are. */
  static Map<String, String> seedValues() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("the e-mail seed", Seeds.EMAIL);
    values.put("the phone seed", Seeds.PHONE);
    values.put("the phone pattern", Seeds.PHONE_PATTERN);
    values.put("the resident number seed", Seeds.RESIDENT_NUMBER);
    values.put("the resident number pattern", Seeds.RESIDENT_NUMBER_PATTERN);
    values.put("the card seed", Seeds.CARD);
    values.put("the card pattern", Seeds.CARD_PATTERN);
    values.put("the plain seed user id", Seeds.USER_ID);
    values.put("the X-Forwarded-For seed", Seeds.FORWARDED_FOR);
    values.put("the JWT-shaped seed", Seeds.JWT_LIKE);
    values.put("the access-key seed", Seeds.ACCESS_KEY_LIKE);
    return values;
  }

  /**
   * Values only the agent can produce or knows: the CAPTURE collector's salt in the usual text
   * forms, the hashed user ids of the seed requests (the session user ids of {@code seed.user},
   * {@code seed.xss.user.*} and {@code seed.csv.*}, and the HMAC in hex), the header and request id
   * values of the capture request.
   */
  static Map<String, String> agentOnlyValues() throws Exception {
    Map<String, String> values = new LinkedHashMap<>();
    byte[] salt = ServletRuns.SALT;
    values.put("the salt in hex", HexFormat.of().formatHex(salt));
    values.put("the salt in upper-case hex", HexFormat.of().withUpperCase().formatHex(salt));
    values.put("the salt in Base64", Base64.getEncoder().withoutPadding().encodeToString(salt));
    values.put(
        "the salt in Base64url", Base64.getUrlEncoder().withoutPadding().encodeToString(salt));
    values.put("the salt as a Java array", Arrays.toString(salt));
    values.put("the hashed seed user id", CaptureTest.hashed(Seeds.USER_ID));
    values.put(
        "the HMAC of the seed user id in hex", HexFormat.of().formatHex(hmac(Seeds.USER_ID)));
    for (int i = 0; i < Seeds.XSS.size(); i++) {
      values.put("the hashed XSS user id " + i, CaptureTest.hashed(Seeds.XSS.get(i)));
    }
    for (int i = 0; i < Seeds.CSV.size(); i++) {
      values.put("the hashed CSV user id " + i, CaptureTest.hashed(Seeds.CSV.get(i)));
    }
    values.put("the whitelisted header value", HEADER_VALUE);
    values.put("the request id header value", REQUEST_ID);
    return values;
  }

  /**
   * Fails when a line of {@code lines} contains {@code value}; the message names {@code what} and
   * the line number, never the value or the line (11 §11: a secret must not reach the test output).
   */
  static void assertAbsent(String where, List<String> lines, String what, String value) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(value)) {
        throw new AssertionError(
            where
                + " contains "
                + what
                + " ("
                + value.length()
                + " characters, not printed) on line "
                + (i + 1));
      }
    }
  }

  private static byte[] hmac(String userId) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(ServletRuns.SALT, "HmacSHA256"));
    return mac.doFinal(userId.getBytes(StandardCharsets.UTF_8));
  }

  private static String randomHex() {
    byte[] bytes = new byte[8];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
