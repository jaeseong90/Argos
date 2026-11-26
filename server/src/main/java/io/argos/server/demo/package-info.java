/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Demo mode of the Compose demo profile (17 §3.2, §3.9, O-A01; WO-41): the plaintext gRPC defaults
 * ({@link io.argos.server.demo.DemoModeDefaults}) and the start warning ({@link
 * io.argos.server.demo.DemoModeNotice}). The UI learns about demo mode from {@code GET
 * /api/v1/setup/status} ({@code demo_mode}); the demo applications and their tokens are registered
 * by {@code io.argos.server.admin.bootstrap}.
 *
 * <p>Owner: WO-41 (lane B).
 */
package io.argos.server.demo;
