/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.command;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The persistence of remote commands (V001, V011): {@link CommandRepository} on PostgreSQL; unit
 * tests of {@code CommandRouter} use an in-memory one. State changes are conditional on the row
 * still being in flight ({@code QUEUED} or {@code SENT}), so that a late transition never
 * overwrites a final one.
 */
public interface CommandStore {

  /** Status values of {@code commands.status}. */
  String QUEUED = "QUEUED";

  /** Sent on the session. */
  String SENT = "SENT";

  /** Result stored. */
  String DONE = "DONE";

  /** The agent refused or failed it ({@code error_code}). */
  String FAILED = "FAILED";

  /** TTL, result timeout or session end ({@code error_code}). */
  String EXPIRED = "EXPIRED";

  /** A new command row ({@code QUEUED}). */
  record NewCommand(
      UUID id,
      int instanceId,
      String type,
      Map<String, String> args,
      UUID requestedBy,
      Instant requestedAt,
      Instant expiresAt) {}

  /**
   * A command row with its result's metadata.
   *
   * @param resultSize uncompressed result bytes, null without a stored result
   * @param truncated the result was cut at 1 MiB, null without a stored result
   * @param contentType the result's content type, null without a stored result
   * @param requestedByName the requester's user name ({@code argos.users.username}, WO-37): the
   *     anonymised {@code deleted-<id>} of a deleted user (ADR-0010 C-231 (5)), null when unknown
   *     (an in-memory store)
   */
  record CommandRow(
      UUID id,
      int instanceId,
      String type,
      Map<String, String> args,
      UUID requestedBy,
      Instant requestedAt,
      Instant expiresAt,
      String status,
      @Nullable Instant sentAt,
      @Nullable Instant finishedAt,
      @Nullable Integer durationMs,
      @Nullable String errorCode,
      @Nullable Integer resultSize,
      @Nullable Boolean truncated,
      @Nullable String contentType,
      @Nullable String requestedByName) {

    /** A row without the requester's name (stores that do not join the users). */
    public CommandRow(
        UUID id,
        int instanceId,
        String type,
        Map<String, String> args,
        UUID requestedBy,
        Instant requestedAt,
        Instant expiresAt,
        String status,
        @Nullable Instant sentAt,
        @Nullable Instant finishedAt,
        @Nullable Integer durationMs,
        @Nullable String errorCode,
        @Nullable Integer resultSize,
        @Nullable Boolean truncated,
        @Nullable String contentType) {
      this(
          id,
          instanceId,
          type,
          args,
          requestedBy,
          requestedAt,
          expiresAt,
          status,
          sentAt,
          finishedAt,
          durationMs,
          errorCode,
          resultSize,
          truncated,
          contentType,
          null);
    }
  }

  /**
   * A stored result.
   *
   * @param envelope the 16 §8.3 envelope of the gzip-compressed result
   * @param size uncompressed bytes
   */
  record StoredResult(String envelope, int size, boolean truncated, String contentType) {}

  /** A row the expiry sweep moved to {@code EXPIRED}. */
  record Expired(UUID id, int instanceId, String type, String errorCode) {}

  /** Inserts a {@code QUEUED} command. */
  void insert(NewCommand command);

  /** {@code QUEUED} → {@code SENT}; false when the row is not queued anymore. */
  boolean markSent(UUID id, Instant sentAt);

  /**
   * An in-flight row → {@code status} ({@code FAILED} or {@code EXPIRED}) with its error code;
   * false when the row is already final.
   */
  boolean finish(
      UUID id, String status, String errorCode, Instant finishedAt, @Nullable Integer durationMs);

  /**
   * An in-flight row → {@code DONE} with its result, in one transaction; false (nothing stored)
   * when the row is already final.
   */
  boolean complete(UUID id, Instant finishedAt, @Nullable Integer durationMs, StoredResult result);

  /** The command. */
  Optional<CommandRow> find(UUID id);

  /** The newest {@code limit} commands of an instance. */
  List<CommandRow> listByInstance(int instanceId, int limit);

  /** The stored result of a command. */
  Optional<StoredResult> result(UUID id);

  /**
   * Expires the rows this node no longer tracks (a restart): {@code QUEUED} past {@code expires_at}
   * ({@code ttl}) and {@code SENT} at or before {@code sentBefore} ({@code timeout}).
   */
  List<Expired> expireOverdue(Instant now, Instant sentBefore);

  /** Deletes final commands (and their results) requested before {@code cutoff}. */
  int deleteOlderThan(Instant cutoff);

  /** Deletes the final commands of each instance beyond its newest {@code keep}. */
  int trimPerInstance(int keep);

  /** Whether the instance exists (archived ones included). */
  boolean instanceExists(int instanceId);
}
