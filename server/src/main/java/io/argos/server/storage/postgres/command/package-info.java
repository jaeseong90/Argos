/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access of the remote commands (WO-26; V001 and V011 {@code commands}, {@code
 * command_results}; 12 §6.1, 16 §5.2, §5.3): the command rows and their state transitions, the
 * encrypted results (the envelope only; encryption is {@code
 * io.argos.server.secrets.EnvelopeCipher} in the caller), the expiry sweep and the retention.
 *
 * <p>Owner: WO-26 (lane B).
 */
package io.argos.server.storage.postgres.command;
