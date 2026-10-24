/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.http;

import java.net.URI;

/**
 * One HTTP client library used for the outgoing call of GET /api/pay/{id} (18 §4.2, F-A33). Each
 * app registers the clients of its stack ({@code ?client=hurl|hc4|hc5|okhttp|jdk11|webclient|
 * resttemplate}). Implementations use the read and connect timeouts of {@link
 * io.argos.sample.common.SampleConfig}, follow redirects, and throw the library's own exception on
 * a transport failure ({@link OutboundOutcome#classify} maps it).
 */
public interface OutboundClient {

  /** Name used in {@code ?client=}. */
  String name();

  /** GET {@code uri}; returns status and body (the body may be truncated). */
  OutboundResponse get(URI uri) throws Exception;
}
