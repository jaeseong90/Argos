/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.overview;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SessionClose;
import io.argos.server.api.apps.InstanceStates;
import io.argos.server.config.ServerBuild;
import io.argos.server.session.AgentSession;
import io.argos.server.session.AppRef;
import io.argos.server.session.ClockSync;
import io.argos.server.session.InstanceStateTracker;
import io.argos.server.session.SessionRegistry;
import io.argos.server.session.SessionStream;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.UserIdMode;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The application status and up count of the overview and of {@code GET /apps} (ADR-0010 C-216
 * (3)): the worst state of the application's instances that are not archived ({@code DOWN} &gt;
 * {@code STALE} &gt; {@code WARN} &gt; {@code UP}, {@code DOWN} without instances) and the
 * instances in {@code UP} or {@code WARN} ({@link InstanceStates#summarize}, which {@link
 * OverviewService} and {@code AppsService} call). The end-to-end check on the real server is {@code
 * session.OverviewStateIT}.
 */
@Tag("req:F-U17")
@Tag("req:F-S28")
class OverviewServiceTest {

  private static IntFunction<String> states(Map<Integer, String> byId) {
    return id -> byId.getOrDefault(id, InstanceStates.DOWN);
  }

  private static InstanceStates.Summary summarize(Map<Integer, String> byId) {
    return InstanceStates.summarize(byId.keySet(), states(byId));
  }

  @Test
  @DisplayName("T-SV-022 every instance UP: status UP, up = total")
  void allUp() {
    assertThat(summarize(Map.of(1, "UP", 2, "UP"))).isEqualTo(new InstanceStates.Summary("UP", 2));
  }

  @Test
  @DisplayName("T-SV-022 the worst instance state wins: DOWN > STALE > WARN > UP")
  void worstStateWins() {
    assertThat(summarize(Map.of(1, "UP", 2, "WARN")).status()).isEqualTo("WARN");
    assertThat(summarize(Map.of(1, "UP", 2, "WARN", 3, "STALE")).status()).isEqualTo("STALE");
    assertThat(summarize(Map.of(1, "UP", 2, "WARN", 3, "STALE", 4, "DOWN")).status())
        .isEqualTo("DOWN");
    assertThat(summarize(Map.of(1, "STALE", 2, "DOWN")).status()).isEqualTo("DOWN");
    assertThat(summarize(Map.of(1, "WARN")).status()).isEqualTo("WARN");
    assertThat(summarize(Map.of(1, "STALE")).status()).isEqualTo("STALE");
    // Order does not matter.
    List<String> order = List.of("DOWN", "UP", "STALE", "WARN");
    for (int shift = 0; shift < order.size(); shift++) {
      List<String> rotated = new ArrayList<>(order.subList(shift, order.size()));
      rotated.addAll(order.subList(0, shift));
      assertThat(InstanceStates.worst(rotated)).as(rotated.toString()).isEqualTo("DOWN");
    }
    assertThat(InstanceStates.worst(List.of("WARN", "UP", "STALE"))).isEqualTo("STALE");
    assertThat(InstanceStates.worst(List.of("UP", "WARN", "UP"))).isEqualTo("WARN");
  }

  @Test
  @DisplayName("T-SV-022 an application without instances is DOWN with 0 up")
  void noInstancesIsDown() {
    assertThat(InstanceStates.summarize(List.of(), id -> "UP"))
        .isEqualTo(new InstanceStates.Summary("DOWN", 0));
    assertThat(InstanceStates.worst(List.of())).isEqualTo("DOWN");
  }

  @Test
  @DisplayName("T-SV-022 up counts the instances in UP or WARN, never STALE or DOWN")
  void upCountsUpAndWarn() {
    assertThat(summarize(Map.of(1, "UP", 2, "WARN", 3, "STALE", 4, "DOWN")).up()).isEqualTo(2);
    assertThat(summarize(Map.of(1, "WARN", 2, "WARN")))
        .isEqualTo(new InstanceStates.Summary("WARN", 2));
    assertThat(summarize(Map.of(1, "STALE", 2, "DOWN")).up()).isZero();
    assertThat(InstanceStates.countsAsUp("UP")).isTrue();
    assertThat(InstanceStates.countsAsUp("WARN")).isTrue();
    assertThat(InstanceStates.countsAsUp("STALE")).isFalse();
    assertThat(InstanceStates.countsAsUp("DOWN")).isFalse();
  }

  @Test
  @DisplayName("T-SV-022 an unknown state value ranks as DOWN and does not count as up")
  void unknownStateIsDown() {
    assertThat(summarize(Map.of(1, "UP", 2, "SOMETHING")))
        .isEqualTo(new InstanceStates.Summary("DOWN", 1));
    assertThat(InstanceStates.worst(Arrays.asList("UP", null))).isEqualTo("DOWN");
  }

  @Test
  @DisplayName(
      "T-SV-022 without the state machine (no collector in the context) a live session is UP and"
          + " anything else DOWN")
  void fallbackWithoutTracker() {
    long now = 1_800_000_000_000L;
    AgentSession live = session(7, 11, now);
    InstanceStates states =
        new InstanceStates(
            new OneSession(live),
            new ServerBuild("1.0.0", null, null),
            Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
            new StaticListableBeanFactory().getBeanProvider(InstanceStateTracker.class));
    assertThat(states.state(11)).isEqualTo("UP");
    assertThat(states.state(12)).isEqualTo("DOWN");
    assertThat(states.summarize(List.of(11))).isEqualTo(new InstanceStates.Summary("UP", 1));
    assertThat(states.summarize(List.of(11, 12))).isEqualTo(new InstanceStates.Summary("DOWN", 1));
    assertThat(states.summarize(List.of())).isEqualTo(new InstanceStates.Summary("DOWN", 0));
  }

  private static AgentSession session(int app, int instance, long nowMs) {
    return new AgentSession(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "argos_AbCdEfGh",
        new AppRef(app, "app-" + app, "prod"),
        instance,
        "host-" + instance + ":8080",
        1L,
        IpMode.MASKED,
        UserIdMode.OFF,
        "1.0.0",
        "",
        false,
        null,
        List.of(),
        nowMs,
        new ClockSync(nowMs, nowMs),
        new SessionStream(new NoopObserver()));
  }

  /** A registry holding one session. */
  private record OneSession(AgentSession session) implements SessionRegistry {
    @Override
    public Optional<AgentSession> find(int instanceId) {
      return session.instanceId() == instanceId ? Optional.of(session) : Optional.empty();
    }

    @Override
    public Optional<AgentSession> register(AgentSession s) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void unregister(AgentSession s) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int closeByToken(UUID tokenId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int closeAll(SessionClose close, Status status, Metadata trailers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<AgentSession> byApp(int appId) {
      return session.appId() == appId ? List.of(session) : List.of();
    }

    @Override
    public Collection<AgentSession> all() {
      return List.of(session);
    }

    @Override
    public int countByToken(UUID tokenId) {
      return 0;
    }

    @Override
    public void onHeartbeat(int instanceId, long receivedAtMs) {}

    @Override
    public int size() {
      return 1;
    }
  }

  /** A stream observer that drops everything. */
  private static final class NoopObserver extends ServerCallStreamObserver<ServerMessage> {
    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public void setOnCancelHandler(Runnable handler) {}

    @Override
    public void setCompression(String compression) {}

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setOnReadyHandler(Runnable handler) {}

    @Override
    public void disableAutoInboundFlowControl() {}

    @Override
    public void request(int count) {}

    @Override
    public void setMessageCompression(boolean enable) {}

    @Override
    public void onNext(ServerMessage value) {}

    @Override
    public void onError(Throwable t) {}

    @Override
    public void onCompleted() {}
  }
}
