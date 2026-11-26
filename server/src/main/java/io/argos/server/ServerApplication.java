/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server;

import io.argos.server.cli.AdminCli;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.ServerBuild;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
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

  /** The lone argument that prints the version instead of starting the server (O-R12). */
  static final String VERSION_FLAG = "--version";

  /** Where the build writes the version, commit, protocol and (with SOURCE_DATE_EPOCH) time. */
  static final String BUILD_INFO = "META-INF/build-info.properties";

  /**
   * Starts the server. A failed start phase exits with a non-zero code. With {@code admin} as the
   * first argument it runs an {@code argos-admin} command instead ({@link AdminCli}: configuration
   * and the databases only, no listener) and exits with its code. With {@value #VERSION_FLAG} as
   * the only argument it prints {@link #versionLine} and exits with 0, without starting Spring.
   */
  public static void main(String[] args) {
    if (isVersionRequest(args)) {
      System.out.println(versionLine(buildInfo(ServerApplication.class.getClassLoader())));
      System.out.flush();
      System.exit(0);
      return;
    }
    if (AdminCli.selected(args)) {
      System.exit(AdminCli.run(AdminCli.commandArguments(args), Map.of(), System.out, System.err));
      return;
    }
    SpringApplication.run(ServerApplication.class, args);
  }

  /** True for exactly one argument, {@value #VERSION_FLAG}. */
  static boolean isVersionRequest(String[] args) {
    return args != null && args.length == 1 && VERSION_FLAG.equals(args[0]);
  }

  /** The jar's build information; empty when the class path has none (unknown values). */
  static Properties buildInfo(ClassLoader loader) {
    Properties p = new Properties();
    try (InputStream in = loader.getResourceAsStream(BUILD_INFO)) {
      if (in != null) {
        p.load(in);
      }
    } catch (IOException e) {
      // unknown values below
    }
    return p;
  }

  /**
   * {@code Argos server <version> (commit <commit>, protocol <protocol>[, built <time>])}: the
   * values of {@link ServerBuild} ({@value ServerBuild#UNKNOWN} when missing); the build time only
   * when the build recorded one (SOURCE_DATE_EPOCH set, 19 §3.7).
   */
  static String versionLine(Properties buildInfo) {
    ServerBuild build =
        new ServerBuild(
            buildInfo.getProperty("build.version"),
            buildInfo.getProperty("build.commit"),
            buildInfo.getProperty("build.protocol"));
    String time = buildInfo.getProperty("build.time");
    return String.format(
        Locale.ROOT,
        "Argos server %s (commit %s, protocol %s%s)",
        build.version(),
        build.commit(),
        build.protocol(),
        time == null || time.isBlank() ? "" : ", built " + time.strip());
  }
}
