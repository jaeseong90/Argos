/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest.api;

import com.google.protobuf.ByteString;
import io.argos.server.session.api.SessionContext;
import java.util.List;

/**
 * The ingest side of the alert windows (12 §5.2 {@code WindowProvider} and {@code CounterProvider},
 * DSN-12-07; WO-25), implemented by the alert engine's sample intake. {@link #noop()} is the
 * fallback the ingest service uses when no sink bean exists (tests that build the ingest path
 * without the engine).
 *
 * <p>Rules for the caller ({@code IngestService}), the same as for {@link RealtimePublisher}:
 *
 * <ul>
 *   <li>only <b>live</b> records are handed over: read within 5 s of their receipt, never during
 *       WAL recovery or replay, and only the XLog points of rows with {@code late = 0};
 *   <li>calls never block and never throw for a full queue: an implementation queues the batch for
 *       its own thread ({@code argos-alert-eval}) and drops (and counts) what does not fit.
 * </ul>
 *
 * <p>Metric and statistics batches are handed over as the protobuf bytes of the WAL record: the
 * engine parses them on its own thread, so the ingest dispatcher does not pay for a second parse.
 */
public interface AlertSampleSink {

  /** XLog points of one live transaction batch of {@code session} (all with {@code late = 0}). */
  void onXlog(SessionContext session, List<XlogPoint> points);

  /** The bytes of one live {@code MetricBatch} of {@code session}. */
  void onMetricBatch(SessionContext session, ByteString metricBatch);

  /** The bytes of one live {@code StatBatch} of {@code session} (outgoing call statistics). */
  void onStatBatch(SessionContext session, ByteString statBatch);

  /** The sink that drops everything: the fallback when no sink bean exists. */
  static AlertSampleSink noop() {
    return Noop.INSTANCE;
  }

  /** {@link #noop()}. */
  enum Noop implements AlertSampleSink {
    /** The only instance. */
    INSTANCE;

    @Override
    public void onXlog(SessionContext session, List<XlogPoint> points) {}

    @Override
    public void onMetricBatch(SessionContext session, ByteString metricBatch) {}

    @Override
    public void onStatBatch(SessionContext session, ByteString statBatch) {}
  }
}
