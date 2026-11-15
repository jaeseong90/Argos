/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import io.argos.agent.core.config.TokenHolder;
import java.util.regex.Pattern;

/**
 * The token masking of log text that the agent does not write itself (11 §11, SEC-06, C-94 defence
 * in depth): library messages ({@link JulLogger}) and the text of a logged throwable, its message
 * and the messages of its causes ({@link LogPipeline#format}). A {@code Bearer} value of any format
 * and every {@code argos_} run of any length become {@value TokenHolder#MASK}.
 */
final class LogMask {
  private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+\\S+");

  private LogMask() {}

  /** {@code text} without a token: {@code Bearer} values and every {@code argos_} run. */
  static String mask(String text) {
    if (text == null) {
      return null;
    }
    return TokenHolder.maskTokenRuns(BEARER.matcher(text).replaceAll("Bearer " + TokenHolder.MASK));
  }
}
