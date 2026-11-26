/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.pii;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.sample.common.seed.Seeds;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.alert.FakeSmtpServer;
import io.argos.server.testing.alert.FakeWebhookReceiver;
import io.argos.server.testing.alert.TestTls;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.TestCertificates;
import io.argos.servertest.security.matrix.OpenApiSpec;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-NFR-10c (N-10c, 18 §12 last paragraph, 03 §3.5, DSN-18-09): with the default privacy settings,
 * the PII seeds of 18 §4.3 sent through a real agent leave the sentinel nowhere. Everything is this
 * class's own: PostgreSQL and ClickHouse (not the shared suite environment, whose other tests plant
 * sentinels on purpose), the server as a child JVM from the boot jar with its own log directory,
 * the real agent jar on shop-boot3 (a child JVM with the seed environment of {@code
 * Seeds.environment()}, {@code capture.remote-ip=masked}, every other capture key at its default,
 * every profile kept so that the steps are stored too), and the smoke runner sending the PII seed
 * set of {@code test/seed/seed-traffic.sh --set pii} (the {@code pii_prefixes} line of the script;
 * the XSS and CSV seeds are stored by design and checked by T-SEC-62/63, WO-46 plan PM question 2):
 * 10,000 requests by default ({@code -Psecurity.pii.requests}).
 *
 * <p>The phone and card seeds that end up in an exception message have the real shape ({@code
 * Seeds.PHONE_ERROR_MESSAGE}, {@code Seeds.CARD_ERROR_MESSAGE}, ADR-0010 C-250 (1)): the agent
 * masks messages with the SEC-36 regular expressions only, so only a real-shaped value tests that
 * masking. Query-string and bind seeds keep {@code ARGOS_SEED}.
 *
 * <p>Then every leak sentinel of {@code Seeds.LEAK_SENTINELS} ({@code ARGOS_SEED}, {@code
 * ARGOSSEED} of ADR-0004 C-44, and the real-shaped phone, resident number and card values) is
 * counted in (a) every ClickHouse column of the {@code argos*} databases whose type mentions {@code
 * String} ({@code system.columns}, {@code toString(column)}), (b) {@code pg_dump --data-only} of
 * PostgreSQL, (c) the server log directory and console, the agent log directory, and the agent's
 * part of the app console, (d) the webhook and SMTP inboxes after an error-rate rule on the seed
 * app fired, and (e) every operation of the served OpenAPI document that answers {@code text/csv},
 * called as an administrator: all zero. Failures name the location and the count, never a value.
 *
 * <p>Masking control in the same run (C-250 (1)): the error groups of the seed app hold the phone
 * and card exception seeds with the masking markers the agent writes ({@code <phone>}, {@code
 * <card>}), so the seeds arrived and were masked, not lost.
 *
 * <p>Positive control in the same run: a second app with {@code capture.user-id.mode=plain} and
 * {@code capture.query-string=full} receives about 100 seed requests; the same column scan finds
 * the sentinel in {@code xlog.user_id} (the scan is not vacuous), and after {@code POST
 * /admin/privacy/deletions} for the seed user id every {@code user_id} column is 0 again. Report:
 * {@code server/build/reports/security/pii-seed-grep.md}.
 */
@Tag("req:N-10c")
@Tag("req:T-NFR-10c")
@Tag("req:SEC-26")
@Tag("req:SEC-33")
@Tag("slow")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PiiSeedGrepSecurityTest {

  /** The seed user id of 18 §4.3 ({@code Seeds.USER_ID} of sample-apps/common). */
  static final String SEED_USER_ID = Seeds.USER_ID;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String SMTP_USER = "argos-alerts";
  private static final String SEED_APP = "pii-seed";
  private static final String CONTROL_APP = "pii-plain";
  private static final String ENV = "prod";
  private static final Duration SMOKE_TIMEOUT = Duration.ofMinutes(30);
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @TempDir static Path work;

  private static ServerTestEnvironment env;
  private static Process server;
  private static String baseUrl;
  private static ApiTestClient admin;
  private static FakeWebhookReceiver receiver;
  private static FakeSmtpServer smtp;
  private static final List<Process> APPS = new ArrayList<>();
  private static final StringBuilder REPORT = new StringBuilder();
  private static Instant runStart;

  // --- set-up ---------------------------------------------------------------------------------

  @BeforeAll
  static void start() throws Exception {
    runStart = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    for (String key : List.of("agentJar", "shopBoot3Jar", "smokeJar")) {
      assertThat(jar(key))
          .as("-Dargos.test.%s (run through ./gradlew :server:securityTest)", key)
          .isRegularFile();
    }
    env = ServerTestEnvironment.start();
    Path alertDir = Files.createDirectories(work.resolve("alert"));
    TestCertificates.TestPki pki = TestTls.pki(alertDir);
    SSLContext tls = TestTls.serverContext(pki);
    receiver = FakeWebhookReceiver.https(tls);
    String smtpPassword = "smtp-" + randomToken();
    smtp = new FakeSmtpServer(true, tls, SMTP_USER, smtpPassword);
    Path smtpPasswordFile = writeSecret(alertDir.resolve("smtp-password"), smtpPassword + "\n");

    int http = freePort();
    int management = freePort();
    baseUrl = "http://127.0.0.1:" + http;
    Path logs = Files.createDirectories(work.resolve("server-logs"));
    Files.createDirectories(work.resolve("server-tmp"));
    List<String> command = new ArrayList<>();
    command.add(java());
    command.add("-Xmx1g");
    command.add("-Djava.io.tmpdir=" + work.resolve("server-tmp"));
    command.add("-DLOG_PATH=" + logs);
    command.add("-jar");
    command.add(bootJar().toString());
    command.addAll(env.commandLineArguments());
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + http);
    command.add("--management.server.address=127.0.0.1");
    command.add("--management.server.port=" + management);
    command.add("--argos.web.public-url=" + baseUrl);
    // Alert channels (16 §8.1): the in-process receivers on the loopback, the test CA trusted
    // through the development-only key of WO-25; SMTP with STARTTLS and AUTH.
    command.add("--argos.alert.webhook.allow-cidrs[0]=127.0.0.1/32");
    command.add("--argos.testing.alert-ca-file=" + pki.caCertificate());
    command.add("--argos.alert.smtp.host=localhost");
    command.add("--argos.alert.smtp.port=" + smtp.port());
    command.add("--argos.alert.smtp.username=" + SMTP_USER);
    command.add("--argos.alert.smtp.password-file=" + smtpPasswordFile);
    command.add("--argos.alert.smtp.from=argos@example.test");
    command.add("--argos.alert.smtp.allowed-recipient-domains[0]=example.test");
    // Every CSV export of (e) in one minute: the heavy bucket is not what this test measures.
    command.add("--argos.ratelimit.export-per-user-per-minute=1000");
    server =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(work.resolve("server-console.log").toFile())
            .start();
    await(
        Duration.ofMinutes(4),
        "the server (HTTP and gRPC ports)",
        () -> {
          requireAlive(server, "server", work.resolve("server-console.log"));
          return accepts(env.collectorPort()) && httpStatus(baseUrl + "/api/v1/auth/csrf") > 0;
        });
    TestUsers.TestUser user = new TestUsers(env).create(Role.ADMIN);
    admin = ApiTestClient.of(baseUrl);
    ApiResponse login = admin.login(user.username(), user.password());
    assertThat(login.status()).as(login.toString()).isEqualTo(200);
    REPORT
        .append("# PII seed grep (T-NFR-10c)\n\n")
        .append("Run ")
        .append(runStart)
        .append(" on ")
        .append(System.getProperty("os.name"))
        .append(" (shared machine: durations are a reference only). Server: child JVM from ")
        .append(bootJar().getFileName())
        .append("; agent ")
        .append(jar("agentJar").getFileName())
        .append(" on shop-boot3, JDK ")
        .append(Runtime.version().feature())
        .append(".\n\n");
  }

  @AfterAll
  static void stop() throws Exception {
    try {
      for (Process p : APPS) {
        stopProcess(p);
      }
      stopProcess(server);
      writeReport();
    } finally {
      if (receiver != null) {
        receiver.close();
      }
      if (smtp != null) {
        smtp.close();
      }
      if (env != null) {
        env.close();
      }
    }
  }

  // --- the grep ---------------------------------------------------------------------------------

  @Test
  @Order(1)
  @DisplayName(
      "T-NFR-10c PII seed traffic (default settings, real agent) leaves no ARGOS_SEED, ARGOSSEED"
          + " or real-shaped phone, resident number or card sentinel in ClickHouse string columns,"
          + " the PostgreSQL dump, server and agent logs, alert inboxes or CSV exports; the phone"
          + " and card exception seeds reach the error groups masked")
  void seedTrafficLeavesNoSentinel() throws Exception {
    long t0 = System.nanoTime();
    int appId = provisionApp(SEED_APP);
    String webhook = createChannel("webhook", "pii-hook", receiver.url("/hook"));
    String email = createChannel("email", "pii-mail", "ops@example.test");
    createErrorRateRule(appId, List.of(webhook, email));

    App app = startApp(SEED_APP, appId, List.of());
    long started = System.nanoTime();
    List<String> prefixes = piiPrefixes();
    int target = Integer.getInteger("argos.test.piiRequests", 10_000);
    // One call of every entry first: the number of entries of the set, then the repeats that
    // bring the total to the target.
    Smoke probe = smoke(app, "probe", prefixes, 1);
    int entries = probe.passed();
    assertThat(entries).as("seed entries of the PII set").isGreaterThanOrEqualTo(prefixes.size());
    int repeat = Math.max(1, (int) Math.ceil((target - entries) / (double) entries));
    Smoke traffic = smoke(app, "traffic", prefixes, repeat);
    int requests = probe.passed() + traffic.passed();
    long sent = System.nanoTime();

    long stored = awaitStoredTransactions(app, appId, requests);
    // The error-rate rule fires on the seed errors (3 of the 12 entries answer 500): both inboxes
    // must hold a notification, or (d) would scan nothing.
    List<FakeWebhookReceiver.Received> hooks = receiver.await(1, Duration.ofMinutes(3));
    List<FakeSmtpServer.Message> mails = smtp.await(1, Duration.ofMinutes(3));
    // Statistics windows (10 s) and metrics (5 s) of the last requests reach storage too.
    TimeUnit.SECONDS.sleep(20);
    stopProcess(app.process());
    long loaded = System.nanoTime();

    ClickHouseHttp clickHouse = env.clickHouse(Account.READER);
    List<SentinelScans.Column> columns = SentinelScans.stringColumns(clickHouse);
    SentinelScans.Counts ch = SentinelScans.clickHouse(clickHouse, columns);
    long scannedClickHouse = System.nanoTime();
    SentinelScans.Counts pg = SentinelScans.pgDump(pgDump());
    long scannedPostgres = System.nanoTime();
    SentinelScans.Counts logs = SentinelScans.Counts.empty();
    merge(logs, SentinelScans.files("server-logs", work.resolve("server-logs")));
    logs.add("server-console.log", SentinelScans.fileHits(work.resolve("server-console.log")));
    merge(
        logs,
        SentinelScans.files("server-audit-fallback", env.secretsDirectory().resolve("audit")));
    merge(logs, SentinelScans.files("agent-logs/" + SEED_APP, app.agentLogs()));
    logs.add("app-console (agent lines)/" + SEED_APP, agentConsoleHits(app.stdout(), app.stderr()));
    long appConsoleLines = consoleSentinelLines(app.stdout(), app.stderr());
    SentinelScans.Counts inboxes = SentinelScans.Counts.empty();
    for (int i = 0; i < hooks.size(); i++) {
      FakeWebhookReceiver.Received r = hooks.get(i);
      inboxes.add(
          "webhook/" + i,
          SentinelScans.occurrences(r.path() + "\n" + r.headers() + "\n" + r.bodyText()));
    }
    for (int i = 0; i < smtp.inbox().size(); i++) {
      FakeSmtpServer.Message m = smtp.inbox().get(i);
      inboxes.add(
          "smtp/" + i,
          SentinelScans.occurrences(
              m.from() + "\n" + m.recipients() + "\n" + m.data() + "\n" + m.decodedBody()));
    }
    SentinelScans.Counts csv = csvExports(appId);
    long scanned = System.nanoTime();
    // Masking control (C-250 (1)): the exception seeds arrived, as their masked patterns.
    long phoneMasked = errorGroups(appId, "%payment declined for phone <phone>%");
    long cardMasked = errorGroups(appId, "%card <card> declined%");

    REPORT
        .append("## Default settings: ")
        .append(SEED_APP)
        .append("\n\n")
        .append("| Item | Value |\n|---|---|\n")
        .append(row("Seed set (seed-traffic.sh --set pii)", String.join(", ", prefixes)))
        .append(row("Seed entries in the set", Integer.toString(entries)))
        .append(row("Seed requests sent", requests + " (1 + " + repeat + " rounds)"))
        .append(row("Transactions stored (argos.xlog)", Long.toString(stored)))
        .append(row("Webhook / SMTP notifications", hooks.size() + " / " + mails.size()))
        .append(
            row(
                "ClickHouse string columns scanned",
                columns.size() + " columns in " + tables(columns) + " tables"))
        .append(
            row(
                "PostgreSQL columns in the dump",
                (pg.locations() - 1) + " columns in " + SentinelScans.tables(pg) + " tables"))
        .append(row("Log files scanned", Integer.toString(logs.locations())))
        .append(row("CSV exports called", Integer.toString(csv.locations())))
        .append(row("Leak sentinels searched", String.join(", ", SentinelScans.SENTINELS)))
        .append(
            row(
                "Error groups with the masked phone / card exception seed (<phone> / <card>)",
                phoneMasked + " / " + cardMasked))
        .append(
            row(
                "App console lines with a sentinel (the app's own output, not an Argos sink)",
                Long.toString(appConsoleLines)))
        .append(row("Set-up (server, app, channels, rule)", seconds(t0, started)))
        .append(row("Seed traffic", seconds(started, sent)))
        .append(row("Until stored and notified", seconds(sent, loaded)))
        .append(row("ClickHouse scan", seconds(loaded, scannedClickHouse)))
        .append(row("PostgreSQL dump scan", seconds(scannedClickHouse, scannedPostgres)))
        .append(row("Logs, inboxes, CSV", seconds(scannedPostgres, scanned)))
        .append("\n");
    List<String> hits = new ArrayList<>();
    section("(a) ClickHouse", ch, hits);
    section("(b) PostgreSQL pg_dump --data-only", pg, hits);
    section("(c) Server and agent logs", logs, hits);
    section("(d) Alert inboxes", inboxes, hits);
    section("(e) CSV exports", csv, hits);
    writeReport();

    assertThat(hooks).as("webhook notifications of the seed app's rule").isNotEmpty();
    assertThat(mails).as("SMTP notifications of the seed app's rule").isNotEmpty();
    assertThat(stored).as("transactions of the seed traffic in ClickHouse").isGreaterThan(0);
    assertThat(columns).as("ClickHouse string columns").hasSizeGreaterThan(10);
    assertThat(csv.locations()).as("CSV operations of the OpenAPI document").isGreaterThan(0);
    assertThat(phoneMasked)
        .as("error groups of %s whose message pattern holds the masked phone seed", SEED_APP)
        .isGreaterThan(0);
    assertThat(cardMasked)
        .as("error groups of %s whose message pattern holds the masked card seed", SEED_APP)
        .isGreaterThan(0);
    assertThat(hits)
        .as(
            "locations holding a seed sentinel after %d PII seed requests (counts only; report"
                + " server/build/reports/security/pii-seed-grep.md)",
            requests)
        .isEmpty();
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-NFR-10c positive control: with user-id plain and query-string full the same scan finds"
          + " the seed user id in xlog.user_id; after the deletion API every user_id column is 0")
  void positiveControlAndDeletion() throws Exception {
    long t0 = System.nanoTime();
    Instant from = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    int appId = provisionApp(CONTROL_APP);
    App app =
        startApp(
            CONTROL_APP,
            appId,
            List.of(
                "-Dargos.capture.user-id.mode=plain",
                "-Dargos.capture.user-id.source=header:X-User-Id",
                "-Dargos.capture.query-string=full"));
    Smoke traffic = smoke(app, "control", List.of("seed.user", "seed.email"), 34);
    long stored = awaitStoredTransactions(app, appId, traffic.passed());
    stopProcess(app.process());

    ClickHouseHttp clickHouse = env.clickHouse(Account.READER);
    List<SentinelScans.Column> columns = SentinelScans.stringColumns(clickHouse);
    SentinelScans.Counts before = SentinelScans.clickHouse(clickHouse, columns);
    long[] userIdBefore = before.of("argos.xlog.user_id");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("user_id", SEED_USER_ID);
    body.put("app_id", appId);
    body.put("from", from.toString());
    body.put("to", Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS).toString());
    ApiResponse accepted = admin.post("/api/v1/admin/privacy/deletions", body);
    assertThat(accepted.status()).as(accepted.toString()).isEqualTo(202);
    String jobId = accepted.json().path("data").path("job_id").asString();
    JsonNode job = awaitJob(jobId);

    SentinelScans.Counts after = SentinelScans.clickHouse(clickHouse, columns);
    List<String> userIdColumns =
        columns.stream()
            .map(SentinelScans.Column::location)
            .filter(l -> l.endsWith(".user_id"))
            .toList();
    List<String> remaining = new ArrayList<>();
    for (String location : userIdColumns) {
      long[] counts = after.of(location);
      if (SentinelScans.Counts.total(counts) > 0) {
        remaining.add(location + " " + SentinelScans.format(counts));
      }
    }
    REPORT
        .append("## Positive control: ")
        .append(CONTROL_APP)
        .append(" (user-id plain, query-string full)\n\n")
        .append("| Item | Value |\n|---|---|\n")
        .append(
            row("Seed requests (seed.user, seed.email x 34)", Integer.toString(traffic.passed())))
        .append(row("Transactions stored", Long.toString(stored)))
        .append(row("argos.xlog.user_id before deletion", SentinelScans.format(userIdBefore)))
        .append(row("Locations with a sentinel before deletion", before.hits().size() + ""))
        .append(row("Deletion job", job.path("status").asString()))
        .append(row("user_id columns", String.join(", ", userIdColumns)))
        .append(row("user_id columns with a sentinel after deletion", remaining.size() + ""))
        .append(row("Duration", seconds(t0, System.nanoTime())))
        .append("\n")
        .append("Locations with a sentinel before deletion (expected: plain user id, full query")
        .append(" string):\n\n");
    for (String hit : before.hits()) {
      REPORT.append("- ").append(hit).append('\n');
    }
    REPORT.append('\n');
    writeReport();

    assertThat(userIdBefore[0])
        .as("rows of argos.xlog.user_id with the seed user id (the scan finds what is there)")
        .isGreaterThan(0);
    assertThat(job.path("status").asString()).as(job.toString()).isEqualTo("done");
    assertThat(remaining).as("user_id columns with a sentinel after the deletion API").isEmpty();
  }

  // --- traffic ----------------------------------------------------------------------------------

  /** A started shop-boot3 with the agent. */
  record App(String name, Process process, String url, Path agentLogs, Path stdout, Path stderr) {}

  /** The smoke runner's counts. */
  record Smoke(int passed, int failed) {}

  /** The app row and an ACTIVE token, written to an owner-only file. */
  private static int provisionApp(String name) throws Exception {
    int appId;
    String token;
    try (Connection c = env.connect(PgAccount.APP)) {
      appId = AgentTokens.ensureApp(c, name, ENV);
      token = AgentTokens.issue(c, appId).token();
    }
    writeSecret(work.resolve(name + ".token"), token);
    return appId;
  }

  /**
   * shop-boot3 with the real agent over TLS to the server's collector, the seed environment of
   * {@code Seeds.environment()} and {@code extra} agent properties.
   */
  private static App startApp(String name, int appId, List<String> extra) throws Exception {
    int port = freePort();
    Path agentLogs = Files.createDirectories(work.resolve("agent-logs-" + name));
    Path tmp = Files.createDirectories(work.resolve("app-tmp-" + name));
    List<String> command = new ArrayList<>();
    command.add(java());
    command.add("-Xmx512m");
    command.add("-Djava.io.tmpdir=" + tmp);
    command.add("-javaagent:" + jar("agentJar"));
    command.add("-Dargos.server.address=localhost:" + env.collectorPort());
    command.add("-Dargos.server.tls.ca-file=" + env.collectorCaCertificate());
    command.add("-Dargos.server.token-file=" + work.resolve(name + ".token"));
    command.add("-Dargos.app.name=" + name);
    command.add("-Dargos.app.env=" + ENV);
    command.add("-Dargos.app.instance=" + name + "-1");
    command.add("-Dargos.log.dir=" + agentLogs);
    // The capture default of 16 §6.4 stated explicitly; every other capture key is the default.
    command.add("-Dargos.capture.remote-ip=masked");
    // Every profile is kept (steps, SQL text and error steps of each request reach storage), and
    // the CPU guard that halves the rate on few cores is off (development-only key).
    command.add("-Dargos.sampling.profile.normal-rate=1.0");
    command.add("-Dargos.test.overhead-monitor=off");
    command.addAll(extra);
    command.add("-jar");
    command.add(jar("shopBoot3Jar").toString());
    command.add("--server.port=" + port);
    command.add("--server.address=127.0.0.1");
    Path stdout = work.resolve("app-" + name + ".out");
    Path stderr = work.resolve("app-" + name + ".err");
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
    Map<String, String> environment = builder.environment();
    environment
        .keySet()
        .removeIf(
            k ->
                k.startsWith("ARGOS_")
                    || k.equals("JAVA_TOOL_OPTIONS")
                    || k.equals("JDK_JAVA_OPTIONS"));
    environment.putAll(seedEnvironment());
    Process process = builder.start();
    APPS.add(process);
    String url = "http://127.0.0.1:" + port;
    await(
        Duration.ofMinutes(3),
        name + " at " + url,
        () -> {
          requireAlive(process, name, stdout);
          return httpStatus(url + "/api/version") == 200;
        });
    await(
        Duration.ofMinutes(2),
        "the agent session of " + name + " (instances.status UP)",
        () -> {
          requireAlive(process, name, stdout);
          return instanceUp(appId, name + "-1");
        });
    return new App(name, process, url, agentLogs, stdout, stderr);
  }

  /**
   * Secret-looking environment variables of the app process ({@code Seeds.environment()}, SEC-20).
   */
  static Map<String, String> seedEnvironment() {
    return Seeds.environment();
  }

  /**
   * The smoke runner against {@code app}: every catalog entry whose id starts with one of {@code
   * prefixes}, {@code repeat} times; every answer must be as the catalog expects.
   */
  private static Smoke smoke(App app, String run, List<String> prefixes, int repeat)
      throws Exception {
    Path reports = Files.createDirectories(work.resolve("smoke-" + app.name() + "-" + run));
    List<String> command = new ArrayList<>();
    command.add(java());
    command.add("-jar");
    command.add(jar("smokeJar").toString());
    command.add("--app");
    command.add(app.name());
    command.add("--variant");
    command.add("pii-seed-grep-" + run);
    command.add("--kind");
    command.add("url");
    command.add("--url");
    command.add(app.url());
    command.add("--catalog");
    command.add("shop");
    for (String prefix : prefixes) {
      command.add("--only");
      command.add(prefix);
    }
    command.add("--repeat");
    command.add(Integer.toString(repeat));
    command.add("--report-dir");
    command.add(reports.toString());
    Path console = reports.resolve("console.log");
    Process p =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(console.toFile())
            .start();
    if (!p.waitFor(SMOKE_TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
      p.destroyForcibly();
      throw new AssertionError("smoke runner did not finish in " + SMOKE_TIMEOUT);
    }
    JsonNode summary =
        JSON.readTree(Files.readString(reports.resolve("smoke.json"), StandardCharsets.UTF_8))
            .path("summary");
    Smoke result = new Smoke(summary.path("passed").asInt(), summary.path("failed").asInt());
    assertThat(p.exitValue())
        .as("smoke runner exit code (%s, report %s)", result, reports.resolve("smoke.md"))
        .isZero();
    assertThat(result.failed()).isZero();
    return result;
  }

  /**
   * The PII seed set: the entry id prefixes of the {@code pii_prefixes} line of {@code
   * test/seed/seed-traffic.sh} (one list for the script and this test).
   */
  static List<String> piiPrefixes() throws IOException {
    Path script = repositoryRoot().resolve("test/seed/seed-traffic.sh");
    Matcher m =
        Pattern.compile("(?m)^pii_prefixes=\\(([^)]*)\\)$")
            .matcher(Files.readString(script, StandardCharsets.UTF_8));
    assertThat(m.find()).as("pii_prefixes=(...) line of %s", script).isTrue();
    List<String> prefixes = List.of(m.group(1).strip().split("\\s+"));
    assertThat(prefixes).allMatch(p -> p.startsWith("seed.") && !p.startsWith("seed.xss"));
    return prefixes;
  }

  /** Waits until argos.xlog holds at least {@code expected} rows of the app and stops growing. */
  private static long awaitStoredTransactions(App app, int appId, int expected)
      throws InterruptedException {
    ClickHouseHttp clickHouse = env.clickHouse(Account.READER);
    long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
    long last = -1;
    int stable = 0;
    while (System.nanoTime() < deadline) {
      long now = clickHouse.scalarLong("SELECT count() FROM argos.xlog WHERE app_id = " + appId);
      stable = now == last && now >= expected ? stable + 1 : 0;
      last = now;
      if (stable >= 2) {
        return now;
      }
      TimeUnit.SECONDS.sleep(3);
    }
    throw new AssertionError(
        "argos.xlog of "
            + app.name()
            + " holds "
            + last
            + " rows, expected at least "
            + expected
            + " (agent log "
            + app.agentLogs()
            + ")");
  }

  // --- server API -------------------------------------------------------------------------------

  private static String createChannel(String type, String name, String target) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("name", name);
    body.put("target", target);
    ApiResponse r = admin.post("/api/v1/alerts/channels", body);
    assertThat(r.status()).as(r.toString()).isEqualTo(201);
    return r.json().path("data").path("channel").path("channel_id").asString();
  }

  /** An error-rate rule on the app that fires at once (the seed errors are a quarter). */
  private static void createErrorRateRule(int appId, List<String> channels) {
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("name", "T-NFR-10c seed error rate");
    rule.put("target", Map.of("app_id", appId, "env", ENV, "type", "app", "ids", List.of()));
    rule.put("metric", "error_rate");
    rule.put("window", "30s");
    rule.put("for", "0s");
    rule.put("condition", Map.of("operator", ">=", "warn", 0.05));
    rule.put("min_samples", 1);
    rule.put("warmup_grace", "0s");
    rule.put("channels", Map.of("warn", channels, "critical", List.of()));
    ApiResponse saved = admin.post("/api/v1/alerts/rules", rule);
    assertThat(saved.status()).as(saved.toString()).isEqualTo(201);
  }

  /**
   * (e) Every operation of the served OpenAPI document with a {@code text/csv} response, called as
   * an administrator over the run's range ({@code {appId}} = the seed app).
   */
  private static SentinelScans.Counts csvExports(int appId) {
    ApiResponse document = admin.get("/api/v1/openapi");
    assertThat(document.status()).as(document.toString()).isEqualTo(200);
    OpenApiSpec spec = OpenApiSpec.parse(document.body());
    Instant from = runStart.minus(5, ChronoUnit.MINUTES);
    Instant to = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
    SentinelScans.Counts counts = SentinelScans.Counts.empty();
    for (OpenApiSpec.Operation op : spec.operations()) {
      if (!answersCsv(op.node())) {
        continue;
      }
      String path = op.path().replace("{appId}", Integer.toString(appId));
      assertThat(path).as("path parameters of %s", op).doesNotContain("{");
      List<JsonNode> parameters = new ArrayList<>();
      op.node().path("parameters").forEach(parameters::add);
      op.pathParameters().forEach(parameters::add);
      boolean range = false;
      for (JsonNode p : parameters) {
        String name = p.path("name").asString();
        if (!"query".equals(p.path("in").asString()) || !p.path("required").asBoolean()) {
          continue;
        }
        if (!name.equals("from") && !name.equals("to")) {
          throw new AssertionError(op + ": unknown required parameter " + name);
        }
        range = true;
      }
      String location = op.method() + " " + op.path();
      counts.add(location, new long[SentinelScans.SENTINELS.size()]);
      csvExport(op, path, range || op.method().equals("POST"), from, to, counts, location, 0);
    }
    return counts;
  }

  /**
   * One CSV export over [from, to); a result above the 10,000-row limit (400 {@code
   * limit_exceeded}, 14 §4.17) is exported again as two halves, so that every row is read.
   */
  private static void csvExport(
      OpenApiSpec.Operation op,
      String path,
      boolean range,
      Instant from,
      Instant to,
      SentinelScans.Counts counts,
      String location,
      int depth) {
    String query =
        "?format=csv" + (range && op.method().equals("GET") ? "&from=" + from + "&to=" + to : "");
    Object body = null;
    if (op.method().equals("POST")) {
      Map<String, Object> search = new LinkedHashMap<>();
      search.put("from", from.toString());
      search.put("to", to.toString());
      search.put("kind", "ALL");
      body = search;
    }
    ApiResponse r = admin.send(op.method(), path + query, body, Map.of());
    long seconds = Duration.between(from, to).toSeconds();
    if (r.status() == 400
        && r.errorCode().filter("limit_exceeded"::equals).isPresent()
        && range
        && seconds > 1
        && depth < 12) {
      Instant mid = from.plusSeconds(seconds / 2);
      csvExport(op, path, range, from, mid, counts, location, depth + 1);
      csvExport(op, path, range, mid, to, counts, location, depth + 1);
      return;
    }
    assertThat(r.status()).as("%s [%s, %s): %s", location, from, to, r.errorCode()).isEqualTo(200);
    assertThat(r.header("Content-Type"))
        .as("%s Content-Type", location)
        .hasValueSatisfying(v -> assertThat(v).startsWith("text/csv"));
    counts.add(location, SentinelScans.occurrences(r.body()));
  }

  private static boolean answersCsv(JsonNode operation) {
    for (JsonNode response : operation.path("responses")) {
      if (response.path("content").has("text/csv")) {
        return true;
      }
    }
    return false;
  }

  private static JsonNode awaitJob(String jobId) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
    JsonNode last = null;
    while (System.nanoTime() < deadline) {
      ApiResponse r = admin.get("/api/v1/admin/privacy/jobs/" + jobId);
      assertThat(r.status()).as(r.toString()).isEqualTo(200);
      last = r.json().path("data");
      String status = last.path("status").asString();
      if (status.equals("done") || status.equals("failed")) {
        return last;
      }
      TimeUnit.MILLISECONDS.sleep(500);
    }
    throw new AssertionError("privacy deletion job " + jobId + " did not end: " + last);
  }

  // --- storage and logs -------------------------------------------------------------------------

  /** {@code pg_dump --data-only} of the {@code argos} database, run in the PostgreSQL container. */
  private static String pgDump() throws Exception {
    var result =
        env.postgresContainer()
            .execInContainer(
                "pg_dump",
                "--data-only",
                "--encoding=UTF8",
                "-U",
                "postgres",
                "-d",
                ServerTestEnvironment.DATABASE);
    assertThat(result.getExitCode())
        .as("pg_dump exit code (stderr: %s)", result.getStderr())
        .isZero();
    assertThat(result.getStdout()).as("pg_dump output").contains("COPY ");
    return result.getStdout();
  }

  /** Error groups of the app whose message pattern is {@code LIKE pattern} (PostgreSQL). */
  private static long errorGroups(int appId, String pattern) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM argos.error_groups WHERE app_id = ? AND message_pattern LIKE"
                    + " ?")) {
      ps.setInt(1, appId);
      ps.setString(2, pattern);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0;
      }
    }
  }

  private static boolean instanceUp(int appId, String instance) {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM argos.instances WHERE app_id = ? AND name = ? AND status ="
                    + " 'UP'")) {
      ps.setInt(1, appId);
      ps.setString(2, instance);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getLong(1) > 0;
      }
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * The agent's part of the app console: lines that carry a sentinel and come from the agent (its
   * start banner text or its package). The agent writes nothing there (T-AG-060, F-A35: the banner
   * goes to the agent log), so any such line would be a defect; the app's own error log lines with
   * the seed values are the sample app's output, counted separately.
   */
  private static long[] agentConsoleHits(Path stdout, Path stderr) {
    long[] total = new long[SentinelScans.SENTINELS.size()];
    for (Path file : List.of(stdout, stderr)) {
      for (String line : SentinelScans.lines(file)) {
        if (line.contains("Argos agent") || line.contains("io.argos.agent")) {
          long[] found = SentinelScans.lineHits(line);
          for (int i = 0; i < total.length; i++) {
            total[i] += found[i];
          }
        }
      }
    }
    return total;
  }

  private static long consoleSentinelLines(Path stdout, Path stderr) {
    long lines = 0;
    for (Path file : List.of(stdout, stderr)) {
      for (String line : SentinelScans.lines(file)) {
        if (SentinelScans.Counts.total(SentinelScans.lineHits(line)) > 0) {
          lines++;
        }
      }
    }
    return lines;
  }

  private static void merge(SentinelScans.Counts into, SentinelScans.Counts from) {
    from.byLocation().forEach(into::add);
  }

  // --- report -----------------------------------------------------------------------------------

  private static void section(String title, SentinelScans.Counts counts, List<String> hits) {
    List<String> found = counts.hits();
    REPORT
        .append("### ")
        .append(title)
        .append(": ")
        .append(counts.locations())
        .append(" locations, ")
        .append(found.size())
        .append(" with a sentinel\n\n");
    for (String hit : found) {
      REPORT.append("- ").append(hit).append('\n');
      hits.add(title + ": " + hit);
    }
    if (!found.isEmpty()) {
      REPORT.append('\n');
    }
  }

  private static void writeReport() {
    try {
      Path dir = repositoryRoot().resolve("server/build/reports/security");
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("pii-seed-grep.md"), REPORT.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.err.println("pii-seed-grep.md not written: " + e.getMessage());
    }
  }

  private static String row(String key, String value) {
    return "| " + key + " | " + value.replace("|", "\\|") + " |\n";
  }

  private static String seconds(long fromNanos, long toNanos) {
    return String.format(Locale.ROOT, "%.1f s", (toNanos - fromNanos) / 1e9);
  }

  private static long tables(List<SentinelScans.Column> columns) {
    return columns.stream().map(c -> c.database() + "." + c.table()).distinct().count();
  }

  // --- processes and files ----------------------------------------------------------------------

  private static Path jar(String key) {
    String value = System.getProperty("argos.test." + key);
    return value == null ? work.resolve("missing-" + key) : Paths.get(value);
  }

  private static Path bootJar() {
    String value = System.getProperty("argos.test.bootJar");
    assertThat(value).as("-Dargos.test.bootJar").isNotNull();
    Path jar = Paths.get(value);
    assertThat(jar).as("the server boot jar").isRegularFile();
    return jar;
  }

  private static Path repositoryRoot() {
    return Paths.get(System.getProperty("argos.repositoryRoot", "..")).toAbsolutePath();
  }

  private static String java() {
    return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static Path writeSecret(Path file, String value) throws IOException {
    Files.writeString(file, value, StandardCharsets.UTF_8);
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
    return file;
  }

  private static String randomToken() {
    byte[] bytes = new byte[18];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static void stopProcess(Process p) throws InterruptedException {
    if (p == null || !p.isAlive()) {
      return;
    }
    p.destroy();
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      p.waitFor(10, TimeUnit.SECONDS);
    }
  }

  private static void requireAlive(Process p, String name, Path log) {
    if (!p.isAlive()) {
      throw new AssertionError(name + " exited with " + p.exitValue() + "; log " + log);
    }
  }

  private static void await(Duration timeout, String what, BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out after " + timeout + " waiting for " + what);
      }
      TimeUnit.MILLISECONDS.sleep(500);
    }
  }

  private static int httpStatus(String url) {
    try {
      HttpResponse<Void> r =
          HTTP.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build(),
              HttpResponse.BodyHandlers.discarding());
      return r.statusCode();
    } catch (IOException e) {
      return -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  private static boolean accepts(int port) {
    try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
      return s.isConnected();
    } catch (IOException e) {
      return false;
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
