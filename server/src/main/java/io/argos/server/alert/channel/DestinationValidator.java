/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import io.argos.server.config.ArgosProperties;
import io.argos.server.config.CidrRange;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Destination validation of webhook and Slack channels (16 §8.1, 12 §5.5, SEC-51), run at every
 * save, update, test send and real send, so that a host that later resolves to an internal address
 * is caught at the next delivery (DNS rebinding).
 *
 * <ol>
 *   <li>The URL must parse as an absolute {@code http(s)} URL of printable ASCII, at most 2,048
 *       characters, with a host: otherwise {@code invalid_url}. Ambiguous numeric hosts ({@code
 *       2130706433}, {@code 0x7f.1}, {@code 127.1}) are refused as {@code invalid_url}: only
 *       canonical IP literals are taken as addresses.
 *   <li>The scheme is {@code https}; {@code http} only when {@code argos.alert.webhook.allow-http}
 *       is set and the channel asks for it ({@code allow_http}): otherwise {@code scheme}.
 *   <li>User information ({@code user:pass@}) is refused: {@code userinfo}.
 *   <li>An IP literal is its own address; a name is resolved (every A and AAAA, 2 seconds): no
 *       address is {@code dns}.
 *   <li>Every address must pass: one inside {@code argos.alert.webhook.allow-cidrs} passes; one in
 *       a range of {@link BlockedRanges} is refused with that range's reason; an address of this
 *       server's interfaces or of the public origin's host ({@code argos.web.public-url}) is {@code
 *       own_address}.
 *   <li>The first address is the one the connection is pinned to ({@link Destination#pinned()});
 *       the delivery connects to it, never resolves again ({@link PinnedHttpClient}).
 *   <li>Redirects, time limits and the response body are the client's ({@link PinnedHttpClient}).
 * </ol>
 *
 * Every refusal is a {@link DestinationBlockedException} whose only detail is the reason code.
 */
@Component
public class DestinationValidator {

  /** Longest URL accepted. */
  static final int MAX_URL_LENGTH = 2048;

  /** DNS time limit of step 4 (16 §8.1). */
  static final Duration DNS_TIMEOUT = Duration.ofSeconds(2);

  private static final Duration OWN_ADDRESSES_TTL = Duration.ofSeconds(60);
  private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9.]+");
  private static final Pattern HEX_LABEL = Pattern.compile("(^|\\.)0x[0-9a-f]*(\\.|$)");

  private final boolean allowHttp;
  private final List<CidrRange> allowCidrs;
  private final String publicHost;
  private final HostResolver resolver;
  private final Clock clock;
  private volatile OwnAddresses own;

  @Autowired
  public DestinationValidator(ArgosProperties properties, Clock clock) {
    this(properties, HostResolver.system(DNS_TIMEOUT), clock);
  }

  /** A validator with its own resolver (tests: no lookup leaves the machine). */
  public DestinationValidator(ArgosProperties properties, HostResolver resolver, Clock clock) {
    ArgosProperties.Alert.Webhook webhook = properties.alert().webhook();
    this.allowHttp = webhook.allowHttp();
    List<CidrRange> ranges = new ArrayList<>();
    for (String cidr : webhook.allowCidrs()) {
      ranges.add(CidrRange.parse(cidr));
    }
    this.allowCidrs = List.copyOf(ranges);
    this.publicHost = publicHost(properties.web().publicUrl());
    this.resolver = resolver;
    this.clock = clock;
  }

  /** Whether plain http is allowed by the server configuration. */
  public boolean httpAllowed() {
    return allowHttp;
  }

  /**
   * Validates {@code url} (steps 1 to 6).
   *
   * @param url the destination URL (a secret: never logged)
   * @param channelAllowsHttp the channel's {@code allow_http}
   * @return the destination with the pinned address
   * @throws DestinationBlockedException when any step refuses
   */
  public Destination validate(String url, boolean channelAllowsHttp) {
    URI uri = parse(url);
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    boolean https = "https".equals(scheme);
    if (!https && !("http".equals(scheme) && allowHttp && channelAllowsHttp)) {
      throw new DestinationBlockedException("scheme");
    }
    if (uri.getRawUserInfo() != null || uri.getRawAuthority().indexOf('@') >= 0) {
      throw new DestinationBlockedException("userinfo");
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    int port = uri.getPort() < 0 ? (https ? 443 : 80) : uri.getPort();
    if (port < 1 || port > 65535) {
      throw new DestinationBlockedException("invalid_url");
    }
    List<InetAddress> addresses = addresses(host);
    Set<String> ownAddresses = ownAddresses();
    for (InetAddress address : addresses) {
      if (allowed(address)) {
        continue;
      }
      Optional<String> reason = BlockedRanges.reasonFor(address);
      if (reason.isPresent()) {
        throw new DestinationBlockedException(reason.get());
      }
      if (ownAddresses.contains(key(address))) {
        throw new DestinationBlockedException("own_address");
      }
    }
    return new Destination(uri, host, port, https, addresses, addresses.get(0));
  }

  private boolean allowed(InetAddress address) {
    for (CidrRange range : allowCidrs) {
      if (range.contains(address)) {
        return true;
      }
    }
    return false;
  }

  private static URI parse(String url) {
    if (url == null || url.isEmpty() || url.length() > MAX_URL_LENGTH) {
      throw new DestinationBlockedException("invalid_url");
    }
    for (int i = 0; i < url.length(); i++) {
      char c = url.charAt(i);
      if (c <= 0x20 || c >= 0x7f) {
        throw new DestinationBlockedException("invalid_url");
      }
    }
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new DestinationBlockedException("invalid_url");
    }
    if (!uri.isAbsolute()
        || uri.isOpaque()
        || uri.getScheme() == null
        || uri.getRawAuthority() == null) {
      throw new DestinationBlockedException("invalid_url");
    }
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"https".equals(scheme) && !"http".equals(scheme)) {
      throw new DestinationBlockedException("scheme");
    }
    if (uri.getRawUserInfo() != null || uri.getRawAuthority().indexOf('@') >= 0) {
      throw new DestinationBlockedException("userinfo");
    }
    if (uri.getHost() == null || uri.getHost().isEmpty()) {
      throw new DestinationBlockedException("invalid_url");
    }
    return uri;
  }

  private List<InetAddress> addresses(String host) {
    Optional<InetAddress> literal = CidrRange.literal(host);
    if (literal.isPresent()) {
      return List.of(literal.get());
    }
    if (NUMERIC_HOST.matcher(host).matches() || HEX_LABEL.matcher(host).find()) {
      // Not a canonical literal, but resolvers read such names as addresses (inet_aton forms).
      throw new DestinationBlockedException("invalid_url");
    }
    List<InetAddress> resolved;
    try {
      resolved = resolver.resolve(host);
    } catch (UnknownHostException e) {
      throw new DestinationBlockedException("dns");
    }
    if (resolved == null || resolved.isEmpty()) {
      throw new DestinationBlockedException("dns");
    }
    return List.copyOf(resolved);
  }

  /** The addresses of this server, refreshed at most once a minute. */
  private Set<String> ownAddresses() {
    Instant now = clock.instant();
    OwnAddresses current = own;
    if (current != null && now.isBefore(current.until())) {
      return current.keys();
    }
    Set<String> keys = new HashSet<>();
    try {
      for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        for (InetAddress a : Collections.list(nic.getInetAddresses())) {
          keys.add(key(a));
        }
      }
    } catch (SocketException | RuntimeException e) {
      // No interface list: the blocked ranges still cover loopback and private addresses.
    }
    if (publicHost != null) {
      Optional<InetAddress> literal = CidrRange.literal(publicHost);
      if (literal.isPresent()) {
        keys.add(key(literal.get()));
      } else {
        try {
          for (InetAddress a : resolver.resolve(publicHost)) {
            keys.add(key(a));
          }
        } catch (UnknownHostException e) {
          // The public origin does not resolve here: nothing to add.
        }
      }
    }
    Set<String> frozen = Set.copyOf(keys);
    own = new OwnAddresses(frozen, now.plus(OWN_ADDRESSES_TTL));
    return frozen;
  }

  /** An address without its scope (IPv6 zone) for comparison. */
  private static String key(InetAddress address) {
    byte[] bytes = address.getAddress();
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  private static String publicHost(String publicUrl) {
    if (publicUrl == null || publicUrl.isBlank()) {
      return null;
    }
    try {
      String host = new URI(publicUrl.strip()).getHost();
      return host == null ? null : host.toLowerCase(Locale.ROOT);
    } catch (URISyntaxException e) {
      return null;
    }
  }

  private record OwnAddresses(Set<String> keys, Instant until) {}
}
