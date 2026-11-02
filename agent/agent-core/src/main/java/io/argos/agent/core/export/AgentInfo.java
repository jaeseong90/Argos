/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import java.io.File;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Identity of the running agent from the jar manifest (19 §3.3 step 3): version, commit, protocol
 * version. "unknown" outside a packaged jar (unit tests).
 */
public final class AgentInfo {
  public static final String UNKNOWN = "unknown";

  private final String version;
  private final String commit;
  private final String protocolVersion;

  public AgentInfo(String version, String commit, String protocolVersion) {
    this.version = version;
    this.commit = commit;
    this.protocolVersion = protocolVersion;
  }

  /** Reads {@code Implementation-Version}, {@code Argos-Commit}, {@code Argos-Protocol-Version}. */
  public static AgentInfo fromJar(File agentJar) {
    if (agentJar == null || !agentJar.isFile()) {
      return new AgentInfo(UNKNOWN, UNKNOWN, UNKNOWN);
    }
    try (JarFile jar = new JarFile(agentJar, false)) {
      Manifest manifest = jar.getManifest();
      if (manifest == null) {
        return new AgentInfo(UNKNOWN, UNKNOWN, UNKNOWN);
      }
      Attributes main = manifest.getMainAttributes();
      return new AgentInfo(
          value(main, "Implementation-Version"),
          value(main, "Argos-Commit"),
          value(main, "Argos-Protocol-Version"));
    } catch (IOException e) {
      return new AgentInfo(UNKNOWN, UNKNOWN, UNKNOWN);
    }
  }

  private static String value(Attributes attributes, String name) {
    String value = attributes.getValue(name);
    return value == null || value.trim().isEmpty() ? UNKNOWN : value.trim();
  }

  public String version() {
    return version;
  }

  public String commit() {
    return commit;
  }

  public String protocolVersion() {
    return protocolVersion;
  }
}
