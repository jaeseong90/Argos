/* SPDX-License-Identifier: Apache-2.0 */

/**
 * HTTP port plumbing shared by every REST and WebSocket endpoint (14 §2.1, §2.6, §7.2): request ids
 * ({@code X-Request-Id}, MDC {@code request_id}), the {@code X-Argos-Server-Version} header, the
 * request URL and body limits and the error body written by filters. Connector limits, error-page
 * rules, virtual threads and graceful shutdown are in {@code application.yml}.
 *
 * <p>Owner: WO-20. Authentication, CSRF, security headers and the REST error mapping are WO-24.
 */
package io.argos.server.web;
