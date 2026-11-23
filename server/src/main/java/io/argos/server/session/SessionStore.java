/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.server.ingest.hash.Xxh3;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The PostgreSQL reads and writes of a session start (11 §5 checks 5 and 6, the {@code instances}
 * row of 12 §4.3) and of {@code InstanceUpdate} (ADR-0010 C-146), behind one port so that the check
 * order is testable without a database. Implemented in {@code
 * io.argos.server.storage.postgres.instance} (SRV-01: JDBC only in storage).
 *
 * <p>Only identity and metadata columns are written here; the state columns of {@code instances}
 * ({@code status}, {@code warn_flags}, {@code clock_offset_ms}, {@code last_seen_at}, {@code
 * archived_at}) are WO-22's (ADR-0010 C-128).
 *
 * <p>Every method may throw a Spring {@code DataAccessException} when PostgreSQL fails. The
 * collector reads through {@link CachingSessionStore}, which answers the app and instance lookups
 * of known rows from memory meanwhile; a Hello that still needs the database is answered {@code
 * UNAVAILABLE} (12 §3.9).
 */
public interface SessionStore {

  /**
   * Characters of a stored instance name: {@code instances.name VARCHAR(128)} (V001), although 11
   * §4.2 allows 2 KiB; longer names are stored as {@link #storedInstanceName} (ADR-0010 C-157 (4)).
   */
  int MAX_INSTANCE_NAME_CHARS = 128;

  /** Leading characters kept of an instance name above {@link #MAX_INSTANCE_NAME_CHARS}. */
  int INSTANCE_NAME_PREFIX_CHARS = 119;

  /**
   * The stored form of instance name {@code name} (ADR-0010 C-157 (4)): the name itself up to
   * {@value #MAX_INSTANCE_NAME_CHARS} characters; a longer name becomes its first {@value
   * #INSTANCE_NAME_PREFIX_CHARS} characters, {@code ~} and the low 32 bits of the XXH3-64 of the
   * whole name's UTF-8 bytes as 8 lower-case hex digits (128 characters), so that two names that
   * share their first 128 characters stay two instances. An identity column is never merged by
   * cutting; metadata columns are cut ({@link #cut}). {@code null} stays {@code null}.
   */
  static String storedInstanceName(String name) {
    if (name == null || name.length() <= MAX_INSTANCE_NAME_CHARS) {
      return name;
    }
    byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
    long hash = Xxh3.raw64(utf8, 0, utf8.length);
    return cut(name, INSTANCE_NAME_PREFIX_CHARS) + "~" + HexFormat.of().toHexDigits((int) hash);
  }

  /**
   * {@code value} cut to at most {@code maxChars} characters without splitting a surrogate pair;
   * {@code null} stays {@code null}.
   */
  static String cut(String value, int maxChars) {
    if (value == null || value.length() <= maxChars) {
      return value;
    }
    int end = maxChars;
    if (Character.isHighSurrogate(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(0, end);
  }

  /** The app ({@code name}, {@code env}) when it exists (DSN-02-14). */
  Optional<AppRef> findApp(String name, String env);

  /**
   * Creates the app ({@code name}, {@code env}) as an automatic registration (12 §4.4) and an
   * environment variant of the token's home app {@code homeAppId} ({@code registered_from_app_id},
   * ADR-0010 C-155), unless the home app already has {@code maxVariants} variants. The count and
   * the insert run in one transaction with the home app's row locked, so that concurrent Hellos, on
   * any server, never register more than {@code maxVariants}.
   *
   * @return the app ({@code name}, {@code env}): the new row, or the row another session created
   *     meanwhile (whatever its origin: the caller checks {@link AppRef#usableWith}); empty when
   *     the home app has {@code maxVariants} variants already or no longer exists
   */
  Optional<AppRef> createVariantApp(String name, String env, int homeAppId, int maxVariants);

  /**
   * Queues a refused registration in the approval queue (12 §4.4, F-S30, ADR-0010 C-155 (3)):
   * ({@code name}, {@code env}, {@code host}) upserted with one more attempt at {@code at}, for the
   * agent token's home app {@code homeAppId}; at most 1,000 rows, the least recently attempted one
   * replaced beyond them.
   */
  void recordPendingRegistration(String name, String env, String host, int homeAppId, Instant at);

  /** The id of instance ({@code appId}, {@code name}) when it exists (11 §5: reused). */
  OptionalInt findInstance(int appId, String name);

  /** Instances of {@code appId} that are not archived (11 §5 check 6, SEC-14). */
  int countInstances(int appId);

  /** Instances of {@code appId} first seen at or after {@code since} (new instances per hour). */
  int countInstancesCreatedSince(int appId, Instant since);

  /**
   * Creates instance ({@code appId}, {@code name}) with {@code metadata}; returns the id of the
   * existing row when another session created it meanwhile.
   */
  int createInstance(int appId, String name, InstanceMetadata metadata);

  /**
   * Writes the Hello metadata of an existing instance; a row whose values are already equal is not
   * written.
   *
   * @return whether the row changed
   */
  boolean updateInstanceMetadata(int instanceId, InstanceMetadata metadata);

  /**
   * Applies the fields of an {@code InstanceUpdate} that are set (11 §4.4: absent = unchanged); a
   * row whose values are already equal is not written (C-146).
   */
  UpdateResult applyInstanceUpdate(int instanceId, InstanceChanges changes);

  /**
   * Records that token {@code tokenId} started a session at {@code now} ({@code
   * agent_tokens.last_used_at}); written at most once per 5 minutes per token (16 §2.2).
   */
  void touchToken(UUID tokenId, Instant now);

  /** The outcome of {@link #applyInstanceUpdate}. */
  enum UpdateResult {
    /** The row already had these values: nothing written. */
    UNCHANGED,
    /** The row was written. */
    CHANGED,
    /**
     * Another instance of the app has the requested name: the name was kept, the other set fields
     * were applied (12 §4.3; reported to the PM).
     */
    NAME_CONFLICT
  }
}
