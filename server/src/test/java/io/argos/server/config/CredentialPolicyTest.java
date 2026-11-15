/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import static io.argos.server.config.ConfigTestSupport.bind;
import static io.argos.server.config.ConfigTestSupport.environment;
import static io.argos.server.config.ConfigTestSupport.file;
import static io.argos.server.config.ConfigTestSupport.newSecret;
import static io.argos.server.config.ConfigTestSupport.secretFile;
import static io.argos.server.config.ConfigTestSupport.validSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.config.CredentialPolicy.Problem;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The database credential policy (SEC-74, 12 §10, 16 §10.6): rules, the findings of the five
 * accounts and the start step. The end-to-end start refusal is {@code RuntimeCredentialPolicyIT}.
 */
@Tag("req:SEC-74")
class CredentialPolicyTest {

  @BeforeAll
  static void warmUp() {
    ConfigTestSupport.warmUp();
  }

  @TempDir Path dir;

  static Iterable<String> forbiddenWords() {
    return CredentialPolicy.FORBIDDEN;
  }

  @Test
  @DisplayName("T-SV-049 a missing or empty password is rejected")
  void missingAndEmpty() {
    assertThat(CredentialPolicy.check(Optional.empty())).contains(Problem.NOT_SET);
    assertThat(CredentialPolicy.check(Optional.of(""))).contains(Problem.EMPTY);
  }

  @Test
  @DisplayName("T-SV-049 a password of 11 characters is rejected, 12 pass")
  void length() {
    String twelve = newSecret().substring(0, 12);
    assertThat(CredentialPolicy.check(Optional.of(twelve.substring(0, 11))))
        .contains(Problem.TOO_SHORT);
    assertThat(CredentialPolicy.check(Optional.of(twelve))).isEmpty();
  }

  static Stream<Arguments> blankOrPaddedFiles() {
    String eleven = newSecret().substring(0, 11);
    return Stream.of(
        Arguments.of("12 spaces", " ".repeat(12), Problem.EMPTY),
        Arguments.of("tabs only", "\t".repeat(12), Problem.EMPTY),
        Arguments.of("11 characters padded with spaces", " " + eleven + "  ", Problem.TOO_SHORT));
  }

  @ParameterizedTest(
      name = "T-SV-049 a password file with {0} is rejected, no value in the message")
  @MethodSource("blankOrPaddedFiles")
  void blankOrPaddedFile(String kind, String value, Problem problem) {
    Map<String, Object> settings = validSettings(dir);
    settings.put(
        "argos.clickhouse.reader.password-file",
        secretFile(dir, "reader-" + problem, value).toString());
    ArgosProperties p = bind(environment(false, Map.of(), file(settings)));
    String finding =
        "argos.clickhouse.reader.password-file (ClickHouse reader argos_reader) " + problem.text();

    assertThat(SecretValues.password("argos.clickhouse.reader", p.clickhouse().reader()))
        .as("the file is read as it is")
        .contains(value);
    assertThat(CredentialPolicy.check(Optional.of(value))).contains(problem);
    assertThat(CredentialPolicy.findings(p)).containsExactly(finding);
    CredentialPolicyCheck check = new CredentialPolicyCheck(p);
    assertThatThrownBy(check::run)
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageContaining(finding)
        .satisfies(
            e -> {
              assertThat(e.getMessage()).doesNotContain(value);
              if (!value.isBlank()) {
                assertThat(e.getMessage()).doesNotContain(value.strip());
              }
            });
  }

  @ParameterizedTest(name = "T-SV-049 forbidden password {0} is rejected in any case")
  @MethodSource("forbiddenWords")
  void forbiddenList(String word) {
    assertThat(CredentialPolicy.check(Optional.of(word))).contains(Problem.FORBIDDEN);
    assertThat(CredentialPolicy.check(Optional.of(word.toUpperCase(Locale.ROOT))))
        .contains(Problem.FORBIDDEN);
    assertThat(CredentialPolicy.check(Optional.of(" " + word + " "))).contains(Problem.FORBIDDEN);
  }

  @Test
  @DisplayName("the forbidden list is the union of 12 §10 and 16 §10.6")
  void forbiddenUnion() {
    assertThat(CredentialPolicy.FORBIDDEN)
        .containsExactlyInAnyOrder(
            "changeme",
            "argos",
            "password",
            "postgres",
            "clickhouse",
            "admin",
            "123456",
            "qwerty",
            "default");
    assertThat(CredentialPolicy.MIN_LENGTH).isEqualTo(12);
  }

  @Test
  @DisplayName("T-SV-049 findings name setting and account of all five accounts, never the value")
  void findingsNameSettingNotValue() {
    String shortValue = newSecret().substring(0, 11);
    Map<String, Object> settings = validSettings(dir);
    settings.put(
        "argos.clickhouse.writer.password-file",
        secretFile(dir, "writer-short", shortValue).toString());
    settings.put(
        "argos.postgres.owner.password-file", secretFile(dir, "owner-empty", "").toString());
    settings.remove("argos.clickhouse.reader.password-file");
    ArgosProperties p = bind(environment(false, Map.of(), file(settings)));

    assertThat(CredentialPolicy.findings(p))
        .containsExactly(
            "argos.postgres.owner.password-file (PostgreSQL schema owner argos) is empty",
            "argos.clickhouse.writer.password-file (ClickHouse writer argos_writer) is shorter than"
                + " 12 characters",
            "argos.clickhouse.reader.password-file (ClickHouse reader argos_reader) is not set")
        .noneMatch(f -> f.contains(shortValue));
  }

  @Test
  @DisplayName("T-SV-049 a plain password is named by its value key; a file wins over a value")
  void plainValueKey() {
    Map<String, Object> settings = validSettings(dir);
    String forbidden = "Changeme";
    settings.remove("argos.clickhouse.migrator.password-file");
    settings.put("argos.clickhouse.migrator.password", forbidden);
    settings.put("argos.postgres.password", forbidden); // the file of argos.postgres still wins
    ArgosProperties p = bind(environment(false, Map.of(), file(settings)));

    assertThat(CredentialPolicy.findings(p))
        .containsExactly(
            "argos.clickhouse.migrator.password (ClickHouse migrator argos_migrator) is a forbidden"
                + " default password");
  }

  @Test
  @DisplayName("T-SV-049 an unreadable password file is a finding with the path, not a crash")
  void unreadableFile() {
    Map<String, Object> settings = validSettings(dir);
    settings.put("argos.postgres.password-file", dir.resolve("missing.pw").toString());
    ArgosProperties p = bind(environment(false, Map.of(), file(settings)));

    assertThat(CredentialPolicy.findings(p))
        .singleElement()
        .asString()
        .startsWith("argos.postgres.password-file names a file that cannot be read (")
        .contains("missing.pw")
        .endsWith("(PostgreSQL runtime account argos_app)");
  }

  @Test
  @DisplayName("T-SV-049 the start step refuses with one message naming every rejected setting")
  void startStep() throws Exception {
    Map<String, Object> settings = validSettings(dir);
    new CredentialPolicyCheck(bind(environment(false, Map.of(), file(settings)))).run();

    settings.put("argos.postgres.password-file", secretFile(dir, "admin", "admin").toString());
    CredentialPolicyCheck check =
        new CredentialPolicyCheck(bind(environment(false, Map.of(), file(settings))));
    assertThatThrownBy(check::run)
        .isInstanceOf(StartupPhaseException.class)
        .hasMessage(
            "Database credentials rejected (SEC-74): argos.postgres.password-file (PostgreSQL"
                + " runtime account argos_app) is a forbidden default password. Use generated"
                + " passwords of at least 12 characters from password files (*-file settings)");
  }
}
