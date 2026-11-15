/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import io.argos.proto.v1.AgentServiceGrpc;
import io.grpc.MethodDescriptor;

/** The five batch RPCs of {@code AgentService} (11 §3), each with its own seq counter (11 §6). */
public enum BatchKind {
  /** {@code SendTransactions}. */
  TRANSACTIONS("SendTransactions"),
  /** {@code SendProfiles}. */
  PROFILES("SendProfiles"),
  /** {@code SendMetrics}. */
  METRICS("SendMetrics"),
  /** {@code SendStats}. */
  STATS("SendStats"),
  /** {@code SendActiveSnapshot} (not written to the WAL, not deduplicated). */
  ACTIVE("SendActiveSnapshot");

  private final String fullMethodName;

  BatchKind(String method) {
    this.fullMethodName =
        MethodDescriptor.generateFullMethodName(AgentServiceGrpc.SERVICE_NAME, method);
  }

  /** {@code argos.v1.AgentService/<Method>}, as in the generated stubs. */
  public String fullMethodName() {
    return fullMethodName;
  }
}
