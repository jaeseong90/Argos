/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Contracts between the alert engine and the delivery side (WO-25 plan §3.2): {@link
 * io.argos.server.alert.api.AlertNotification} (what the engine hands over), {@link
 * io.argos.server.alert.api.NotificationSink} (implemented by the notifier) and {@link
 * io.argos.server.alert.api.ChannelHealthEvent} (published by the notifier, read by the engine's
 * channel failure rule).
 *
 * <p>Records and interfaces only. Owner: WO-25 (lane A creates, lane B implements).
 */
package io.argos.server.alert.api;
