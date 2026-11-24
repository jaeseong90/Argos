/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.server.cli.AdminCli;
import io.argos.server.security.SecuritySuiteAccess;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.storage.clickhouse.Account;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The diagnostic bundle of {@code argos-admin bundle} holds no secret (T-SEC-06 server part, 12
 * §9.5, 17 §4.4, SEC-06, SEC-26, ADR-0010 C-94): a server log and a configuration seeded with an
 * agent token, the database passwords, the master key, a webhook (channel) URL, the metrics token
 * and the setup token line give a bundle in which none of them appears in any entry after
 * decompression, the agent token only as {@code argos_****}; the archive is owner-only (0600 on
 * POSIX), the token inventory lists prefixes only, and the run is audited.
 */
@Tag("security")
@Tag("req:SEC-06")
@Tag("req:SEC-26")
@Tag("req:DSN-17-14")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DiagnosticBundleSecurityTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SecureRandom RANDOM = new SecureRandom();

  @TempDir static Path dir;

  private static ServerTestEnvironment env;
  private static final List<String> PLANTED = new ArrayList<>();
  private static String agentToken;
  private static String issuedToken;
  private static String issuedPrefix;
  private static String metricsToken;
  private static Map<String, byte[]> entries;
  private static JsonNode result;
  private static Path archive;

  record Run(int code, String out, String err) {}

  private static String random(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static Run run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Map<String, Object> properties = new LinkedHashMap<>(env.springProperties());
    properties.put("argos.web.public-url", "http://127.0.0.1:8080");
    int code =
        AdminCli.run(
            args,
            properties,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    restoreServerLogging();
    return new Run(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  /** Puts the server's log configuration back after {@code AdminCli.run} (C-226 (11)). */
  private static void restoreServerLogging() {
    LoggingSystem system = LoggingSystem.get(DiagnosticBundleSecurityTest.class.getClassLoader());
    system.cleanUp();
    system.beforeInitialize();
    system.initialize(
        new LoggingInitializationContext(new StandardEnvironment()),
        "classpath:log4j2-spring.xml",
        null);
  }

  @BeforeAll
  static void bundle() throws Exception {
    env = SecuritySuiteAccess.environment();
    // A migrated schema and a running server of this JVM (the suite's).
    assertThat(SecuritySuiteAccess.server().port()).isPositive();

    Run issued = run("token", "issue", "--app", "diag-bundle", "--env", "prod", "--json");
    assertThat(issued.code()).as(issued.err()).isZero();
    JsonNode token = JSON.readTree(issued.out());
    issuedToken = token.path("token").asString();
    issuedPrefix = token.path("prefix").asString();

    agentToken = "argos_" + random(32);
    assertThat(agentToken).hasSize(49);
    metricsToken = random(24);
    String webhook = "https://hooks.slack.example/services/T0D1AG/B0BUNDLE/" + random(18);
    String setupToken = random(32);
    PLANTED.add(agentToken);
    PLANTED.add(issuedToken);
    PLANTED.add(metricsToken);
    PLANTED.add(webhook);
    PLANTED.add("services/T0D1AG/B0BUNDLE");
    PLANTED.add(setupToken);
    PLANTED.add(env.masterKey().hex());
    for (PgAccount a : PgAccount.values()) {
      PLANTED.add(env.password(a));
    }
    for (Account a : Account.values()) {
      PLANTED.add(env.password(a));
    }

    Path logs = Files.createDirectories(dir.resolve("logs"));
    String now = Instant.now().toString();
    String old = Instant.now().minusSeconds(3 * 86400).toString();
    List<String> lines = new ArrayList<>();
    lines.add(line(old, "outside the period " + agentToken + " OLD-LINE-MARKER"));
    lines.add(line(now, "agent connected with token " + agentToken));
    lines.add(line(now, "database login as argos_app with " + env.password(PgAccount.APP)));
    lines.add(line(now, "owner password " + env.password(PgAccount.OWNER) + " in a message"));
    lines.add(line(now, "master key " + env.masterKey().hex() + " leaked by a bug"));
    lines.add(line(now, "delivery to " + webhook + " failed: 500"));
    lines.add(line(now, "scrape with Authorization: Bearer " + metricsToken));
    lines.add(line(now, "metrics token " + metricsToken + " plain"));
    lines.add(
        line(
            now,
            "Setup token (one use, valid for 30 minutes until "
                + now
                + "; same value in conf/setup-token): "
                + setupToken));
    Files.write(logs.resolve("argos-server.log"), lines, StandardCharsets.UTF_8);
    Files.write(
        logs.resolve("argos-server.log.1"),
        List.of(line(now, "rolled file " + agentToken)),
        StandardCharsets.UTF_8);

    Path out = dir.resolve("out");
    Run r =
        run(
            "bundle",
            "--since",
            "24h",
            "--out",
            out.toString(),
            "--log-dir",
            logs.toString(),
            "--json",
            "--argos.metrics.token=" + metricsToken,
            "--argos.alert.webhook.allow-cidrs[0]=127.0.0.1/32");
    assertThat(r.code()).as(r.out() + r.err()).isZero();
    for (String secret : PLANTED) {
      assertThat(r.out() + r.err()).as("command output").doesNotContain(secret);
    }
    result = JSON.readTree(r.out());
    archive = Path.of(result.path("file").asString());
    entries = read(Files.readAllBytes(archive));
  }

  private static String line(String ts, String message) {
    return "{\"ts\":\""
        + ts
        + "\",\"level\":\"WARN\",\"logger\":\"test\",\"message\":\""
        + message.replace("\"", "\\\"")
        + "\"}";
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SEC-06g the decompressed bundle contains none of the seeded secrets (agent tokens,"
          + " database passwords, master key, webhook URL, metrics token, setup token) in any"
          + " entry; the agent token appears as argos_**** only")
  void noSecret() {
    assertThat(entries).isNotEmpty();
    StringBuilder all = new StringBuilder();
    for (Map.Entry<String, byte[]> e : entries.entrySet()) {
      String text = new String(e.getValue(), StandardCharsets.UTF_8);
      for (String secret : PLANTED) {
        assertThat(text)
            .as(e.getKey() + " must not contain a seeded secret")
            .doesNotContain(secret);
      }
      all.append(text).append('\n');
    }
    String logs = text("logs/argos-server.log");
    assertThat(logs).contains("argos_****").contains("https://hooks.slack.example/****");
    assertThat(logs).as("lines before --since are left out").doesNotContain("OLD-LINE-MARKER");
    assertThat(text("logs/argos-server.log.1")).contains("rolled file argos_****");
    assertThat(all.toString()).doesNotContain("PRIVATE KEY-----\n");
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SEC-06g the bundle has the documented sections: manifest, version, masked effective"
          + " configuration (metrics token ****), schema history, ClickHouse state, instances,"
          + " token prefixes only, system information; ADMIN_COMMAND_EXECUTED recorded")
  void contents() throws Exception {
    List<String> names =
        entries.keySet().stream().map(n -> n.substring(n.indexOf('/') + 1)).toList();
    assertThat(names)
        .contains(
            "manifest.json",
            "version.json",
            "config/effective.json",
            "logs/argos-server.log",
            "postgres/ch_schema_history.json",
            "inventory/instances.json",
            "inventory/tokens.json",
            "clickhouse/disks.jsonl",
            "clickhouse/tables.jsonl",
            "system/info.json");
    JsonNode manifest = JSON.readTree(text("manifest.json"));
    assertThat(manifest.path("format").asString()).isEqualTo("argos-diag/1");
    for (JsonNode s : manifest.path("sections")) {
      assertThat(s.path("ok").asBoolean()).as(s.toString()).isTrue();
    }
    assertThat(manifest.path("masking").path("configured_secrets").asInt()).isPositive();
    JsonNode effective = JSON.readTree(text("config/effective.json"));
    boolean seen = false;
    for (JsonNode s : effective) {
      if (s.path("key").asString().equals("argos.metrics.token")) {
        assertThat(s.path("value").asString()).isEqualTo("****");
        seen = true;
      }
    }
    assertThat(seen).isTrue();
    String tokens = text("inventory/tokens.json");
    assertThat(tokens).contains(issuedPrefix).doesNotContain(issuedToken);
    // The suite's ClickHouse holds no collected rows, so tables.jsonl may be empty; the server and
    // disk answers always have a row.
    assertThat(text("clickhouse/server.jsonl")).contains("\"version\"");
    assertThat(text("clickhouse/disks.jsonl")).contains("\"free_space\"");

    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT actor_name, result, after::text FROM argos.audit_logs WHERE action ="
                    + " 'ADMIN_COMMAND_EXECUTED' AND target_type = 'cli' AND target_name = 'bundle'"
                    + " ORDER BY ts DESC LIMIT 1")) {
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("argos-admin");
        assertThat(rs.getString(2)).isEqualTo("success");
        assertThat(rs.getString(3)).contains(archive.getFileName().toString());
      }
    }
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SEC-06g the archive is argos-diag-<host>-<ts>.tar.gz and owner-only (rw-------; skipped"
          + " where the file system has no POSIX permissions, e.g. Windows NTFS)")
  void ownerOnly() throws Exception {
    assertThat(archive.getFileName().toString())
        .matches("argos-diag-[A-Za-z0-9.-]+-\\d{8}T\\d{6}Z\\.tar\\.gz");
    try (var listing = Files.list(archive.getParent())) {
      assertThat(listing.toList()).as("no work directory left").containsExactly(archive);
    }
    assumeTrue(
        archive.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "the file system has no POSIX permissions (e.g. Windows NTFS)");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(archive)))
        .isEqualTo("rw-------");
  }

  private static String text(String name) {
    for (Map.Entry<String, byte[]> e : entries.entrySet()) {
      if (e.getKey().endsWith("/" + name)) {
        return new String(e.getValue(), StandardCharsets.UTF_8);
      }
    }
    throw new AssertionError("no entry " + name + " in " + entries.keySet());
  }

  /** Reads a ustar.gz archive (independent of the writer): name to content. */
  static Map<String, byte[]> read(byte[] gz) throws IOException {
    Map<String, byte[]> out = new LinkedHashMap<>();
    byte[] all;
    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
      all = in.readAllBytes();
    }
    int pos = 0;
    while (pos + 512 <= all.length) {
      byte[] h = Arrays.copyOfRange(all, pos, pos + 512);
      pos += 512;
      if (h[0] == 0) {
        continue;
      }
      assertThat(new String(h, 257, 5, StandardCharsets.US_ASCII)).isEqualTo("ustar");
      String name = field(h, 0, 100);
      String prefix = field(h, 345, 155);
      long size = Long.parseLong(field(h, 124, 11), 8);
      assertThat(Integer.parseInt(field(h, 100, 7), 8)).as(name).isEqualTo(0600);
      out.put(
          prefix.isEmpty() ? name : prefix + "/" + name,
          Arrays.copyOfRange(all, pos, pos + (int) size));
      pos += (int) ((size + 511) / 512 * 512);
    }
    return out;
  }

  private static String field(byte[] h, int offset, int length) {
    int end = offset;
    while (end < offset + length && h[end] != 0) {
      end++;
    }
    return new String(h, offset, end - offset, StandardCharsets.US_ASCII).strip();
  }
}
