/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.xlog;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.api.transactions.TxApiFixture;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.QueryDataset;
import io.argos.server.testing.querydata.QueryTestData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * XLog (14 §4.5) on the shared generator data (tiny: about 130,000 transactions per application in
 * 43 minutes): deterministic sampling above 100,000 points (T-API-006, A-5), the columns, gzip
 * (DSN-14-08), {@code truncated} when the points that satisfy the sampling rule exceed the limit
 * (14 §2.5), and the summary of a dragged rectangle.
 */
@Tag("integration")
@Tag("req:F-U02")
class XlogIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Hand-made application of the outage test (this lane: 950..989). */
  private static final int OUTAGE_APP = 985;

  /** Hand-made application of the sampling overflow test. */
  private static final int SAMPLING_APP = 986;

  private static ApiTestClient viewer;
  private static QueryDataset.App busiest;
  private static long busiestTotal;
  private static Instant from;
  private static Instant to;

  @BeforeAll
  static void start() {
    QueryDataset data = TxApiFixture.shared().dataset();
    viewer = TxApiFixture.client(Role.VIEWER);
    // The generator's rows lie in [start - 1 min, end + 1 min] (late and clamped ones); one day.
    from = data.start().minus(Duration.ofMinutes(5));
    to = data.end().plus(Duration.ofMinutes(5));
    long best = -1;
    for (QueryDataset.App app : data.apps()) {
      long n = TxApiFixture.count(httpCount(app.appId(), ""));
      if (n > best) {
        best = n;
        busiest = app;
      }
    }
    busiestTotal = best;
  }

  private static String httpCount(int appId, String extra) {
    return "SELECT count() FROM argos.xlog WHERE app_id = "
        + appId
        + " AND kind = 'HTTP' AND ts >= '"
        + from.toString().replace("T", " ").replace("Z", "")
        + "' AND ts < '"
        + to.toString().replace("T", " ").replace("Z", "")
        + "'"
        + extra;
  }

  private static String path(int appId, String extra) {
    return "/api/v1/apps/" + appId + "/xlog?from=" + from + "&to=" + to + extra;
  }

  @Test
  @Tag("req:A-5")
  @DisplayName(
      "T-API-006 more than 100,000 points: two identical requests return identical arrays, every"
          + " error and slow point is present, shown/total and sampling.rule say so")
  void deterministicSampling() {
    assertThat(busiestTotal)
        .as("the busiest app has more than 100,000 HTTP points")
        .isGreaterThan(100_000);
    ApiResponse first = viewer.get(path(busiest.appId(), ""));
    ApiResponse second = viewer.get(path(busiest.appId(), ""));
    assertThat(first.status()).as(first.toString()).isEqualTo(200);
    JsonNode a = first.json();
    JsonNode b = second.json();
    assertThat(a.path("data")).isEqualTo(b.path("data"));
    JsonNode meta = a.path("meta");
    long n = (busiestTotal + 99_999) / 100_000;
    assertThat(meta.path("total").asLong()).isEqualTo(busiestTotal);
    assertThat(meta.path("sampling").path("applied").asBoolean()).isTrue();
    assertThat(meta.path("sampling").path("rule").asString())
        .isEqualTo("error OR slow OR cityHash64(tx_id) % " + n + " = 0");
    int slow = meta.path("slow_threshold_ms").asInt();
    assertThat(slow).isPositive();
    assertThat(meta.path("source").asString()).isEqualTo("raw");
    JsonNode data = a.path("data");
    int shown = data.path("ts").size();
    assertThat(meta.path("shown").asLong()).isEqualTo(shown);
    assertThat(shown).isLessThanOrEqualTo(100_000);
    for (String column :
        List.of("tx_id", "elapsed_ms", "status", "instance_id", "service_id", "kind")) {
      assertThat(data.path(column).size()).as(column).isEqualTo(shown);
    }
    long errorOrSlow =
        TxApiFixture.count(
            httpCount(busiest.appId(), " AND (error = 1 OR elapsed_ms >= " + slow + ")"));
    long flagged = 0;
    for (JsonNode s : data.path("status")) {
      if (s.asInt() > 0) {
        flagged++;
      }
    }
    // Errors and slow points are never sampled out; only more of them than the limit drop some.
    assertThat(flagged).as("every error and slow point").isEqualTo(Math.min(errorOrSlow, 100_000));
    long expectedSampled =
        TxApiFixture.count(
            httpCount(
                busiest.appId(),
                " AND (error = 1 OR elapsed_ms >= "
                    + slow
                    + " OR cityHash64(tx_id) % "
                    + n
                    + " = 0)"));
    assertThat(shown).isEqualTo(Math.min(100_000, expectedSampled));
    // 14 §2.5: truncated when points that satisfy the rule were cut by the limit.
    assertThat(meta.path("truncated").asBoolean()).isEqualTo(expectedSampled > 100_000);
    JsonNode first0 = data.path("service_id").path(0);
    assertThat(first0.isString()).as("64-bit keys are strings").isTrue();
    assertThat(data.path("dictionary").path("service").has(first0.asString())).isTrue();
    assertThat(data.path("tx_id").path(0).isString()).isTrue();
    for (int i = 1; i < shown; i++) {
      assertThat(data.path("ts").path(i).asLong())
          .isGreaterThanOrEqualTo(data.path("ts").path(i - 1).asLong());
    }
  }

  @Test
  @Tag("req:A-5")
  @Tag("req:DSN-14-04")
  @DisplayName(
      "T-API-006b total = 2 x limit with every other point sampled: the errors at the end of the"
          + " range are all returned (errors and slow points first inside the exact LIMIT), sampled"
          + " points beyond the limit are left out and truncated is true (14 §2.5); when the points"
          + " of the rule fit in the limit, truncated is false")
  void errorsSurviveASampleOverflow() throws Exception {
    int app = SAMPLING_APP;
    int instance = SAMPLING_APP * 10;
    TxApiFixture.app(app, "xlog-sampling", instance, "xlog-smp-1:8080", "off", "off", "masked");
    Instant base = Anchors.thisMinute().minus(Duration.ofMinutes(40));
    // 30 fast points whose tx_id hashes to 0 modulo 2 (all picked by N = 2), then 10 errors.
    List<Long> picked = hashedModTwo(base, 30, 0);
    List<HandMadeRows.XlogRow> rows = new java.util.ArrayList<>();
    for (int i = 0; i < 30; i++) {
      rows.add(
          HandMadeRows.XlogRow.at(app, instance, base.plusSeconds(i))
              .txId(picked.get(i))
              .elapsedMs(10));
    }
    for (int i = 0; i < 10; i++) {
      rows.add(
          HandMadeRows.XlogRow.at(app, instance, base.plusSeconds(40 + i))
              .elapsedMs(20)
              .error(true)
              .httpStatus(500));
    }
    // Ten minutes later: 30 fast points whose tx_id hashes to 1 modulo 2 (none picked by N = 2),
    // then 10 errors: the points of the rule (10) fit in the limit.
    Instant fits = base.plus(Duration.ofMinutes(10));
    List<Long> notPicked = hashedModTwo(fits, 30, 1);
    for (int i = 0; i < 30; i++) {
      rows.add(
          HandMadeRows.XlogRow.at(app, instance, fits.plusSeconds(i))
              .txId(notPicked.get(i))
              .elapsedMs(10));
    }
    for (int i = 0; i < 10; i++) {
      rows.add(
          HandMadeRows.XlogRow.at(app, instance, fits.plusSeconds(40 + i))
              .elapsedMs(20)
              .error(true)
              .httpStatus(500));
    }
    QueryTestData.rows().xlog(rows);
    String url =
        "/api/v1/apps/"
            + app
            + "/xlog?from="
            + base
            + "&to="
            + base.plus(Duration.ofMinutes(2))
            + "&limit=20";
    ApiResponse r = viewer.get(url);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode meta = r.json().path("meta");
    assertThat(meta.path("total").asLong()).isEqualTo(40);
    assertThat(meta.path("shown").asLong()).isEqualTo(20);
    // 40 points satisfy the rule (30 picked + 10 errors) and 20 are shown: cut by the row limit.
    assertThat(meta.path("truncated").asBoolean()).isTrue();
    assertThat(meta.path("sampling").path("rule").asString())
        .isEqualTo("error OR slow OR cityHash64(tx_id) % 2 = 0");
    JsonNode data = r.json().path("data");
    long errors = 0;
    for (JsonNode st : data.path("status")) {
      if (st.asInt() == 2) {
        errors++;
      }
    }
    assertThat(errors).as("every error, although they are the newest points").isEqualTo(10);
    for (int i = 1; i < data.path("ts").size(); i++) {
      assertThat(data.path("ts").path(i).asLong())
          .isGreaterThanOrEqualTo(data.path("ts").path(i - 1).asLong());
    }
    assertThat(viewer.get(url).json().path("data")).isEqualTo(data);

    ApiResponse fit =
        viewer.get(
            "/api/v1/apps/"
                + app
                + "/xlog?from="
                + fits
                + "&to="
                + fits.plus(Duration.ofMinutes(2))
                + "&limit=20");
    assertThat(fit.status()).as(fit.toString()).isEqualTo(200);
    JsonNode fitMeta = fit.json().path("meta");
    assertThat(fitMeta.path("total").asLong()).isEqualTo(40);
    assertThat(fitMeta.path("sampling").path("applied").asBoolean()).isTrue();
    assertThat(fitMeta.path("sampling").path("rule").asString())
        .isEqualTo("error OR slow OR cityHash64(tx_id) % 2 = 0");
    assertThat(fitMeta.path("shown").asLong()).as("the 10 errors only").isEqualTo(10);
    assertThat(fitMeta.path("truncated").asBoolean())
        .as("every point of the rule is shown")
        .isFalse();
    for (JsonNode st : fit.json().path("data").path("status")) {
      assertThat(st.asInt()).isEqualTo(2);
    }
  }

  /**
   * {@code count} tx_ids near {@code base} (agent form) with {@code cityHash64(tx_id) % 2 =
   * remainder}.
   */
  private static List<Long> hashedModTwo(Instant base, int count, int remainder) {
    long first = (base.toEpochMilli() - 1_767_225_600_000L) << 23;
    StringBuilder candidates = new StringBuilder();
    for (int i = 0; i < count * 8; i++) {
      if (i > 0) {
        candidates.append(',');
      }
      candidates.append(Long.toUnsignedString(first + ((long) i << 23) + 0x5A5AL));
    }
    List<List<String>> found =
        TxApiFixture.reader(
            "SELECT x FROM (SELECT arrayJoin(CAST(["
                + candidates
                + "] AS Array(UInt64))) AS x) WHERE cityHash64(x) % 2 = "
                + remainder
                + " ORDER BY x LIMIT "
                + count);
    assertThat(found).as("candidate tx_ids hashed to %d modulo 2", remainder).hasSize(count);
    return found.stream().map(row -> Long.parseUnsignedLong(row.get(0))).toList();
  }

  @Test
  @DisplayName("a range with fewer points than the limit: every point, sampling.applied false")
  void unsampled() {
    QueryDataset.App app = busiest;
    Instant narrowTo = from.plus(Duration.ofMinutes(8));
    ApiResponse r =
        viewer.get("/api/v1/apps/" + app.appId() + "/xlog?from=" + from + "&to=" + narrowTo);
    JsonNode meta = r.json().path("meta");
    assertThat(meta.path("sampling").path("applied").asBoolean()).isFalse();
    assertThat(meta.path("sampling").has("rule")).isFalse();
    assertThat(meta.path("shown").asLong()).isEqualTo(meta.path("total").asLong());
    assertThat(meta.path("truncated").asBoolean()).isFalse();
  }

  @Test
  @Tag("req:DSN-14-15")
  @DisplayName(
      "T-API-004 errors alone above the limit: cut at the limit with meta.truncated; range over"
          + " 24 h and limit over 100,000 are 400")
  void truncatedAndLimits() {
    ApiResponse r = viewer.get(path(busiest.appId(), "&error=true&limit=10"));
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().path("meta").path("truncated").asBoolean()).isTrue();
    assertThat(r.json().path("data").path("ts").size()).isEqualTo(10);
    ApiResponse wide =
        viewer.get(
            "/api/v1/apps/"
                + busiest.appId()
                + "/xlog?from="
                + to.minus(Duration.ofHours(25))
                + "&to="
                + to);
    assertThat(wide.status()).isEqualTo(400);
    assertThat(wide.errorCode()).contains("range_invalid");
    ApiResponse limit = viewer.get(path(busiest.appId(), "&limit=100001"));
    assertThat(limit.status()).isEqualTo(400);
    assertThat(limit.errorCode()).contains("limit_exceeded");
    ApiResponse unknown = viewer.get(path(999_999, ""));
    assertThat(unknown.status()).isEqualTo(404);
  }

  @Test
  @Tag("req:DSN-14-08")
  @DisplayName(
      "DSN-14-08 the JSON is gzip-compressed for a client that accepts gzip (Content-Encoding),"
          + " and plain otherwise")
  void gzip() throws Exception {
    String cookies = viewer.cookies().header("/api/v1/apps", false);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(viewer.baseUrl() + path(busiest.appId(), "&limit=5000")))
            .header("Accept-Encoding", "gzip")
            .header("Cookie", cookies)
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
    HttpResponse<byte[]> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
    assertThat(response.headers().firstValue("Vary").orElse("")).contains("Accept-Encoding");
    byte[] plain = gunzip(response.body());
    assertThat(plain.length).isGreaterThan(response.body().length);
    JsonNode json = JSON.readTree(new String(plain, StandardCharsets.UTF_8));
    assertThat(json.path("data").path("ts").size()).isEqualTo(5000);
    assertThat(json.path("meta").path("request_id").asString()).isNotEmpty();

    ApiResponse identity = viewer.get(path(busiest.appId(), "&limit=5000"));
    assertThat(identity.header("Content-Encoding")).isEmpty();
  }

  @Test
  @Tag("req:DSN-14-08")
  @Tag("req:N-07")
  @DisplayName(
      "14 §12 #3 reference: size and client time of a 100,000-point response, columnar JSON plain"
          + " and gzip (shared laptop: INVALID, written to build/reports/xlog-payload)")
  void payloadReference() throws Exception {
    String cookies = viewer.cookies().header("/api/v1/apps", false);
    URI uri = URI.create(viewer.baseUrl() + path(busiest.appId(), "&limit=100000"));
    HttpClient http = HttpClient.newHttpClient();
    StringBuilder report = new StringBuilder();
    report
        .append("# XLog 100,000-point payload (14 §12 #3, DSN-14-08)\n\n")
        .append("INVALID / reference only: shared laptop (Docker Desktop, tiny generator data),")
        .append(" loopback, warm caches. Client time = request sent to body read.\n\n")
        .append("| encoding | points | body bytes | JSON bytes | client ms (3 runs) |\n")
        .append("|---|---|---|---|---|\n");
    for (boolean gz : new boolean[] {false, true}) {
      long[] ms = new long[3];
      int bodyBytes = 0;
      int jsonBytes = 0;
      int points = 0;
      for (int i = 0; i < ms.length; i++) {
        HttpRequest.Builder b =
            HttpRequest.newBuilder(uri)
                .header("Cookie", cookies)
                .timeout(Duration.ofMinutes(2))
                .GET();
        if (gz) {
          b.header("Accept-Encoding", "gzip");
        }
        long t0 = System.nanoTime();
        HttpResponse<byte[]> response =
            http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        ms[i] = (System.nanoTime() - t0) / 1_000_000;
        assertThat(response.statusCode()).isEqualTo(200);
        byte[] body = response.body();
        byte[] plain = gz ? gunzip(body) : body;
        assertThat(response.headers().firstValue("Content-Encoding").isPresent()).isEqualTo(gz);
        bodyBytes = body.length;
        jsonBytes = plain.length;
        points =
            JSON.readTree(new String(plain, StandardCharsets.UTF_8)).path("data").path("ts").size();
      }
      assertThat(points).isGreaterThan(50_000);
      report
          .append("| ")
          .append(gz ? "gzip" : "identity")
          .append(" | ")
          .append(points)
          .append(" | ")
          .append(bodyBytes)
          .append(" | ")
          .append(jsonBytes)
          .append(" | ")
          .append(ms[0])
          .append(", ")
          .append(ms[1])
          .append(", ")
          .append(ms[2])
          .append(" |\n");
    }
    java.nio.file.Path out =
        java.nio.file.Path.of(
            System.getProperty("argos.reportsDir", "build/reports"), "xlog-payload", "report.md");
    java.nio.file.Files.createDirectories(out.getParent());
    java.nio.file.Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
    System.out.println(
        "XlogIT payload reference written to " + out.toAbsolutePath() + "\n" + report);
  }

  private static byte[] gunzip(byte[] body) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
      return in.readAllBytes();
    }
  }

  @Test
  @Tag("req:D-18")
  @DisplayName(
      "T-ACC-18b XLog meta.outages: an instance state transition to DOWN and back is the"
          + " dashboard's outage span, clipped to the range; points around it are still returned")
  void outages() throws Exception {
    int app = OUTAGE_APP;
    int instance = OUTAGE_APP * 10;
    TxApiFixture.app(app, "xlog-outages", instance, "xlog-out-1:8080", "off", "off", "masked");
    Instant base = Anchors.thisMinute().minus(Duration.ofMinutes(30));
    QueryTestData.rows()
        .xlog(
            List.of(
                HandMadeRows.XlogRow.at(app, instance, base.plusSeconds(5)).elapsedMs(20),
                HandMadeRows.XlogRow.at(app, instance, base.plus(Duration.ofMinutes(20)))
                    .elapsedMs(30)));
    Instant down = base.plus(Duration.ofMinutes(5));
    Instant up = base.plus(Duration.ofMinutes(12));
    stateEvent(
        app, instance, down, "{\"from\":\"UP\",\"to\":\"DOWN\",\"reason\":\"session_lost\"}");
    stateEvent(app, instance, up, "{\"from\":\"DOWN\",\"to\":\"UP\"}");

    ApiResponse r =
        viewer.get(
            "/api/v1/apps/"
                + app
                + "/xlog?from="
                + base
                + "&to="
                + base.plus(Duration.ofMinutes(25)));
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode meta = r.json().path("meta");
    assertThat(meta.path("outages").size()).as(meta.toString()).isEqualTo(1);
    JsonNode o = meta.path("outages").path(0);
    assertThat(Instant.parse(o.path("from").asString())).isEqualTo(down);
    assertThat(Instant.parse(o.path("to").asString())).isEqualTo(up);
    assertThat(o.path("reason").asString()).isEqualTo("session_lost");
    assertThat(r.json().path("data").path("ts").size()).isEqualTo(2);

    // A range that ends inside the outage clips it; another instance's filter does not see it.
    ApiResponse clipped =
        viewer.get("/api/v1/apps/" + app + "/xlog?from=" + base + "&to=" + down.plusSeconds(60));
    JsonNode c = clipped.json().path("meta").path("outages");
    assertThat(c.size()).isEqualTo(1);
    assertThat(Instant.parse(c.path(0).path("to").asString())).isEqualTo(down.plusSeconds(60));
    ApiResponse other =
        viewer.get(
            "/api/v1/apps/"
                + app
                + "/xlog?from="
                + base
                + "&to="
                + base.plus(Duration.ofMinutes(25))
                + "&instance="
                + (instance + 1));
    assertThat(other.json().path("meta").path("outages").size()).isZero();
  }

  private static void stateEvent(int app, int instance, Instant ts, String detail)
      throws SQLException {
    try (Connection c = TxApiFixture.env().connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.events (id, app_id, env, instance_id, type, title, detail, ts)"
                    + " VALUES (?, ?, 'prod', ?, 'state', 'state', ?::jsonb, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setInt(2, app);
      ps.setInt(3, instance);
      ps.setString(4, detail);
      ps.setTimestamp(5, Timestamp.from(ts));
      ps.executeUpdate();
    }
  }

  @Test
  @DisplayName(
      "the summary of a dragged rectangle: count = count() of the rectangle, groups largest first")
  void summary() {
    Map<String, Object> body =
        Map.of("from", from.toString(), "to", to.toString(), "min_ms", 200, "max_ms", 5000);
    ApiResponse r = viewer.post("/api/v1/apps/" + busiest.appId() + "/xlog/summary", body);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode d = r.json().path("data");
    assertThat(d.path("count").asLong())
        .isEqualTo(
            TxApiFixture.count(httpCount(busiest.appId(), " AND elapsed_ms BETWEEN 200 AND 5000")));
    JsonNode services = d.path("by_service");
    assertThat(services.size()).isBetween(1, 10);
    for (int i = 1; i < services.size(); i++) {
      assertThat(services.path(i).path("count").asLong())
          .isLessThanOrEqualTo(services.path(i - 1).path("count").asLong());
    }
    assertThat(services.path(0).path("name").asString()).isNotEmpty();
    assertThat(services.path(0).path("max_ms").asLong()).isBetween(200L, 5000L);
    assertThat(d.path("by_instance").size()).isPositive();
    assertThat(d.path("by_status").size()).isPositive();
    long statusSum = 0;
    for (JsonNode s : d.path("by_status")) {
      statusSum += s.path("count").asLong();
    }
    assertThat(statusSum).isLessThanOrEqualTo(d.path("count").asLong());
  }
}
