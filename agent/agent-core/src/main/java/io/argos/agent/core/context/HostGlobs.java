/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Host globs of {@code propagation.exclude-hosts} and {@code propagation.argos-state.hosts} (10 §8,
 * §15, SEC-38): {@code *} matches any characters (dots included), {@code ?} one character, every
 * other character itself, ignoring case. A glob is compared with the host only (no scheme, no
 * port), normalized by {@link Propagation#normalizeHost} (IPv6 without brackets). At most {@value
 * #MAX_ITEMS} globs of at most {@value #MAX_GLOB_CHARS} characters are kept; blank items are
 * skipped. Matching is iterative (no regular expression, no backtracking blow-up): at most host
 * length x glob length steps per glob.
 */
public final class HostGlobs {
  /** Globs kept at most (the {@code items(256, 256)} bound of the configuration keys). */
  public static final int MAX_ITEMS = 256;

  /** Characters per glob at most. */
  public static final int MAX_GLOB_CHARS = 256;

  /** No globs: matches nothing. */
  public static final HostGlobs NONE = new HostGlobs(new String[0]);

  private final String[] globs;

  private HostGlobs(String[] globs) {
    this.globs = globs;
  }

  /** The globs of a configuration list (null or empty: {@link #NONE}). */
  public static HostGlobs of(List<String> items) {
    if (items == null || items.isEmpty()) {
      return NONE;
    }
    List<String> kept = new ArrayList<String>(Math.min(items.size(), MAX_ITEMS));
    for (String item : items) {
      if (kept.size() >= MAX_ITEMS) {
        break;
      }
      if (item == null) {
        continue;
      }
      String glob = item.trim();
      if (glob.isEmpty() || glob.length() > MAX_GLOB_CHARS) {
        continue;
      }
      if (glob.length() >= 2 && glob.charAt(0) == '[' && glob.charAt(glob.length() - 1) == ']') {
        glob = glob.substring(1, glob.length() - 1);
      }
      kept.add(glob.toLowerCase(Locale.ROOT));
    }
    return kept.isEmpty() ? NONE : new HostGlobs(kept.toArray(new String[0]));
  }

  /** Globs kept. */
  public int size() {
    return globs.length;
  }

  public boolean isEmpty() {
    return globs.length == 0;
  }

  /** True when {@code host} (raw: brackets, case and a trailing dot are normalized) matches. */
  public boolean matches(String host) {
    if (globs.length == 0) {
      return false;
    }
    String h = Propagation.normalizeHost(host);
    if (h == null) {
      return false;
    }
    for (String glob : globs) {
      if (match(glob, h)) {
        return true;
      }
    }
    return false;
  }

  /** Glob match with {@code *} and {@code ?}: the classic two-pointer walk, O(n x m) at worst. */
  static boolean match(String glob, String text) {
    int g = 0;
    int t = 0;
    int star = -1;
    int mark = 0;
    while (t < text.length()) {
      if (g < glob.length() && (glob.charAt(g) == '?' || glob.charAt(g) == text.charAt(t))) {
        g++;
        t++;
      } else if (g < glob.length() && glob.charAt(g) == '*') {
        star = g++;
        mark = t;
      } else if (star >= 0) {
        g = star + 1;
        t = ++mark;
      } else {
        return false;
      }
    }
    while (g < glob.length() && glob.charAt(g) == '*') {
      g++;
    }
    return g == glob.length();
  }
}
