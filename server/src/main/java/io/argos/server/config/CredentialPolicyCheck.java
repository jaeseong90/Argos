/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Start step {@code credential-policy} ({@code CONFIG}, SEC-74, T-SV-049): refuses the start when a
 * database password of the five accounts (PostgreSQL owner and runtime, ClickHouse writer, reader
 * and migrator) breaks the {@link CredentialPolicy}. The ERROR line names every rejected setting
 * and account, never a value; no database connection is attempted before this step.
 */
@Component
@Order(ConfigValidation.ORDER + 100)
public class CredentialPolicyCheck implements StartupPhase {

  private final ArgosProperties properties;

  public CredentialPolicyCheck(ArgosProperties properties) {
    this.properties = properties;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.CONFIG;
  }

  @Override
  public String name() {
    return "credential-policy";
  }

  @Override
  public void run() throws StartupPhaseException {
    List<String> findings = CredentialPolicy.findings(properties);
    if (!findings.isEmpty()) {
      throw new StartupPhaseException(
          "Database credentials rejected (SEC-74): "
              + String.join("; ", findings)
              + ". Use generated passwords of at least "
              + CredentialPolicy.MIN_LENGTH
              + " characters from password files (*-file settings)");
    }
  }
}
