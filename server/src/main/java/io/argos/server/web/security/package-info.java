/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Request security of the HTTP port (WO-24; 16 §7, §9.8, §9.9): the Spring Security chains ({@link
 * io.argos.server.web.security.SecurityConfig}), the filter order ({@link
 * io.argos.server.web.security.FilterOrder}), the {@link io.argos.server.web.security.ApiFilter}
 * contract of the {@code /api} chain, and the rate-limit, security-header and CSP filters.
 *
 * <p>Owner: WO-24.
 */
package io.argos.server.web.security;
