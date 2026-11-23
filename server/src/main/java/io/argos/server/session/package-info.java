/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Agent sessions (12 §4). WO-21 (ADR-0010 C-128) builds the minimal session the data path needs:
 * the 11 §5 checks 1-7 with automatic registration only ({@link
 * io.argos.server.session.HelloService}), the {@code Session} stream ({@link
 * io.argos.server.session.SessionHandler}), the in-memory {@link
 * io.argos.server.session.SessionRegistry}, the server-side clock offset ({@link
 * io.argos.server.session.ClockSync}, input of the C-84 correction), the {@code instances} rows
 * (identity and metadata, {@link io.argos.server.session.SessionStore}) and idempotent {@code
 * InstanceUpdate} (C-146).
 *
 * <p>WO-22 adds the instance state machine (UP/STALE/DOWN/WARN), offset persistence, the {@code
 * AgentBye} grace, the approval queue ({@link
 * io.argos.server.session.SessionStore#recordPendingRegistration}), the 90 s idle timeout (C-19),
 * the merge of a temporary {@code host:pid} instance into the existing instance of its new name
 * (C-158 (2)), the daily instance retention ({@link io.argos.server.session.InstanceRetentionJob}),
 * events and the realtime bus behind the same registry.
 *
 * <p>Owner: WO-21 (lane collector), then WO-22.
 */
package io.argos.server.session;
