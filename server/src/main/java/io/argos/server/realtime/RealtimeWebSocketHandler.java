/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.auth.ArgosPrincipal;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * The {@code /ws} endpoint (14 §5, plain WebSocket, no STOMP): binds each Spring session to a
 * {@link WsConnection} of the {@link WsConnectionRegistry} and hands complete text messages to the
 * {@link SubscriptionManager}. The handshake was authenticated by {@link WsHandshakeInterceptor}; a
 * session without its principal is closed at once. Partial messages are accepted so that the 4 KiB
 * limit closes with {@value WsConnection#CLOSE_TOO_BIG} whatever the container's buffer; a binary
 * message closes with {@value WsConnection#CLOSE_UNSUPPORTED}.
 */
final class RealtimeWebSocketHandler implements WebSocketHandler {

  /** Session attribute of the connection. */
  static final String CONNECTION = "argos.ws.connection";

  private static final Logger LOG = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

  private final WsConnectionRegistry registry;
  private final SubscriptionManager subscriptions;
  private final Clock clock;

  RealtimeWebSocketHandler(
      WsConnectionRegistry registry, SubscriptionManager subscriptions, Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    if (!(session.getAttributes().get(WsHandshakeInterceptor.PRINCIPAL)
        instanceof ArgosPrincipal principal)) {
      session.close(new CloseStatus(WsConnection.CLOSE_UNAUTHENTICATED, "Unauthenticated"));
      return;
    }
    WsConnection c = registry.open(session.getId(), principal, new SessionTransport(session));
    session.getAttributes().put(CONNECTION, c);
  }

  @Override
  public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
    if (!(session.getAttributes().get(CONNECTION) instanceof WsConnection c)) {
      return;
    }
    if (message instanceof TextMessage text) {
      c.onText(text.getPayload(), text.isLast(), clock.millis(), subscriptions);
    } else if (message instanceof BinaryMessage) {
      registry.close(c, WsConnection.CLOSE_UNSUPPORTED, "Binary messages are not supported", false);
    }
    // Ping and pong control frames: the container answers pings itself.
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    LOG.debug("WebSocket transport error: {}", exception.getClass().getSimpleName());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
    if (session.getAttributes().get(CONNECTION) instanceof WsConnection c) {
      registry.closed(c);
    }
  }

  @Override
  public boolean supportsPartialMessages() {
    return true;
  }

  /** A Spring session as the connection's transport. */
  private record SessionTransport(WebSocketSession session) implements WsConnection.Transport {

    @Override
    public void send(String text) throws IOException {
      session.sendMessage(new TextMessage(text));
    }

    @Override
    public void close(int code, String reason) {
      try {
        session.close(new CloseStatus(code, reason));
      } catch (IOException | RuntimeException e) {
        LOG.debug("WebSocket close failed: {}", e.getClass().getSimpleName());
      }
    }

    @Override
    public boolean isOpen() {
      return session.isOpen();
    }
  }
}
