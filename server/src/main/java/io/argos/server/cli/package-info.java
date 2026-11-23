/* SPDX-License-Identifier: Apache-2.0 */

/**
 * {@code argos-admin} command line (12 §1, 17 §4.4): rollup rebuild, reconcile, master key
 * rotation, token issue, setup token, configuration check and effective configuration. Entry point
 * {@link io.argos.server.cli.AdminCli} ({@code java -jar argos-server.jar admin ...}); WO-27 adds
 * {@code rollup rebuild}, {@code consistency check} and {@code apdex reapply}.
 *
 * <p>Owner: WO-28 (rollup rebuild, consistency check and apdex reapply subcommands: WO-27).
 */
package io.argos.server.cli;
