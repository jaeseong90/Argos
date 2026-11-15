/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Test fixtures of the server (java-test-fixtures, not shipped): {@link
 * io.argos.server.testing.ServerTestEnvironment} starts PostgreSQL 16 and ClickHouse in
 * Testcontainers like an installation (pinned images, C-11 accounts, generated passwords in files)
 * and returns the Spring properties of a server instance. Used by the integration and security test
 * suites of WO-20 and reused by WO-21, WO-23 and WO-24.
 *
 * <p>Owner: WO-20.
 */
package io.argos.server.testing;
