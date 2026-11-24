/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.privacyjob;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One privacy deletion job ({@code argos.privacy_jobs}, V001, V014; 13 §9, 14 §4.14). The subject
 * value is never here: only its kind and the SHA-256 of its normalised value.
 *
 * @param id job id (UUIDv4)
 * @param subjectKind {@code user_id} or {@code ip}
 * @param subjectHash SHA-256 hex of the normalised value
 * @param appId the application, or null for every application
 * @param from range start (inclusive)
 * @param to range end (exclusive)
 * @param status the state
 * @param createdBy the administrator, or null
 * @param createdAt when requested
 * @param startedAt when the runner took it, or null
 * @param finishedAt when it ended, or null
 * @param rowsAffected matching rows counted before the mutations were issued, or null while the
 *     mutations are being issued
 * @param tables the issued mutations
 * @param warnings machine codes (e.g. {@code ip_masked_scope})
 * @param error a short failure code, or null
 */
public record PrivacyJob(
    UUID id,
    String subjectKind,
    String subjectHash,
    @Nullable Integer appId,
    Instant from,
    Instant to,
    Status status,
    @Nullable UUID createdBy,
    Instant createdAt,
    @Nullable Instant startedAt,
    @Nullable Instant finishedAt,
    @Nullable Long rowsAffected,
    List<TableMutation> tables,
    List<String> warnings,
    @Nullable String error) {

  public PrivacyJob {
    tables = List.copyOf(tables);
    warnings = List.copyOf(warnings);
  }

  /** The states of a job (14 §4.14). */
  public enum Status {
    /** Waiting for the runner. */
    QUEUED,
    /** Mutations are issued or pending. */
    RUNNING,
    /** Every mutation finished. */
    DONE,
    /** The job ended without finishing every mutation. */
    FAILED;

    /** The column and API value. */
    public String value() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** The state of a column value. */
    public static Status of(String value) {
      return valueOf(value.toUpperCase(Locale.ROOT));
    }
  }

  /**
   * One issued mutation.
   *
   * @param name table name without the configured prefix ({@code xlog}, {@code profile_step})
   * @param partition the UTC day {@code yyyy-MM-dd}
   * @param mutationId the id in {@code system.mutations}
   * @param done whether it finished
   */
  public record TableMutation(String name, String partition, String mutationId, boolean done) {

    /** This mutation marked as finished. */
    public TableMutation finished() {
      return new TableMutation(name, partition, mutationId, true);
    }
  }
}
