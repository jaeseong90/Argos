/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.argos.server.collector.limits.Violation;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * The request marshaller of every collector method: parses the protobuf message from a {@link
 * BoundedInputStream} (at most {@code argos.collector.max-message-bytes} decompressed, 12 §3.3
 * class 1) and returns an {@link Inbound} instead of throwing, so that an oversized or malformed
 * message gets {@code RESOURCE_EXHAUSTED} / {@code INVALID_ARGUMENT} from the handler rather than
 * grpc-java's {@code UNKNOWN}. Three ways a message can be too large all end here: this stream's
 * bound, the bounded gzip decompressor's bound and grpc-java's own {@code SizeEnforcingInputStream}
 * ({@code RESOURCE_EXHAUSTED} thrown inside the parser).
 *
 * <p>Server side only: {@link #stream} serializes a decoded message for completeness.
 *
 * @param <T> the message type
 */
public final class InboundMarshaller<T extends Message>
    implements MethodDescriptor.Marshaller<Inbound<T>> {

  private final Parser<T> parser;
  private final long limit;

  /**
   * @param parser the message parser
   * @param limit decompressed bytes allowed
   */
  public InboundMarshaller(Parser<T> parser, long limit) {
    this.parser = Objects.requireNonNull(parser, "parser");
    this.limit = limit;
  }

  @Override
  public InputStream stream(Inbound<T> value) {
    if (value.rejected()) {
      throw new IllegalArgumentException("A rejected message has no bytes");
    }
    return value.message().toByteString().newInput();
  }

  @Override
  public Inbound<T> parse(InputStream stream) {
    BoundedInputStream bounded = new BoundedInputStream(stream, limit);
    try {
      CodedInputStream coded = CodedInputStream.newInstance(bounded);
      T message = parser.parseFrom(coded);
      return Inbound.of(message);
    } catch (InvalidProtocolBufferException e) {
      return Inbound.rejected(classify(e));
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.RESOURCE_EXHAUSTED) {
        return Inbound.rejected(tooLarge());
      }
      return Inbound.rejected(Violation.format("Malformed message"));
    } finally {
      try {
        bounded.close();
      } catch (IOException ignored) {
        // closing a transport buffer does not fail in practice; nothing to report
      }
    }
  }

  private Violation classify(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof MessageTooLargeException) {
        return tooLarge();
      }
      if (t instanceof StatusRuntimeException s
          && s.getStatus().getCode() == Status.Code.RESOURCE_EXHAUSTED) {
        return tooLarge();
      }
    }
    return Violation.format("Malformed message: not a valid protobuf message or gzip stream");
  }

  private Violation tooLarge() {
    return Violation.exhausted(
        Violation.MESSAGE_SIZE, "Message larger than " + limit + " bytes after decompression");
  }
}
