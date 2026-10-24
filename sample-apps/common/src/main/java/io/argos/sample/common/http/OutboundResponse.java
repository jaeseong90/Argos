/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.http;

/** Status and body of an outgoing call. */
public final class OutboundResponse {

  /** Bodies longer than this are cut (ext-stub answers are small). */
  public static final int MAX_BODY_CHARS = 4096;

  private final int status;
  private final String body;

  public OutboundResponse(int status, String body) {
    this.status = status;
    String text = body == null ? "" : body;
    this.body = text.length() > MAX_BODY_CHARS ? text.substring(0, MAX_BODY_CHARS) : text;
  }

  public int status() {
    return status;
  }

  public String body() {
    return body;
  }
}
