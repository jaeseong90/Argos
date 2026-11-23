/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.template;

import io.argos.server.alert.rule.RuleSpec.TargetType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The deep link of an alert (14 §6 "알림 딥링크 생성 규약", URL grammar of ADR-0010 C-166 and C-189 (1)):
 * {@code <public-url>/apps/{appId}/dashboard?env=..&from=<firing − 15m>&to=<firing +
 * 15m>&f.<target>=..&alert=<eventId>} with absolute ISO times (no {@code live}: the UI stops the
 * live mode for an {@code alert} key). A server self alert has no dashboard: its link is the alert
 * history {@code <public-url>/alerts/events?from=..&to=..&alert=<eventId>}.
 */
public final class DeepLinks {

  /** The fixed range around the firing (14 §6, 05 §2.0). */
  public static final Duration AROUND = Duration.ofMinutes(15);

  private DeepLinks() {}

  /**
   * The link of an alert.
   *
   * @param publicUrl {@code argos.web.public-url} (trailing slashes ignored), or null for a path
   * @param appId the application, null for a server self alert
   * @param env the application's environment, or null
   * @param targetType the unit's target type
   * @param targetId the unit's instance, service or host id, or null
   * @param firingAt the firing time
   * @param eventId the event the UI highlights
   */
  public static String alert(
      String publicUrl,
      Integer appId,
      String env,
      TargetType targetType,
      Long targetId,
      Instant firingAt,
      UUID eventId) {
    String base = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    Instant at = firingAt.truncatedTo(ChronoUnit.SECONDS);
    StringBuilder b = new StringBuilder(base);
    if (appId == null) {
      b.append("/alerts/events?");
    } else {
      b.append("/apps/").append(appId).append("/dashboard?");
      if (env != null) {
        b.append("env=").append(enc(env)).append('&');
      }
    }
    b.append("from=").append(enc(at.minus(AROUND).toString()));
    b.append("&to=").append(enc(at.plus(AROUND).toString()));
    if (appId != null && targetId != null) {
      String key =
          switch (targetType) {
            case INSTANCE -> "f.instance";
            case SERVICE -> "f.service";
            case EXT_HOST -> "f.host";
            default -> null;
          };
      if (key != null) {
        b.append('&').append(key).append('=').append(Long.toUnsignedString(targetId));
      }
    }
    b.append("&alert=").append(eventId);
    return b.toString();
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
