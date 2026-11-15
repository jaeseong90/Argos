/* SPDX-License-Identifier: Apache-2.0 */

/**
 * gRPC collector (12 §3.1-§3.3): server, interceptor chain (authentication, session, limits, quota,
 * rate), {@code AgentServiceImpl}, session handler. The gRPC listener (9100) starts as a {@code
 * LISTENERS} step of the start sequence.
 *
 * <p>Owner: WO-21 (agent tokens: WO-26).
 */
package io.argos.server.collector;
