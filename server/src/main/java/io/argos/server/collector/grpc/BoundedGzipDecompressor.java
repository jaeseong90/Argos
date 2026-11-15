/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import io.grpc.Codec;
import io.grpc.Decompressor;
import io.grpc.DecompressorRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * The collector's {@code gzip} decompressor (11 §2 message compression, 12 §3.1): a streaming
 * {@link GZIPInputStream} behind a {@link BoundedInputStream} that fails as soon as the inflated
 * bytes cross {@code argos.collector.max-message-bytes}. grpc-java also bounds the decompressed
 * stream by {@code maxInboundMessageSize} (measured in WO-21: it stops a 1 GiB bomb at 4 MiB), but
 * its failure surfaces as {@code UNKNOWN "Application error processing RPC"}; this bound makes the
 * collector's own marshaller see the overflow and answer {@code RESOURCE_EXHAUSTED} with the {@code
 * message_size} counter (12 §3.3, T-SEC-11).
 */
public final class BoundedGzipDecompressor implements Decompressor {

  private static final int BUFFER = 8 * 1024;

  private final long limit;

  /**
   * @param limit inflated bytes allowed per message
   */
  public BoundedGzipDecompressor(long limit) {
    this.limit = limit;
  }

  @Override
  public String getMessageEncoding() {
    return "gzip";
  }

  @Override
  public InputStream decompress(InputStream is) throws IOException {
    return new BoundedInputStream(new GZIPInputStream(is, BUFFER), limit);
  }

  /** A registry with identity and this bounded gzip (advertised), nothing else. */
  public static DecompressorRegistry registry(long limit) {
    return DecompressorRegistry.emptyInstance()
        .with(Codec.Identity.NONE, false)
        .with(new BoundedGzipDecompressor(limit), true);
  }
}
