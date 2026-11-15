/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The PostgreSQL side of the ingest pipeline (13 §12.2, §12.3): dictionary rows, error groups and
 * the Apdex T of the apps. Implemented in {@code io.argos.server.storage.postgres.ingest} with the
 * runtime account {@code argos_app} (SRV-01: JDBC only in storage packages). Every method is called
 * from the ingest helper threads ({@code argos-dict-writer}, the Apdex refresh), never from a gRPC
 * thread.
 */
public interface IngestStore {

  /**
   * One dictionary row.
   *
   * @param appId {@code apps.id}
   * @param kind the PostgreSQL {@code dict_kind} name ({@link DictKinds})
   * @param hash xxh3_64 of the text, stored as signed {@code BIGINT}
   * @param text the text, at most 64 KiB of UTF-8 and without NUL characters
   * @param truncated the text was cut (by the agent or here)
   * @param normVersion normalizer version of {@code sql} entries, {@code null} otherwise
   */
  record DictionaryRow(
      int appId, String kind, long hash, String text, boolean truncated, Integer normVersion) {

    /** Validates the references. */
    public DictionaryRow {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(text, "text");
    }

    @Override
    public String toString() {
      // Never the text: it may hold collected data.
      return "DictionaryRow[" + appId + "/" + kind + "/" + Long.toHexString(hash) + "]";
    }
  }

  /**
   * Accumulated occurrences of one error group.
   *
   * @param appId {@code apps.id}
   * @param groupId {@code error_group_id} (ADR-0010 C-131)
   * @param errorClassId CLASS dictionary id of the exception class (0: none); the store fills
   *     {@code error_class} from the dictionary when the text is known
   * @param topFrameId FRAME dictionary id of the top application frame (0: none); fills {@code
   *     top_frame} likewise
   * @param messagePattern masked, normalized message pattern
   * @param firstSeen earliest transaction time of the occurrences
   * @param lastSeen latest transaction time of the occurrences
   * @param count number of occurrences
   */
  record ErrorGroupRow(
      int appId,
      long groupId,
      long errorClassId,
      long topFrameId,
      String messagePattern,
      Instant firstSeen,
      Instant lastSeen,
      long count) {

    @Override
    public String toString() {
      return "ErrorGroupRow[" + appId + "/" + Long.toHexString(groupId) + " x" + count + "]";
    }
  }

  /**
   * Inserts the rows; an existing {@code (app_id, kind, hash64)} only gets {@code last_seen =
   * now()} (11 §7, 12 §3.5 (2)).
   */
  void upsertDictionary(List<DictionaryRow> rows) throws Exception;

  /** The hashes of {@code hashes} that already have a row for {@code (appId, kind)}. */
  Set<Long> existingDictionary(int appId, String kind, Collection<Long> hashes) throws Exception;

  /** Number of dictionary rows of {@code appId} (the 1,000,000 cap of 16 §4.4). */
  long dictionaryCount(int appId) throws Exception;

  /**
   * Adds the occurrences to {@code error_groups} (first_seen, last_seen, count_total,
   * message_pattern; the representative stack is WO-27's).
   */
  void upsertErrorGroups(List<ErrorGroupRow> rows) throws Exception;

  /** {@code apps.id → apps.apdex_t_ms} of every app. */
  Map<Integer, Integer> apdexByApp() throws Exception;
}
