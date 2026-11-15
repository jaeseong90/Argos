/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The identity of a WAL directory (ADR-0007 C-61): a random instance id created with the WAL and an
 * epoch that increases whenever the WAL is re-initialized (an emptied or replaced directory). Both
 * are the first two parts of every deduplication token, so two servers, or one server before and
 * after a WAL reset, never produce the same token. Kept in a persistent identity file of the WAL
 * directory (lane {@code durable-ingest}).
 *
 * @param walInstanceId letters, digits and dashes, 1-40 characters (a UUID string fits)
 * @param epoch at least 1
 */
public record WalIdentity(String walInstanceId, long epoch) {

  /** Allowed instance ids: the first part of the C-61 token format. */
  public static final Pattern INSTANCE_ID = Pattern.compile("[A-Za-z0-9-]{1,40}");

  /** Validates the token-safe shape. */
  public WalIdentity {
    Objects.requireNonNull(walInstanceId, "walInstanceId");
    if (!INSTANCE_ID.matcher(walInstanceId).matches()) {
      throw new IllegalArgumentException("Invalid WAL instance id: " + walInstanceId);
    }
    if (epoch < 1) {
      throw new IllegalArgumentException("WAL epoch < 1: " + epoch);
    }
  }
}
