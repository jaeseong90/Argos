/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The REST endpoints of authentication (WO-24; 14 §4.1, §4.14): {@code /api/v1/auth/*}, {@code
 * /api/v1/setup/*} and {@code /api/v1/admin/users*}. Every handler carries {@code @RequiresRole}
 * (DSN-12-11) and state-changing ones {@code @ArgosAudit}; the work happens in the services.
 *
 * <p>Owner: WO-24.
 */
package io.argos.server.auth.api;
