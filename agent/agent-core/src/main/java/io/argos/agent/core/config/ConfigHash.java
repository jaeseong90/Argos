/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import io.argos.agent.core.hash.Xxh3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The configuration hash (10 §4.3 P4 and P8 banner, §12.2 step 3 {@code effective_hash}, 16 §2.6
 * {@code config_hash}): {@link Xxh3} over the canonical lines of the effective configuration, as 16
 * lower-case hex digits.
 *
 * <p>Canonical lines: one {@code key=value} line per key of {@link ConfigKeys} that has a value
 * (explicit or default; keys without a value are left out), plus one line per per-service slow
 * threshold ({@code sampling.profile.slow-threshold-ms.services.<service>=<ms>}), sorted by {@link
 * String#compareTo(String)} and joined with {@code \n}, hashed over their UTF-8 bytes. Values are
 * rendered from their typed form, so the way a value was written does not matter ({@code 32MB} and
 * {@code 33554432}, {@code a, b} and {@code ["a", "b"]} give the same hash): booleans {@code
 * true}/{@code false}, numbers in decimal ({@code BYTES} as bytes, {@code DURATION} as
 * milliseconds), doubles by {@link Double#toString(double)}, strings with {@code \}, newline and
 * carriage return escaped, lists as {@code ["a","b"]} with {@code \} and {@code "} escaped.
 *
 * <p>Secret keys ({@code server.token}) are never part of the input (SEC-06, 16 §2.6), whatever the
 * token is; {@code server.token-file} (a path) is.
 */
public final class ConfigHash {
  private ConfigHash() {}

  /** The hash of {@code config}: 16 lower-case hex digits. */
  public static String of(AgentConfig config) {
    StringBuilder text = new StringBuilder(4096);
    List<String> lines = canonicalLines(config);
    for (int i = 0; i < lines.size(); i++) {
      if (i > 0) {
        text.append('\n');
      }
      text.append(lines.get(i));
    }
    return hex(Xxh3.hash64(text.toString()));
  }

  /** The sorted canonical lines of {@code config} (see the class comment). */
  public static List<String> canonicalLines(AgentConfig config) {
    List<String> lines = new ArrayList<String>();
    for (KeySpec spec : ConfigKeys.all()) {
      if (spec.isSecret() || spec.isFamily()) {
        continue;
      }
      Object value = config.value(spec.name());
      if (value != null) {
        lines.add(spec.name() + "=" + render(value));
      }
    }
    for (Map.Entry<String, Integer> entry : config.slowThresholdServices().entrySet()) {
      lines.add(
          ConfigKeys.SLOW_THRESHOLD_SERVICES
              + escape(entry.getKey())
              + "="
              + entry.getValue().intValue());
    }
    Collections.sort(lines);
    return lines;
  }

  /** The canonical text of a typed value. */
  static String render(Object value) {
    if (value instanceof List) {
      StringBuilder out = new StringBuilder("[");
      boolean first = true;
      for (Object item : (List<?>) value) {
        if (!first) {
          out.append(',');
        }
        first = false;
        out.append('"');
        String text = String.valueOf(item);
        for (int i = 0; i < text.length(); i++) {
          char c = text.charAt(i);
          if (c == '"' || c == '\\') {
            out.append('\\');
          }
          out.append(c);
        }
        out.append('"');
      }
      return out.append(']').toString();
    }
    if (value instanceof Double) {
      return Double.toString((Double) value);
    }
    if (value instanceof String) {
      return escape((String) value);
    }
    return String.valueOf(value);
  }

  private static String escape(String text) {
    if (text.indexOf('\\') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\') {
        out.append("\\\\");
      } else if (c == '\n') {
        out.append("\\n");
      } else if (c == '\r') {
        out.append("\\r");
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static String hex(long value) {
    String digits = Long.toHexString(value);
    StringBuilder out = new StringBuilder(16);
    for (int i = digits.length(); i < 16; i++) {
      out.append('0');
    }
    return out.append(digits).toString();
  }
}
