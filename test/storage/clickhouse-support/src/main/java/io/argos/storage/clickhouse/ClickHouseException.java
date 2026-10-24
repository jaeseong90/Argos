/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.clickhouse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A ClickHouse server error returned over HTTP, with its numeric code and symbolic name. */
public final class ClickHouseException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private static final Pattern CODE = Pattern.compile("Code: (\\d+)\\.");
  private static final Pattern NAME = Pattern.compile("\\(([A-Z][A-Z0-9_]+)\\)");
  private static final Pattern NAME_BEFORE_VERSION =
      Pattern.compile("\\(([A-Z][A-Z0-9_]+)\\)\\s*\\(version ");

  private final int code;
  private final String errorName;
  private final int httpStatus;

  ClickHouseException(int httpStatus, String headerCode, String body) {
    super(body.strip());
    this.httpStatus = httpStatus;
    this.code = parseCode(headerCode, body);
    this.errorName = parseName(body);
  }

  /** ClickHouse error code, e.g. 497 (ACCESS_DENIED); -1 when the response carried none. */
  public int code() {
    return code;
  }

  /** Symbolic error name, e.g. {@code ACCESS_DENIED}; empty when the message carried none. */
  public String errorName() {
    return errorName;
  }

  /** HTTP status of the response. */
  public int httpStatus() {
    return httpStatus;
  }

  private static int parseCode(String headerCode, String body) {
    if (headerCode != null && headerCode.matches("\\d+")) {
      return Integer.parseInt(headerCode);
    }
    Matcher m = CODE.matcher(body);
    return m.find() ? Integer.parseInt(m.group(1)) : -1;
  }

  private static String parseName(String body) {
    // The name stands right before the version: "... privileges. (ACCESS_DENIED) (version
    // 26.8...)".
    Matcher versioned = NAME_BEFORE_VERSION.matcher(body);
    if (versioned.find()) {
      return versioned.group(1);
    }
    Matcher m = NAME.matcher(body);
    String last = "";
    while (m.find()) {
      last = m.group(1);
    }
    return last;
  }
}
