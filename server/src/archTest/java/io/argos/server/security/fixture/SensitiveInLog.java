/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.fixture;

import io.argos.server.auth.Sensitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Negative fixture: logging next to a secret (never production code). */
public final class SensitiveInLog {

  private static final Logger LOG = LoggerFactory.getLogger(SensitiveInLog.class);

  @Sensitive private final String token;

  /** A holder of {@code token}. */
  public SensitiveInLog(String token) {
    this.token = token;
  }

  /** Reads the secret field and logs. */
  public void logField() {
    LOG.info("token {}", token);
  }

  /** Has a secret parameter and logs. */
  public void logParameter(@Sensitive String password) {
    LOG.info("password {}", password);
  }

  /** Logs, without any secret. */
  public void logNothingSecret() {
    LOG.info("no secret here");
  }
}
