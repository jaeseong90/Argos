/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * A validated notification destination (16 §8.1): the parsed URL, the original host name (SNI,
 * {@code Host} header and certificate check) and the address the connection is pinned to, the first
 * of the resolved addresses that passed (step 6). The URL is a secret of the channel (SEC-52):
 * {@link #toString()} shows the scheme and the pinned address only.
 *
 * @param uri the URL
 * @param host the host as written in the URL (lower case, without brackets)
 * @param port the port (443 or 80 when the URL has none)
 * @param https whether the scheme is https
 * @param addresses every address the host resolved to (all passed the checks)
 * @param pinned the address to connect to
 */
public record Destination(
    URI uri,
    String host,
    int port,
    boolean https,
    List<InetAddress> addresses,
    InetAddress pinned) {

  public Destination {
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(host, "host");
    addresses = List.copyOf(addresses);
    Objects.requireNonNull(pinned, "pinned");
  }

  /** The request target: the raw path (at least {@code /}) and query of the URL. */
  public String requestTarget() {
    String path = uri.getRawPath();
    if (path == null || path.isEmpty()) {
      path = "/";
    }
    String query = uri.getRawQuery();
    return query == null ? path : path + "?" + query;
  }

  /** The {@code Host} header value: the host, bracketed for IPv6, with a non-default port. */
  public String hostHeader() {
    String h = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    boolean defaultPort = (https && port == 443) || (!https && port == 80);
    return defaultPort ? h : h + ":" + port;
  }

  @Override
  public String toString() {
    return "Destination["
        + (https ? "https" : "http")
        + ", pinned="
        + pinned.getHostAddress()
        + "]";
  }
}
