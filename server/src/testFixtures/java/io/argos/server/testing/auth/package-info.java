/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Test support for authentication and REST tests (WO-24): {@link
 * io.argos.server.testing.auth.ArgosTestServer} (the real server on loopback ports), {@link
 * io.argos.server.testing.auth.ApiTestClient} (browser-like client: cookie jar with Path and
 * SameSite, CSRF header, Origin and Fetch metadata), {@link io.argos.server.testing.auth.TestUsers}
 * (users with run-time generated passwords) and {@link io.argos.server.testing.auth.AuditLog}
 * (audit rows).
 */
package io.argos.server.testing.auth;
