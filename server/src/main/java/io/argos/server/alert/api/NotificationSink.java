/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.api;

/**
 * The delivery side of the alert engine (12 §5.5; WO-25 plan §3.2), implemented by the {@code
 * Notifier} ({@code argos-notify}, 4 threads): delivery to the channels with retries, the {@code
 * notification_deliveries} rows and the channel health ({@link ChannelHealthEvent}).
 *
 * <p>The engine calls {@link #submit} from its evaluation thread ({@code argos-alert-eval}) and
 * from the instance state listener (a session or monitor thread): an implementation never blocks,
 * it queues and returns. Exactly one bean is required; the engine does not start without it.
 */
public interface NotificationSink {

  /** Queues {@code notification} for delivery; never blocks, never throws for a full queue. */
  void submit(AlertNotification notification);
}
