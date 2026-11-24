/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.active;

import io.argos.server.config.ArgosProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The operator-wide default elapsed-colour thresholds of the active transactions screen (ADR-0010
 * C-225 (3), WO-39): {@code argos.ui.active-thresholds-ms}, three positive ascending milliseconds,
 * sent as {@code default_thresholds_ms} of {@code GET /apps/{appId}/active}. The UI uses a viewer's
 * own thresholds (browser storage) first, then these, then its built-in 1/3/8 s.
 *
 * <p>A wrong setting does not stop the server (a screen preference is not worth a failed start):
 * one WARN line at start names the key, and {@link #DEFAULT} applies.
 */
@Component
public class ActiveThresholds {

  /** The default: 1 s, 3 s, 8 s (the buckets of 14 §4.9). */
  public static final List<Integer> DEFAULT = List.of(1_000, 3_000, 8_000);

  /** The largest threshold accepted: one day. */
  static final int MAX_MS = 86_400_000;

  private static final Logger LOG = LoggerFactory.getLogger(ActiveThresholds.class);

  private final List<Integer> values;

  public ActiveThresholds(ArgosProperties properties) {
    List<Integer> configured =
        properties.ui() == null ? null : properties.ui().activeThresholdsMs();
    if (valid(configured)) {
      this.values = List.copyOf(configured);
    } else {
      LOG.warn(
          "argos.ui.active-thresholds-ms must be three ascending positive milliseconds (at most"
              + " {}); using the default {}",
          MAX_MS,
          DEFAULT);
      this.values = DEFAULT;
    }
  }

  /** The thresholds in effect (three ascending positive values). */
  public List<Integer> values() {
    return values;
  }

  /** Whether {@code values} are three positive, strictly ascending values up to {@link #MAX_MS}. */
  static boolean valid(List<Integer> values) {
    if (values == null || values.size() != 3) {
      return false;
    }
    int previous = 0;
    for (Integer v : values) {
      if (v == null || v <= previous || v > MAX_MS) {
        return false;
      }
      previous = v;
    }
    return true;
  }
}
