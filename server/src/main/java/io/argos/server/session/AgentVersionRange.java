/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The agent versions a server accepts (11 §5 check 4, O-R01, O-R02): for server version {@code
 * X.Y.Z} the agents {@code X.(Y-2)} to {@code X.Y} (any patch, any pre-release suffix); a newer
 * agent is {@link Verdict#TOO_NEW}, an older one or an unparsable version {@link Verdict#TOO_OLD}.
 * Only the major and minor numbers count. A server whose own version is unknown (no build
 * information) accepts every agent version.
 */
public final class AgentVersionRange {

  /** {@code X.Y}, optionally {@code .Z}, optionally a {@code -pre} or {@code +build} suffix. */
  private static final Pattern SEMVER =
      Pattern.compile("(\\d{1,9})\\.(\\d{1,9})(?:\\.\\d{1,9})?(?:[-+].*)?");

  /** Minor versions behind the server that are still accepted (11 §5). */
  public static final int MINORS_BEHIND = 2;

  private final String serverVersion;
  private final int major;
  private final int minor;
  private final boolean known;

  /** The range of a server of {@code serverVersion} ({@code 0.0.0-SNAPSHOT}, {@code 1.2.0}). */
  public AgentVersionRange(String serverVersion) {
    this.serverVersion = Objects.requireNonNull(serverVersion, "serverVersion");
    Matcher m = SEMVER.matcher(serverVersion.strip());
    if (m.matches()) {
      this.major = Integer.parseInt(m.group(1));
      this.minor = Integer.parseInt(m.group(2));
      this.known = true;
    } else {
      this.major = 0;
      this.minor = 0;
      this.known = false;
    }
  }

  /** The decision for {@code agentVersion}. */
  public Verdict check(String agentVersion) {
    if (!known) {
      return Verdict.ACCEPTED;
    }
    Matcher m = SEMVER.matcher(agentVersion == null ? "" : agentVersion.strip());
    if (!m.matches()) {
      return Verdict.TOO_OLD;
    }
    int agentMajor = Integer.parseInt(m.group(1));
    int agentMinor = Integer.parseInt(m.group(2));
    if (agentMajor > major || (agentMajor == major && agentMinor > minor)) {
      return Verdict.TOO_NEW;
    }
    if (agentMajor < major || agentMinor < minMinor()) {
      return Verdict.TOO_OLD;
    }
    return Verdict.ACCEPTED;
  }

  /** {@code ServerHello.min_agent_version}: {@code X.(Y-2)}; empty when unknown. */
  public String min() {
    return known ? major + "." + minMinor() : "";
  }

  /** {@code ServerHello.max_agent_version}: {@code X.Y}; empty when unknown. */
  public String max() {
    return known ? major + "." + minor : "";
  }

  /** The server version this range belongs to. */
  public String serverVersion() {
    return serverVersion;
  }

  /** English text for a rejection: the server version and the accepted range. */
  public String describe() {
    return "server " + serverVersion + " accepts agent versions " + min() + ".x to " + max() + ".x";
  }

  private int minMinor() {
    return Math.max(0, minor - MINORS_BEHIND);
  }

  /** The outcome of {@link #check}. */
  public enum Verdict {
    /** Inside the range. */
    ACCEPTED,
    /** Newer than the server ({@code AGENT_VERSION_TOO_NEW}). */
    TOO_NEW,
    /** Older than the range or unparsable ({@code AGENT_VERSION_TOO_OLD}). */
    TOO_OLD
  }
}
