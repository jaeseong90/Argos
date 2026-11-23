/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.configchange;

import java.util.ArrayList;
import java.util.List;

/**
 * An agent's {@code remote-config.allow} list as it sent it in {@code AgentHello} (SEC-23, 16 §5.5
 * step 2, docs/agent-config.md "Remote configuration"): key globs whose segments are separated by
 * {@code .}, {@code *} matching within one segment and a segment {@code **} matching every
 * remaining segment (at least one); a pattern may carry the {@code argos.} prefix. The server
 * pushes a key to an instance only when a pattern of that instance's list matches it (11 §8.1); the
 * agent checks again. Same rules as the agent's {@code AllowList}.
 */
public final class AllowPatterns {

  /** Patterns kept at most (the agent's cap). */
  public static final int MAX_PATTERNS = 128;

  private final List<String> patterns;

  private AllowPatterns(List<String> patterns) {
    this.patterns = patterns;
  }

  /** The list of {@code patterns}; blank ones dropped, {@code argos.} removed, 128 kept. */
  public static AllowPatterns of(List<String> patterns) {
    List<String> kept = new ArrayList<>();
    for (String p : patterns) {
      if (p == null || p.isBlank() || kept.size() >= MAX_PATTERNS) {
        continue;
      }
      kept.add(AgentKeySchema.canonical(p));
    }
    return new AllowPatterns(List.copyOf(kept));
  }

  /** The canonical patterns. */
  public List<String> patterns() {
    return patterns;
  }

  /** Whether a pattern matches the canonical {@code key}. */
  public boolean matches(String key) {
    String k = AgentKeySchema.canonical(key);
    for (String p : patterns) {
      if (globMatches(p, k)) {
        return true;
      }
    }
    return false;
  }

  static boolean globMatches(String pattern, String key) {
    String[] ps = pattern.split("\\.", -1);
    String[] ks = key.split("\\.", -1);
    for (int i = 0; i < ps.length; i++) {
      if (ps[i].equals("**")) {
        return i < ks.length;
      }
      if (i >= ks.length || !segmentMatches(ps[i], ks[i])) {
        return false;
      }
    }
    return ps.length == ks.length;
  }

  private static boolean segmentMatches(String pattern, String segment) {
    int p = 0;
    int s = 0;
    int star = -1;
    int mark = 0;
    while (s < segment.length()) {
      if (p < pattern.length() && pattern.charAt(p) == segment.charAt(s)) {
        p++;
        s++;
      } else if (p < pattern.length() && pattern.charAt(p) == '*') {
        star = p++;
        mark = s;
      } else if (star >= 0) {
        p = star + 1;
        s = ++mark;
      } else {
        return false;
      }
    }
    while (p < pattern.length() && pattern.charAt(p) == '*') {
      p++;
    }
    return p == pattern.length();
  }

  @Override
  public String toString() {
    return patterns.toString();
  }
}
