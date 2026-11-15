/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionRecord;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * The five attacks of 18 §8 ({@code --attack}) at protocol level, for T-SEC-11..15 and
 * T-SV-012..014. Each helper only builds or sends; the test asserts the server's codes, counters,
 * memory and the latency of other instances.
 *
 * <ul>
 *   <li>{@code gzip-bomb}: {@link #gzipBomb(long)} (1 MB of gzip that inflates to 1 GB) sent with
 *       {@link FakeAgent#sendRaw} over a channel with {@link
 *       AgentChannels.Options#withPreCompressedGzip()}: the server must refuse it before decoding
 *       (4 MiB after decompression, SEC-11);
 *   <li>{@code batch-overflow}: {@link #oversizedTransactionBatch} (5,001 records, 11 §4.4);
 *   <li>{@code dict-flood}: {@link #randomServiceTransactions} and {@link #randomServiceEntries}
 *       (100,000 random service names, SEC-13, 16 §4.4);
 *   <li>{@code instance-flood}: {@link #instanceFlood} (1,000 new instances, SEC-14);
 *   <li>{@code env-rotation}: {@link #envRotation} (one token's Hellos for {@value
 *       #ENV_ROTATION_ENVIRONMENTS} new environments, each with a new instance: SEC-13 and SEC-14
 *       must hold per token, ADR-0010 C-155, WO-21 D-1);
 *   <li>{@code unauth-flood}: {@link #unauthenticatedHelloFlood} (Hellos with unknown tokens from
 *       one address, SEC-05: 20 failures per minute block the address for 10 minutes).
 * </ul>
 */
public final class Attacks {

  /** 18 §8 gzip bomb: inflated size. */
  public static final long GZIP_BOMB_INFLATED_BYTES = 1L << 30;

  /** 18 §8 batch overflow: one record above the cap. */
  public static final int OVERFLOW_RECORDS = ProtocolLimits.MAX_RECORDS_PER_BATCH + 1;

  /** 18 §8 dictionary flood: random service names. */
  public static final int DICTIONARY_FLOOD_SERVICES = 100_000;

  /** 18 §8 instance flood: new instances per hour. */
  public static final int INSTANCE_FLOOD_INSTANCES = 1_000;

  /** 18 §8 unauthenticated flood: failed Hellos per minute from one address. */
  public static final int UNAUTHENTICATED_HELLOS_PER_MINUTE = 100;

  /** Env rotation (C-155): environments one token tries (the WO-21 reviewer probe used 30). */
  public static final int ENV_ROTATION_ENVIRONMENTS = 30;

  private static final Map<Long, byte[]> BOMBS = new ConcurrentHashMap<>();

  private Attacks() {}

  /**
   * A gzip stream (cached per size, about 1 MB for 1 GiB) that inflates to {@code inflatedBytes} of
   * a <b>well-formed</b> {@code TransactionBatch} prefix: field 2 ({@code records}) holding one
   * {@code TransactionRecord} whose field 13 ({@code url_path}) is a string of {@code 'a'} filling
   * the rest. A protobuf parser therefore keeps reading until the inflated-size limit stops it; a
   * bomb of zero bytes would end at the first byte with an "invalid tag" parse error (gRPC {@code
   * INTERNAL}) and hide whether the limit works (observed with grpc-java 1.85.1, WO-21 contracts
   * step).
   *
   * @param inflatedBytes inflated size, at least 64 bytes
   */
  public static byte[] gzipBomb(long inflatedBytes) {
    if (inflatedBytes < 64) {
      throw new IllegalArgumentException("inflatedBytes < 64: " + inflatedBytes);
    }
    return BOMBS.computeIfAbsent(inflatedBytes, Attacks::deflateBatchPrefix).clone();
  }

  private static byte[] deflateBatchPrefix(long inflatedBytes) {
    // TransactionBatch { records (2, LEN) = TransactionRecord { url_path (13, LEN) = "aaa..." } }
    long stringLength = inflatedBytes - 24;
    long recordLength = 1 + varintSize(stringLength) + stringLength;
    ByteArrayOutputStream prefix = new ByteArrayOutputStream(32);
    prefix.write(0x12);
    writeVarint(prefix, recordLength);
    prefix.write(0x6A);
    writeVarint(prefix, stringLength);
    ByteArrayOutputStream out = new ByteArrayOutputStream(2 * 1024 * 1024);
    byte[] fill = new byte[1024 * 1024];
    java.util.Arrays.fill(fill, (byte) 'a');
    try (GZIPOutputStream gzip = new MaxCompressionGzip(out)) {
      prefix.writeTo(gzip);
      long left = stringLength;
      while (left > 0) {
        int n = (int) Math.min(fill.length, left);
        gzip.write(fill, 0, n);
        left -= n;
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  private static int varintSize(long value) {
    int size = 1;
    for (long v = value >>> 7; v != 0; v >>>= 7) {
      size++;
    }
    return size;
  }

  private static void writeVarint(ByteArrayOutputStream out, long value) {
    long v = value;
    while ((v & ~0x7FL) != 0) {
      out.write((int) ((v & 0x7F) | 0x80));
      v >>>= 7;
    }
    out.write((int) v);
  }

  /** A gzip stream at the best deflate compression (about 1:1,000 for a repeated byte). */
  private static final class MaxCompressionGzip extends GZIPOutputStream {
    MaxCompressionGzip(OutputStream out) throws IOException {
      super(out, 64 * 1024);
      def.setLevel(Deflater.BEST_COMPRESSION);
    }
  }

  /**
   * A transaction batch of {@code records} records (5,001 for the 18 §8 overflow) with the next
   * seq.
   */
  public static TransactionBatch oversizedTransactionBatch(FakeAgent agent, int records) {
    List<TransactionRecord> list = new ArrayList<>(records);
    long start = agent.clock().nowMs() - 1_000;
    for (int i = 0; i < records; i++) {
      list.add(agent.records().http("GET /api/orders/{id}", start, 1_000).build());
    }
    return agent.batches().transactions(list);
  }

  /**
   * {@code count} transactions, each with its own random service name (SERVICE ids the server has
   * never seen), in batches of at most 5,000 records with consecutive seqs.
   */
  public static List<TransactionBatch> randomServiceTransactions(
      FakeAgent agent, int count, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    List<TransactionBatch> batches = new ArrayList<>();
    List<TransactionRecord> current = new ArrayList<>();
    long start = agent.clock().nowMs() - 1_000;
    for (int i = 0; i < count; i++) {
      String service = "GET /flood/" + Long.toHexString(random.nextLong());
      current.add(agent.records().http(service, start, 1_000).build());
      if (current.size() == ProtocolLimits.MAX_RECORDS_PER_BATCH) {
        batches.add(agent.batches().transactions(current));
        current = new ArrayList<>();
      }
    }
    if (!current.isEmpty()) {
      batches.add(agent.batches().transactions(current));
    }
    return batches;
  }

  /**
   * {@code count} dictionary entries of random service names (send with {@link
   * FakeAgent#sendDictionaryEntries}).
   */
  public static List<DictionaryEntry> randomServiceEntries(FakeAgent agent, int count, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    List<DictionaryEntry> entries = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      String service = "GET /flood/" + Long.toHexString(random.nextLong());
      long hash = agent.records().id(DictionaryKind.SERVICE, service);
      entries.add(agent.records().entry(DictionaryKind.SERVICE, hash).orElseThrow());
    }
    return entries;
  }

  /**
   * Starts {@code count} sessions, each with a new instance name ({@code <prefix>-<i>}), one after
   * the other on {@code agent}'s channel; each accepted session is cancelled before the next starts
   * (so that the connection's concurrent-call cap is not what stops the flood).
   *
   * @return the outcome of every Hello, in order
   */
  public static List<HelloResult> instanceFlood(FakeAgent agent, String prefix, int count) {
    List<HelloResult> results = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      String name = prefix + "-" + i;
      results.add(agent.connect(agent.helloTemplate().toBuilder().setInstanceName(name).build()));
      agent.cancelSession();
    }
    return results;
  }

  /**
   * Starts {@code count} sessions of {@code agent}'s token, each for a new environment ({@code
   * <prefix>-<i>}) with a new instance ({@code <prefix>:<i>}), one after the other; each accepted
   * session is cancelled before the next starts. Without a token scope this registers one app and
   * one instance per environment (WO-21 D-1).
   *
   * @return the outcome of every Hello, in order
   */
  public static List<HelloResult> envRotation(FakeAgent agent, String prefix, int count) {
    List<HelloResult> results = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      results.add(
          agent.connect(
              agent.helloTemplate().toBuilder()
                  .setEnv(prefix + "-" + i)
                  .setInstanceName(prefix + ":" + i)
                  .build()));
      agent.cancelSession();
    }
    return results;
  }

  /**
   * {@code attempts} session starts with a well-formed but unknown token (a fresh {@link
   * AgentTokens#generate()} each), all from the channel's address, {@code pause} apart.
   *
   * @param agents creates the fake agent of attempt {@code i} with the given token (same channel)
   */
  public static List<HelloResult> unauthenticatedHelloFlood(
      IntFunction<FakeAgent> agents, int attempts, Duration pause) {
    List<HelloResult> results = new ArrayList<>(attempts);
    for (int i = 0; i < attempts; i++) {
      FakeAgent agent = agents.apply(i);
      try {
        results.add(agent.connect());
      } finally {
        agent.cancelSession();
      }
      if (!pause.isZero() && i + 1 < attempts) {
        try {
          Thread.sleep(pause.toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    return results;
  }
}
