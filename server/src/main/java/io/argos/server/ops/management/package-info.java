/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The management port (02 C-7, SEC-55, 16 §7.12, 17 §11.1): 8081 on 127.0.0.1, Actuator {@code
 * health} (open, no details; liveness and readiness groups), {@code info} and {@code prometheus}
 * (bearer token and {@code argos.metrics.allow-cidrs}), the {@code migrations} readiness
 * contributor, the {@code argos_build_info} gauge and the meter naming rule of the server.
 *
 * <p>Owner: WO-20. Server self metrics are added by the work orders that own them (12 §9.4).
 */
package io.argos.server.ops.management;
