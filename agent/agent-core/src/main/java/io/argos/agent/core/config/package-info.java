/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Configuration (10 §15, §12.2 steps 1-4): the key registry {@link
 * io.argos.agent.core.config.ConfigKeys} with {@link io.argos.agent.core.config.KeySpec}, the typed
 * snapshot {@link io.argos.agent.core.config.AgentConfig}, the loader (yml, {@code -Dargos.*},
 * {@code ARGOS_*}, three token sources), the effective values with sources, the configuration hash
 * without the token and the remote hard cap evaluation {@link
 * io.argos.agent.core.config.RemoteConfigGate}. Owner: WO-10; applying a push is WO-17.
 */
package io.argos.agent.core.config;
