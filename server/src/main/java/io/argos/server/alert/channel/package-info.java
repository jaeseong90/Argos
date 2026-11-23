/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Notification channels and delivery (12 §5.5, 16 §8.1 to §8.5, SEC-51 to SEC-54): destination
 * validation ({@link io.argos.server.alert.channel.DestinationValidator}), the pinned HTTP client,
 * the webhook, Slack and e-mail channels (an own minimal SMTP client with mandatory STARTTLS), the
 * webhook signature, channel administration ({@link io.argos.server.alert.channel.ChannelService})
 * and the {@link io.argos.server.alert.channel.Notifier} that implements the engine's {@link
 * io.argos.server.alert.api.NotificationSink}.
 *
 * <p>No outbound connection goes anywhere but to a destination an administrator configured; every
 * channel secret (URL, recipients, signing key) is stored encrypted and never logged.
 *
 * <p>Owner: WO-25 (lane B).
 */
package io.argos.server.alert.channel;
