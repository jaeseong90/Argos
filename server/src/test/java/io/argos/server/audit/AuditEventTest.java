/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Audit rows of the Compose token command must carry the server's names (ADR-0010 C-199 (3)). Since
 * ADR-0010 C-173 (4) (WO-26) {@code argos-compose.sh token create} no longer writes {@code
 * audit_logs} with SQL: it runs {@code argos-admin token create} inside the server container, so
 * the row is written by the server's own {@code TokenService} with {@link AuditEvent#TOKEN_ISSUED}
 * and the actor {@code argos-compose} (asserted end to end by TokenLifecycleIT, T-SV-040j). This
 * test keeps the script from regaining a direct SQL writer with names of its own.
 */
@Tag("req:SEC-80")
class AuditEventTest {

  private static final Path TOKEN_CREATE_SCRIPT =
      Path.of(System.getProperty("argos.repositoryRoot", ".."))
          .resolve("deploy/compose/argos-compose.sh");

  @Test
  @DisplayName(
      "C-199 (3) argos-compose.sh token create writes no audit SQL of its own: the server's"
          + " argos-admin token create writes TOKEN_ISSUED with the actor argos-compose")
  void tokenCreateUsesTheServerAudit() throws IOException {
    String script = Files.readString(TOKEN_CREATE_SCRIPT, StandardCharsets.UTF_8);
    assertThat(script)
        .as("no direct audit_logs writer in %s", TOKEN_CREATE_SCRIPT)
        .doesNotContain("INSERT INTO argos.audit_logs")
        .doesNotContain("INSERT INTO argos.agent_tokens");
    assertThat(script).contains("token create --app").contains("--actor argos-compose");
  }

  @Test
  @DisplayName("C-199 (3) TOKEN_ISSUED is the 16 §11.1 name stored in audit_logs.action")
  void tokenIssuedName() {
    assertThat(AuditEvent.valueOf("TOKEN_ISSUED").name()).isEqualTo("TOKEN_ISSUED");
  }
}
