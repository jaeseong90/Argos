/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Audit rows written outside the server must parse with the server's names (ADR-0010 C-199 (3)):
 * {@code argos-compose.sh token create} of the Compose installation (C-173) writes {@code
 * audit_logs} with SQL, so its action, actor type and result are read here from the script itself.
 */
@Tag("req:SEC-80")
class AuditEventTest {

  private static final Path TOKEN_CREATE_SCRIPT =
      Path.of(System.getProperty("argos.repositoryRoot", ".."))
          .resolve("deploy/compose/argos-compose.sh");

  /** The column list and the leading string literals of the INSERT INTO argos.audit_logs. */
  private static final Pattern AUDIT_INSERT =
      Pattern.compile("INSERT INTO argos\\.audit_logs \\(([^)]*)\\)\\s*VALUES \\(([^;]*)");

  private static final Pattern LITERAL = Pattern.compile("\\G\\s*'([^']*)'\\s*,");

  @Test
  @DisplayName(
      "C-199 (3) the audit row of argos-compose.sh token create parses: action TOKEN_ISSUED (16"
          + " §11.1), actor type system, result success")
  void tokenCreateRowParses() throws IOException {
    String script = Files.readString(TOKEN_CREATE_SCRIPT, StandardCharsets.UTF_8);
    Matcher insert = AUDIT_INSERT.matcher(script);
    assertThat(insert.find())
        .as("INSERT INTO argos.audit_logs in %s", TOKEN_CREATE_SCRIPT)
        .isTrue();
    String columnList = insert.group(1);
    String values = insert.group(2);
    assertThat(insert.find()).as("one audit INSERT only").isFalse();

    List<String> columns = Arrays.stream(columnList.split(",")).map(String::strip).toList();
    List<String> literals = new ArrayList<>();
    Matcher literal = LITERAL.matcher(values);
    while (literal.find()) {
      literals.add(literal.group(1));
    }
    int action = columns.indexOf("action");
    int actorType = columns.indexOf("actor_type");
    assertThat(action).isNotNegative().isLessThan(literals.size());
    assertThat(actorType).isNotNegative().isLessThan(literals.size());

    assertThat(AuditEvent.valueOf(literals.get(action))).isEqualTo(AuditEvent.TOKEN_ISSUED);
    assertThat(Actor.Type.valueOf(literals.get(actorType).toUpperCase(Locale.ROOT)).value())
        .isEqualTo(literals.get(actorType))
        .isEqualTo(Actor.Type.SYSTEM.value());
    // The result column follows target_id and target_name, which are expressions.
    assertThat(values).contains("'success'");
    assertThat(AuditResult.Outcome.SUCCESS.value()).isEqualTo("success");
  }

  @Test
  @DisplayName("C-199 (3) TOKEN_ISSUED is the 16 §11.1 name stored in audit_logs.action")
  void tokenIssuedName() {
    assertThat(AuditEvent.valueOf("TOKEN_ISSUED").name()).isEqualTo("TOKEN_ISSUED");
  }
}
