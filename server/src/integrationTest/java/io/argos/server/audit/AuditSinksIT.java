/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.TestUsers;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit sinks of WO-28 on the real server (PostgreSQL 16, ClickHouse; 12 §8.1, 16 §11.3,
 * §11.4): every record reaches {@code audit_logs}, the JSON Lines file and syslog receivers over
 * UDP and TCP (fake receivers on loopback, RFC 5424 with octet counting) with the same {@code ts};
 * a record PostgreSQL cannot take (its table locked past the 5 s statement timeout) goes to the
 * fallback file, is counted, and the request still succeeds. The start writes {@code
 * MIGRATION_APPLIED} per applied schema version and {@code SERVER_STARTED}.
 */
@Tag("integration")
@Tag("req:SEC-80")
@Tag("req:SEC-82")
class AuditSinksIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One RFC 5424 message: header, the argos SD element, the JSON body after the BOM. */
  private static final Pattern SYSLOG =
      Pattern.compile(
          "<(\\d{1,3})>1 (\\S+) (\\S+) argos (\\S+) (\\S+) (\\[argos@32473 .*?(?<!\\\\)\\])"
              + "(?: \uFEFF(\\{.*\\}))?",
          Pattern.DOTALL);

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static Path dir;
  private static Path jsonl;
  private static Path fallback;
  private static UdpReceiver udp;
  private static TcpReceiver tcp;

  @BeforeAll
  static void start() throws IOException {
    env = ServerTestEnvironment.start();
    dir = Files.createTempDirectory("argos-audit-sinks-");
    jsonl = dir.resolve("audit/audit.jsonl");
    fallback = dir.resolve("audit/audit-fallback.jsonl");
    udp = new UdpReceiver();
    tcp = new TcpReceiver();
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("argos.audit.sinks[0]", "postgres");
    props.put("argos.audit.sinks[1]", "syslog://127.0.0.1:" + udp.port());
    props.put("argos.audit.sinks[2]", "syslog+tcp://127.0.0.1:" + tcp.port());
    props.put("argos.audit.sinks[3]", "file:" + jsonl.toAbsolutePath());
    props.put("argos.audit.fallback-file", fallback.toAbsolutePath().toString());
    server = ArgosTestServer.start(env, props);
  }

  @AfterAll
  static void stop() throws IOException {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      if (env != null) {
        env.close();
      }
      if (udp != null) {
        udp.close();
      }
      if (tcp != null) {
        tcp.close();
      }
    }
  }

  private static List<JsonNode> lines(Path file) throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }
    List<JsonNode> out = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      out.add(JSON.readTree(line));
    }
    return out;
  }

  private static <T> T await(java.util.function.Supplier<List<T>> source, Predicate<T> match)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      for (T t : source.get()) {
        if (match.test(t)) {
          return t;
        }
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Not received within 15 s");
  }

  private static List<JsonNode> jsonlLines() {
    try {
      return lines(jsonl);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static double failures() {
    return server
        .bean(MeterRegistry.class)
        .get(DefaultAuditService.FAILURES_METER)
        .counter()
        .count();
  }

  @Test
  @DisplayName(
      "T-SV-044b one record reaches audit_logs and the JSON Lines file with the same ts and the"
          + " audit_logs column names")
  void jsonLines() throws Exception {
    String marker = "it-sinks-" + UUID.randomUUID();
    server
        .bean(AuditService.class)
        .record(
            AuditEvent.ROLE_CHANGED,
            new Actor(Actor.Type.USER, "u-1", "admin-1", "127.0.0.1", "JUnit"),
            new Target("user", marker, "ops"),
            AuditResult.success("admin_change"),
            Map.of("role", "VIEWER", "password", "never-in-a-sink"),
            Map.of("role", "ADMIN"));
    AuditLog.Row row =
        AuditLog.rows(env, "ROLE_CHANGED").stream()
            .filter(r -> marker.equals(r.targetId()))
            .findFirst()
            .orElseThrow();
    JsonNode line =
        await(AuditSinksIT::jsonlLines, n -> marker.equals(n.path("target_id").asString()));
    assertThat(Instant.parse(line.path("ts").asString())).isEqualTo(row.ts());
    assertThat(line.path("action").asString()).isEqualTo("ROLE_CHANGED");
    assertThat(line.path("actor_type").asString()).isEqualTo("user");
    assertThat(line.path("actor_name").asString()).isEqualTo("admin-1");
    assertThat(line.path("ip").asString()).isEqualTo("127.0.0.1");
    assertThat(line.path("result").asString()).isEqualTo("success");
    assertThat(line.path("reason").asString()).isEqualTo("admin_change");
    assertThat(line.path("before").path("role").asString()).isEqualTo("VIEWER");
    assertThat(line.path("before").path("password").asString()).isEqualTo("****");
    assertThat(Files.readString(jsonl)).doesNotContain("never-in-a-sink");
  }

  @Test
  @DisplayName(
      "T-SV-044c the same record reaches syslog over UDP and over TCP (octet counting) as RFC 5424"
          + " with SD-ID argos@32473, the event as MSGID and the record's ts")
  void syslog() throws Exception {
    String marker = "it-syslog-" + UUID.randomUUID();
    server
        .bean(AuditService.class)
        .record(
            AuditEvent.USER_DISABLED,
            Actor.system(),
            new Target("user", marker, "a \"quoted\" ]name"),
            AuditResult.success());
    AuditLog.Row row =
        AuditLog.rows(env, "USER_DISABLED").stream()
            .filter(r -> marker.equals(r.targetId()))
            .findFirst()
            .orElseThrow();
    for (List<String> received : List.of(udp.messages, tcp.messages)) {
      String message = await(() -> received, m -> m.contains(marker));
      Matcher m = SYSLOG.matcher(message);
      assertThat(m.matches()).as(message).isTrue();
      assertThat(m.group(1)).isEqualTo(Integer.toString(13 * 8 + 5));
      assertThat(Instant.parse(m.group(2))).isEqualTo(row.ts());
      assertThat(m.group(5)).isEqualTo("USER_DISABLED");
      assertThat(m.group(6))
          .contains(" event=\"USER_DISABLED\"")
          .contains(" target_id=\"" + marker + "\"")
          .contains(" target_name=\"a \\\"quoted\\\" \\]name\"");
      JsonNode body = JSON.readTree(m.group(7));
      assertThat(body.path("target_id").asString()).isEqualTo(marker);
    }
    assertThat(tcp.framingErrors.get()).as("every TCP frame is <length> SP <message>").isZero();
  }

  @Test
  @DisplayName(
      "T-SV-044d PostgreSQL cannot take the record (audit_logs locked past the 5 s timeout): the"
          + " login still succeeds, the record is in the fallback file and the other sinks, and"
          + " argos_audit_write_failures_total counts it")
  void fallbackWhenPostgresFails() throws Exception {
    TestUsers.TestUser admin = new TestUsers(env).create(Role.ADMIN);
    double before = failures();
    ApiResponse login;
    long elapsedMs;
    try (Connection owner = env.connect(PgAccount.OWNER);
        Statement s = owner.createStatement()) {
      owner.setAutoCommit(false);
      s.execute("LOCK TABLE argos.audit_logs IN ACCESS EXCLUSIVE MODE");
      ApiTestClient client = server.client().timeout(Duration.ofSeconds(60));
      long start = System.nanoTime();
      login = client.login(admin.username(), admin.password());
      elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      owner.rollback();
    }
    assertThat(login.status()).as(login.toString()).isEqualTo(200);
    assertThat(elapsedMs).as("bounded by the 5 s statement timeout").isLessThan(30_000);
    assertThat(failures()).isGreaterThanOrEqualTo(before + 1);
    JsonNode saved =
        await(
            () -> {
              try {
                return lines(fallback);
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            },
            n ->
                "LOGIN_SUCCESS".equals(n.path("action").asString())
                    && admin.username().equals(n.path("actor_name").asString()));
    assertThat(saved.path("result").asString()).isEqualTo("success");
    assertThat(AuditLog.rows(env, "LOGIN_SUCCESS"))
        .noneMatch(r -> admin.username().equals(r.actorName()));
    await(
        AuditSinksIT::jsonlLines,
        n ->
            "LOGIN_SUCCESS".equals(n.path("action").asString())
                && admin.username().equals(n.path("actor_name").asString()));
    assertThat(Files.readString(fallback)).doesNotContain(admin.password());
  }

  @Test
  @DisplayName(
      "T-SV-044b the start records MIGRATION_APPLIED per applied PostgreSQL and ClickHouse version"
          + " and SERVER_STARTED with the version")
  void startEvents() {
    List<AuditLog.Row> migrations = AuditLog.rows(env, "MIGRATION_APPLIED");
    assertThat(migrations)
        .extracting(AuditLog.Row::targetId)
        .contains("postgres:001", "postgres:013")
        .anyMatch(id -> id.startsWith("clickhouse:"));
    assertThat(migrations)
        .allMatch(r -> "system".equals(r.actorType()) && "schema".equals(r.targetType()));
    List<AuditLog.Row> started = AuditLog.rows(env, "SERVER_STARTED");
    assertThat(started).isNotEmpty();
    AuditLog.Row s = started.get(started.size() - 1);
    assertThat(s.actorType()).isEqualTo("system");
    assertThat(s.targetType()).isEqualTo("server");
    assertThat(JSON.readTree(s.after()).path("version").asString()).isNotBlank();
    assertThat(started.get(0).ts()).isAfterOrEqualTo(migrations.get(migrations.size() - 1).ts());
  }

  /** A UDP syslog receiver on loopback. */
  static final class UdpReceiver implements AutoCloseable {
    final List<String> messages = new CopyOnWriteArrayList<>();
    private final DatagramSocket socket;
    private final Thread thread;

    UdpReceiver() throws SocketException {
      socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
      thread =
          Thread.ofPlatform()
              .daemon()
              .name("it-syslog-udp")
              .start(
                  () -> {
                    byte[] buffer = new byte[65_535];
                    while (!socket.isClosed()) {
                      DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                      try {
                        socket.receive(p);
                        messages.add(
                            new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                      } catch (IOException e) {
                        return;
                      }
                    }
                  });
    }

    int port() {
      return socket.getLocalPort();
    }

    @Override
    public void close() {
      socket.close();
      thread.interrupt();
    }
  }

  /** A TCP syslog receiver on loopback that reads RFC 6587 octet-counted frames. */
  static final class TcpReceiver implements AutoCloseable {
    final List<String> messages = new CopyOnWriteArrayList<>();
    final java.util.concurrent.atomic.AtomicInteger framingErrors =
        new java.util.concurrent.atomic.AtomicInteger();
    private final ServerSocket server;

    TcpReceiver() throws IOException {
      server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
      Thread.ofPlatform()
          .daemon()
          .name("it-syslog-tcp")
          .start(
              () -> {
                while (!server.isClosed()) {
                  try {
                    Socket s = server.accept();
                    Thread.ofPlatform().daemon().start(() -> read(s));
                  } catch (IOException e) {
                    return;
                  }
                }
              });
    }

    private void read(Socket s) {
      try (s;
          InputStream in = s.getInputStream()) {
        while (true) {
          StringBuilder length = new StringBuilder();
          int c;
          while ((c = in.read()) != ' ') {
            if (c < 0) {
              return;
            }
            if (c < '0' || c > '9' || length.length() > 6) {
              framingErrors.incrementAndGet();
              return;
            }
            length.append((char) c);
          }
          int n = Integer.parseInt(length.toString());
          byte[] frame = in.readNBytes(n);
          if (frame.length != n) {
            framingErrors.incrementAndGet();
            return;
          }
          ByteArrayOutputStream copy = new ByteArrayOutputStream();
          copy.write(frame);
          messages.add(copy.toString(StandardCharsets.UTF_8));
        }
      } catch (IOException e) {
        // connection closed
      }
    }

    int port() {
      return server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
      server.close();
    }
  }
}
