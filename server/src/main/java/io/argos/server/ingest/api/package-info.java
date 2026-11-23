/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Contracts of the decode and normalisation pipeline after the WAL (12 §2, §3.5-§3.7): {@link
 * io.argos.server.ingest.api.IngestPipeline} (collector → ingest boundary), the ports the collector
 * calls at receipt ({@link io.argos.server.ingest.api.ApdexProvider}) and on the session stream
 * ({@link io.argos.server.ingest.api.DictionaryIntake}), and the realtime port ({@link
 * io.argos.server.ingest.api.RealtimePublisher}, {@link io.argos.server.ingest.api.XlogPoint},
 * {@link io.argos.server.ingest.api.MetricPoint}).
 *
 * <p>Interfaces, records and enums only. Implemented by WO-21 lane {@code durable-ingest} ({@code
 * Decoder}, {@code DictionaryResolver}, {@code TimestampGuard}, {@code ErrorGroupFingerprinter},
 * {@code ApdexStamper}); called by lane {@code collector}. {@link
 * io.argos.server.ingest.api.RealtimePublisher} is implemented by WO-22 (a no-op until then).
 */
package io.argos.server.ingest.api;
