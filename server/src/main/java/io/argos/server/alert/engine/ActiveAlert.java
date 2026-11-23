/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import java.time.Instant;
import java.util.UUID;

/**
 * One active (FIRING or ACKED) alert of the engine: the items of {@code Dashboard.firing[]} and
 * {@code GET /overview/alerts} (WO-25 plan §3.1, 14 §4.13 event schema subset; ADR-0010 C-212 (2)).
 *
 * @param eventId the FIRING event of the current firing (the ACK target, the deep link's {@code
 *     alert})
 * @param ts the FIRING transition
 * @param appId the application, null for a server self alert
 * @param appName its name (untrusted text), null for a server self alert
 * @param env the environment, or null
 * @param targetType {@code app}, {@code instance}, {@code service}, {@code ext_host} or {@code
 *     server}
 * @param targetId the instance, service or host id (decimal), or null
 * @param targetName the target's name (untrusted text), or null
 * @param ruleId the rule
 * @param ruleName the rule's name (operator text)
 * @param ruleMetric the rule's metric (F-S31 wire name)
 * @param severity {@code warn} or {@code critical}
 * @param state {@code FIRING} or {@code ACKED}
 * @param value the last value, or null
 * @param threshold the threshold of the severity, or null
 * @param durationS seconds since the firing
 * @param ackedBy the acknowledging user, or null
 * @param ackedByName that user's name, or null
 * @param ackedAt the acknowledgement time, or null
 * @param note the acknowledgement note (untrusted text), or null
 * @param deeplink the 14 §6 deep link
 */
public record ActiveAlert(
    UUID eventId,
    Instant ts,
    Integer appId,
    String appName,
    String env,
    String targetType,
    String targetId,
    String targetName,
    UUID ruleId,
    String ruleName,
    String ruleMetric,
    String severity,
    String state,
    Double value,
    Double threshold,
    int durationS,
    UUID ackedBy,
    String ackedByName,
    Instant ackedAt,
    String note,
    String deeplink) {}
