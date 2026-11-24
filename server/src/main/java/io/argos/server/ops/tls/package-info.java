/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Certificate reload of the gRPC listener (12 §9.5 {@code CertReloader}, 16 §3.1, §3.3, SEC-09):
 * reloadable TLS context, file watch, manual reload, expiry gauges, {@code TLS_RELOADED} audit.
 *
 * <p>Owner: WO-28.
 */
package io.argos.server.ops.tls;
