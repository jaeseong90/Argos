/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.TestUsers;
import io.argos.test.soak.SoakVerdicts.Firing;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The server's API as the soak run uses it (18 §11.2 "알림 오탐"): an operator signed in through the
 * login API applies the recommended template rules to an application ({@code POST
 * /api/v1/alerts/templates/apply}), reads the FIRING events of the run ({@code GET
 * /api/v1/alerts/events}) and the instances of an application at the end.
 */
final class AlertWatch {

  /** The {@code error_rate} metric of the rules. */
  static final String ERROR_RATE = "error_rate";

  /** The server self rule on the collector certificate's expiry. */
  static final String CERT_EXPIRY = "argos_server_tls_cert_expiry_days";

  /** Slack of the "expected by the load" rule: the load's intended share vs the threshold. */
  static final double SHARE_SLACK = 0.001;

  private final ApiTestClient client;
  private final TestUsers.TestUser user;

  private AlertWatch(ApiTestClient client, TestUsers.TestUser user) {
    this.client = client;
    this.user = user;
  }

  /** Signs in a new operator account (created in PostgreSQL like an installation's user). */
  static AlertWatch signIn(ServerTestEnvironment env, String baseUrl) {
    TestUsers.TestUser user = new TestUsers(env).create(Role.OPERATOR);
    AlertWatch watch = new AlertWatch(ApiTestClient.of(baseUrl), user);
    watch.login();
    return watch;
  }

  private void login() {
    ApiResponse login = client.login(user.username(), user.password());
    if (login.status() != 200) {
      throw new IllegalStateException("Operator login failed: " + login.status());
    }
  }

  /**
   * A call that signs in again once when the session has ended (401: the access and refresh cookies
   * of a run of hours or days expire, as the server load run's virtual users found).
   */
  private ApiResponse call(java.util.function.Supplier<ApiResponse> request) {
    ApiResponse r = request.get();
    if (r.status() == 401) {
      login();
      r = request.get();
    }
    return r;
  }

  /**
   * Applies every application template to {@code appId}. The server applies the recommended rules
   * to an application created by SQL on its own within a minute (TemplateService) and the batch
   * template at the first BATCH transaction, so most templates may already exist ({@code exists}):
   * the call makes sure, and the answer says which.
   */
  String applyTemplates(int appId) {
    ApiResponse r = call(() -> client.post("/api/v1/alerts/templates/apply", Map.of("app", appId)));
    if (r.status() != 200) {
      throw new IllegalStateException("Applying the templates failed: " + r.status());
    }
    return describeApplied(r.json().path("data"));
  }

  /** {@code created [..]; skipped: <reason> [..]} of an apply answer. */
  static String describeApplied(JsonNode data) {
    List<String> created = new ArrayList<>();
    for (JsonNode n : data.path("created")) {
      created.add(n.asString());
    }
    Map<String, List<String>> skipped = new java.util.TreeMap<>();
    for (JsonNode n : data.path("skipped")) {
      skipped
          .computeIfAbsent(n.path("reason").asString("?"), k -> new ArrayList<>())
          .add(n.path("key").asString("?"));
    }
    StringBuilder b = new StringBuilder("created ").append(created);
    skipped.forEach(
        (reason, keys) -> b.append("; skipped (").append(reason).append(") ").append(keys));
    return b.toString();
  }

  /** The FIRING events between {@code from} and {@code to} (every page). */
  List<Firing> firing(Instant from, Instant to, double intendedErrorShare) {
    List<Firing> out = new ArrayList<>();
    String cursor = null;
    for (int page = 0; page < 100; page++) {
      String path =
          "/api/v1/alerts/events?from="
              + enc(from.toString())
              + "&to="
              + enc(to.toString())
              + "&state=FIRING&limit=500"
              + (cursor == null ? "" : "&cursor=" + enc(cursor));
      ApiResponse r = call(() -> client.get(path));
      if (r.status() != 200) {
        throw new IllegalStateException("Reading the alert events failed: " + r.status());
      }
      JsonNode root = r.json();
      out.addAll(parse(root.path("data"), intendedErrorShare));
      JsonNode next = root.path("meta").path("next_cursor");
      if (next.isMissingNode() || next.isNull() || next.asString("").isEmpty()) {
        break;
      }
      cursor = next.asString();
    }
    return out;
  }

  /** {@code name: state} of the instances of {@code appId}. */
  String instances(int appId) {
    ApiResponse r = call(() -> client.get("/api/v1/apps/" + appId + "/instances"));
    if (r.status() != 200) {
      return "HTTP " + r.status();
    }
    // Instance names carry the host name of the machine; the published report shows an ordinal.
    List<String> parts = new ArrayList<>();
    for (JsonNode n : r.json().path("data")) {
      parts.add("instance " + (parts.size() + 1) + ": " + n.path("state").asString("?"));
    }
    return parts.isEmpty() ? "none" : String.join(", ", parts);
  }

  /**
   * The FIRING events of an events page. An {@code error_rate} event is expected by the load when
   * the mix's intended error share reaches the event's threshold (18 §7.2 bench mix: 5% of {@code
   * /api/error-500} against the WARN threshold of 5%).
   */
  static List<Firing> parse(JsonNode data, double intendedErrorShare) {
    List<Firing> out = new ArrayList<>();
    for (JsonNode e : data) {
      if (!"FIRING".equals(e.path("state").asString(""))) {
        continue;
      }
      String metric = e.path("rule").path("metric").asString("");
      double threshold = number(e.path("threshold"));
      boolean expected =
          (ERROR_RATE.equals(metric)
                  && !Double.isNaN(threshold)
                  && intendedErrorShare + SHARE_SLACK >= threshold)
              // The harness's collector certificate (TestCertificates) is valid for 2 days: the
              // server self rule on certificate expiry fires by design, not by a product fault.
              || CERT_EXPIRY.equals(metric);
      JsonNode app = e.path("app");
      out.add(
          new Firing(
              e.path("ts").asString(""),
              app.isObject()
                  ? app.path("name").asString("app " + app.path("id").asInt())
                  : "server",
              metric,
              e.path("severity").asString(""),
              number(e.path("value")),
              threshold,
              expected));
    }
    return out;
  }

  private static double number(JsonNode n) {
    return n.isNumber() ? n.asDouble() : Double.NaN;
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
