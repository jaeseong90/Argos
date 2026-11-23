/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.alert.api.AlertNotification;
import io.argos.server.alert.api.AlertNotification.Group;
import io.argos.server.alert.api.AlertNotification.Item;
import io.argos.server.alert.api.AlertNotification.Kind;
import io.argos.server.alert.api.AlertNotification.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Grouping per (application, environment, severity) with the leading-edge rule (12 §5.4, WO-25 plan
 * §3.3): the first item of a group is sent at once (N-23), later items within the 30 s window go
 * out together at its end, at most 20 plus "and N more".
 */
@Tag("req:F-S17")
@Tag("req:N-23")
class GrouperTest {

  private static final long T0 = 1_800_000_000_000L;
  private static final UUID CH = UUID.randomUUID();
  private static final Group SHOP_WARN = new Group(7, "shop", "prod", Severity.WARN);

  private static Item item(int n) {
    return new Item(
        UUID.randomUUID(),
        UUID.randomUUID(),
        Kind.FIRING,
        Severity.WARN,
        Instant.ofEpochMilli(T0),
        Map.of("rule", "rule-" + n, "app", "shop"),
        "message " + n);
  }

  @Test
  @DisplayName(
      "T-SV-030a leading edge: the first item of a group is a notification at once; items within"
          + " the 30 s window wait and are merged at its end")
  void leadingEdge() {
    Grouper g = new Grouper(Duration.ofSeconds(30));
    AlertNotification first = g.offer(SHOP_WARN, item(1), List.of(CH), T0);
    assertThat(first).isNotNull();
    assertThat(first.items()).hasSize(1);
    assertThat(first.title()).isEqualTo("[WARN] FIRING: rule-1 - shop prod");
    assertThat(first.recipients().channelIds()).containsExactly(CH);
    assertThat(g.offer(SHOP_WARN, item(2), List.of(CH), T0 + 5_000)).isNull();
    assertThat(g.offer(SHOP_WARN, item(3), List.of(CH), T0 + 29_000)).isNull();
    Group critical = new Group(7, "shop", "prod", Severity.CRITICAL);
    assertThat(g.offer(critical, item(4), List.of(CH), T0 + 6_000))
        .as("another severity is another group")
        .isNotNull();
    assertThat(g.flush(T0 + 29_999)).isEmpty();
    List<AlertNotification> merged = g.flush(T0 + 30_000);
    assertThat(merged).hasSize(1);
    assertThat(merged.get(0).items())
        .extracting(Item::message)
        .containsExactly("message 2", "message 3");
    assertThat(merged.get(0).title()).isEqualTo("[WARN] 2 alerts - shop prod");
    assertThat(merged.get(0).body()).isEqualTo("message 2\nmessage 3");
    assertThat(g.offer(SHOP_WARN, item(5), List.of(CH), T0 + 31_000))
        .as("window closed: at once again")
        .isNotNull();
  }

  @Test
  @DisplayName(
      "T-SV-030a a burst becomes one notification of at most 20 items plus 'and N more', with every"
          + " event id")
  void cap() {
    Grouper g = new Grouper(Duration.ofSeconds(30));
    g.offer(SHOP_WARN, item(0), List.of(CH), T0);
    for (int i = 1; i <= 25; i++) {
      g.offer(SHOP_WARN, item(i), List.of(CH), T0 + i * 100L);
    }
    AlertNotification n = g.flush(T0 + 30_000).get(0);
    assertThat(n.items()).hasSize(AlertNotification.MAX_ITEMS);
    assertThat(n.omitted()).isEqualTo(5);
    assertThat(n.eventIds()).hasSize(25);
    assertThat(n.body()).endsWith("\nand 5 more");
  }
}
