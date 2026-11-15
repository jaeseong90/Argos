/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.collector.limits.Violation;
import io.argos.server.testing.fakeagent.Attacks;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 12 §3.1 / §3.3 class 1: the decompressed size is bounded while it is read, and the collector's
 * marshaller turns an overflow or a malformed message into a violation instead of an exception
 * (grpc-java would answer {@code UNKNOWN}).
 */
@Tag("req:SEC-11")
class InboundMarshallerTest {

  private static final int LIMIT = 4 * 1024 * 1024;

  private static byte[] gzip(byte[] data) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(data);
    }
    return out.toByteArray();
  }

  @Test
  @DisplayName(
      "T-SEC-11 a gzip bomb stops at the limit: message_size, inflated bytes read stay bounded")
  void gzipBomb() throws IOException {
    // 64 MiB inflated (the 1 GiB bomb of 18 §8 is the securityTest's, with the RSS check): what
    // matters here is that reading stops at the limit, measured in bytes, not time (C-146 (2)).
    byte[] bomb = Attacks.gzipBomb(64L << 20);
    CountingStream counted =
        new CountingStream(
            new java.util.zip.GZIPInputStream(new ByteArrayInputStream(bomb), 8 * 1024));
    InputStream decompressed = new BoundedInputStream(counted, LIMIT);
    InboundMarshaller<TransactionBatch> m =
        new InboundMarshaller<>(TransactionBatch.parser(), LIMIT);
    Inbound<TransactionBatch> in = m.parse(decompressed);
    assertThat(in.rejected()).isTrue();
    assertThat(in.violation().reason()).isEqualTo(Violation.MESSAGE_SIZE);
    assertThat(in.violation().code()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    assertThat(counted.count).as("inflated bytes read").isBetween((long) LIMIT, LIMIT + 64L * 1024);
    // The production decompressor stops the same way.
    Inbound<TransactionBatch> viaDecompressor =
        m.parse(new BoundedGzipDecompressor(LIMIT).decompress(new ByteArrayInputStream(bomb)));
    assertThat(viaDecompressor.violation().reason()).isEqualTo(Violation.MESSAGE_SIZE);
  }

  /** Counts the bytes read through it. */
  private static final class CountingStream extends java.io.FilterInputStream {
    long count;

    CountingStream(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) {
        count++;
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      if (n > 0) {
        count += n;
      }
      return n;
    }
  }

  @Test
  @DisplayName("12 §3.3 exactly the limit passes, one byte more does not")
  void boundary() throws IOException {
    TransactionRecord record = TransactionRecord.newBuilder().setUrlPath("x".repeat(1_000)).build();
    TransactionBatch batch =
        TransactionBatch.newBuilder()
            .setHeader(BatchHeader.newBuilder().setSeq(1))
            .addRecords(record)
            .build();
    byte[] bytes = batch.toByteArray();
    InboundMarshaller<TransactionBatch> exact =
        new InboundMarshaller<>(TransactionBatch.parser(), bytes.length);
    assertThat(exact.parse(new ByteArrayInputStream(bytes)).message()).isEqualTo(batch);
    InboundMarshaller<TransactionBatch> smaller =
        new InboundMarshaller<>(TransactionBatch.parser(), bytes.length - 1);
    assertThat(smaller.parse(new ByteArrayInputStream(bytes)).violation().reason())
        .isEqualTo(Violation.MESSAGE_SIZE);
    InputStream inflated =
        new BoundedGzipDecompressor(bytes.length).decompress(new ByteArrayInputStream(gzip(bytes)));
    assertThat(exact.parse(inflated).message()).isEqualTo(batch);
  }

  @Test
  @DisplayName(
      "12 §3.3 class 5: bytes that are no protobuf message, or a broken gzip stream, are format violations")
  void malformed() throws IOException {
    InboundMarshaller<TransactionBatch> m =
        new InboundMarshaller<>(TransactionBatch.parser(), LIMIT);
    Inbound<TransactionBatch> garbage =
        m.parse(new ByteArrayInputStream(new byte[] {(byte) 0xFF, 0x00, 0x01}));
    assertThat(garbage.violation().reason()).isEqualTo(Violation.FORMAT);
    assertThat(garbage.violation().code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    byte[] broken = gzip(new byte[100]);
    broken[broken.length - 5] ^= 0x55;
    broken[12] ^= 0x7F;
    InputStream in =
        new BoundedGzipDecompressor(LIMIT).decompress(new ByteArrayInputStream(broken));
    assertThat(m.parse(in).violation().reason()).isEqualTo(Violation.FORMAT);
  }

  @Test
  @DisplayName(
      "12 §3.1 BoundedInputStream fails as soon as the limit is crossed and reports what remains")
  void boundedStream() throws IOException {
    BoundedInputStream in = new BoundedInputStream(new ByteArrayInputStream(new byte[100]), 10);
    assertThat(in.available()).isEqualTo(10);
    byte[] buffer = new byte[64];
    assertThat(in.read(buffer, 0, 10)).isEqualTo(10);
    assertThat(in.count()).isEqualTo(10);
    assertThatThrownBy(() -> in.read(buffer))
        .as("the read that crosses the limit fails (at most one byte beyond it is read)")
        .isInstanceOf(MessageTooLargeException.class);
    assertThat(in.count()).isEqualTo(11);
    BoundedInputStream greedy = new BoundedInputStream(new ByteArrayInputStream(new byte[100]), 10);
    assertThatThrownBy(() -> greedy.read(buffer)).isInstanceOf(MessageTooLargeException.class);
    assertThat(greedy.count()).isEqualTo(11);
    BoundedInputStream exact = new BoundedInputStream(new ByteArrayInputStream(new byte[10]), 10);
    assertThat(exact.readAllBytes()).hasSize(10);
    assertThat(
            new InboundMarshaller<>(TransactionBatch.parser(), LIMIT)
                .parse(ByteString.EMPTY.newInput())
                .message())
        .isEqualTo(TransactionBatch.getDefaultInstance());
  }
}
