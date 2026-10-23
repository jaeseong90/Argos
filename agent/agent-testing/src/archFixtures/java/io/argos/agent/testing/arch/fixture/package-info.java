/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Agent-like classes for the ArchUnit and bytecode rules in src/archTest (19 §5.3, 10 §2, 10 §5.3).
 *
 * <ul>
 *   <li>{@code compliant}: a miniature agent (bootstrap, core, instrumentation) that satisfies
 *       every rule.
 *   <li>{@code violating}: the same layout where each class breaks exactly one rule; the rule tests
 *       assert that the rule fails on it (T-AG-001..004 negative tests).
 *   <li>{@code app}: an application type; {@code thirdparty}: stands in for a library that the
 *       agent shades (relocates) in production.
 * </ul>
 *
 * <p>Compiled with {@code --release 8} like agent code, test-only, never packaged.
 */
package io.argos.agent.testing.arch.fixture;
