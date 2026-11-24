/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.querydata.Placement;
import io.argos.server.testing.querydata.QueryDataProfile;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * T-ST-054 deletion mutation duration of one day partition (SEC-33, 13 §9 "5,000 TPS 하루 파티션은 수십 분",
 * ADR-0007 C-66): the generator's {@code ci} profile (1/100, 7.2 hours of xlog, about 13 million
 * rows) is loaded, and a deletion of the busiest masked address network of the busiest day
 * partition runs through the API. The test asserts only that the job finishes ({@code done}, all
 * mutations done, rows removed); the durations go to {@code
 * server/build/reports/privacy/mutation-timing.md} as reference values (a shared laptop is not the
 * target hardware: INVALID; the target hardware run is a human check).
 */
@Tag("integration")
@Tag("slow")
@Tag("req:SEC-33")
class MutationDurationIT {

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ClickHouseHttp ch;
  private static Duration load;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env);
    // Read-only queries; system.parts is granted to the query account only (13 §7.3).
    ch = env.clickHouse(Account.READER);
    long t0 = System.nanoTime();
    QueryTestData.load(env, QueryDataProfile.CI, Placement.RECENT);
    load = Duration.ofNanos(System.nanoTime() - t0);
  }

  @AfterAll
  static void stop() {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      if (env != null) {
        env.close();
      }
    }
  }

  private static String scalar(String sql) {
    return ch.scalar(sql).strip();
  }

  @Test
  @DisplayName(
      "T-ST-054 one day partition: a masked address deletion of the ci profile finishes; its"
          + " duration is written to the reference report")
  void deletionOfOneDay() throws Exception {
    String partition =
        scalar(
            "SELECT partition_id FROM system.parts WHERE database = 'argos' AND table = 'xlog'"
                + " AND active GROUP BY partition_id ORDER BY sum(rows) DESC LIMIT 1");
    LocalDate day = LocalDate.parse(partition, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    String partStats =
        scalar(
            "SELECT concat(toString(sum(rows)), '\\t', toString(count()), '\\t',"
                + " toString(sum(bytes_on_disk))) FROM system.parts WHERE database = 'argos' AND"
                + " table = 'xlog' AND active AND partition_id = '"
                + partition
                + "'");
    String profileStats =
        scalar(
            "SELECT concat(toString(sum(rows)), '\\t', toString(count()), '\\t',"
                + " toString(sum(bytes_on_disk))) FROM system.parts WHERE database = 'argos' AND"
                + " table = 'profile_step' AND active AND partition_id = '"
                + partition
                + "'");
    String network =
        scalar(
            "SELECT replaceOne(toString(remote_ip), '::ffff:', '') FROM argos.xlog WHERE toDate(ts)"
                + " = '"
                + day
                + "' AND ip_mode = 'masked' GROUP BY remote_ip ORDER BY count() DESC LIMIT 1");
    long matching =
        Long.parseLong(
            scalar(
                "SELECT count() FROM argos.xlog WHERE toDate(ts) = '"
                    + day
                    + "' AND ip_mode = 'masked' AND remote_ip = toIPv6('"
                    + network
                    + "')"));
    assertThat(matching).isPositive();

    TestUsers.TestUser a = new TestUsers(env).create(Role.ADMIN);
    ApiTestClient admin = server.client();
    assertThat(admin.login(a.username(), a.password()).status()).isEqualTo(200);
    Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    long t0 = System.nanoTime();
    ApiResponse r =
        admin.post(
            "/api/v1/admin/privacy/deletions",
            java.util.Map.of("ip", network, "from", from.toString(), "to", to.toString()));
    assertThat(r.status()).as(r.body()).isEqualTo(202);
    String jobId = r.json().path("data").path("job_id").asString();
    JsonNode job = null;
    long deadline = System.nanoTime() + Duration.ofMinutes(60).toNanos();
    while (System.nanoTime() < deadline) {
      job = admin.get("/api/v1/admin/privacy/jobs/" + jobId).json().path("data");
      String s = job.path("status").asString();
      if (s.equals("done") || s.equals("failed")) {
        break;
      }
      Thread.sleep(1000);
    }
    Duration wall = Duration.ofNanos(System.nanoTime() - t0);
    assertThat(job).isNotNull();
    assertThat(job.path("status").asString()).as(job.toString()).isEqualTo("done");
    for (JsonNode t : job.path("tables")) {
      assertThat(t.path("done").asBoolean()).isTrue();
    }
    assertThat(
            scalar(
                "SELECT count() FROM argos.xlog WHERE toDate(ts) = '"
                    + day
                    + "' AND ip_mode = 'masked' AND remote_ip = toIPv6('"
                    + network
                    + "')"))
        .isEqualTo("0");
    Duration jobTime =
        Duration.between(
            Instant.parse(job.path("started_at").asString()),
            Instant.parse(job.path("finished_at").asString()));
    String[] x = partStats.split("\t", -1);
    String[] p = profileStats.split("\t", -1);
    String report =
        String.join(
            "\n",
            List.of(
                "# T-ST-054 privacy deletion mutation duration (reference, INVALID)",
                "",
                "Shared development machine, not the target hardware: reference values only"
                    + " (ADR-0007 C-66: the target hardware run, 3 or more repetitions, is a human"
                    + " check).",
                "",
                "| Item | Value |",
                "|---|---|",
                "| Generated at | " + Instant.now() + " |",
                "| Profile | ci (1/100), placement recent, load " + load.toSeconds() + " s |",
                "| Partition | " + day + " |",
                "| xlog partition | " + x[0] + " rows, " + x[1] + " parts, " + mib(x[2]) + " |",
                "| profile_step partition | "
                    + (p.length < 3 ? "empty" : p[0] + " rows, " + p[1] + " parts, " + mib(p[2]))
                    + " |",
                "| Subject | a masked /24 network (" + matching + " xlog rows) |",
                "| Mutations | " + job.path("tables").size() + " |",
                "| Matching rows (xlog + profile_step) | "
                    + job.path("rows_affected").asLong()
                    + " |",
                "| Job started -> finished | " + jobTime.toMillis() + " ms |",
                "| Request -> done seen by the client | " + wall.toMillis() + " ms |",
                ""));
    Path out =
        Paths.get(
            System.getProperty("argos.repositoryRoot", "."),
            "server",
            "build",
            "reports",
            "privacy",
            "mutation-timing.md");
    Files.createDirectories(out.getParent());
    Files.writeString(out, report, StandardCharsets.UTF_8);
    System.out.println("[T-ST-054] " + report.replace('\n', ' '));
  }

  private static String mib(String bytes) {
    return String.format(Locale.ROOT, "%.1f MiB", Long.parseLong(bytes) / 1048576.0);
  }
}
