/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import java.io.File;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Identity of the running agent from the jar manifest (19 §3.3 step 3): version, commit, protocol
 * version, and whether the jar is a release build (T-BLD-010). "unknown" for a value the manifest
 * does not carry and outside a packaged jar (unit tests).
 */
public final class AgentInfo {
  public static final String UNKNOWN = "unknown";

  /** Suffix of every development version ({@code gradle.properties}, DSN-19-10). */
  static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

  private final String version;
  private final String commit;
  private final String protocolVersion;
  private final boolean releaseBuild;

  /**
   * Values read from a packaged jar: a release build unless {@code version} ends with {@code
   * -SNAPSHOT} ({@link #isReleaseVersion(String)}).
   */
  public AgentInfo(String version, String commit, String protocolVersion) {
    this(version, commit, protocolVersion, isReleaseVersion(version));
  }

  private AgentInfo(String version, String commit, String protocolVersion, boolean releaseBuild) {
    this.version = version;
    this.commit = commit;
    this.protocolVersion = protocolVersion;
    this.releaseBuild = releaseBuild;
  }

  /**
   * Reads {@code Implementation-Version}, {@code Argos-Commit}, {@code Argos-Protocol-Version};
   * never throws. The release-build marker fails closed (T-BLD-010): only a {@code null} jar (the
   * agent outside a packaged jar, unit tests) and an {@code Implementation-Version} ending with
   * {@code -SNAPSHOT} count as development builds. A jar whose manifest is missing, unreadable or
   * without {@code Implementation-Version} counts as a release build, so {@code argos.test.*} is
   * ignored.
   */
  public static AgentInfo fromJar(File agentJar) {
    if (agentJar == null) {
      return new AgentInfo(UNKNOWN, UNKNOWN, UNKNOWN, false);
    }
    if (!agentJar.isFile()) {
      return unreadable(agentJar);
    }
    try (JarFile jar = new JarFile(agentJar, false)) {
      Manifest manifest = jar.getManifest();
      if (manifest == null) {
        return unreadable(agentJar);
      }
      Attributes main = manifest.getMainAttributes();
      return new AgentInfo(
          value(main, "Implementation-Version"),
          value(main, "Argos-Commit"),
          value(main, "Argos-Protocol-Version"));
    } catch (IOException | RuntimeException e) {
      return unreadable(agentJar);
    }
  }

  /**
   * Identity when the manifest of {@code agentJar} cannot be read: every value {@value #UNKNOWN},
   * and a release build unless {@code agentJar} is {@code null} (fail-closed, T-BLD-010).
   */
  public static AgentInfo unreadable(File agentJar) {
    return new AgentInfo(UNKNOWN, UNKNOWN, UNKNOWN, agentJar != null);
  }

  private static String value(Attributes attributes, String name) {
    String value = attributes.getValue(name);
    return value == null || value.trim().isEmpty() ? UNKNOWN : value.trim();
  }

  public String version() {
    return version;
  }

  /**
   * True for a release build, the marker of the release-build gate of {@code argos.test.*}
   * (T-BLD-010). Development builds carry {@code 0.0.0-SNAPSHOT} ({@code gradle.properties},
   * DSN-19-10) and tag builds the tag's version ({@code -Pversion=X.Y.Z[-pre]}, 19 §7.2). The gate
   * fails closed ({@link #fromJar(File)}): only an agent outside a packaged jar (unit tests) and a
   * {@code -SNAPSHOT} version are development builds.
   */
  public boolean isReleaseBuild() {
    return releaseBuild;
  }

  /**
   * The rule for an {@code Implementation-Version} read from a packaged jar: everything but a
   * version ending with {@code -SNAPSHOT} is a release, a missing or blank version included
   * (fail-closed). See {@link #isReleaseBuild()}.
   */
  public static boolean isReleaseVersion(String version) {
    return version == null || !version.trim().endsWith(SNAPSHOT_SUFFIX);
  }

  public String commit() {
    return commit;
  }

  public String protocolVersion() {
    return protocolVersion;
  }
}
