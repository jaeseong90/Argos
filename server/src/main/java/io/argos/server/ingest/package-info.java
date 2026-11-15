/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Decode and normalisation pipeline after the WAL (12 §3.5): {@code Decoder}, {@code
 * DictionaryResolver}, {@code TimestampGuard}, {@code ErrorGroupFingerprinter}, {@code
 * ApdexStamper}, {@code IngestPipeline}.
 *
 * <p>Owner: WO-21 (fingerprint and Apdex final rules: WO-27).
 */
package io.argos.server.ingest;
