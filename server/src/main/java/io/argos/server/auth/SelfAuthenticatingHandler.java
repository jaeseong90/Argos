/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

/**
 * Marks a handler of the HTTP port that is not a {@code @RequestMapping} method and authenticates
 * and authorises every request itself, so it cannot carry {@link RequiresRole} (DSN-12-11).
 *
 * <p>The hook exists for the WebSocket handshake of WO-22 ({@code /ws}, 16 §7.7: the handshake
 * checks the session cookie, the {@code Origin} and the role on its own and refuses with its own
 * status): the handler object that Spring maps to the path, for example a subclass of Spring's
 * {@code WebSocketHttpRequestHandler}, implements this interface.
 *
 * <p>What the rest of the server does with it:
 *
 * <ul>
 *   <li>{@link RoleAuthorizationInterceptor} lets a request through to such a handler (it refuses
 *       every other handler that is not a handler method, except static resources);
 *   <li>{@link RoleMatrix} accepts it in a URL handler mapping at start, but never under {@code
 *       /api}: the REST API is {@code @RequestMapping} methods with {@link RequiresRole} only;
 *   <li>the ArchUnit rules of the server accept an {@code HttpRequestHandler} or MVC {@code
 *       Controller} only when it implements this interface.
 * </ul>
 *
 * Implementing it is a security decision: the handler must refuse unauthenticated and unauthorised
 * requests before doing anything, and its tests must show that.
 */
public interface SelfAuthenticatingHandler {}
