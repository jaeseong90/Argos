/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

/**
 * Invalid subscription parameters: the WebSocket handler answers {@code error invalid_params} (14
 * §5.2) with {@link #getMessage()} ({@code "<param>: <reason>"}, never the sent value).
 */
public final class InvalidTopicParams extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String param;
  private final String reason;

  /**
   * @param param the parameter name
   * @param reason a fixed reason ({@code required}, {@code invalid_value}, ...)
   */
  public InvalidTopicParams(String param, String reason) {
    super(param + ": " + reason, null, false, false);
    this.param = param;
    this.reason = reason;
  }

  /** The parameter. */
  public String param() {
    return param;
  }

  /** The reason. */
  public String reason() {
    return reason;
  }
}
