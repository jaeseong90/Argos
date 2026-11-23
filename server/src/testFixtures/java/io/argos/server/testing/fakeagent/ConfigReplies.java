/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.RejectedKey;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Canned {@code ConfigApplyResult} replies of a {@link FakeAgent} ({@link
 * FakeAgent.Builder#onConfigPush}, WO-26), shaped as 11 §8.1 and 16 §5.5 say an agent answers: the
 * keys it applied, the keys it rejected with a code, and its new effective configuration hash. The
 * fake agent applies nothing; the hash is derived from the pushed values so that two agents that
 * applied the same values report the same hash.
 *
 * <pre>{@code
 * FakeAgent agent = FakeAgent.builder(channel)
 *     .token(token)
 *     .hello(h -> h.clearRemoteConfigAllow().addRemoteConfigAllow("log.level"))
 *     .onConfigPush(ConfigReplies.applyAll())
 *     .build();
 * }</pre>
 */
public final class ConfigReplies {

  private ConfigReplies() {}

  /** Applies every pushed key. */
  public static Function<ConfigPush, ConfigApplyResult> applyAll() {
    return push -> answer(push, Map.of());
  }

  /**
   * Rejects {@code rejected} keys with their codes (the agent's own re-check), applies the rest.
   */
  public static Function<ConfigPush, ConfigApplyResult> rejecting(
      Map<String, RejectCode> rejected) {
    return push -> answer(push, rejected);
  }

  /** The answer to {@code push}: rejected keys with their codes, the others applied. */
  public static ConfigApplyResult answer(ConfigPush push, Map<String, RejectCode> rejected) {
    ConfigApplyResult.Builder b =
        ConfigApplyResult.newBuilder().setChangeId(push.getChangeId()).setDurationMs(3);
    Map<String, String> applied = new TreeMap<>();
    for (Map.Entry<String, String> e : new TreeMap<>(push.getValuesMap()).entrySet()) {
      RejectCode code = rejected.get(e.getKey());
      if (code == null) {
        b.addAppliedKeys(e.getKey());
        applied.put(e.getKey(), e.getValue());
      } else {
        b.addRejected(
            RejectedKey.newBuilder()
                .setKey(e.getKey())
                .setCode(code)
                .setMessage("rejected by the fake agent"));
      }
    }
    return b.setEffectiveConfigHash(
            String.format(java.util.Locale.ROOT, "%016x", (long) applied.hashCode()))
        .build();
  }
}
