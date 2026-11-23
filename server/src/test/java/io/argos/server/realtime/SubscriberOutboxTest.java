/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The send queue of a connection (14 §5.3 backpressure) and the server frame texts (14 §5.2). */
@Tag("req:F-S03")
class SubscriberOutboxTest {

  private static final class Owner implements SubscriberOutbox.XlogOwner {
    long dropped;
    final List<Set<Long>> unsent = new ArrayList<>();

    @Override
    public void dropped(int points, Set<Long> unsentServices) {
      dropped += points;
      unsent.add(unsentServices);
    }

    @Override
    public long takeDropped() {
      long d = dropped;
      dropped = 0;
      return d;
    }
  }

  @Test
  @DisplayName(
      "T-SV-021e control frames and latest-only pushes are never dropped; xlog pushes go first,"
          + " oldest first")
  void dropOrder() {
    AtomicInteger wakeUps = new AtomicInteger();
    List<Long> hook = new ArrayList<>();
    SubscriberOutbox o = new SubscriberOutbox(300, wakeUps::incrementAndGet, hook::add);
    Owner owner = new Owner();
    String data = "{\"tx_id\":[\"" + "1".repeat(20) + "\"]}";
    o.offerControl(ServerFrames.ack("s1", Topic.XLOG, Instant.EPOCH));
    o.offerXlog(owner, "s1", 0, data, "{}", 1, Set.of(17L));
    o.offerXlog(owner, "s1", 100, data, "{}", 2, Set.of());
    o.offerLatest("c", "{\"op\":\"push\",\"id\":\"c\",\"n\":1}");
    o.offerXlog(owner, "s1", 200, data, "{}", 3, Set.of());
    o.offerLatest("c", "{\"op\":\"push\",\"id\":\"c\",\"n\":2}");
    o.offerControl(ServerFrames.pong(5, Instant.EPOCH));
    assertThat(wakeUps.get()).isEqualTo(7);
    assertThat(o.queuedBytes()).isLessThanOrEqualTo(300);
    // The two oldest xlog pushes (1 + 2 points) went; the first carried the name of 17.
    assertThat(hook).containsExactly(1L, 2L);
    assertThat(owner.unsent).containsExactly(Set.of(17L), Set.of());
    List<String> frames = new ArrayList<>();
    String f;
    while ((f = o.poll()) != null) {
      frames.add(f);
    }
    assertThat(frames)
        .containsExactly(
            "{\"op\":\"notice\",\"code\":\"backpressure\",\"topic\":\"xlog\",\"dropped\":3}",
            "{\"op\":\"ack\",\"id\":\"s1\",\"topic\":\"xlog\",\"since\":\"1970-01-01T00:00:00.000Z\"}",
            "{\"op\":\"push\",\"id\":\"c\",\"n\":2}",
            "{\"op\":\"push\",\"id\":\"s1\",\"topic\":\"xlog\",\"t\":\"1970-01-01T00:00:00.200Z\","
                + "\"data\":"
                + data
                + ",\"dict\":{},\"dropped\":3}",
            "{\"op\":\"pong\",\"t\":5,\"server_t\":\"1970-01-01T00:00:00.000Z\"}");
    assertThat(o.droppedPoints()).isEqualTo(3);
    assertThat(o.queuedBytes()).isZero();
  }

  @Test
  @DisplayName("forget removes a waiting latest-only push; close drops everything")
  void forgetAndClose() {
    SubscriberOutbox o = new SubscriberOutbox(() -> {}, n -> {});
    o.offerLatest("c", "{\"a\":1}");
    o.forget("c");
    assertThat(o.poll()).isNull();
    Owner owner = new Owner();
    o.offerControl("{}");
    o.close();
    o.offerXlog(owner, "s", 0, "{}", null, 4, Set.of());
    assertThat(owner.dropped).isEqualTo(4);
    assertThat(o.poll()).isNull();
    assertThat(o.size()).isZero();
  }

  @Test
  @DisplayName("frame texts: error without id or message, JSON escaping of ids, UTF-8 lengths")
  void frameTexts() {
    assertThat(ServerFrames.error(null, ServerFrames.INVALID_PARAMS, null))
        .isEqualTo("{\"op\":\"error\",\"code\":\"invalid_params\"}");
    assertThat(ServerFrames.error("a\"b", ServerFrames.LIMIT_EXCEEDED, "too many"))
        .isEqualTo(
            "{\"op\":\"error\",\"id\":\"a\\\"b\",\"code\":\"limit_exceeded\","
                + "\"message\":\"too many\"}");
    assertThat(ServerFrames.reauth()).isEqualTo("{\"op\":\"reauth\"}");
    assertThat(ServerFrames.push("s", Topic.COUNTER, 1_500, "{}", null, null))
        .isEqualTo(
            "{\"op\":\"push\",\"id\":\"s\",\"topic\":\"counter\","
                + "\"t\":\"1970-01-01T00:00:01.500Z\",\"data\":{}}");
    assertThat(SubscriberOutbox.utf8Length("aé가😀")).isEqualTo(1 + 2 + 3 + 4);
  }
}
