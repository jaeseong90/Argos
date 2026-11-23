/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access of the configuration change control and the kill switch (WO-26; V001 and V011
 * {@code config_changes}, {@code config_change_results}, {@code app_config}; 12 §6.2, 14 §4.14):
 * change records with their preview and status, per-instance apply results, and the values the
 * server holds per application.
 *
 * <p>Owner: WO-26 (lane B).
 */
package io.argos.server.storage.postgres.configchange;
