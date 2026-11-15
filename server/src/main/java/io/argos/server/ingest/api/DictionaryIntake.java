/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest.api;

import io.argos.proto.v1.DictionaryEntry;
import java.util.List;

/**
 * Dictionary texts sent by an agent on the session stream (11 §7: pre-sent new hashes, or the
 * answer to a {@code DictionaryRequest}). Implemented by lane {@code durable-ingest} ({@code
 * DictionaryResolver}: memory cache, PostgreSQL {@code dictionary} upsert by {@code
 * argos-dict-writer}, the 16 §4.4 caps with the {@code <overflow>} mapping); called by lane {@code
 * collector} ({@code SessionHandler}).
 *
 * <p>The collector has checked the 11 §4.4 caps of the message first (at most 1,000 entries, text
 * caps, no hash 0, SEC-11); entries with {@code DICTIONARY_KIND_UNSPECIFIED} or an unknown kind are
 * dropped and counted by the collector ({@code argos_collector_unknown_enum_total{field="kind"}}).
 * The call never blocks the session stream.
 */
@FunctionalInterface
public interface DictionaryIntake {

  /**
   * Entries of one {@code DictionaryEntries} message of {@code instanceId}'s session in {@code
   * appId}.
   */
  void onDictionaryEntries(int appId, int instanceId, List<DictionaryEntry> entries);
}
