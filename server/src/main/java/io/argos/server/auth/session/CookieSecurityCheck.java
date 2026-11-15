/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.config.ArgosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code argos.security.cookie.secure=false} is accepted for non-production setups only (16 §7.1):
 * every start says so in a WARN line. Browsers treat {@code localhost} as a secure context, so the
 * demo works with {@code Secure} cookies; this switch is for test installations on plain HTTP.
 */
@Component
public class CookieSecurityCheck implements StartupPhase {

  private static final Logger LOG = LoggerFactory.getLogger(CookieSecurityCheck.class);

  private final ArgosProperties properties;

  public CookieSecurityCheck(ArgosProperties properties) {
    this.properties = properties;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.CONFIG;
  }

  @Override
  public String name() {
    return "cookie-security";
  }

  @Override
  public void run() {
    if (!properties.security().cookie().secure()) {
      LOG.warn(
          "argos.security.cookie.secure=false: session cookies are sent over plain HTTP. For"
              + " non-production use only (16 §7.1)");
    }
  }
}
