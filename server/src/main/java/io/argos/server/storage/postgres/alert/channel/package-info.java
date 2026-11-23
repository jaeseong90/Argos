/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access of the notification channels (WO-25; 12 §5.5, 13 §12, 16 §8.3, §8.4): {@code
 * notification_channels} with its health columns, {@code webhook_signing_keys} and {@code
 * notification_deliveries}. Secrets are stored as envelope strings only.
 *
 * <p>Owner: WO-25 (lane B).
 */
package io.argos.server.storage.postgres.alert.channel;
