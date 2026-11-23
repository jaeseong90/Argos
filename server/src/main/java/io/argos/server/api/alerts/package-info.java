/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Alert resources of the REST API (WO-25; 14 §4.13): rules, templates, the preview, events and
 * their acknowledgement, mutes and statistics, and the active alert item that the dashboard and the
 * overview share ({@link io.argos.server.api.alerts.AlertViews}, ADR-0010 C-212 (2)). The logic is
 * in {@code io.argos.server.alert} (outside the {@code api} package, so the contract suite stubs
 * it).
 *
 * <p>Owner: WO-25.
 */
package io.argos.server.api.alerts;
