/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.http;

import java.net.URI;
import java.util.Collections;
import java.util.List;

/**
 * One HTTP client library used for the outgoing calls of GET /api/pay/{id} and GET /api/hop/{id}
 * (18 §4.2, F-A33). Each app registers the clients of its stack ({@code ?client=hurl|hc4|hc5|
 * okhttp|jdk11|webclient|resttemplate}). Implementations use the read and connect timeouts of
 * {@link io.argos.sample.common.SampleConfig} (the hop read timeout for {@link
 * OutboundRequest#hop()} calls), follow redirects, set the request headers of {@link
 * OutboundRequest#headers()}, and throw the library's own exception on a transport failure ({@link
 * OutboundOutcome#classify} maps it).
 */
public interface OutboundClient {

  /** Name used in {@code ?client=}. */
  String name();

  /** Plain synchronous GET of {@code uri}; returns status and body (the body may be truncated). */
  OutboundResponse get(URI uri) throws Exception;

  /**
   * The call styles of {@code ?mode=} this client supports ({@link OutboundRequest#SYNC} always
   * first). OkHttp and java.net.http add {@code async}, WebClient {@code block}, {@code future} and
   * {@code subscribe}.
   */
  default List<String> modes() {
    return Collections.singletonList(OutboundRequest.SYNC);
  }

  /**
   * GET with the headers, mode and hop timeout of {@code request}. The default supports only a
   * plain synchronous call (scripted test clients); the app's clients implement every part.
   */
  default OutboundResponse send(OutboundRequest request) throws Exception {
    if (!request.headers().isEmpty()
        || request.hop()
        || !OutboundRequest.SYNC.equals(request.mode())) {
      throw new IllegalArgumentException(
          "Client '" + name() + "' supports plain synchronous calls only: " + request);
    }
    return get(request.uri());
  }
}
