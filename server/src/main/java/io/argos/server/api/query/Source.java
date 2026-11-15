/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import com.fasterxml.jackson.annotation.JsonValue;
import io.argos.server.storage.query.TableLevel;

/**
 * The {@code meta.source} of a time-series or statistics response (14 §2.5, DSN-14-04): which
 * storage level the numbers come from, {@code mixed} when raw rows and rollups were merged (the
 * last 2 minutes, unaligned range edges, partial hours; 14 §3).
 */
public enum Source {
  /** Raw tables only. */
  RAW("raw"),
  /** Minute rollups only. */
  MINUTE("1m"),
  /** Hour rollups only. */
  HOUR("1h"),
  /** More than one level merged. */
  MIXED("mixed");

  private final String value;

  Source(String value) {
    this.value = value;
  }

  /** The wire value. */
  @JsonValue
  public String value() {
    return value;
  }

  /** The source of a single storage level. */
  public static Source of(TableLevel level) {
    return switch (level) {
      case RAW -> RAW;
      case MINUTE -> MINUTE;
      case HOUR -> HOUR;
    };
  }
}
