/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Shared beans of user authentication (WO-24).
 *
 * <p>The {@link Clock} of the authentication code: access-token times, session expiry, lockout
 * durations, setup-token lifetime. The UTC system clock unless the embedding code registers another
 * {@code Clock} bean before the context refreshes (the integration and security tests register a
 * mutable clock to test expiry and lockout; configuration cannot replace it, so a release build
 * always runs on the system clock).
 *
 * <p>Scheduling is on for the cleanup of ended sessions ({@code SessionCleanupJob}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AuthConfiguration {

  /** The system UTC clock, unless another {@code Clock} bean exists. */
  @Bean
  @ConditionalOnMissingBean(Clock.class)
  public Clock argosClock() {
    return Clock.systemUTC();
  }
}
