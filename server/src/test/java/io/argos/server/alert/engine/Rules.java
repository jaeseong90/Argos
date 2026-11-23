/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import io.argos.server.alert.rule.RuleCodec;
import io.argos.server.alert.rule.RuleSpec;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Rules for the engine unit tests, from the rule JSON of 14 §4.13. */
final class Rules {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Rules() {}

  /** An application p95 rule: warn 3000, critical 10000, for 5m, window 1m, plus {@code extra}. */
  static RuleSpec p95(String extra) {
    return parse(
        "{\"name\":\"p95\",\"target\":{\"app_id\":7,\"type\":\"app\"},\"metric\":\"response_time_p95\","
            + "\"window\":\"1m\",\"for\":\"5m\",\"condition\":{\"operator\":\">=\",\"warn\":3000,"
            + "\"critical\":10000},\"min_samples\":20"
            + (extra.isEmpty() ? "" : "," + extra)
            + "}");
  }

  /** A rule from its JSON. */
  static RuleSpec parse(String json) {
    return RuleCodec.parse(JSON.readTree(json));
  }

  /** The canonical JSON of a rule. */
  static ObjectNode json(RuleSpec r) {
    return RuleCodec.toJson(r);
  }
}
