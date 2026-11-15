/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The {@code argos.*} configuration tree ({@link io.argos.server.config.ArgosProperties}, 12 §10,
 * ADR-0010 C-96): binding, validation (required keys, forbidden database passwords of SEC-74),
 * secrets from files, unknown-key warnings (O-R03), the short environment names of 17 §3.8 and the
 * effective configuration with sources and masked secrets.
 *
 * <p>Owner: WO-20.
 */
package io.argos.server.config;
