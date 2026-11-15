/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Start sequence of the core (10 §4.3 P3-P8, §4.4, §4.5, §5.2): {@code AgentStarter} (timed phases,
 * ADR-0009 C-79), the ByteBuddy agent builder, the ignore list, JPMS read edges, helper injection,
 * the module registry read from the module index (ADR-0003 C-25) and the transformation recorder.
 * Owner: WO-10 (core), modules register through {@code instrumentation.api}.
 */
package io.argos.agent.core.boot;
