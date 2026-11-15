/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import java.util.concurrent.Executor;

/**
 * The agent token on every RPC (11 §2, §11, SEC-01, SEC-06): {@code authorization: Bearer
 * argos_<base64url>} in the call metadata, nowhere else. The value is the effective token of the
 * three ways of 16 §2.6 ({@code TokenHolder.reveal()}); this class never prints it ({@link
 * #toString()} shows the mask of C-94).
 */
final class TokenCredentials extends CallCredentials {
  private final Metadata metadata = new Metadata();

  TokenCredentials(String token) {
    metadata.put(GrpcTransport.AUTHORIZATION, "Bearer " + token);
  }

  @Override
  public void applyRequestMetadata(
      RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
    Metadata headers = new Metadata();
    headers.merge(metadata);
    applier.apply(headers);
  }

  @Override
  public String toString() {
    return "TokenCredentials[argos_****]";
  }
}
