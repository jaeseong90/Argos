/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.api;

import io.argos.server.wal.api.WalIdentity;
import io.argos.server.wal.api.WalPosition;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code insert_deduplication_token} of one ClickHouse block (ADR-0007 C-61, correcting C-2 and
 * DSN-02-12; 13 §5.1, 12 §3.8): {@code <wal_instance_id>:<epoch>:<first>-<last>}, where {@code
 * first} and {@code last} are the {@linkplain WalPosition#packed() packed WAL positions} of the
 * first and last data record of the block in lower-case hex, the shape of {@code
 * io.argos.storage.generator.load.DedupToken} (shared vectors: {@code BlockTokenTest}).
 *
 * <ul>
 *   <li>The token is deterministic and recorded in the {@code BLOCK_INTENT} WAL record before the
 *       insert ({@link BlockIntent}); a retry and the re-insert after recovery send the recorded
 *       token again, never a new one.
 *   <li>The writer never judges a duplicate by {@code X-ClickHouse-Summary.written_rows}:
 *       ClickHouse reports the rows of a deduplicated retry as written too (C-61).
 *   <li>Deduplication is per table: a {@code STAT} record range can feed {@code sql_stat_raw},
 *       {@code ext_stat_raw} and {@code metric} blocks with the same token, and each table (and
 *       each materialized view target) keeps its own window of 1,000 blocks.
 * </ul>
 *
 * @param walInstanceId {@link WalIdentity#walInstanceId()}
 * @param epoch {@link WalIdentity#epoch()}, at least 1
 * @param first packed position of the first data record, unsigned
 * @param last packed position of the last data record, unsigned, not below {@code first}
 */
public record BlockToken(String walInstanceId, long epoch, long first, long last) {

  /**
   * Accepted token shape, equal to {@code DedupToken.FORMAT} of the storage generator: {@code
   * <wal_instance_id>:<epoch>:<first>-<last>}.
   */
  public static final Pattern FORMAT =
      Pattern.compile("[A-Za-z0-9-]{1,40}:[1-9][0-9]*:[0-9a-f]+-[0-9a-f]+");

  private static final Pattern PARTS =
      Pattern.compile("([A-Za-z0-9-]{1,40}):([1-9][0-9]*):([0-9a-f]{1,16})-([0-9a-f]{1,16})");

  /** Validates the parts. */
  public BlockToken {
    Objects.requireNonNull(walInstanceId, "walInstanceId");
    if (!WalIdentity.INSTANCE_ID.matcher(walInstanceId).matches()) {
      throw new IllegalArgumentException("Invalid WAL instance id: " + walInstanceId);
    }
    if (epoch < 1) {
      throw new IllegalArgumentException("WAL epoch < 1: " + epoch);
    }
    if (Long.compareUnsigned(first, last) > 0) {
      throw new IllegalArgumentException(
          "Block range ends before it starts: "
              + Long.toHexString(first)
              + "-"
              + Long.toHexString(last));
    }
  }

  /**
   * The token of the block of the data records from {@code first} to {@code last} of {@code wal}.
   */
  public static BlockToken of(WalIdentity wal, WalPosition first, WalPosition last) {
    Objects.requireNonNull(wal, "wal");
    if (first.compareTo(last) > 0) {
      throw new IllegalArgumentException("first " + first + " after last " + last);
    }
    return new BlockToken(wal.walInstanceId(), wal.epoch(), first.packed(), last.packed());
  }

  /** {@code <wal_instance_id>:<epoch>:<first>-<last>}, the value sent to ClickHouse. */
  public String value() {
    return String.format(
        Locale.ROOT,
        "%s:%d:%s-%s",
        walInstanceId,
        epoch,
        Long.toHexString(first),
        Long.toHexString(last));
  }

  /** The WAL identity of the token. */
  public WalIdentity identity() {
    return new WalIdentity(walInstanceId, epoch);
  }

  /**
   * Parses a {@link #value()}.
   *
   * @throws IllegalArgumentException when {@code token} does not have the C-61 shape
   */
  public static BlockToken parse(String token) {
    Matcher m = PARTS.matcher(Objects.requireNonNull(token, "token"));
    if (!m.matches()) {
      throw new IllegalArgumentException("Not a C-61 block token: " + token);
    }
    return new BlockToken(
        m.group(1),
        Long.parseLong(m.group(2)),
        Long.parseUnsignedLong(m.group(3), 16),
        Long.parseUnsignedLong(m.group(4), 16));
  }

  /** The payload of the {@code BLOCK_COMMIT} record of this block: the UTF-8 {@link #value()}. */
  public byte[] commitPayload() {
    return value().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The token of a {@code BLOCK_COMMIT} payload.
   *
   * @throws IllegalArgumentException for a payload that is not a token
   */
  public static BlockToken fromCommitPayload(byte[] payload) {
    return parse(new String(payload, StandardCharsets.UTF_8));
  }

  /** {@link #value()}. */
  @Override
  public String toString() {
    return value();
  }
}
