/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Operations jobs (12 §9): nightly reconcile and rollup rebuild, storage health and emergency
 * retention, self metrics, certificate reload, diagnostic bundle. The schema migrations live in
 * {@link io.argos.server.ops.migration}.
 *
 * <p>Owner: WO-27 (reconcile, rebuild), WO-28 (health, retention, metrics, certificates, bundle).
 */
package io.argos.server.ops;
