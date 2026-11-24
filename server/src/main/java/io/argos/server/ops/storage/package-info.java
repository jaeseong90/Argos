/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Storage health and disk protection (12 §9.2, 13 §7.5, §8, 17 §9.2, F-S22, F-S23): the storage
 * health job ({@link io.argos.server.ops.storage.StorageHealthJob}) turns the ClickHouse system
 * tables into the {@code argos_ch_*} and {@code argos_server_disk_usage_ratio} meters, and the
 * emergency retention ({@link io.argos.server.ops.storage.EmergencyRetention}) drops the oldest raw
 * day partitions and pauses the inserts when the ClickHouse disk fills up.
 *
 * <p>Owner: WO-28.
 */
package io.argos.server.ops.storage;
