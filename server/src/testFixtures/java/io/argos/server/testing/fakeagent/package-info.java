/* SPDX-License-Identifier: Apache-2.0 */

/**
 * A protocol-level fake agent for the collector tests (18 §8, 11 §16): it speaks argos.v1 over a
 * real gRPC channel, not through the agent code, and records every answer.
 *
 * <ul>
 *   <li>{@link io.argos.server.testing.fakeagent.FakeAgent}: the session stream (Hello, heartbeats
 *       with {@code prev_t1}/{@code prev_t4}, dictionary entries, instance updates, bye) and the
 *       five batch RPCs with explicit epoch and seq, gzip on or off, every ack, status, trailer and
 *       server message recorded;
 *   <li>{@link io.argos.server.testing.fakeagent.AgentClock}: the agent clock with a configurable
 *       skew, applied to every agent-clock field;
 *   <li>{@link io.argos.server.testing.fakeagent.Records} and {@link
 *       io.argos.server.testing.fakeagent.Batches}: record builders that fill every V001-relevant
 *       field and batches with per-kind seq counters (resend, gaps);
 *   <li>{@link io.argos.server.testing.fakeagent.Attacks}: the five attacks of 18 §8 (gzip bomb,
 *       5,001 records, 100,000 random services, 1,000 new instances, unauthenticated Hello flood);
 *   <li>{@link io.argos.server.testing.fakeagent.AgentChannels} and {@link
 *       io.argos.server.testing.fakeagent.TestCertificates}: TLS channels that trust a CA generated
 *       at run time (keytool), plaintext and in-process channels;
 *   <li>{@link io.argos.server.testing.fakeagent.AgentTokens}: agent tokens generated at run time,
 *       stored as SHA-256 hashes in {@code agent_tokens} (16 §2.1).
 * </ul>
 *
 * <p>No JUnit dependency: WO-29's load generator reuses these classes. Nothing here logs or prints
 * a token, a key or a password; all of them are generated at run time (gitleaks 0).
 *
 * <p>Owner: WO-21 contracts step.
 */
package io.argos.server.testing.fakeagent;
