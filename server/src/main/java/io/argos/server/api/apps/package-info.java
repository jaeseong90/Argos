/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Applications and instances of the query API (WO-23; 14 §4.3, GET only): the resources, the
 * application scope of every application- or instance-scoped request ({@link
 * io.argos.server.api.apps.AppScope}, 404 and {@code AppAccessPolicy}) and the instance states of
 * the instance state machine with the live values of the session registry ({@link
 * io.argos.server.api.apps.InstanceStates}).
 *
 * <p>WO-26 adds the administration: {@link io.argos.server.api.apps.AppsAdminController} (create,
 * change and delete applications, service Apdex T overrides, archive and delete instances; ADR-0010
 * C-153, C-207 (3), C-220 (1)).
 *
 * <p>Owner: WO-23, WO-26 (administration).
 */
package io.argos.server.api.apps;
