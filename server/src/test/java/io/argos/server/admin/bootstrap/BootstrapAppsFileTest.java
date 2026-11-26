/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.admin.bootstrap.BootstrapAppsFile.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The bootstrap applications file format (WO-41; 17 §3.8, §6.4). */
@Tag("req:N-16")
class BootstrapAppsFileTest {

  /** The demo profile's file (deploy/compose/config/demo/bootstrap-apps.yaml). */
  static final String DEMO =
      """
      apps:
        - name: order-api
          env: demo
          token-file: /run/secrets/demo_order_api_token
        - name: payment-api
          env: demo
          token-file: /run/secrets/demo_payment_api_token
      """;

  @Test
  @DisplayName("T-NFR-16a the demo file lists order-api and payment-api with their token files")
  void demoFile() {
    assertThat(BootstrapAppsFile.parse(DEMO))
        .containsExactly(
            new Entry("order-api", "demo", "/run/secrets/demo_order_api_token"),
            new Entry("payment-api", "demo", "/run/secrets/demo_payment_api_token"));
    // Windows line ends and blanks around values are accepted.
    assertThat(BootstrapAppsFile.parse(DEMO.replace("\n", "\r\n").replace(": demo", ":  demo  ")))
        .extracting(Entry::env)
        .containsOnly("demo");
  }

  @ParameterizedTest(name = "T-NFR-16a invalid file: {0}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          not YAML                  | "apps: [unclosed"                                                       | not valid YAML
          not a map                 | "- a"                                                                   | the top level must be a map
          empty document            | ""                                                                      | the top level must be a map
          unknown top-level key     | "apps: []\\nextra: 1"                                                   | unknown top-level key 'extra'
          apps missing              | "other: 1"                                                              | unknown top-level key 'other'
          apps not a list           | "apps: x"                                                               | 'apps' must be a list
          apps empty                | "apps: []"                                                              | lists no application
          entry not a map           | "apps: [x]"                                                             | apps[1]: an entry is a map
          unknown entry key         | "apps: [{name: a, env: e, token-file: f, token: x}]"                    | apps[1]: unknown key 'token'
          name missing              | "apps: [{env: e, token-file: f}]"                                       | apps[1]: 'name' is required
          env not a string          | "apps: [{name: a, env: 1, token-file: f}]"                              | apps[1]: 'env' must be a string
          token-file empty          | "apps: [{name: a, env: e, token-file: '  '}]"                         | apps[1]: 'token-file' is empty
          env too long              | "apps: [{name: a, env: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, token-file: f}]" | 'env' is longer than 32 characters
          duplicate app             | "apps: [{name: a, env: e, token-file: f}, {name: a, env: e, token-file: g}]" | apps[2]: the application (name, env) is listed twice
          duplicate token file      | "apps: [{name: a, env: e, token-file: f}, {name: b, env: e, token-file: f}]" | apps[2]: the token-file is used by another entry
          duplicate YAML key        | "apps: [{name: a, name: b, env: e, token-file: f}]"                     | not valid YAML
          """)
  void invalid(String label, String yaml, String message) {
    String text = yaml.replace("\\n", "\n");
    assertThatThrownBy(() -> BootstrapAppsFile.parse(text))
        .as(label)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }

  @Test
  @DisplayName("T-NFR-16a more than 100 applications are refused")
  void tooMany() {
    StringBuilder yaml = new StringBuilder("apps:\n");
    for (int i = 0; i <= BootstrapAppsFile.MAX_APPS; i++) {
      yaml.append("  - {name: a")
          .append(i)
          .append(", env: e, token-file: f")
          .append(i)
          .append("}\n");
    }
    assertThatThrownBy(() -> BootstrapAppsFile.parse(yaml.toString()))
        .hasMessageContaining("more than 100");
    assertThat(BootstrapAppsFile.parse(yaml.toString().replaceFirst("(?m)^  - .*\\n", "")))
        .hasSize(BootstrapAppsFile.MAX_APPS);
  }
}
