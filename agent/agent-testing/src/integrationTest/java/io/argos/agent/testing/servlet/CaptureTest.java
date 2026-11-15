/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.servlet;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static io.argos.agent.testing.servlet.ServletRuns.byReason;
import static io.argos.agent.testing.servlet.ServletRuns.path;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.agent.testing.servlet.ServletRuns.Combo;
import io.argos.agent.testing.servlet.ServletRuns.Profile;
import io.argos.agent.testing.servlet.ServletRuns.Run;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
 * Privacy modes of the request data the agent sends (16 §6.1-§6.4, 10 §15, SEC-31, SEC-32, SEC-34,
 * SEC-37, ADR-0010 C-124, C-125, C-126); the storage, /24 search and deletion parts of the T-SEC
 * IDs are the server's (WO-21, WO-23, WO-28):
 *
 * <ul>
 *   <li>Privacy defaults (every shop jar app, JSONL): IP {@code masked} ({@code 127.0.0.0}), a
 *       spoofed {@code X-Forwarded-For} ignored without trusted proxies (SEC-37), no user id
 *       although the request carries one (SEC-32 {@code off}), the query string removed from the
 *       path (SEC-34: {@code ?phone=} nowhere in the record), the service name without it.
 *   <li>Widened (every shop jar app, gRPC to a TLS FakeCollector with a salt): query string
 *       whitelist ({@code page} only), IP {@code full}, trusted proxy 127.0.0.1/32 so the
 *       right-most untrusted {@code X-Forwarded-For} hop is the client and an unparsable one falls
 *       back to the socket, user id {@code hashed} from the session attribute ({@code
 *       base64url(HMAC-SHA256(salt, id))[0:16]}, 16 §6.3), the two whitelisted headers only (value
 *       cut at 1 KiB), the request id header (C-124, cut at 128 bytes).
 *   <li>Other modes (first shop jar app, JSONL): query string {@code full}, IP {@code off}, user id
 *       {@code plain} from the {@code X-User-Id} header (cut at 128 characters).
 *   <li>Limits (first shop jar app, gRPC, no salt as from the server until WO-26/WO-28): {@code
 *       hashed} sends no user id and never the plain value, counts {@code
 *       argos_agent_user_id_skipped_total{reason="no_salt"}} and WARNs once (C-125); headers and
 *       request id are off by default on the wire.
 * </ul>
 */
@Tag("req:SEC-31")
@Tag("req:SEC-32")
@Tag("req:SEC-34")
@Tag("req:SEC-37")
@ExtendWith(ServletRuns.class)
class CaptureTest {
  static final String PHONE = "010-9999-0001";
  static final String USER = "user-7";
  static final String SPOOFED = "203.0.113.7";

  @TestFactory
  @DisplayName(
      "T-AG-014/T-AG-017/T-SEC-31/T-SEC-32/T-SEC-34/T-SEC-37 privacy modes of the request data"
          + " (shop jar apps)")
  Stream<DynamicTest> capture() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : ServletRuns.jarCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-014/T-AG-017/T-SEC-31/T-SEC-32/T-SEC-34/T-SEC-37 "
                  + combo
                  + " privacy defaults: IP masked, spoofed X-Forwarded-For ignored, no user id, no"
                  + " query string",
              () -> defaults(ServletRuns.run(combo, Profile.DEFAULTS))));
    }
    // Profile-major order: the DEFAULTS runs of every combination first, then CAPTURE (fewer
    // restarts of the shared runs, ServletRuns.MAX_ALIVE).
    for (Combo combo : ServletRuns.jarCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-014/T-AG-017 "
                  + combo
                  + " widened over gRPC: query whitelist, IP full, trusted X-Forwarded-For, hashed"
                  + " session user id, header whitelist (1 KiB), request id (128 B)",
              () -> widened(ServletRuns.run(combo, Profile.CAPTURE))));
    }
    for (Combo combo : ServletRuns.firstJarCombo()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-014/T-AG-017 "
                  + combo
                  + ": query string full, IP off, plain user id from a header (128 characters)",
              () -> otherModes(ServletRuns.run(combo, Profile.MODES))));
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-017 "
                  + combo
                  + " over gRPC without salt: hashed sends no user id, counts no_salt, WARNs once;"
                  + " headers and request id off by default",
              () -> noSalt(ServletRuns.run(combo, Profile.LIMITS))));
    }
    return tests.stream();
  }

  /** One request and its single new transaction. */
  static TransactionRecord request(Run run, String requestPath, Map<String, String> headers)
      throws Exception {
    String bare = ServletRuns.bare(requestPath);
    int before = run.settle();
    int pathBefore = run.transactions(path(bare)).size();
    HttpResult result = run.call("GET", requestPath, headers);
    assertThat(result.status()).as("GET %s", requestPath).isEqualTo(200);
    List<TransactionRecord> added = run.newTransactions(path(bare), pathBefore, 1);
    assertThat(added).as("GET %s%n%s", requestPath, run.describe()).hasSize(1);
    assertThat(run.transactions().size() - before).isEqualTo(1);
    return added.get(0);
  }

  static void defaults(Run run) throws Exception {
    TransactionRecord user =
        request(
            run,
            "/api/user?id=42",
            Map.of(
                "X-User-Id",
                USER,
                "X-Forwarded-For",
                SPOOFED,
                "X-Test-Header",
                "header-value-wo12",
                "X-Request-Id",
                "req-wo12-default"));
    assertThat(user.remoteIp())
        .as("SEC-31 masked, SEC-37 socket address: %s", user)
        .isEqualTo("127.0.0.0");
    assertThat(user.userId()).as("SEC-32 off: %s", user).isNull();
    assertThat(user.headers()).as("no header whitelist by default").isEmpty();
    assertThat(user.requestId()).as("C-124: no request id header by default").isNull();
    assertThat(user.path()).isEqualTo("/api/user");
    assertThat(user.service()).isEqualTo("GET /api/user");
    assertThat(user.line())
        .doesNotContain(USER, SPOOFED, "header-value-wo12", "req-wo12-default", "id=42");

    TransactionRecord orders = request(run, "/api/orders?page=0&phone=" + PHONE, Map.of());
    assertThat(orders.path()).as("SEC-34 off: %s", orders).isEqualTo("/api/orders");
    assertThat(orders.service()).isEqualTo("GET /api/orders");
    assertThat(orders.line()).doesNotContain("phone").doesNotContain(PHONE).doesNotContain("page=");
    assertThat(run.transactions())
        .as("SEC-34: no service name carries a query string")
        .allMatch(tx -> tx.service() == null || !tx.service().contains("?"));
  }

  static void widened(Run run) throws Exception {
    assertThat(run.collector).isNotNull();
    // The first request creates the session (the user id source); the second carries its cookie.
    HttpResult first = run.get("/api/user?id=42");
    assertThat(first.status()).isEqualTo(200);
    String cookie = sessionCookie(first);
    assertThat(cookie).as("JSESSIONID of %s", first.headers()).isNotNull();
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Cookie", cookie);
    headers.put("X-Forwarded-For", SPOOFED + ", 127.0.0.1");
    headers.put("X-Test-Header", "abc");
    headers.put("X-Other-Header", "not-whitelisted");
    headers.put("Accept-Language", "ko-KR");
    headers.put("X-Request-Id", "req-wo12-1");
    TransactionRecord user = request(run, "/api/user?id=42&page=3", headers);
    assertThat(user.path()).as("SEC-34 whitelist: %s", user).isEqualTo("/api/user?page=3");
    assertThat(user.service()).isEqualTo("GET /api/user");
    assertThat(user.remoteIp())
        .as("SEC-37: right-most untrusted X-Forwarded-For hop, full mode: %s", user)
        .isEqualTo(SPOOFED);
    assertThat(user.userId()).as("SEC-32 hashed (16 §6.3): %s", user).isEqualTo(hashed("42"));
    Map<String, String> recorded = user.headers();
    assertThat(recorded)
        .as("header whitelist: %s", user)
        .containsEntry("X-Test-Header", "abc")
        .containsEntry("Accept-Language", "ko-KR")
        .doesNotContainKey("X-Other-Header")
        .doesNotContainKey("Cookie")
        .hasSize(2);
    assertThat(user.requestId()).as("C-124: %s", user).isEqualTo("req-wo12-1");

    String longValue = "a".repeat(1500);
    String longId = "r".repeat(200);
    TransactionRecord caps =
        request(
            run,
            "/api/orders?page=0&phone=" + PHONE,
            Map.of(
                "X-Test-Header", longValue,
                "X-Request-Id", longId,
                "X-Forwarded-For", "203.0.113.ARGOS_SEED"));
    assertThat(caps.path()).isEqualTo("/api/orders?page=0");
    assertThat(caps.line()).doesNotContain(PHONE);
    assertThat(caps.headers().get("X-Test-Header"))
        .as("1 KiB value cap")
        .isEqualTo("a".repeat(1024));
    assertThat(caps.requestId()).as("C-124: 128 B cap").isEqualTo("r".repeat(128));
    assertThat(caps.remoteIp())
        .as("unparsable X-Forwarded-For: the socket address: %s", caps)
        .isEqualTo("127.0.0.1");
    assertThat(caps.userId()).as("no session: no user id").isNull();
    assertThat(run.transactions())
        .allMatch(tx -> tx.service() == null || !tx.service().contains("?"));
  }

  static void otherModes(Run run) throws Exception {
    TransactionRecord orders = request(run, "/api/orders?page=0&phone=" + PHONE, Map.of());
    assertThat(orders.path())
        .as("SEC-34 full: %s", orders)
        .isEqualTo("/api/orders?page=0&phone=" + PHONE);
    assertThat(orders.service()).as("never in the service name").isEqualTo("GET /api/orders");

    TransactionRecord user =
        request(run, "/api/user?id=42", Map.of("X-User-Id", USER, "X-Forwarded-For", SPOOFED));
    assertThat(user.userId()).as("SEC-32 plain from header: %s", user).isEqualTo(USER);
    String ip = user.remoteIp();
    assertThat(ip == null || ip.equals("0.0.0.0") || ip.equals("::"))
        .as("SEC-31 off (16 §6.2: 0.0.0.0, or no address at all): %s", user)
        .isTrue();
    // The socket address appears only in Tomcat's thread name (http-nio-127.0.0.1-<port>-exec-N).
    assertThat(user.line()).doesNotContain(SPOOFED).doesNotContain("\"remote_ip\":\"127.");

    String longUser = "u".repeat(200);
    TransactionRecord cut = request(run, "/api/user?id=43", Map.of("X-User-Id", longUser));
    assertThat(cut.userId()).as("128-character cap").isEqualTo("u".repeat(128));
  }

  static void noSalt(Run run) throws Exception {
    assertThat(run.collector).isNotNull();
    SelfDiagnostics before = run.freshSelfDiagnostics();
    long skippedBefore =
        byReason(before, "argos_agent_user_id_skipped_total").getOrDefault("no_salt", 0L);
    int requests = 3;
    for (int i = 0; i < requests; i++) {
      TransactionRecord user =
          request(
              run,
              "/api/user?id=" + (50 + i),
              Map.of(
                  "X-User-Id", USER,
                  "X-Test-Header", "abc",
                  "X-Request-Id", "req-wo12-limits"));
      assertThat(user.userId()).as("C-125: no salt, no user id: %s", user).isNull();
      assertThat(user.line()).as("never the plain user id").doesNotContain(USER);
      assertThat(user.headers()).as("no header whitelist by default").isEmpty();
      assertThat(user.requestId()).as("C-124 off by default").isNull();
      assertThat(user.remoteIp()).as("C-126 masked by default").isEqualTo("127.0.0.0");
    }
    SelfDiagnostics after =
        run.awaitCounter("argos_agent_user_id_skipped_total", skippedBefore + requests);
    assertThat(
            byReason(after, "argos_agent_user_id_skipped_total").getOrDefault("no_salt", 0L)
                - skippedBefore)
        .as("argos_agent_user_id_skipped_total{reason=\"no_salt\"} %s", after.counters())
        .isGreaterThanOrEqualTo(requests);
    assertThatAgentLog(run.app.agentLog()).hasWarningCount("salt", 1).hasNoErrors();
  }

  /** {@code JSESSIONID=...} of a response's {@code Set-Cookie}, or null. */
  static String sessionCookie(HttpResult result) {
    for (Map.Entry<String, List<String>> header : result.headers().entrySet()) {
      if (header.getKey().equalsIgnoreCase("set-cookie")) {
        for (String value : header.getValue()) {
          if (value.startsWith("JSESSIONID=")) {
            int end = value.indexOf(';');
            return end < 0 ? value : value.substring(0, end);
          }
        }
      }
    }
    return null;
  }

  /** {@code base64url(HMAC-SHA256(salt, userId))[0:16]} with the CAPTURE collector's salt. */
  static String hashed(String userId) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(ServletRuns.SALT, "HmacSHA256"));
    byte[] digest = mac.doFinal(userId.getBytes(StandardCharsets.UTF_8));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
  }
}
