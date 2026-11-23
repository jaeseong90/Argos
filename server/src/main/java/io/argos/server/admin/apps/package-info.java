/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Application administration (WO-26; 14 §4.3, §4.14, ADR-0010 C-153, C-207 (3), C-220 (1), F-S30):
 * create, change and delete applications ({@link io.argos.server.admin.apps.AppAdminService}),
 * service Apdex T overrides, archive and delete instances, and the approval queue of automatic
 * registration ({@link io.argos.server.admin.apps.ApprovalService}). Input rules are {@link
 * io.argos.server.admin.apps.AppInputs}.
 *
 * <p>Owner: WO-26 (lane C).
 */
package io.argos.server.admin.apps;
