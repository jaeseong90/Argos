/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The server's own meters written to ClickHouse {@code metric} as the virtual application {@code
 * app_id = 0} on the 5 s grid (12 §9.4, DSN-12-14), read back by the collection health and the
 * dashboard.
 *
 * <p>Owner: WO-28 (lane C).
 */
package io.argos.server.ops.selfmetrics;
