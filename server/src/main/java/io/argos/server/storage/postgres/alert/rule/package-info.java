/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access of the alert engine (WO-25): {@code alert_rules}, {@code alert_rule_history},
 * {@code alert_states}, {@code alert_events}, {@code mutes} and the default rule bookkeeping
 * ({@code apps.recommended_rules_applied}, {@code settings}).
 *
 * <p>Owner: WO-25.
 */
package io.argos.server.storage.postgres.alert.rule;
