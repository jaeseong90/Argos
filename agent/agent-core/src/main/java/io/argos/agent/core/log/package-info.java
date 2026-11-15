/* SPDX-License-Identifier: Apache-2.0 */
/**
 * The agent's own logger (10 §14, F-A35, SEC-26): asynchronous {@code argos-logger} thread, rolling
 * file in a 0700 directory, rate-limited stderr fallback, and the single start-failure line of
 * ADR-0009 C-78. No logging framework; INFO and below never carry collected data. Owner: WO-10.
 */
package io.argos.agent.core.log;
