/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * Version, commit and protocol version of this server, from {@code META-INF/build-info.properties}
 * (written by the build, 19 §7.10): the {@code X-Argos-Server-Version} header (14 §2.1), {@code
 * /actuator/info}, the {@code argos_build_info} gauge and the release check of the test hooks.
 *
 * <p>A build is a <em>release</em> when its version is known and has no {@code -SNAPSHOT} suffix.
 * An unknown version (no build information) also counts as a release, so test hooks fail closed (18
 * §15, ADR-0009 C-89).
 */
@Component
public class ServerBuild {

  /** Shown when the build information is missing. */
  public static final String UNKNOWN = "unknown";

  private final String version;
  private final String commit;
  private final String protocol;

  @Autowired
  public ServerBuild(ObjectProvider<BuildProperties> buildProperties) {
    this(buildProperties.getIfAvailable());
  }

  ServerBuild(BuildProperties build) {
    this(
        build == null ? null : build.getVersion(),
        build == null ? null : build.get("commit"),
        build == null ? null : build.get("protocol"));
  }

  /** A build with explicit values (tests); null or blank values are unknown. */
  public ServerBuild(String version, String commit, String protocol) {
    this.version = known(version);
    this.commit = known(commit);
    this.protocol = known(protocol);
  }

  private static String known(String value) {
    return value == null || value.isBlank() ? UNKNOWN : value.strip();
  }

  /** The server version, e.g. {@code 1.0.0} or {@code 0.0.0-SNAPSHOT}, or {@value #UNKNOWN}. */
  public String version() {
    return version;
  }

  /** The commit the server was built from, or {@value #UNKNOWN}. */
  public String commit() {
    return commit;
  }

  /** The agent protocol version (11), or {@value #UNKNOWN}. */
  public String protocol() {
    return protocol;
  }

  /**
   * Whether this is a release build: test hooks are ignored (fail-closed for an unknown version).
   */
  public boolean isRelease() {
    return UNKNOWN.equals(version) || !version.endsWith("-SNAPSHOT");
  }
}
