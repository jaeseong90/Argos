/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.config.ArgosProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The origin of {@code argos.web.public-url} (RFC 6454 serialisation: lower-case scheme and host,
 * the port only when it is not the scheme's default): the one origin that may send state-changing
 * requests (16 §7.6) and open WebSockets (16 §7.7, WO-22).
 */
@Component
public class PublicOrigin {

  private final String origin;

  @Autowired
  public PublicOrigin(ArgosProperties properties) {
    // A wrong URL is reported by the configuration phase (ConfigValidation); nothing matches then.
    this(properties.web().publicUrl());
  }

  /** The origin of an explicit URL. */
  public PublicOrigin(String publicUrl) {
    this.origin = originOf(publicUrl).orElse(null);
  }

  /** Whether the value of an {@code Origin} header is this origin. */
  public boolean matchesOrigin(String header) {
    if (origin == null || header == null) {
      return false;
    }
    Optional<URI> uri = parse(header);
    if (uri.isEmpty()
        || (uri.get().getRawPath() != null && !uri.get().getRawPath().isEmpty())
        || uri.get().getRawQuery() != null
        || uri.get().getRawFragment() != null) {
      return false;
    }
    return origin.equals(originOf(uri.get()).orElse(null));
  }

  /** Whether the value of a {@code Referer} header is a URL of this origin. */
  public boolean matchesUrl(String url) {
    return origin != null && url != null && origin.equals(originOf(url).orElse(null));
  }

  /** The origin of an absolute http or https URL. */
  static Optional<String> originOf(String url) {
    return url == null ? Optional.empty() : parse(url).flatMap(PublicOrigin::originOf);
  }

  private static Optional<URI> parse(String text) {
    try {
      return Optional.of(new URI(text.strip()));
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
  }

  private static Optional<String> originOf(URI uri) {
    String scheme = uri.getScheme();
    String host = uri.getHost();
    if (scheme == null || host == null || uri.getRawUserInfo() != null) {
      return Optional.empty();
    }
    String s = scheme.toLowerCase(Locale.ROOT);
    if (!s.equals("http") && !s.equals("https")) {
      return Optional.empty();
    }
    int port = uri.getPort();
    boolean defaultPort = port < 0 || (s.equals("http") ? port == 80 : port == 443);
    return Optional.of(s + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port));
  }
}
