/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * The "open logs" link of a transaction (F-U18, 14 §4.6 {@code links.logs}, 15 §16 "LogLink": the
 * server fills the template of {@code argos.ui.log-link-template}). The placeholders {@code
 * {trace_id}}, {@code {tx_id}}, {@code {from}}, {@code {to}}, {@code {app}} and {@code {instance}}
 * are replaced by their values encoded as URI components (16 §9.3 {@code safeHref}: UTF-8, a space
 * as {@code %20}); an unknown value is the empty string. {@code from} and {@code to} are the
 * transaction's run widened by {@link #MARGIN} on both sides (ISO-8601 UTC), so that the log lines
 * written just before and after it are in the search.
 *
 * <p>The link is built from operator configuration only: the server never calls it. A template that
 * does not start with {@code http://}, {@code https://} or a path ({@code /...}, not {@code //})
 * gives no link, as the UI's {@code safeHref} would refuse it.
 */
public final class LogLinks {

  /** Time added before the start and after the end of the transaction. */
  static final Duration MARGIN = Duration.ofMinutes(1);

  private LogLinks() {}

  /**
   * The link of a transaction, or null without a (usable) template.
   *
   * @param template the configured template, or null
   * @param traceId the trace id (32 hex digits), or null
   * @param txId the transaction id (decimal)
   * @param start the transaction's start
   * @param elapsedMs its elapsed time
   * @param app the application name, or null
   * @param instance the instance name, or null
   */
  public static String transaction(
      String template,
      String traceId,
      String txId,
      Instant start,
      long elapsedMs,
      String app,
      String instance) {
    if (template == null || !usable(template)) {
      return null;
    }
    return template
        .replace("{trace_id}", component(traceId))
        .replace("{tx_id}", component(txId))
        .replace("{from}", component(start.minus(MARGIN).toString()))
        .replace(
            "{to}", component(start.plusMillis(Math.max(0, elapsedMs)).plus(MARGIN).toString()))
        .replace("{app}", component(app))
        .replace("{instance}", component(instance));
  }

  /** {@code http://}, {@code https://} or a path of this server. */
  static boolean usable(String template) {
    String t = template.strip().toLowerCase(Locale.ROOT);
    return t.startsWith("https://")
        || t.startsWith("http://")
        || (t.startsWith("/") && !t.startsWith("//"));
  }

  /** A value as a URI component; empty for null. */
  static String component(String value) {
    if (value == null) {
      return "";
    }
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
