/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Test doubles of the notification channels (WO-25; 18 §19 "알림 채널 Mock"): {@link
 * io.argos.server.testing.alert.FakeWebhookReceiver} (HTTP and HTTPS on 127.0.0.1, redirect and
 * failure modes), {@link io.argos.server.testing.alert.FakeSmtpServer} (STARTTLS offered or not,
 * AUTH, inbox), {@link io.argos.server.testing.alert.TestTls} (server TLS from a run-time PKI) and
 * {@link io.argos.server.testing.alert.TestMasterKey} (a run-time master key file). Nothing here
 * opens a connection beyond the loopback interface.
 */
package io.argos.server.testing.alert;
