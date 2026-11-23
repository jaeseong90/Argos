/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Remote commands and remote configuration (10 §12.1, §12.2, 11 §8, 16 §5, SEC-20-SEC-25; WO-17).
 *
 * <ul>
 *   <li>Handlers ({@link io.argos.agent.core.command.CommandHandler}, built by {@link
 *       io.argos.agent.core.command.Handlers} from a {@link
 *       io.argos.agent.core.command.CommandEnv}): {@code THREAD_DUMP} (JSON on every JDK, virtual
 *       threads through {@code HotSpotDiagnosticMXBean.dumpThreads} on JDK 21+ read by the bounded
 *       {@code JdkThreadDumpReader}, {@code tx_id} per thread, cut at 1 MiB), {@code THREAD_STACK}
 *       ({@code tx_id} or {@code thread_id}), {@code SELF_DIAGNOSTICS} (the self-diagnostics
 *       snapshot members plus the 10 §12.1 items, 256 KiB) and {@code ENV_DUMP} (masked by {@link
 *       io.argos.agent.core.command.EnvMasker} with the fixed 16 §5.4 rules, which no setting turns
 *       off, 256 KiB). Handlers run on {@code argos-command} only and never log collected data
 *       (thread names, frames, environment values).
 *   <li>The dispatcher ({@code CommandDispatcher}: closed command list, TTL from receipt,
 *       cooldowns, one at a time, chunking), {@code CONFIG_RELOAD} ({@code ReloadCommand}) and the
 *       remote configuration path ({@code ConfigPushApplier}, {@code RuntimeApplier}).
 *   <li>Custom methods (SEC-25): {@link io.argos.agent.core.command.CustomMethodPolicy} (forbidden
 *       prefixes ignored with a WARN, at most 500 classes per item, generated classes excluded;
 *       public so WO-18 reuses it) and {@code Retransformer} (at most 50 classes per change and one
 *       change per minute through {@code RetransformLimiter}, reported as {@code
 *       retransformed_classes}).
 * </ul>
 */
package io.argos.agent.core.command;
