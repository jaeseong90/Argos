/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.config.ArgosProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sinks of {@code argos.audit.sinks} (12 §8.1, 16 §11.3, §11.4): parsing, the JSON Lines file
 * with its rolling and modes, the PostgreSQL fallback.
 */
@Tag("req:SEC-80")
@Tag("req:SEC-82")
class AuditSinksTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path dir;

  static AuditRecord record(String action, String result) {
    return new AuditRecord(
        Instant.parse("2026-11-24T01:02:03.456789Z"),
        "user",
        "u-1",
        "admin \"one\"",
        "10.0.0.1",
        "JUnit",
        "01JREQ",
        action,
        "user",
        "u-2",
        "ops]x",
        result,
        "admin_change",
        "{\"role\":\"VIEWER\"}",
        "{\"role\":\"ADMIN\"}",
        null,
        null);
  }

  @Test
  @DisplayName(
      "T-SV-044b argos.audit.sinks: postgres, syslog://, syslog+tcp://, syslog+tls:// and file:"
          + " parse with default ports")
  void parse() {
    List<AuditSinks.Spec> specs =
        AuditSinks.parse(
            List.of(
                " postgres ",
                "syslog://siem.example:5514",
                "SYSLOG+TCP://10.0.0.5",
                "syslog+tls://[2001:db8::1]",
                "file:logs/audit/audit.jsonl"));
    assertThat(specs)
        .extracting(AuditSinks.Spec::kind)
        .containsExactly(
            AuditSinks.Kind.POSTGRES,
            AuditSinks.Kind.SYSLOG,
            AuditSinks.Kind.SYSLOG,
            AuditSinks.Kind.SYSLOG,
            AuditSinks.Kind.FILE);
    assertThat(specs.get(1).port()).isEqualTo(5514);
    assertThat(specs.get(1).transport()).isEqualTo(SyslogAuditSink.Transport.UDP);
    assertThat(specs.get(2).port()).isEqualTo(514);
    assertThat(specs.get(2).transport()).isEqualTo(SyslogAuditSink.Transport.TCP);
    assertThat(specs.get(3).host()).isEqualTo("2001:db8::1");
    assertThat(specs.get(3).port()).isEqualTo(6514);
    assertThat(specs.get(4).path().isAbsolute()).isTrue();
    assertThat(specs.get(4).path().getFileName().toString()).isEqualTo("audit.jsonl");
  }

  @Test
  @DisplayName(
      "T-SV-044b an unknown or malformed sink, a duplicate or a list without postgres refuses the"
          + " start; a user part is not repeated in the message")
  void refused() {
    assertThatThrownBy(() -> AuditSinks.parse(List.of("postgres", "kafka://x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("argos.audit.sinks")
        .hasMessageContaining("unknown sink");
    assertThatThrownBy(() -> AuditSinks.parse(List.of("syslog://siem:514")))
        .hasMessageContaining("must contain postgres");
    assertThatThrownBy(() -> AuditSinks.parse(List.of("postgres", "postgres")))
        .hasMessageContaining("twice");
    assertThatThrownBy(() -> AuditSinks.parse(List.of("postgres", "syslog://siem:514/path")))
        .hasMessageContaining("without a path");
    assertThatThrownBy(() -> AuditSinks.parse(List.of("postgres", "syslog://siem:99999")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AuditSinks.parse(List.of("postgres", "file:")))
        .hasMessageContaining("needs a path");
    assertThatThrownBy(() -> AuditSinks.parse(List.of("postgres", "syslog://user:s3cret@siem:514")))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("s3cret"));
    assertThatThrownBy(() -> AuditSinks.parse(List.of()))
        .hasMessageContaining("must contain postgres");
  }

  @Test
  @DisplayName(
      "T-SV-044b configure: the primary sinks first, a file sink, the fallback file; a file sink"
          + " may not be the fallback file")
  void configure() {
    Path file = dir.resolve("a/audit.jsonl");
    Path fallback = dir.resolve("a/fallback.jsonl");
    AuditSink primary = r -> {};
    ArgosProperties.Audit audit =
        new ArgosProperties.Audit(
            List.of("file:" + file, "postgres"),
            new ArgosProperties.Audit.AuditFile(DataSize.ofKilobytes(64), 3),
            fallback.toString(),
            1);
    AuditSinks.Configured c =
        AuditSinks.configure(audit, List.of(primary), new SimpleMeterRegistry());
    assertThat(c.sinks()).hasSize(2);
    assertThat(c.sinks().get(0)).isSameAs(primary);
    assertThat(c.sinks().get(1)).isInstanceOf(JsonlAuditSink.class);
    assertThat(((JsonlAuditSink) c.fallback()).file())
        .isEqualTo(fallback.toAbsolutePath().normalize());
    ArgosProperties.Audit clash =
        new ArgosProperties.Audit(
            List.of("postgres", "file:" + fallback), null, fallback.toString(), 1);
    assertThatThrownBy(
            () -> AuditSinks.configure(clash, List.of(primary), new SimpleMeterRegistry()))
        .hasMessageContaining("fallback-file");
  }

  @Test
  @DisplayName(
      "T-SV-044b JSON Lines: one record per line with the audit_logs column names, before and"
          + " after as objects, the same ts")
  void jsonl() throws IOException {
    Path file = dir.resolve("logs/audit/audit.jsonl");
    JsonlAuditSink sink = new JsonlAuditSink(file, 1024 * 1024, 10);
    sink.write(record("ROLE_CHANGED", "success"));
    sink.write(record("LOGIN_FAILED", "failure"));
    sink.close();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    assertThat(lines).hasSize(2);
    JsonNode first = JSON.readTree(lines.get(0));
    assertThat(first.path("ts").asString()).isEqualTo("2026-11-24T01:02:03.456789Z");
    assertThat(first.path("action").asString()).isEqualTo("ROLE_CHANGED");
    assertThat(first.path("actor_name").asString()).isEqualTo("admin \"one\"");
    assertThat(first.path("before").path("role").asString()).isEqualTo("VIEWER");
    assertThat(first.path("after").path("role").asString()).isEqualTo("ADMIN");
    assertThat(first.has("subject_user_id")).isFalse();
    assertThat(JSON.readTree(lines.get(1)).path("result").asString()).isEqualTo("failure");
  }

  @Test
  @DisplayName(
      "T-SV-044b JSON Lines rolls by size: at most max-files files, the newest records in the"
          + " current file, nothing lost within the kept files")
  void rolling() throws IOException {
    Path file = dir.resolve("roll/audit.jsonl");
    int lineBytes =
        (AuditRecordJson.line(record("LOGOUT", "success")) + "\n")
            .getBytes(StandardCharsets.UTF_8)
            .length;
    JsonlAuditSink sink = new JsonlAuditSink(file, Math.max(1024, lineBytes * 3L), 3);
    int perFile = (int) (Math.max(1024, lineBytes * 3L) / lineBytes);
    int total = perFile * 5 + 1;
    for (int i = 0; i < total; i++) {
      sink.write(record("LOGOUT", "success"));
    }
    sink.close();
    try (var files = Files.list(file.getParent())) {
      assertThat(files.map(p -> p.getFileName().toString()).sorted())
          .containsExactly("audit.jsonl", "audit.jsonl.1", "audit.jsonl.2");
    }
    assertThat(Files.readAllLines(file)).hasSize(1);
    assertThat(Files.readAllLines(sink.rolled(1))).hasSize(perFile);
    assertThat(Files.readAllLines(sink.rolled(2))).hasSize(perFile);
    assertThat(Files.size(sink.rolled(1))).isLessThanOrEqualTo(Math.max(1024, lineBytes * 3L));
  }

  @Test
  @DisplayName(
      "T-SV-044b JSON Lines files are 0600 and a new directory 0700 on POSIX (Windows: the"
          + " directory's access list applies, documented)")
  void modes() throws IOException {
    Path file = dir.resolve("modes/sub/audit.jsonl");
    JsonlAuditSink sink = new JsonlAuditSink(file, 1024 * 1024, 2);
    sink.write(record("LOGOUT", "success"));
    sink.close();
    if (!JsonlAuditSink.posix()) {
      assertThat(file).exists();
      return;
    }
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        .isEqualTo("rw-------");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())))
        .isEqualTo("rwx------");
  }

  @Test
  @DisplayName(
      "T-SV-044d a record the primary sink cannot write goes to the fallback file and is counted;"
          + " the other sinks still get it; nothing is thrown")
  void fallback() throws IOException {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Path fallbackFile = dir.resolve("fallback/audit-fallback.jsonl");
    List<AuditRecord> other = new ArrayList<>();
    AuditSink broken =
        new AuditSink() {
          @Override
          public void write(AuditRecord record) {
            throw new IllegalStateException("PostgreSQL down");
          }

          @Override
          public boolean primary() {
            return true;
          }
        };
    DefaultAuditService service =
        new DefaultAuditService(
            List.of(broken, other::add),
            new JsonlAuditSink(fallbackFile, 1024 * 1024, 2),
            registry,
            Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC));
    service.record(
        AuditEvent.USER_CREATED,
        Actor.system(),
        Target.user(null, "new-user"),
        AuditResult.success(),
        null,
        Map.of("role", "VIEWER"));
    service.destroy();
    assertThat(registry.get(DefaultAuditService.FAILURES_METER).counter().count()).isEqualTo(1);
    assertThat(other).hasSize(1);
    List<String> lines = Files.readAllLines(fallbackFile);
    assertThat(lines).hasSize(1);
    JsonNode line = JSON.readTree(lines.get(0));
    assertThat(line.path("action").asString()).isEqualTo("USER_CREATED");
    assertThat(line.path("ts").asString()).isEqualTo("2026-11-24T00:00:00Z");
    assertThat(line.path("target_name").asString()).isEqualTo("new-user");
  }

  @Test
  @DisplayName("T-SV-044d a failing non-primary sink is counted but does not use the fallback file")
  void secondaryFailure() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    List<AuditRecord> fallback = new ArrayList<>();
    List<AuditRecord> primary = new ArrayList<>();
    AuditSink pg =
        new AuditSink() {
          @Override
          public void write(AuditRecord record) {
            primary.add(record);
          }

          @Override
          public boolean primary() {
            return true;
          }
        };
    AuditSink broken =
        r -> {
          throw new IllegalStateException("syslog down");
        };
    DefaultAuditService service =
        new DefaultAuditService(List.of(pg, broken), fallback::add, registry, Clock.systemUTC());
    service.record(AuditEvent.LOGOUT, Actor.system(), Target.none(), AuditResult.success());
    assertThat(primary).hasSize(1);
    assertThat(fallback).isEmpty();
    assertThat(registry.get(DefaultAuditService.FAILURES_METER).counter().count()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "T-SEC-81 a personal data subject is stored normalised: control characters removed, 128"
          + " characters at most")
  void subject() {
    List<AuditRecord> written = new ArrayList<>();
    DefaultAuditService service =
        new DefaultAuditService(List.of(written::add), new SimpleMeterRegistry());
    service.record(
        AuditEvent.SENSITIVE_VIEW,
        Actor.system(),
        new Target("transaction", "1", null),
        AuditResult.success("sql_params_full"),
        null,
        null,
        new AuditSubject(" kim\u0007" + "x".repeat(200), -1L));
    AuditRecord r = written.get(0);
    assertThat(r.subjectUserId()).startsWith("kimx").hasSize(128);
    assertThat(r.subjectTxId()).isEqualTo(-1L);
    assertThat(Long.toUnsignedString(r.subjectTxId())).isEqualTo("18446744073709551615");
  }
}
