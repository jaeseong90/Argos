/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.auth.SelfAuthenticatingHandler;
import java.util.List;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

/**
 * The HTTP handler of {@code /ws}: Spring's WebSocket upgrade with {@link WsHandshakeInterceptor}
 * as its only interceptor. It cannot carry {@code @RequiresRole}; it authenticates every request
 * itself (the interceptor runs before the handshake and refuses with 401 or 403), which {@link
 * SelfAuthenticatingHandler} declares (DSN-12-11). The tests of that refusal are {@code
 * WebSocketSecurityTest} (T-SEC-44).
 */
public final class ArgosWebSocketRequestHandler extends WebSocketHttpRequestHandler
    implements SelfAuthenticatingHandler {

  ArgosWebSocketRequestHandler(WebSocketHandler handler, WsHandshakeInterceptor interceptor) {
    super(handler, new DefaultHandshakeHandler());
    setHandshakeInterceptors(List.of(interceptor));
  }
}
