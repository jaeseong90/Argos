/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Configuration change control and the kill switch (12 §6.2, F-S29, DSN-12-09, 16 §5.5, 11 §8.1):
 * the server-side agent key schema, proposals with a reason and a diff, pre-validation against each
 * target's {@code remote_config_allow}, the push to live sessions, results per instance, pending
 * values in the next {@code ServerHello.config}, revert, drift and the effective configuration as
 * the server knows it.
 *
 * <p>Owner: WO-26 (lane B).
 */
package io.argos.server.admin.configchange;
