/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server;

import io.argos.server.config.ArgosProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point of the Argos collector server (12 §1): one Spring Boot process with every role
 * (collector, web, alert, admin, ops; DSN-02-19).
 *
 * <p>Start order (DSN-12-15) is enforced by {@link io.argos.server.bootstrap.StartupSequence}, not
 * by Spring auto-configuration. Flyway therefore runs only inside the {@code POSTGRES_MIGRATION}
 * phase, never through Spring Boot's Flyway auto-configuration: it is excluded here by name (Boot 4
 * ships it in the optional {@code spring-boot-flyway} module, which the server does not use; the
 * exclusion keeps it off even if that module ever reaches the classpath).
 */
@SpringBootApplication(excludeName = ServerApplication.FLYWAY_AUTO_CONFIGURATION)
@EnableConfigurationProperties(ArgosProperties.class)
public class ServerApplication {

  /** Spring Boot 4's Flyway auto-configuration, never used by the server. */
  static final String FLYWAY_AUTO_CONFIGURATION =
      "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";

  /** Starts the server. A failed start phase exits with a non-zero code. */
  public static void main(String[] args) {
    SpringApplication.run(ServerApplication.class, args);
  }
}
