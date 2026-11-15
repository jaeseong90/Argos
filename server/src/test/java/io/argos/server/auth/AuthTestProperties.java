/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import io.argos.server.config.ArgosProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** {@link ArgosProperties} of the authentication unit tests: the defaults plus some keys. */
public final class AuthTestProperties {

  private AuthTestProperties() {}

  /** The defaults with a public URL, plus {@code extra}. */
  public static ArgosProperties of(Map<String, Object> extra) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("argos.web.public-url", "https://argos.example.test");
    values.putAll(extra);
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", values));
    ConfigurationPropertySources.attach(environment);
    return Binder.get(environment).bindOrCreate("argos", ArgosProperties.class);
  }

  /** The defaults with a public URL. */
  public static ArgosProperties defaults() {
    return of(Map.of());
  }
}
