/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.ServerApplication;
import io.argos.server.bootstrap.StartupSequence;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.web.RequestUrlLimitFilter;
import io.argos.storage.clickhouse.Account;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The management port and the HTTP port of the real server (WO-20; SEC-55, 02 C-7, 16 §7.12, 14
 * §2.1, §7.2, 17 §3.5, §11.1) against PostgreSQL 16 and ClickHouse (Testcontainers), with every
 * start phase.
 *
 * <p>One server starts for the class and is held before its ClickHouse migration by the test hook
 * {@code argos.testing.hold-before-phase}: while held, neither port accepts a connection, so no
 * probe can see the server ready; after the release readiness is UP. A second short start with
 * other {@code allow-cidrs} checks the address rule.
 *
 * <p>Boot 4.1 and Tomcat: {@code management.server.port} starts a second Tomcat connector in a
 * child context bound to {@code management.server.address}; the Actuator endpoints are served there
 * only (404 on the HTTP port), and the servlet filters of the HTTP port do not run there.
 */
@Tag("req:SEC-55")
@Tag("req:F-S20")
class RuntimeManagementIT {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /** Meters whose registering code (first frame outside Micrometer) is an Argos server class. */
  private static final Set<String> ARGOS_METERS = ConcurrentHashMap.newKeySet();

  @TempDir static Path tmp;

  private static ServerTestEnvironment env;
  private static ConfigurableApplicationContext server;
  private static String token;
  private static int httpPort;
  private static int managementPort;
  private static long logStart;
  private static final Map<String, String> DURING_HOLD =
      Collections.synchronizedMap(new LinkedHashMap<>());

  @BeforeAll
  static void startServer() throws Exception {
    Path log = IntegrationLogs.logFile();
    logStart = Files.exists(log) ? Files.size(log) : 0;
    env = ServerTestEnvironment.start();
    token = newSecret();
    Path tokenFile =
        Files.writeString(tmp.resolve("metrics-token"), token + "\n", StandardCharsets.UTF_8);
    httpPort = freePort();
    managementPort = freePort();
    Path hold = Files.createFile(tmp.resolve("hold"));

    Map<String, Object> properties = baseProperties(httpPort, managementPort, tokenFile);
    properties.put("argos.metrics.allow-cidrs[0]", "127.0.0.1/32");
    properties.put("argos.metrics.allow-cidrs[1]", "::1/128");
    properties.put("argos.testing.hold-before-phase", "clickhouse-migration");
    properties.put("argos.testing.hold-file", hold.toString());
    SpringApplication app = application(properties);
    app.addInitializers(
        context ->
            context
                .getBeanFactory()
                .registerSingleton(
                    "argosMeterOriginProbe",
                    (MeterRegistryCustomizer<MeterRegistry>)
                        registry ->
                            registry.config().onMeterAdded(RuntimeManagementIT::recordOrigin)));
    CompletableFuture<ConfigurableApplicationContext> started =
        CompletableFuture.supplyAsync(app::run);

    Path reached = tmp.resolve("hold.reached");
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
    while (!Files.exists(reached)) {
      if (started.isDone()) {
        started.join(); // throws the start failure
        throw new AssertionError("the server started without reaching the hold");
      }
      assertThat(System.nanoTime() - deadline).as("hold reached within 5 minutes").isNegative();
      TimeUnit.MILLISECONDS.sleep(100);
    }
    DURING_HOLD.put(
        "http", accepts(InetAddress.getLoopbackAddress(), httpPort) ? "open" : "refused");
    DURING_HOLD.put(
        "management",
        accepts(InetAddress.getLoopbackAddress(), managementPort) ? "open" : "refused");
    DURING_HOLD.put("readiness", probeReadiness());
    TimeUnit.MILLISECONDS.sleep(500);
    DURING_HOLD.put("startedWhileHeld", Boolean.toString(started.isDone()));
    Files.delete(hold);
    server = started.get(5, TimeUnit.MINUTES);
  }

  @AfterAll
  static void stopServer() {
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

  // --- helpers
  // ------------------------------------------------------------------------------------

  private static Map<String, Object> baseProperties(int http, int management, Path tokenFile) {
    IntegrationLogs.ensureLogPath();
    Map<String, Object> p = new LinkedHashMap<>(env.springProperties());
    p.put("server.address", "127.0.0.1");
    p.put("server.port", Integer.toString(http));
    p.put("management.server.address", "127.0.0.1");
    p.put("management.server.port", Integer.toString(management));
    p.put("argos.web.public-url", "http://127.0.0.1:" + http);
    p.put("argos.metrics.token-file", tokenFile.toString());
    return p;
  }

  private static SpringApplication application(Map<String, Object> properties) {
    SpringApplication app = new SpringApplication(ServerApplication.class);
    app.setDefaultProperties(properties);
    return app;
  }

  private static void recordOrigin(Meter meter) {
    Optional<String> origin =
        StackWalker.getInstance()
            .walk(
                frames ->
                    frames
                        .map(StackWalker.StackFrame::getClassName)
                        .filter(
                            c ->
                                !c.startsWith("io.micrometer.")
                                    && !c.startsWith(RuntimeManagementIT.class.getName()))
                        .findFirst());
    if (origin.isPresent() && origin.get().startsWith("io.argos.server.")) {
      ARGOS_METERS.add(meter.getId().getName());
    }
  }

  private static String newSecret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static boolean accepts(InetAddress address, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(address, port), 1000);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static String probeReadiness() {
    try {
      return Integer.toString(get(managementPort, "/actuator/health/readiness", null).statusCode());
    } catch (ConnectException e) {
      return "refused";
    } catch (IOException e) {
      return "refused: " + e.getClass().getSimpleName();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "interrupted";
    }
  }

  private static HttpResponse<String> get(int port, String path, String authorization)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .GET();
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  // --- tests -------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "T-SV-001a readiness: no listener and not ready while the migrations are held, UP"
          + " afterwards")
  void readinessFollowsMigrations() throws Exception {
    assertThat(DURING_HOLD)
        .containsEntry("http", "refused")
        .containsEntry("management", "refused")
        .containsEntry("readiness", "refused")
        .containsEntry("startedWhileHeld", "false");
    StartupSequence sequence = server.getBean(StartupSequence.class);
    assertThat(sequence.isComplete()).isTrue();
    // The real steps of the server, in DSN-12-15 order; no test application leaked into the
    // component scan (its probe steps would show up here).
    assertThat(sequence.results().stream().map(StartupSequence.StepResult::name))
        .contains(
            "config-warnings",
            "effective-config",
            "config-validation",
            "credential-policy",
            "metrics-access",
            "testing-hold",
            "postgres-flyway",
            "postgres-runtime-pool",
            "clickhouse-migration")
        .noneMatch(name -> name.startsWith("probe-"));
    assertThat(sequence.results().stream().map(StartupSequence.StepResult::phase))
        .isSortedAccordingTo(Comparator.naturalOrder());

    HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness", null);
    assertThat(readiness.statusCode()).isEqualTo(200);
    assertThat(readiness.body()).isEqualTo("{\"status\":\"UP\"}");
    HttpResponse<String> liveness = get(managementPort, "/actuator/health/liveness", null);
    assertThat(liveness.statusCode()).isEqualTo(200);
    assertThat(liveness.body()).isEqualTo("{\"status\":\"UP\"}");

    // Readiness follows the availability state; liveness does not.
    AvailabilityChangeEvent.publish(server, ReadinessState.REFUSING_TRAFFIC);
    try {
      HttpResponse<String> refusing = get(managementPort, "/actuator/health/readiness", null);
      assertThat(refusing.statusCode()).isEqualTo(503);
      assertThat(refusing.body()).doesNotContain("components").doesNotContain("details");
      assertThat(get(managementPort, "/actuator/health/liveness", null).statusCode())
          .isEqualTo(200);
    } finally {
      AvailabilityChangeEvent.publish(server, ReadinessState.ACCEPTING_TRAFFIC);
    }
    assertThat(get(managementPort, "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
  }

  @Test
  @DisplayName("T-SEC-55 /actuator/env and the other endpoints are 404 on 8080 and 8081")
  void disabledEndpoints() throws Exception {
    String bearer = "Bearer " + token;
    for (String endpoint :
        List.of(
            "env",
            "heapdump",
            "threaddump",
            "loggers",
            "mappings",
            "shutdown",
            "beans",
            "configprops",
            "metrics")) {
      String path = "/actuator/" + endpoint;
      assertThat(get(managementPort, path, null).statusCode()).as("8081 " + path).isEqualTo(404);
      assertThat(get(managementPort, path, bearer).statusCode())
          .as("8081 " + path + " with token")
          .isEqualTo(404);
      assertThat(get(httpPort, path, null).statusCode()).as("8080 " + path).isEqualTo(404);
    }
    for (String path :
        List.of("/actuator/health", "/actuator/prometheus", "/actuator/info", "/actuator")) {
      assertThat(get(httpPort, path, bearer).statusCode()).as("8080 " + path).isEqualTo(404);
    }
  }

  @Test
  @DisplayName("T-SEC-55 health is open and shows no details")
  void healthOpen() throws Exception {
    HttpResponse<String> health = get(managementPort, "/actuator/health", null);
    assertThat(health.statusCode()).isEqualTo(200);
    // Status and the names of the probe groups only: no contributor, no detail.
    assertThat(health.body())
        .isIn("{\"status\":\"UP\"}", "{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"UP\"}");
    assertThat(health.body())
        .doesNotContain("components")
        .doesNotContain("details")
        .doesNotContain("db")
        .doesNotContain("diskSpace");
  }

  @Test
  @DisplayName(
      "T-SEC-55 prometheus and info: no token 401, wrong token 401, token from 127.0.0.1 200")
  void metricsNeedToken() throws Exception {
    for (String path : List.of("/actuator/prometheus", "/actuator/info")) {
      HttpResponse<String> none = get(managementPort, path, null);
      assertThat(none.statusCode()).as(path).isEqualTo(401);
      assertThat(none.headers().firstValue("WWW-Authenticate"))
          .hasValueSatisfying(v -> assertThat(v).startsWith("Bearer"));
      assertThat(get(managementPort, path, "Bearer " + newSecret()).statusCode())
          .as(path)
          .isEqualTo(401);
      assertThat(get(managementPort, path, "Basic " + token).statusCode()).as(path).isEqualTo(401);
    }
    HttpResponse<String> info = get(managementPort, "/actuator/info", "Bearer " + token);
    assertThat(info.statusCode()).isEqualTo(200);
    assertThat(info.body())
        .contains("\"version\":\"0.0.0-SNAPSHOT\"")
        .contains("\"commit\"")
        .contains("\"protocol\"");
  }

  @Test
  @DisplayName("T-SV-047 prometheus: argos_build_info, application tag, JVM and process series")
  void prometheusContent() throws Exception {
    HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus", "Bearer " + token);
    assertThat(scrape.statusCode()).isEqualTo(200);
    assertThat(scrape.body())
        .containsPattern(
            "(?m)^argos_build_info\\{[^}]*application=\"argos-server\"[^}]*version=\"0\\.0\\.0-SNAPSHOT\"[^}]*} 1(\\.0)?$")
        .containsPattern("(?m)^jvm_memory_used_bytes\\{")
        .containsPattern("(?m)^process_cpu_usage\\{|^process_uptime_seconds\\{")
        .doesNotContain(token);
    List<String> argosSeries =
        scrape
            .body()
            .lines()
            .filter(l -> l.startsWith("argos_"))
            .map(l -> l.replaceAll("[{ ].*", ""))
            .distinct()
            .toList();
    assertThat(argosSeries).allMatch(n -> n.matches("argos_[a-z0-9]+(_[a-z0-9]+)*"));
  }

  @Test
  @DisplayName("T-SV-047 every meter registered by io.argos.server follows the argos_ naming rule")
  void meterNames() {
    MeterRegistry registry = server.getBean(MeterRegistry.class);
    assertThat(ARGOS_METERS)
        .as("the probe saw the server's own meters")
        .contains(BuildInfoMetrics.NAME);
    List<String> violations = new ArrayList<>();
    for (Meter meter : registry.getMeters()) {
      if (ARGOS_METERS.contains(meter.getId().getName())) {
        MeterNames.violation(meter.getId()).ifPresent(violations::add);
      }
    }
    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("T-SEC-55 the management port is bound to 127.0.0.1 only")
  void managementLoopbackOnly() throws Exception {
    assertThat(accepts(InetAddress.getLoopbackAddress(), managementPort)).isTrue();
    InetAddress other = nonLoopbackAddress();
    Assumptions.assumeTrue(
        other != null, "no non-loopback local address on this machine: nothing to probe");
    assertThat(accepts(other, managementPort)).as("8081 on " + other.getHostAddress()).isFalse();
  }

  private static InetAddress nonLoopbackAddress() throws IOException {
    for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
      if (!nic.isUp() || nic.isLoopback()) {
        continue;
      }
      for (InetAddress address : Collections.list(nic.getInetAddresses())) {
        if (address instanceof Inet4Address
            && !address.isLoopbackAddress()
            && !address.isLinkLocalAddress()) {
          return address;
        }
      }
    }
    return null;
  }

  @Test
  @DisplayName(
      "HTTP port: X-Request-Id, X-Argos-Server-Version, header and body limits (14 §2.1, §7.2)")
  void httpPort() throws Exception {
    HttpResponse<String> missing = get(httpPort, "/api/v1/does-not-exist", null);
    assertThat(missing.headers().firstValue("X-Argos-Server-Version")).contains("0.0.0-SNAPSHOT");
    assertThat(missing.headers().firstValue("X-Request-Id"))
        .hasValueSatisfying(id -> assertThat(id).matches("[0-9A-HJKMNP-TV-Z]{26}"));
    assertThat(missing.body()).doesNotContain("Exception").doesNotContain("trace");

    HttpResponse<String> bigHeader =
        HTTP.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/api/v1/x"))
                .header("X-Filler", "a".repeat(17 * 1024))
                .timeout(Duration.ofSeconds(20))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(bigHeader.statusCode()).isEqualTo(400);

    // A declared body over 1 MB is refused before it is sent: headers only, then read the answer
    // (a client that keeps sending would see the connection closed once Tomcat stops swallowing).
    String bigBody =
        rawRequest(
            "POST /api/v1/x HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                + "Content-Length: "
                + (2 * 1024 * 1024)
                + "\r\nConnection: close\r\n\r\n");
    assertThat(bigBody)
        .startsWith("HTTP/1.1 400")
        .contains("\"code\":\"payload_too_large\"")
        .contains("\"request_id\"")
        .containsIgnoringCase("X-Argos-Server-Version: 0.0.0-SNAPSHOT");
  }

  @Test
  @DisplayName(
      "HTTP port: a URL over 8 KB is 400 limit_exceeded with request id and version (14 §7.2)")
  void urlLimit() throws Exception {
    String query = "q=" + "a".repeat(12_000);
    HttpResponse<String> longUrl = get(httpPort, "/api/v1/x?" + query, null);
    assertThat(longUrl.statusCode()).isEqualTo(400);
    assertThat(longUrl.body())
        .contains("\"code\":\"limit_exceeded\"")
        .contains("\"message\":\"The request URL exceeds the limit of 8 KB\"")
        .contains("\"request_id\"")
        .doesNotContain("aaaa");
    assertThat(longUrl.headers().firstValue("X-Argos-Server-Version")).contains("0.0.0-SNAPSHOT");
    assertThat(longUrl.headers().firstValue("X-Request-Id"))
        .hasValueSatisfying(id -> assertThat(longUrl.body()).contains("\"request_id\":\"" + id));

    // Just below the limit the request is handled (no endpoint: 404).
    int room = RequestUrlLimitFilter.MAX_URL_BYTES - "/api/v1/x?q=".length();
    assertThat(get(httpPort, "/api/v1/x?q=" + "a".repeat(room), null).statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName("HTTP port: Tomcat runs requests on virtual threads, shuts down gracefully (12 §11)")
  void virtualThreadsAndGracefulShutdown() throws Exception {
    WebServer web = ((WebServerApplicationContext) server).getWebServer();
    assertThat(web).isInstanceOf(TomcatWebServer.class);
    assertThat(web.getPort()).isEqualTo(httpPort);
    Executor executor =
        ((TomcatWebServer) web).getTomcat().getConnector().getProtocolHandler().getExecutor();
    assertThat(executor).isInstanceOf(VirtualThreadExecutor.class);
    assertThat(server.getEnvironment().getProperty("server.shutdown")).isEqualTo("graceful");
  }

  /** Sends raw request bytes to the HTTP port and returns everything the server answers. */
  private static String rawRequest(String request) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), httpPort), 5000);
      socket.setSoTimeout(3_000);
      socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      java.io.ByteArrayOutputStream answer = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      try {
        for (int n; (n = socket.getInputStream().read(buffer)) > 0; ) {
          answer.write(buffer, 0, n);
        }
      } catch (java.net.SocketTimeoutException | java.net.SocketException e) {
        // The server may keep the connection open or reset it after the answer.
      }
      return answer.toString(StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("start log: phases in order, effective settings, no password or token")
  void startLog() throws Exception {
    byte[] bytes = Files.readAllBytes(IntegrationLogs.logFile());
    String log = new String(bytes, StandardCharsets.UTF_8);
    // This class's part of the shared log: the bytes written since the class started.
    String run = IntegrationLogs.since(logStart);
    assertThat(run)
        .contains("Startup phase CONFIG step config-warnings completed")
        .contains("Startup phase CONFIG step credential-policy completed")
        .contains("Effective configuration: ")
        .contains("Setting argos.metrics.token-file = ")
        .contains("Test hook: start held before phase CLICKHOUSE_MIGRATION");
    assertThat(run.indexOf("step postgres-flyway completed"))
        .isGreaterThan(run.indexOf("step credential-policy completed"));
    List<String> secrets = new ArrayList<>(List.of(token));
    for (PgAccount a : PgAccount.values()) {
      secrets.add(env.password(a));
    }
    for (Account a : Account.values()) {
      secrets.add(env.password(a));
    }
    for (String secret : secrets) {
      assertThat(log).doesNotContain(secret);
    }
    assertThat(run.lines().filter(l -> !l.isBlank())).allMatch(l -> l.startsWith("{\"ts\":\""));
  }

  @Test
  @DisplayName("T-SEC-55 a client outside argos.metrics.allow-cidrs gets 403, even with the token")
  void addressOutsideRanges() throws Exception {
    int http = freePort();
    int management = freePort();
    Map<String, Object> properties = baseProperties(http, management, tmp.resolve("metrics-token"));
    properties.put("argos.metrics.allow-cidrs[0]", "10.0.0.0/8");
    // A second server next to the class's server on the same environment: its own gRPC port and its
    // own WAL directory (WO-21), or it would collide with the first.
    properties.put("argos.collector.port", Integer.toString(freePort()));
    properties.put("argos.wal.dir", tmp.resolve("wal-outside-ranges").toString());
    try (ConfigurableApplicationContext other = application(properties).run()) {
      for (String path : List.of("/actuator/prometheus", "/actuator/info")) {
        HttpResponse<String> withToken = get(management, path, "Bearer " + token);
        assertThat(withToken.statusCode()).as(path).isEqualTo(403);
        assertThat(withToken.body())
            .contains("\"code\":\"forbidden\"")
            .doesNotContain("argos_build_info");
        assertThat(get(management, path, null).statusCode()).as(path).isEqualTo(403);
      }
      assertThat(get(management, "/actuator/health", null).statusCode()).isEqualTo(200);
      assertThat(other.isActive()).isTrue();
    }
  }
}
