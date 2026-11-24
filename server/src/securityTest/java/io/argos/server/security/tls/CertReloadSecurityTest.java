/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.alert.engine.AlertEngine;
import io.argos.server.audit.Actor;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.tls.CertReloader;
import io.argos.server.security.SecuritySuiteAccess;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.Records;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Certificate expiry metric and reload of the gRPC listener (T-SEC-09, SEC-09; 16 §3.1, §3.3, 12
 * §9.5; ADR-0010 C-158 (5); WO-28) on a real server (PostgreSQL and ClickHouse in Testcontainers)
 * whose collector certificate expires in 7 days and whose key is an encrypted PKCS#8 key with
 * {@code argos.collector.tls.key-password-file}, with the file watch on (the default); and the
 * reloader without a server for {@code reload.watch=false}, the request file of {@code argos-admin
 * tls reload}, a failed reload and an expired certificate.
 */
@Tag("req:SEC-09")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CertReloadSecurityTest {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern EXPIRY =
      Pattern.compile(
          "(?m)^argos_server_tls_cert_expiry_seconds\\{[^}]*listener=\"grpc\"[^}]*\\} (\\S+)$");
  private static final Pattern EXPIRY_DAYS =
      Pattern.compile("(?m)^argos_server_tls_cert_expiry_days(?:\\{[^}]*\\})? (\\S+)$");

  @TempDir static Path tmp;

  private static char[] password;
  private static ReloadPki pki;
  private static ReloadPki.Leaf first;
  private static ReloadPki.Leaf second;
  private static Path liveCert;
  private static Path liveKey;
  private static Path passwordFile;
  private static String metricsToken;
  private static ArgosTestServer server;

  @BeforeAll
  static void start() throws Exception {
    password = secret().toCharArray();
    pki = new ReloadPki(tmp.resolve("pki"));
    first = pki.leaf("first", null, 7, password);
    second = pki.leaf("second", null, 30, password);
    Path live = Files.createDirectories(tmp.resolve("live"));
    liveCert = live.resolve("server.pem");
    liveKey = live.resolve("server-key.pem");
    Files.copy(first.chain(), liveCert);
    Files.copy(first.encryptedKey(), liveKey);
    passwordFile = tmp.resolve("key-password");
    Files.writeString(passwordFile, new String(password) + "\n", StandardCharsets.UTF_8);
    metricsToken = secret();
    Path tokenFile = Files.writeString(tmp.resolve("metrics-token"), metricsToken);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("argos.collector.tls.cert-file", liveCert.toString());
    p.put("argos.collector.tls.key-file", liveKey.toString());
    p.put("argos.collector.tls.key-password-file", passwordFile.toString());
    p.put("argos.metrics.allow-cidrs[0]", "127.0.0.1/32");
    p.put("argos.metrics.token-file", tokenFile.toString());
    server = SecuritySuiteAccess.start(p);
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SEC-09a a certificate 7 days from expiry: argos_server_tls_cert_expiry_seconds{listener="
          + "\"grpc\"} ≈ 7 days on the authenticated 8081 endpoint, the alert gauge ≈ 7 days, and"
          + " the server rule \"Server certificate expiry\" (cert_token_expiry template) FIRING")
  void expiryMetricAndAlert() throws Exception {
    String scrape = scrape();
    double seconds = value(EXPIRY, scrape);
    assertThat(seconds).isBetween(7 * 86_400.0 - 3_600, 7 * 86_400.0);
    assertThat(value(EXPIRY_DAYS, scrape)).isBetween(6.95, 7.0);
    UUID rule = certRule();
    AlertEngine engine = server.bean(AlertEngine.class);
    Awaitility.await()
        .atMost(60, TimeUnit.SECONDS)
        .until(
            () -> {
              engine.evaluateNow();
              return engine.activeAlerts().stream()
                  .anyMatch(a -> a.ruleId().equals(rule) && "FIRING".equals(a.state()));
            });
    assertThat(engine.activeAlerts())
        .filteredOn(a -> a.ruleId().equals(rule))
        .singleElement()
        .satisfies(a -> assertThat(a.ruleMetric()).isEqualTo("argos_server_tls_cert_expiry_days"));
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SEC-09c an encrypted PKCS#8 key with argos.collector.tls.key-password-file: the listener"
          + " serves the certificate; the password is in no log line; a wrong password refuses"
          + " the context without the password in the message")
  void encryptedKey() throws Exception {
    assertThat(Files.readString(liveKey)).startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----");
    assertThat(handshakeSerial()).isEqualTo(first.serial());
    assertThat(Files.readString(SecuritySuiteAccess.logFile()))
        .doesNotContain(new String(password));

    Path wrong = Files.writeString(tmp.resolve("wrong-password"), "not-the-password\n");
    ArgosProperties props =
        properties(liveCert, liveKey, wrong, true, tmp.resolve("wrong-data/wal"));
    assertThatThrownBy(() -> reloader(props).grpcContext())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("argos.collector.tls.key-password-file")
        .hasMessageNotContaining("not-the-password");
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SEC-09b the certificate files replaced: without a restart new handshakes present the new"
          + " certificate (serial), an agent session opened before keeps working, the gauge"
          + " follows, TLS_RELOADED is audited (actor system, source watch)")
  void watchReload() throws Exception {
    AgentTokens.IssuedToken token;
    try (Connection c = SecuritySuiteAccess.environment().connect(PgAccount.APP)) {
      token = AgentTokens.issue(c, AgentTokens.ensureApp(c, "tls-reload", "prod"));
    }
    try (FakeAgent agent =
        FakeAgent.builder(AgentChannels.tls("localhost", server.collectorPort(), pki.ca()))
            .ownsChannel(true)
            .token(token.token())
            .appName("tls-reload")
            .instanceName("host-tls:8080")
            .records(new Records(AgentClock.system(), 1, 1))
            .agentEpoch(1_001)
            .callDeadline(Duration.ofSeconds(30))
            .build()) {
      assertThat(agent.connect().accepted()).isTrue();
      assertThat(agent.heartbeatRound(Duration.ofSeconds(5))).isPresent();

      replace(second.chain(), second.encryptedKey());
      Awaitility.await()
          .atMost(45, TimeUnit.SECONDS)
          .pollInterval(Duration.ofSeconds(1))
          .until(() -> second.serial().equals(handshakeSerial()));
      assertThat(agent.heartbeatRound(Duration.ofSeconds(5)))
          .as("the session opened before the reload keeps its connection")
          .isPresent();
    }
    assertThat(value(EXPIRY, scrape())).isBetween(29 * 86_400.0, 30 * 86_400.0);
    List<AuditLog.Row> rows = AuditLog.rows(SecuritySuiteAccess.environment(), "TLS_RELOADED");
    assertThat(rows)
        .anySatisfy(
            r -> {
              assertThat(r.actorType()).isEqualTo("system");
              assertThat(r.result()).isEqualTo("success");
              assertThat(r.targetId()).isEqualTo("grpc");
              assertThat(r.after()).contains("\"watch\"").contains(second.serial().toString(16));
            });
    assertThat(rows).noneSatisfy(r -> assertThat(r.after()).contains(new String(password)));
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-SEC-09d POST /api/v1/admin/tls/reload: admin only (viewer and operator 403), {listener,"
          + " not_after, reloaded, serial}, TLS_RELOADED audited with the user (source api)")
  void manualReloadApi() throws Exception {
    for (String role : List.of("viewer", "operator")) {
      assertThat(
              SecuritySuiteAccess.client(server, role)
                  .post("/api/v1/admin/tls/reload", null)
                  .status())
          .as(role)
          .isEqualTo(403);
    }
    ApiResponse r =
        SecuritySuiteAccess.client(server, "admin").post("/api/v1/admin/tls/reload", null);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode data = r.json().path("data");
    assertThat(data.path("listener").asString()).isEqualTo("grpc");
    assertThat(data.path("reloaded").asBoolean()).isTrue();
    assertThat(data.path("serial").asString()).isEqualTo(second.serial().toString(16));
    assertThat(data.path("not_after").asString()).isNotBlank();
    assertThat(AuditLog.rows(SecuritySuiteAccess.environment(), "TLS_RELOADED"))
        .anySatisfy(
            row -> {
              assertThat(row.actorType()).isEqualTo("user");
              assertThat(row.result()).isEqualTo("success");
              assertThat(row.after()).contains("\"api\"");
            });
  }

  @Test
  @Order(5)
  @DisplayName(
      "T-SEC-09d reload.watch=false: replaced files are not picked up; the argos-admin request"
          + " file reloads once and writes its outcome; a failed reload keeps the previous"
          + " certificate and says why without key material")
  void manualOnlyWithoutWatch() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("nowatch"));
    Path cert = dir.resolve("server.pem");
    Path key = dir.resolve("server-key.pem");
    Files.copy(first.chain(), cert);
    Files.copy(first.key(), key);
    ArgosProperties props = properties(cert, key, null, false, dir.resolve("data/wal"));
    CertReloader reloader = reloader(props);
    try {
      reloader.grpcContext();
      assertThat(serial(reloader)).isEqualTo(first.serial());
      Files.copy(second.chain(), cert, StandardCopyOption.REPLACE_EXISTING);
      Files.copy(second.key(), key, StandardCopyOption.REPLACE_EXISTING);
      TimeUnit.SECONDS.sleep(8);
      assertThat(serial(reloader)).as("not watched").isEqualTo(first.serial());

      Path request = CertReloader.requestFile(props);
      Path result = CertReloader.resultFile(props);
      Files.createDirectories(request.getParent());
      Files.writeString(request, "");
      Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> Files.exists(result));
      assertThat(Files.exists(request)).isFalse();
      assertThat(Files.readString(result))
          .contains("\"reloaded\":true")
          .contains(second.serial().toString(16));
      assertThat(serial(reloader)).isEqualTo(second.serial());

      String keyText = Files.readString(first.key());
      Files.writeString(
          key, "-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----\n");
      CertReloader.ReloadResult failed = reloader.reload(Actor.system(), "api");
      assertThat(failed.reloaded()).isFalse();
      assertThat(failed.error()).contains("argos.collector.tls").doesNotContain("bm90IGEga2V5");
      assertThat(failed.error()).doesNotContain(keyText.substring(30, 60));
      assertThat(serial(reloader)).as("previous certificate kept").isEqualTo(second.serial());
    } finally {
      reloader.destroy();
    }
  }

  @Test
  @Order(6)
  @DisplayName(
      "T-SEC-09a an expired certificate does not stop the listener (16 §3.3: ERROR only): the"
          + " context loads and the expiry is negative")
  void expiredCertificateStarts() throws Exception {
    ReloadPki.Leaf expired = pki.leaf("expired", "-10d", 5, password);
    ArgosProperties props =
        properties(expired.chain(), expired.key(), null, false, tmp.resolve("expired-data/wal"));
    CertReloader reloader = reloader(props);
    try {
      assertThat(reloader.grpcContext()).isNotNull();
      assertThat(reloader.expirySeconds()).isNegative();
    } finally {
      reloader.destroy();
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static BigInteger serial(CertReloader r) {
    return new BigInteger(r.current().orElseThrow().serial(), 16);
  }

  private static void replace(Path chain, Path key) throws IOException {
    for (Map.Entry<Path, Path> e : Map.of(chain, liveCert, key, liveKey).entrySet()) {
      Path staged = e.getValue().resolveSibling(e.getValue().getFileName() + ".new");
      Files.copy(e.getKey(), staged, StandardCopyOption.REPLACE_EXISTING);
      Files.move(
          staged,
          e.getValue(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    }
  }

  private static ArgosProperties properties(
      Path cert, Path key, Path passwordFile, boolean watch, Path walDir) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("argos.collector.tls.cert-file", cert.toString());
    p.put("argos.collector.tls.key-file", key.toString());
    if (passwordFile != null) {
      p.put("argos.collector.tls.key-password-file", passwordFile.toString());
    }
    p.put("argos.collector.tls.reload.watch", Boolean.toString(watch));
    p.put("argos.wal.dir", walDir.toString());
    return new Binder(new MapConfigurationPropertySource(p))
        .bindOrCreate("argos", ArgosProperties.class);
  }

  private static CertReloader reloader(ArgosProperties props) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("meters", new SimpleMeterRegistry());
    return new CertReloader(
        props,
        beans.getBeanProvider(io.argos.server.audit.AuditService.class),
        beans.getBeanProvider(MeterRegistry.class),
        beans.getBeanProvider(java.time.Clock.class));
  }

  private static UUID certRule() throws Exception {
    try (Connection c = SecuritySuiteAccess.environment().connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT id FROM argos.alert_rules WHERE app_id IS NULL AND deleted_at IS NULL"
                    + " AND spec->>'metric' = 'argos_server_tls_cert_expiry_days'");
        ResultSet rs = ps.executeQuery()) {
      assertThat(rs.next()).as("the server certificate rule").isTrue();
      return (UUID) rs.getObject(1);
    }
  }

  /** The serial number of the certificate a new TLS handshake with the collector presents. */
  private static BigInteger handshakeSerial() throws Exception {
    KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
    trust.load(null, null);
    try (InputStream in = Files.newInputStream(pki.ca())) {
      trust.setCertificateEntry(
          "ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
    }
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust);
    SSLContext ssl = SSLContext.getInstance("TLS");
    ssl.init(null, tmf.getTrustManagers(), RANDOM);
    try (SSLSocket socket =
        (SSLSocket) ssl.getSocketFactory().createSocket("127.0.0.1", server.collectorPort())) {
      SSLParameters params = socket.getSSLParameters();
      params.setApplicationProtocols(new String[] {"h2"});
      socket.setSSLParameters(params);
      socket.setSoTimeout(10_000);
      socket.startHandshake();
      return ((X509Certificate) socket.getSession().getPeerCertificates()[0]).getSerialNumber();
    }
  }

  private static String scrape() throws Exception {
    HttpResponse<String> r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://127.0.0.1:" + server.managementPort() + "/actuator/prometheus"))
                    .header("Authorization", "Bearer " + metricsToken)
                    .timeout(Duration.ofSeconds(10))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    return r.body();
  }

  private static double value(Pattern pattern, String scrape) {
    Matcher m = pattern.matcher(scrape);
    List<Double> values = new ArrayList<>();
    while (m.find()) {
      values.add(Double.parseDouble(m.group(1)));
    }
    assertThat(values).as(pattern.pattern()).hasSize(1);
    return values.get(0);
  }

  private static String secret() {
    byte[] bytes = new byte[18];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
