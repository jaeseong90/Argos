/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.wal.api.WalIdentity;
import io.argos.server.wal.api.WalPosition;
import io.argos.storage.generator.load.DedupToken;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The C-61 deduplication token of the batch writer and the WAL block records: fixed vectors, and
 * the same strings as the storage generator's {@link DedupToken} (the contract the storage PoC
 * measured, 13 §5.1).
 */
@Tag("req:C-61")
@Tag("req:D-13")
@Tag("req:DSN-12-02")
class BlockTokenTest {

  private static final WalIdentity WAL = new WalIdentity("3f2c9a1e-7b4d-4c3e-9a8f-0123456789ab", 7);

  @Test
  @DisplayName(
      "fixed vectors: <wal_instance_id>:<epoch>:<first>-<last> with lower-case hex packed positions")
  void fixedVectors() {
    WalPosition first = new WalPosition(1, 16, 120);
    WalPosition last = new WalPosition(2, 0xABCDEFL, 64);
    BlockToken token = BlockToken.of(WAL, first, last);

    assertThat(first.packed()).isEqualTo(0x1_0000_0010L);
    assertThat(last.packed()).isEqualTo(0x2_00AB_CDEFL);
    assertThat(token.value())
        .isEqualTo("3f2c9a1e-7b4d-4c3e-9a8f-0123456789ab:7:100000010-200abcdef")
        .matches(BlockToken.FORMAT)
        .matches(DedupToken.FORMAT);
    assertThat(token).hasToString(token.value());
    assertThat(BlockToken.of(WAL, first, first).value())
        .isEqualTo("3f2c9a1e-7b4d-4c3e-9a8f-0123456789ab:7:100000010-100000010");
    assertThat(
            BlockToken.of(
                    new WalIdentity("w", 1),
                    new WalPosition(0, 0, 1),
                    new WalPosition(Integer.MAX_VALUE, WalPosition.MAX_OFFSET, 1))
                .value())
        .isEqualTo("w:1:0-7fffffffffffffff");
  }

  @Test
  @DisplayName(
      "shared vectors: BlockToken equals DedupToken.of for 10,000 random ranges and parses back")
  void sameAsGeneratorDedupToken() {
    SplittableRandom random = new SplittableRandom(61);
    for (int i = 0; i < 10_000; i++) {
      WalIdentity wal =
          new WalIdentity(
              i % 2 == 0 ? UUID.randomUUID().toString() : "bulk-" + i,
              1 + random.nextLong(1L << 40));
      WalPosition a = position(random);
      WalPosition b = position(random);
      WalPosition first = a.compareTo(b) <= 0 ? a : b;
      WalPosition last = a.compareTo(b) <= 0 ? b : a;

      BlockToken token = BlockToken.of(wal, first, last);
      String expected =
          DedupToken.of(wal.walInstanceId(), wal.epoch(), first.packed(), last.packed());
      assertThat(token.value()).isEqualTo(expected);
      assertThat(BlockToken.parse(expected)).isEqualTo(token);
      assertThat(BlockToken.fromCommitPayload(token.commitPayload())).isEqualTo(token);
      assertThat(token.identity()).isEqualTo(wal);
    }
  }

  @Test
  @DisplayName(
      "WAL order: packed positions increase with (segment, offset), so first <= last holds")
  void packedOrder() {
    WalPosition a = new WalPosition(3, WalPosition.MAX_OFFSET, 10);
    WalPosition b = new WalPosition(4, 0, 10);
    assertThat(a).isLessThan(b);
    assertThat(Long.compareUnsigned(a.packed(), b.packed())).isNegative();
    assertThatThrownBy(() -> BlockToken.of(WAL, b, a)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WalPosition(-1, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WalPosition(0, WalPosition.MAX_OFFSET + 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WalPosition(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("invalid tokens are refused: identity, epoch, range, shape")
  void invalid() {
    assertThatThrownBy(() -> new WalIdentity("has:colon", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WalIdentity("x".repeat(41), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WalIdentity("w", 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BlockToken("w", 1, 5, 4))
        .isInstanceOf(IllegalArgumentException.class);
    for (String bad :
        new String[] {"w:0:1-2", "w:1:1-", "w:1:A-B", "w:1:1-2:3", "", "w:01:1-2", "w:1:-1-2"}) {
      assertThatThrownBy(() -> BlockToken.parse(bad))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> BlockToken.fromCommitPayload("not a token".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a BLOCK_INTENT payload round-trips and its token must name its range")
  void blockIntent() {
    WalPosition first = new WalPosition(12, 4096, 300);
    WalPosition last = new WalPosition(13, 16, 280);
    for (Table table : Table.values()) {
      BlockIntent intent = new BlockIntent(table, first, last, BlockToken.of(WAL, first, last));
      assertThat(BlockIntent.decode(intent.encode())).isEqualTo(intent);
    }
    BlockToken other = BlockToken.of(WAL, first, first);
    assertThatThrownBy(() -> new BlockIntent(Table.XLOG, first, last, other))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] encoded =
        new BlockIntent(Table.METRIC, first, last, BlockToken.of(WAL, first, last)).encode();
    byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 3);
    assertThatThrownBy(() -> BlockIntent.decode(truncated))
        .isInstanceOf(IllegalArgumentException.class);
    encoded[1] = 9;
    assertThatThrownBy(() -> BlockIntent.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("table code 9");
  }

  private static WalPosition position(SplittableRandom random) {
    return new WalPosition(
        random.nextInt(Integer.MAX_VALUE),
        random.nextLong(WalPosition.MAX_OFFSET + 1),
        1 + random.nextInt(4 * 1024 * 1024));
  }
}
