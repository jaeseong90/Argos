/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Events and collection outages of the query API (WO-23; 14 §4.12 GET, D-18): the event list and
 * the outages derived from state transition events ({@link io.argos.server.api.events.Outages}),
 * which the dashboard, the collection health and the XLog share through {@link
 * io.argos.server.api.events.EventService}.
 *
 * <p>WO-26 adds {@link io.argos.server.api.events.EventsWriteController}: {@code POST /events} by
 * an operator session or an application agent token, {@code DELETE /events/{eventId}} (ADR-0010
 * C-153, 02 C-12).
 *
 * <p>Owner: WO-23, WO-26 (writes).
 */
package io.argos.server.api.events;
