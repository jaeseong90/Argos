/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * A time-range query whose range is read from several storage levels at once (14 §3, 13 §6.1,
 * DSN-14-06): the source plan of {@code SourceSelector} splits the range into {@link TimeSegment}s
 * (raw edges and tail, minute and hour rollups), and this query reads all of them in <b>one</b>
 * ClickHouse statement:
 *
 * <pre>
 *   outer template ... FROM ({segments}) ... GROUP BY ...
 *   {segments} = SELECT * FROM (&lt;fragment of segment 0&gt;) UNION ALL SELECT * FROM (&lt;fragment 1&gt;) ...
 * </pre>
 *
 * Each fragment is the template of its segment's level; its parameters {@code {from:...}} and
 * {@code {to:...}} are renamed per segment ({@code from_s0}, {@code to_s0}, ...) and bound to the
 * segment bounds, every other parameter is shared. The fragments return the same columns (partial
 * aggregates such as {@code count}, sums and {@code quantilesDDMergeState}/{@code quantilesDDState}
 * states) and the outer template merges them ({@code sum}, {@code max}, {@code quantilesDDMerge},
 * C-53), so the merge is exact and happens in ClickHouse, as in the {@code 1d_live} form of Q07.
 *
 * @param outer the outer template; contains {@code {segments}} once
 * @param fragments the fragment template of each level the plan may use; each declares {@code
 *     {from:...}} and {@code {to:...}}
 */
public record SegmentedQuery(ReaderQuery outer, Map<TableLevel, ReaderQuery> fragments) {

  public SegmentedQuery {
    Objects.requireNonNull(outer, "outer");
    if (!outer.segmentsPlaceholder()) {
      throw new IllegalArgumentException(
          "Outer template " + outer.name() + " has no {segments} placeholder");
    }
    EnumMap<TableLevel, ReaderQuery> copy = new EnumMap<>(TableLevel.class);
    copy.putAll(fragments);
    for (Map.Entry<TableLevel, ReaderQuery> e : copy.entrySet()) {
      ReaderQuery f = e.getValue();
      if (f.segmentsPlaceholder()) {
        throw new IllegalArgumentException("Fragment " + f.name() + " contains {segments}");
      }
      if (!f.parameters().containsKey("from") || !f.parameters().containsKey("to")) {
        throw new IllegalArgumentException(
            "Fragment " + f.name() + " must declare {from:...} and {to:...}");
      }
    }
    if (copy.isEmpty()) {
      throw new IllegalArgumentException("No fragment for " + outer.name());
    }
    fragments = Collections.unmodifiableMap(copy);
  }

  /** The fragment of {@code level}, or an exception naming the missing level. */
  public ReaderQuery fragment(TableLevel level) {
    ReaderQuery f = fragments.get(level);
    if (f == null) {
      throw new IllegalStateException(
          "Query " + outer.name() + " has no fragment for level " + level.label());
    }
    return f;
  }
}
