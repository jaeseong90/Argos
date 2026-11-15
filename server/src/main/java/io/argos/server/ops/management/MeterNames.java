/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import io.micrometer.core.instrument.Meter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Naming rule of the server's own meters (02 §7 "메트릭 이름", 12 §9.4): Micrometer names are lower-case
 * words joined by dots or underscores and start with {@code argos.}, so that the Prometheus names
 * start with {@code argos_} ({@code argos.ingest.lag} → {@code argos_ingest_lag_seconds}). Counters
 * do not spell {@code total} (Prometheus appends {@code _total}); gauges and summaries do not end
 * with {@code total} either. Third-party meters (JVM, process, Tomcat, HikariCP, ...) keep their
 * own names.
 */
public final class MeterNames {

  /** Prefix of every Argos meter (Micrometer form). */
  public static final String PREFIX = "argos.";

  private static final Pattern NAME = Pattern.compile("argos(?:[._][a-z0-9]+)+");

  private MeterNames() {}

  /** Why {@code id} breaks the rule, or empty when it follows it. */
  public static Optional<String> violation(Meter.Id id) {
    String name = id.getName();
    if (!NAME.matcher(name).matches()) {
      return Optional.of(
          name + ": must match " + NAME.pattern() + " (lower case, prefix argos., 02 §7)");
    }
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".total") || lower.endsWith("_total")) {
      return Optional.of(name + ": must not end with total (Prometheus adds _total to counters)");
    }
    return Optional.empty();
  }
}
