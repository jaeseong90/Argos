/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.argos.server.session.api.ApdexOverrides;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The PostgreSQL side of the ingest pipeline (13 §12.2, §12.3): dictionary rows, error groups and
 * their representative stack, the Apdex T of the apps and their service overrides with the change
 * events (WO-27). Implemented in {@code io.argos.server.storage.postgres.ingest} with the runtime
 * account {@code argos_app} (SRV-01: JDBC only in storage packages). Every method is called from
 * the ingest helper threads ({@code argos-dict-writer}, the Apdex refresh, the stack filler) or an
 * operator path ({@link ApdexChangeService}), never from a gRPC thread.
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
   * The outcome of an Apdex T change ({@link ApdexChangeService}).
   *
   * @param appId {@code apps.id}
   * @param serviceId the service of an override change, {@code null} for the app's T
   * @param oldTMs the T in effect before (for a service: its override, else the app's T)
   * @param newTMs the T in effect after (for a removed override: the app's T)
   * @param eventId the {@code events} row written, {@code null} when nothing changed
   * @param ts the time of the change (the event's {@code ts})
   */
  record ApdexChange(int appId, Long serviceId, int oldTMs, int newTMs, UUID eventId, Instant ts) {

    /** Whether the T in effect changed (an event was written). */
    public boolean changed() {
      return eventId != null;
    }

    /** The event title (English; the UI shows it as data). */
    public String title() {
      return (serviceId == null ? "Apdex T changed from " : "Service Apdex T changed from ")
          + oldTMs
          + " ms to "
          + newTMs
          + " ms";
    }

    /**
     * The event detail: {@code {"old_t_ms": n, "new_t_ms": n, "service_id": "<unsigned decimal>" |
     * null}}. Numbers and an id only, never collected text.
     */
    public String detailJson() {
      return "{\"old_t_ms\":"
          + oldTMs
          + ",\"new_t_ms\":"
          + newTMs
          + ",\"service_id\":"
          + (serviceId == null ? "null" : "\"" + Long.toUnsignedString(serviceId) + "\"")
          + "}";
    }
  }

  /**
   * An error group without a representative stack.
   *
   * @param appId {@code apps.id}
   * @param groupId {@code error_group_id}
   */
  record StackCandidate(int appId, long groupId) {}

  /** The override limit of an app was reached ({@link ApdexOverrides#MAX_PER_APP}). */
  final class OverrideLimitException extends Exception {
    private static final long serialVersionUID = 1L;

    public OverrideLimitException(String message) {
      super(message);
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
   * message_pattern; the representative stack is filled later by {@link ErrorGroupStackFiller}).
   */
  void upsertErrorGroups(List<ErrorGroupRow> rows) throws Exception;

  /** {@code apps.id → apps.apdex_t_ms} of every app. */
  Map<Integer, Integer> apdexByApp() throws Exception;

  /**
   * {@code service_apdex_overrides} of every app: {@code app_id → (service_id → apdex_t_ms)}, at
   * most {@code perAppLimit + 1} rows per app (the caller refuses an app above the limit).
   */
  Map<Integer, Map<Long, Integer>> serviceApdexOverrides(int perAppLimit) throws Exception;

  /**
   * Sets {@code apps.apdex_t_ms} of {@code appId} and, when it changed, writes one {@code events}
   * row of type {@code apdex_t_change}, in one transaction.
   *
   * @param actor the user who made the change, {@code null} for an operator command
   * @return empty when the app does not exist
   */
  Optional<ApdexChange> changeAppApdex(int appId, int tMs, UUID actor, Instant ts) throws Exception;

  /**
   * Sets ({@code tMs} non-null) or removes ({@code tMs} null) the override of {@code serviceId}
   * and, when the T in effect changed, writes one {@code events} row of type {@code
   * apdex_t_change}, in one transaction.
   *
   * @param maxPerApp most overrides of one app
   * @return empty when the app does not exist
   * @throws OverrideLimitException when a new override would exceed {@code maxPerApp}
   */
  Optional<ApdexChange> changeServiceApdex(
      int appId, long serviceId, Integer tMs, UUID actor, Instant ts, int maxPerApp)
      throws Exception;

  /**
   * Error groups whose {@code sample_stack} is null and whose {@code last_seen} is at or after
   * {@code lastSeenFrom}, latest first, at most {@code limit}.
   */
  List<StackCandidate> groupsWithoutStack(Instant lastSeenFrom, int limit) throws Exception;

  /** The texts of {@code hashes} of {@code (appId, kind)} that have a dictionary row. */
  Map<Long, String> dictionaryTexts(int appId, String kind, Collection<Long> hashes)
      throws Exception;

  /**
   * Sets {@code sample_stack} of the group when it is still null.
   *
   * @return {@code true} when this call set it
   */
  boolean fillSampleStack(int appId, long groupId, String stack) throws Exception;
}
