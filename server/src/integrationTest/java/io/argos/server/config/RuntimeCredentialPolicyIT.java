/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.argos.server.ServerApplication;
import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupAbortedException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * T-SV-049 (SEC-74) against the real server: a database password that is empty, shorter than 12
 * characters, on the forbidden list or not set refuses the start in the {@code CONFIG} phase,
 * before any connection (the database URLs point at closed ports), with one ERROR line that names
 * the setting and the account and contains no password.
 */
@Tag("req:SEC-74")
@ExtendWith(OutputCaptureExtension.class)
class RuntimeCredentialPolicyIT {

  private static final SecureRandom RANDOM = new SecureRandom();

  /** Account key prefixes of C-11 (the five database accounts). */
  private static final List<String> ACCOUNTS =
      List.of(
          "argos.postgres.owner",
          "argos.postgres",
          "argos.clickhouse.writer",
          "argos.clickhouse.reader",
          "argos.clickhouse.migrator");

  @TempDir Path dir;

  @BeforeAll
  static void logs() {
    IntegrationLogs.ensureLogPath();
  }

  private static String newSecret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static Stream<Arguments> cases() {
    String eleven = newSecret().substring(0, 11);
    return Stream.of(
        Arguments.of(
            "empty", "argos.postgres.owner", "", "(PostgreSQL schema owner argos) is empty"),
        Arguments.of(
            "11 characters",
            "argos.clickhouse.writer",
            eleven,
            "(ClickHouse writer argos_writer) is shorter than 12 characters"),
        Arguments.of(
            "forbidden list",
            "argos.postgres",
            "ChangeMe",
            "(PostgreSQL runtime account argos_app) is a forbidden default password"),
        Arguments.of(
            "not set",
            "argos.clickhouse.reader",
            null,
            "(ClickHouse reader argos_reader) is not set"));
  }

  @ParameterizedTest(name = "T-SV-049 {0} database password refuses the start, log without value")
  @MethodSource("cases")
  void refused(String kind, String rejected, String badValue, String reason, CapturedOutput output)
      throws IOException {
    Map<String, String> passwords = new LinkedHashMap<>();
    List<String> args = new ArrayList<>(baseArguments());
    for (String prefix : ACCOUNTS) {
      String value = prefix.equals(rejected) ? badValue : newSecret();
      if (value == null) {
        continue;
      }
      passwords.put(prefix, value);
      Path file = dir.resolve(prefix + ".pw");
      Files.writeString(file, value + "\n", StandardCharsets.UTF_8);
      args.add("--" + prefix + ".password-file=" + file);
    }

    StartupAbortedException aborted = start(args);

    assertThat(aborted.phase()).isEqualTo(PhaseOrder.CONFIG);
    assertThat(aborted.step()).isEqualTo("credential-policy");
    assertThat(aborted.getExitCode()).isEqualTo(1);
    String out = output.getAll();
    List<String> errors = out.lines().filter(l -> l.contains("\"level\":\"ERROR\"")).toList();
    assertThat(errors).as(out).hasSize(1);
    assertThat(errors.get(0))
        .contains("Startup phase CONFIG step credential-policy failed")
        .contains(
            "Database credentials rejected (SEC-74): " + rejected + ".password-file " + reason)
        .contains("The server does not start.");
    assertThat(out)
        .as("no listener, no connection: the start stops in CONFIG")
        .doesNotContain("Tomcat started")
        .doesNotContain("postgres-flyway completed");
    String log = Files.readString(IntegrationLogs.logFile(), StandardCharsets.UTF_8);
    for (Map.Entry<String, String> p : passwords.entrySet()) {
      if (!p.getValue().isEmpty() && !p.getValue().equals("ChangeMe")) {
        assertThat(out).as(p.getKey()).doesNotContain(p.getValue());
        assertThat(log).as(p.getKey()).doesNotContain(p.getValue());
      }
    }
    if ("ChangeMe".equals(badValue)) {
      assertThat(out).doesNotContain("ChangeMe");
    }
  }

  private List<String> baseArguments() {
    return List.of(
        "--server.address=127.0.0.1",
        "--server.port=0",
        "--management.server.address=127.0.0.1",
        "--management.server.port=0",
        "--argos.web.public-url=http://127.0.0.1:8080",
        // Closed ports: the policy must refuse the start before anything connects.
        "--argos.postgres.url=jdbc:postgresql://127.0.0.1:1/argos",
        "--argos.clickhouse.url=http://127.0.0.1:1");
  }

  private static StartupAbortedException start(List<String> args) {
    SpringApplication app = new SpringApplication(ServerApplication.class);
    try (ConfigurableApplicationContext context = app.run(args.toArray(String[]::new))) {
      return fail("the server started: " + context.getId());
    } catch (RuntimeException e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof StartupAbortedException aborted) {
          return aborted;
        }
      }
      throw e;
    }
  }
}
