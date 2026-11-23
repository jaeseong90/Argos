/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.auth.Role;
import io.argos.server.web.security.RateLimitBucket;
import io.argos.server.web.security.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The client frames of a WebSocket connection (14 §5.2) and the one authorisation point of the
 * topics (12 §3.7 {@code SubscriptionManager.authorize()}): {@code subscribe} is checked in the
 * order rate limit, topic ({@code topic_unknown}), parameters ({@code invalid_params}), role
 * ({@code forbidden}), the 20 subscriptions of a connection ({@code limit_exceeded}, the connection
 * stays), the application ({@code invalid_params}); then {@code ack} (with {@code since}) is queued
 * before the bus can push anything. A {@code subscribe} with the id of a live subscription replaces
 * it. {@code unsubscribe} of an unknown id is ignored; {@code ping} is answered {@code pong}.
 */
@Component
public class SubscriptionManager implements WsConnection.FrameHandler {

  /** Longest {@code message} of an {@code error} frame (the UI accepts up to 1,024). */
  static final int MAX_MESSAGE_LENGTH = 200;

  private final RealtimeBus bus;
  private final Clock clock;
  private final Counter rejections;

  public SubscriptionManager(RealtimeBus bus, Clock clock, MeterRegistry registry) {
    this.bus = Objects.requireNonNull(bus, "bus");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.rejections =
        Counter.builder(RateLimiter.REJECTIONS_METER)
            .tag("scope", RateLimitBucket.WS_SUBSCRIBE.scope())
            .description("API requests rejected by a rate limit (429)")
            .register(registry);
  }

  /**
   * Whether a role may subscribe to a topic: every role from {@code VIEWER} up for the data topics,
   * {@code ADMIN} for {@code admin} (14 §5.3). Application permissions (1.x) belong here.
   */
  public static boolean authorize(Role role, Topic topic) {
    return role.includes(topic == Topic.ADMIN ? Role.ADMIN : Role.VIEWER);
  }

  @Override
  public void onFrame(WsConnection connection, String message) {
    switch (ClientFrame.parse(message)) {
      case ClientFrame.Invalid i ->
          connection.sendControl(
              ServerFrames.error(i.id(), ServerFrames.INVALID_PARAMS, i.message()));
      case ClientFrame.Ping p -> connection.sendControl(ServerFrames.pong(p.t(), clock.instant()));
      case ClientFrame.Unsubscribe u -> connection.remove(u.id());
      case ClientFrame.Subscribe s -> subscribe(connection, s);
    }
  }

  private void subscribe(WsConnection connection, ClientFrame.Subscribe frame) {
    String id = frame.id();
    if (!connection.bucket().tryTake()) {
      rejections.increment();
      error(connection, id, ServerFrames.LIMIT_EXCEEDED, "Subscription changes are rate limited");
      return;
    }
    Optional<Topic> topic = Topic.fromWire(frame.topic());
    if (topic.isEmpty()) {
      error(connection, id, ServerFrames.TOPIC_UNKNOWN, "Unknown topic");
      return;
    }
    TopicRequest request;
    try {
      request = TopicRequest.parse(topic.get(), frame.params());
    } catch (InvalidTopicParams e) {
      error(connection, id, ServerFrames.INVALID_PARAMS, e.getMessage());
      return;
    }
    if (!authorize(connection.role(), topic.get())) {
      error(connection, id, ServerFrames.FORBIDDEN, "The role does not allow this topic");
      return;
    }
    if (connection.subscription(id) == null
        && connection.subscriptionCount() >= WsConnection.MAX_SUBSCRIPTIONS) {
      error(
          connection,
          id,
          ServerFrames.LIMIT_EXCEEDED,
          "At most " + WsConnection.MAX_SUBSCRIPTIONS + " subscriptions per connection");
      return;
    }
    try {
      bus.validate(request);
    } catch (InvalidTopicParams e) {
      error(connection, id, ServerFrames.INVALID_PARAMS, e.getMessage());
      return;
    }
    connection.sendControl(ServerFrames.ack(id, topic.get(), clock.instant()));
    RealtimeBus.BusSubscription handle;
    try {
      handle = bus.subscribe(id, request, connection.outbox());
    } catch (InvalidTopicParams e) {
      error(connection, id, ServerFrames.INVALID_PARAMS, e.getMessage());
      return;
    }
    connection.put(new WsConnection.Subscription(id, topic.get(), handle));
  }

  /**
   * The role of a connection's session changed without a revocation (a change of the record made
   * elsewhere, 16 §7.4): the subscriptions the new role does not allow get {@code error forbidden}
   * and end; the others stay (14 §5.1).
   */
  void roleChanged(WsConnection connection, Role role) {
    connection.role(role);
    for (WsConnection.Subscription s : connection.subscriptions()) {
      if (!authorize(role, s.topic()) && connection.remove(s.id())) {
        error(connection, s.id(), ServerFrames.FORBIDDEN, "The role does not allow this topic");
      }
    }
  }

  private static void error(WsConnection connection, String id, String code, String message) {
    String m =
        message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) : message;
    connection.sendControl(ServerFrames.error(id, code, m));
  }
}
