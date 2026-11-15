/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Exporter SPI (18 §5.4, ADR-0009 C-80, C-88): the test exporters selected with {@code
 * argos.test.exporter} in development builds ({@code file} JSONL with every transaction's steps,
 * {@code noop} encode-and-drop, {@code drop}), the {@code grpc} slot filled by the transport, and
 * the export worker ({@code argos-sender}) that drains the queues of {@code transport} into the
 * exporter until the gRPC sender of WO-11 replaces it. Owner: WO-10 (test exporters, worker), WO-11
 * (gRPC).
 */
package io.argos.agent.core.export;
