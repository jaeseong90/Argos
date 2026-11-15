/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The local {@code remote-config.allow} list (SEC-23, 16 §5.5): key globs a collector may change.
 * It is the hard cap of remote configuration: only the local yml sets it, never a push.
 *
 * <p>Glob rules of 16 §5.5 step 2: segments are separated by {@code .}; {@code *} matches within
 * one segment (so {@code instrumentation.ignore-*} matches {@code instrumentation.ignore-paths}), a
 * segment {@code **} matches every remaining segment. A pattern written with the {@code argos.}
 * prefix ({@code argos.enabled} in SEC-23 and the 16 §5.5 example) means the key without it.
 *
 * <p>Default ({@link #DEFAULT}): the five patterns of SEC-23 chosen by ADR-0010 C-92.
 */
public final class AllowList {
  /** At most this many patterns are kept (the {@code remote-config.allow} cap of the registry). */
  public static final int MAX_PATTERNS = 128;

  /** The default list (ADR-0010 C-92). */
  public static final AllowList DEFAULT = of(AgentConfig.defaults().remoteConfigAllow());

  private final List<String> patterns;

  private AllowList(List<String> patterns) {
    this.patterns = patterns;
  }

  /**
   * The list of {@code patterns} (the typed value of {@code remote-config.allow}); blank patterns
   * are dropped, the {@code argos.} prefix removed, at most {@value #MAX_PATTERNS} kept.
   */
  public static AllowList of(List<String> patterns) {
    List<String> kept = new ArrayList<String>();
    for (String pattern : patterns) {
      if (pattern == null || pattern.trim().isEmpty() || kept.size() >= MAX_PATTERNS) {
        continue;
      }
      kept.add(ConfigKeys.canonical(pattern.trim()));
    }
    return new AllowList(Collections.unmodifiableList(kept));
  }

  /** The patterns, canonical (no {@code argos.} prefix). */
  public List<String> patterns() {
    return patterns;
  }

  /** True when a pattern matches {@code key} (canonical name). */
  public boolean matches(String key) {
    String canonical = ConfigKeys.canonical(key);
    if (canonical == null) {
      return false;
    }
    for (String pattern : patterns) {
      if (globMatches(pattern, canonical)) {
        return true;
      }
    }
    return false;
  }

  /** 16 §5.5 glob match of a dotted key: {@code *} within a segment, {@code **} the rest. */
  static boolean globMatches(String pattern, String key) {
    String[] patternSegments = pattern.split("\\.", -1);
    String[] keySegments = key.split("\\.", -1);
    for (int i = 0; i < patternSegments.length; i++) {
      if (patternSegments[i].equals("**")) {
        return i < keySegments.length; // matches at least one remaining segment
      }
      if (i >= keySegments.length || !segmentMatches(patternSegments[i], keySegments[i])) {
        return false;
      }
    }
    return patternSegments.length == keySegments.length;
  }

  /** {@code *} matches any run of characters inside one segment. */
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
