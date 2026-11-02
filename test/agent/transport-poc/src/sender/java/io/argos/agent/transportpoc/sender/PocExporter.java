/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.sender;

import io.argos.agent.core.export.Exporter;
import io.argos.agent.core.export.ExporterContext;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.transportpoc.proto.Ack;
import io.argos.agent.transportpoc.proto.TransactionBatch;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Minimal sender of the P-3 comparison jars (the {@code grpc} exporter slot): every second ({@link
 * PocSettings#INTERVAL_MILLIS}) it sends one small {@code TransactionBatch} through a {@link
 * PocTransport} and waits for the {@code Ack}. It runs only on {@code argos-sender} ({@link
 * io.argos.agent.core.export.ExportWorker} calls {@link #flush()} every 100 ms), so application
 * threads never wait for it. Failures are retried with a 1 s to 30 s backoff and logged to the
 * agent log only (rate limited); nothing is printed to stdout or stderr.
 *
 * <p>Log lines read by the P-3 bench ({@code TransportBench}): {@code transport-poc started},
 * {@code transport-poc first-ack}, {@code transport-poc disabled}, {@code transport-poc failed}.
 */
public final class PocExporter implements Exporter {
  private static final long MAX_BACKOFF_MILLIS = 30_000;

  private final PocTransport transport;
  private final PocSettings settings;
  private final BatchFactory batches;
  private final boolean enabled;
  private long nextAttemptNanos;
  private long backoffMillis = PocSettings.INTERVAL_MILLIS;
  private long acked;
  private long failed;

  private PocExporter(PocTransport transport, PocSettings settings, boolean enabled) {
    this.transport = transport;
    this.settings = settings;
    this.enabled = enabled;
    this.batches = new BatchFactory(settings == null ? 0 : settings.instanceId());
    this.nextAttemptNanos = System.nanoTime();
  }

  /**
   * Starts {@code transport}; when that fails (no ALPN on Java 8 before 8u252, bad CA file, ...)
   * the agent keeps instrumenting and only the transport is disabled: one ERROR line (10 §10.3).
   */
  public static Exporter create(ExporterContext context, TransportFactory factory) {
    PocSettings settings;
    PocTransport transport = null;
    try {
      settings = PocSettings.from(context.config());
      transport = factory.create(settings);
      long started = System.nanoTime();
      transport.start();
      AgentLogger.info(
          "transport-poc started: {} to {} in {} ms",
          transport.name(),
          settings,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
      return new PocExporter(transport, settings, true);
    } catch (Throwable t) {
      AgentLogger.error(
          "transport-poc disabled: {} could not start ({}); transactions are recorded and dropped",
          transport == null ? "transport" : transport.name(),
          t.toString());
      if (transport != null) {
        transport.close();
      }
      return new PocExporter(transport, null, false);
    }
  }

  /** Creates the transport of one comparison jar. */
  public interface TransportFactory {
    PocTransport create(PocSettings settings) throws Exception;
  }

  @Override
  public void export(List<Transaction> batch) {
    if (enabled) {
      batches.add(batch);
    }
  }

  @Override
  public void flush() {
    if (!enabled) {
      return;
    }
    long now = System.nanoTime();
    if (now - nextAttemptNanos < 0) {
      return;
    }
    TransactionBatch batch = null;
    try {
      // Building the message is part of the attempt: protobuf-javalite fails here on JDK 24+ with
      // --sun-misc-unsafe-memory-access=deny ("Lite gencode ... uses sun.misc.Unsafe").
      batch = batches.next();
      Ack ack = transport.send(batch);
      acked++;
      backoffMillis = PocSettings.INTERVAL_MILLIS;
      if (acked == 1) {
        AgentLogger.info(
            "transport-poc first-ack: seq={} last_seq={} at_ms={} bytes={} connection={}",
            batch.getHeader().getSeq(),
            ack.getLastSeq(),
            System.currentTimeMillis(),
            batch.getSerializedSize(),
            transport.connectionInfo());
      }
      nextAttemptNanos = now + TimeUnit.MILLISECONDS.toNanos(PocSettings.INTERVAL_MILLIS);
    } catch (Throwable t) {
      failed++;
      if (failed <= 3 || failed % 60 == 0) {
        AgentLogger.warn(
            "transport-poc failed: {} batch seq={} ({} failures): {}",
            transport.name(),
            batch == null ? batches.seq() : batch.getHeader().getSeq(),
            failed,
            t.toString());
      }
      nextAttemptNanos = now + TimeUnit.MILLISECONDS.toNanos(backoffMillis);
      backoffMillis = Math.min(MAX_BACKOFF_MILLIS, backoffMillis * 2);
    }
  }

  @Override
  public void close() {
    if (transport != null) {
      AgentLogger.info("transport-poc closing: acked={} failed={}", acked, failed);
      transport.close();
    }
  }

  @Override
  public String describe() {
    if (!enabled) {
      return "transport-poc (disabled)";
    }
    return "transport-poc " + transport.name() + " " + settings;
  }
}
