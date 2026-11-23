/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import java.nio.file.Path;
import java.util.Map;

/**
 * Public access to {@link SecuritySuite} for the security tests in sub-packages (WO-25 {@code
 * io.argos.server.security.alert}): the same environment and servers of this JVM, so that no second
 * set of containers starts.
 */
public final class SecuritySuiteAccess {

  private SecuritySuiteAccess() {}

  /** {@link SecuritySuite#environment()}. */
  public static ServerTestEnvironment environment() {
    return SecuritySuite.environment();
  }

  /**
   * {@link SecuritySuite#server()}: the shared server with the default settings (no master key).
   */
  public static ArgosTestServer server() {
    return SecuritySuite.server();
  }

  /** {@link SecuritySuite#start(Map)}: another server of this JVM with extra properties. */
  public static ArgosTestServer start(Map<String, ?> properties) {
    return SecuritySuite.start(properties);
  }

  /**
   * {@link SecuritySuite#client}: a client as {@code role} (null: anonymous) with a CSRF cookie.
   */
  public static ApiTestClient client(ArgosTestServer on, String role) {
    return SecuritySuite.client(on, role);
  }

  /** {@link SecuritySuite#resetLimits}. */
  public static void resetLimits(ArgosTestServer on) {
    SecuritySuite.resetLimits(on);
  }

  /** {@link SecuritySuite#logFile()}: the server log of this JVM. */
  public static Path logFile() {
    return SecuritySuite.logFile();
  }
}
