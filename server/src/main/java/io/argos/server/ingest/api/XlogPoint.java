/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest.api;

import io.argos.proto.v1.TransactionKind;
import java.util.Objects;

/**
 * One XLog point for the realtime bus (12 §3.7 topic {@code xlog}), with the values as stored in
 * {@code xlog}: {@code tsMs} after the C-129 correction and the D-15 clamp.
 *
 * @param txId {@code tx_id} (never corrected, C-129 (6))
 * @param tsMs stored {@code ts}, epoch milliseconds
 * @param elapsedMs stored {@code elapsed_ms}
 * @param kind transaction kind (never UNSPECIFIED: such items do not reach the pipeline, C-143 (1))
 * @param serviceId {@code service_id}
 * @param error {@code error}
 * @param httpStatus stored {@code http_status} (BATCH: 1 failure, 0 success, C-143 (3))
 * @param errorGroupId {@code error_group_id} (C-131), 0 without an error
 */
public record XlogPoint(
    long txId,
    long tsMs,
    int elapsedMs,
    TransactionKind kind,
    long serviceId,
    boolean error,
    int httpStatus,
    long errorGroupId) {

  /** Validates the kind. */
  public XlogPoint {
    Objects.requireNonNull(kind, "kind");
  }
}
