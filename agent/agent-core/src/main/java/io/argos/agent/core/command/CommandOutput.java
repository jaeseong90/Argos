/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import java.util.Objects;

/**
 * The result of a {@link CommandHandler}: UTF-8 payload bytes, the {@code CommandResult} content
 * type ({@code application/json} or {@code text/plain}, 11 §4.2) and whether the handler cut the
 * payload at its cap. The payload is already within the handler's cap; the dispatcher only splits
 * it into chunks.
 */
public final class CommandOutput {
  /** {@code CommandResult.content_type} of a JSON payload. */
  public static final String APPLICATION_JSON = "application/json";

  /** {@code CommandResult.content_type} of a text payload. */
  public static final String TEXT_PLAIN = "text/plain";

  private final byte[] payload;
  private final String contentType;
  private final boolean truncated;

  private CommandOutput(byte[] payload, String contentType, boolean truncated) {
    this.payload = Objects.requireNonNull(payload, "payload");
    this.contentType = contentType;
    this.truncated = truncated;
  }

  /** A JSON payload ({@value #APPLICATION_JSON}). */
  public static CommandOutput json(byte[] utf8, boolean truncated) {
    return new CommandOutput(utf8, APPLICATION_JSON, truncated);
  }

  /** A text payload ({@value #TEXT_PLAIN}). */
  public static CommandOutput text(byte[] utf8, boolean truncated) {
    return new CommandOutput(utf8, TEXT_PLAIN, truncated);
  }

  /** The payload bytes (not copied: callers must not modify them). */
  public byte[] payload() {
    return payload;
  }

  public String contentType() {
    return contentType;
  }

  /** True when the handler cut the payload at its cap ({@code CommandResult.truncated}). */
  public boolean truncated() {
    return truncated;
  }
}
