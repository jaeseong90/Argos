/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Recording of transactions (10 §7.1, §8, §9.1, §9.2, §9.5): {@code TracerImpl} with its per-thread
 * bindings ({@code ThreadSlots}: open-step stack per activation scope), {@code Transaction}, the
 * lock-free {@code StepBuffer} (structure of arrays, 64 -> 2x growth, summary mode) and the step
 * builder {@code ProfileSteps} (repeat folding, {@code truncated}/{@code total_steps}), {@link
 * io.argos.agent.core.trace.ActiveRegistry}, the end-of-transaction {@code Sampler}, CPU and
 * allocation deltas ({@code ThreadMeter}), {@code TxIdGenerator} and {@code TraceIds}, the xxh3
 * dictionary and {@link io.argos.agent.core.trace.TraceRuntime}. Owner: WO-10; distributed
 * propagation helpers WO-14.
 */
package io.argos.agent.core.trace;
