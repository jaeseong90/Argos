/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Decode and normalisation pipeline after the WAL (12 §3.5): {@code Decoder}, {@code
 * DictionaryResolver}, {@code TimestampGuard}, {@code ErrorGroupFingerprinter}, {@code
 * ApdexStamper}, {@code IngestPipeline}; the service Apdex overrides and their changes ({@code
 * ApdexChangeService}) and the representative stack of the error groups ({@code
 * ErrorGroupStackFiller}).
 *
 * <p>Owner: WO-21 (service Apdex overrides, change markers and representative stacks: WO-27).
 */
package io.argos.server.ingest;
