/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Self-protection and self-diagnostics (10 §14, 18 §19): {@link
 * io.argos.agent.core.guard.AgentCounters} (bounded LongAdder counters with fixed labels), {@link
 * io.argos.agent.core.guard.SelfDiagnostics} (the snapshot of 18 §19), transform error handling and
 * the retransform limiter. Owner: WO-10; the retransform execution is WO-17.
 */
package io.argos.agent.core.guard;
