/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.Check;
import io.argos.sample.common.catalog.DemoCatalog;
import io.argos.sample.common.db.ShopSchema;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The demo app of the demo profile (17 §3.9, WO-41) against a real PostgreSQL (the pinned image of
 * deploy/postgres/image.properties): order-api and payment-api started together on one database,
 * every DemoCatalog entry with its status, checks and SQL count, the checkout calling payment-api,
 * 502 when payment-api is unreachable, the settlement batch with a short period, the database wait
 * and the password file.
 */
@Tag("req:N-16")
class DemoAppIT {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static PostgreSQLContainer postgres;
  private static Path passwordFile;
  private static ConfigurableApplicationContext order;
  private static ConfigurableApplicationContext payment;
  private static int orderPort;
  private static int paymentPort;

  @BeforeAll
  @SuppressWarnings("resource") // stopped in stop()
  static void start() throws Exception {
    String image = System.getProperty("argos.demo.postgresImage");
    assertThat(image).as("system property argos.demo.postgresImage (set by Gradle)").isNotBlank();
    String password = randomSecret();
    postgres =
        new PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("demo")
            .withUsername("demo")
            .withPassword(password)
            .withLabel("io.argos.test", "demo-app-it");
    postgres.start();
    passwordFile = Files.createTempFile("demo_db_password", "");
    Files.writeString(passwordFile, password + "\n", StandardCharsets.UTF_8);

    orderPort = freePort();
    paymentPort = freePort();
    // Both roles start at the same time on the same database (two schemas, no conflict).
    CompletableFuture<ConfigurableApplicationContext> paymentStart =
        CompletableFuture.supplyAsync(() -> app("payment", paymentPort, Map.of()));
    CompletableFuture<ConfigurableApplicationContext> orderStart =
        CompletableFuture.supplyAsync(
            () ->
                app(
                    "order",
                    orderPort,
                    Map.of("demo.peer-url", "http://127.0.0.1:" + paymentPort)));
    payment = paymentStart.get();
    order = orderStart.get();
  }

  @AfterAll
  static void stop() throws IOException {
    if (order != null) {
      order.close();
    }
    if (payment != null) {
      payment.close();
    }
    if (postgres != null) {
      postgres.stop();
    }
    if (passwordFile != null) {
      Files.deleteIfExists(passwordFile);
    }
  }

  /**
   * One app context with the batch every 2 s (first run after 1 s). The settings are command-line
   * arguments, which win over the app's application.properties (server.port=8080).
   */
  private static ConfigurableApplicationContext app(
      String role, int port, Map<String, String> extra) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("server.port", port);
    properties.put("server.address", "127.0.0.1");
    properties.put("demo.role", role);
    properties.put("demo.db.url", postgres.getJdbcUrl());
    properties.put("demo.db.user", postgres.getUsername());
    properties.put("demo.db.password-file", passwordFile.toString());
    properties.put("demo.batch.initial-delay", "1s");
    properties.put("demo.batch.period", "2s");
    properties.putAll(extra);
    String[] args =
        properties.entrySet().stream()
            .map(e -> "--" + e.getKey() + "=" + e.getValue())
            .toArray(String[]::new);
    return new SpringApplicationBuilder(DemoApplication.class, SqlCounting.class).run(args);
  }

  @Test
  @DisplayName(
      "T-NFR-16e order-api and payment-api start together on one database, one schema each")
  void bothRolesOnOneDatabase() throws Exception {
    JsonNode orderHealth = json(get(orderPort, "/healthz"));
    JsonNode paymentHealth = json(get(paymentPort, "/healthz"));
    assertThat(orderHealth.path("status").asString()).isEqualTo("UP");
    assertThat(orderHealth.path("role").asString()).isEqualTo("order");
    assertThat(paymentHealth.path("status").asString()).isEqualTo("UP");
    assertThat(paymentHealth.path("role").asString()).isEqualTo("payment");
    for (String schema : List.of("demo_order", "demo_payment")) {
      assertThat(count("SELECT COUNT(*) FROM " + schema + ".orders")).isEqualTo(ShopSchema.ORDERS);
      assertThat(count("SELECT COUNT(*) FROM " + schema + ".order_items"))
          .isEqualTo(ShopSchema.ORDERS * ShopSchema.ITEMS_PER_ORDER);
    }
  }

  @Test
  @DisplayName(
      "T-NFR-16e every DemoCatalog entry: status, checks and SQL count (N+1 = 21); checkout calls"
          + " payment-api")
  void catalogEntries() throws Exception {
    SqlCounting.Counter orderSql = order.getBean(SqlCounting.Counter.class);
    SqlCounting.Counter paymentSql = payment.getBean(SqlCounting.Counter.class);
    List<String> checked = new ArrayList<>();
    for (CatalogEntry entry : DemoCatalog.entries()) {
      boolean onPayment = ("role:" + DemoCatalog.ROLE_PAYMENT).equals(entry.requires());
      int port = onPayment ? paymentPort : orderPort;
      SqlCounting.Counter counter = onPayment ? paymentSql : orderSql;
      counter.http.set(0);
      paymentSql.http.set(0);
      long started = System.nanoTime();
      HttpResponse<String> response = get(port, entry.path());
      long elapsedMs = (System.nanoTime() - started) / 1_000_000;
      String what = entry.id() + " " + entry.path();
      assertThat(response.statusCode()).as(what).isEqualTo(entry.expectedStatus());
      for (Check check : entry.checks()) {
        switch (check.type()) {
          case BODY_CONTAINS -> assertThat(response.body()).as(what).contains(check.value());
          case JSON_EQUALS ->
              assertThat(json(response).path(check.key()).asString())
                  .as(what)
                  .isEqualTo(check.value());
          case MIN_ELAPSED_MS ->
              assertThat(elapsedMs).as(what).isGreaterThanOrEqualTo(Long.parseLong(check.value()));
          default -> throw new AssertionError("unhandled check " + check + " of " + what);
        }
      }
      int expectedSql = DemoCatalog.sqlStatements(entry.id());
      assertThat(expectedSql).as("SQL count of %s in DemoCatalog", entry.id()).isNotNegative();
      assertThat(counter.http.get()).as("SQL statements of %s", what).isEqualTo(expectedSql);
      if (entry.id().startsWith("checkout")) {
        // The checkout reached payment-api: 2 SQL for a paid order, 1 for a declined one.
        int peerSql = entry.id().equals("checkout") ? 2 : 1;
        assertThat(paymentSql.http.get()).as("payment-api SQL of %s", what).isEqualTo(peerSql);
      }
      checked.add(entry.id());
    }
    assertThat(checked)
        .contains(
            "orders.items", "checkout", "checkout.declined", "payments.get", "payments.declined");
    assertThat(DemoCatalog.sqlStatements("orders.items")).isEqualTo(21);
    // Each role answers 404 for the other role's endpoint.
    assertThat(get(paymentPort, "/api/orders/1/checkout").statusCode()).isEqualTo(404);
    assertThat(get(orderPort, "/api/payments/1").statusCode()).isEqualTo(404);
    assertThat(get(orderPort, "/api/slow?ms=" + (DemoCatalog.SLOW_LIMIT_MS + 1)).statusCode())
        .isEqualTo(400);
  }

  @Test
  @DisplayName(
      "T-NFR-16e the settlement batch (@Scheduled) runs with a short period, 27 SQL per run")
  void batchRuns() throws Exception {
    long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
    while (json(get(orderPort, "/healthz")).path("batchRuns").asLong() < 2) {
      assertThat(System.nanoTime()).as("two batch runs within 60 s").isLessThan(deadline);
      Thread.sleep(200);
    }
    SqlCounting.Counter counter = order.getBean(SqlCounting.Counter.class);
    long before = json(get(orderPort, "/healthz")).path("batchRuns").asLong();
    int statements = counter.batch.get();
    long after = json(get(orderPort, "/healthz")).path("batchRuns").asLong();
    assertThat(statements)
        .isBetween((int) before * DemoCatalog.BATCH_SQL, (int) (after + 1) * DemoCatalog.BATCH_SQL);
    // The first two runs shipped the orders 1..50 of the order role.
    assertThat(
            count("SELECT COUNT(*) FROM demo_order.orders WHERE id <= 50 AND status = 'SHIPPED'"))
        .isEqualTo(50);
  }

  @Test
  @DisplayName("T-NFR-16e checkout answers 502 when payment-api cannot be reached")
  void checkoutWithoutPeer() throws Exception {
    int port = freePort();
    int deadPeer = freePort();
    try (ConfigurableApplicationContext lonely =
        app("order", port, Map.of("demo.peer-url", "http://127.0.0.1:" + deadPeer))) {
      HttpResponse<String> response = get(port, "/api/orders/1/checkout");
      assertThat(response.statusCode()).isEqualTo(DemoCatalog.CHECKOUT_FAILED_STATUS);
      assertThat(json(response).path("payment").asString()).isEqualTo("UNREACHABLE");
      assertThat(lonely.isActive()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "T-NFR-16e health 503 while the database is not reachable; start fails after DEMO_DB_WAIT")
  void waitsForTheDatabase() throws Exception {
    int port = freePort();
    int noDatabase = freePort();
    CompletableFuture<ConfigurableApplicationContext> start =
        CompletableFuture.supplyAsync(
            () ->
                app(
                    "order",
                    port,
                    Map.of(
                        "demo.db.url",
                        "jdbc:postgresql://127.0.0.1:" + noDatabase + "/demo",
                        "demo.db.wait",
                        "6s")));
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    int status = -1;
    while (status != 503 && System.nanoTime() < deadline) {
      try {
        HttpResponse<String> response = get(port, "/healthz");
        status = response.statusCode();
        if (status == 503) {
          assertThat(json(response).path("status").asString()).isEqualTo("STARTING");
        }
      } catch (IOException notListeningYet) {
        Thread.sleep(100);
      }
    }
    assertThat(status).as("GET /healthz while waiting for the database").isEqualTo(503);
    assertThatThrownBy(start::join).hasStackTraceContaining("Demo database not ready after 6s");
  }

  @Test
  @DisplayName("T-SEC-74 the database password comes from DEMO_DB_PASSWORD_FILE only")
  void passwordFromFileOnly() {
    assertThatThrownBy(
            () -> app("payment", freePort(), Map.of("demo.db.password", "plaintext-in-env")))
        .hasStackTraceContaining("DEMO_DB_PASSWORD is not supported");
  }

  private static HttpResponse<String> get(int port, String path)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> response) throws IOException {
    return JSON.readTree(response.body());
  }

  private static long count(String sql) throws SQLException {
    try (Connection c =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String randomSecret() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
