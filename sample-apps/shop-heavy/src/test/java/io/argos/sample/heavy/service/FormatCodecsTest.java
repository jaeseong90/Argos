/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormatCodecsTest {

  private final FormatCodecs codecs = new FormatCodecs();

  private static Map<String, Object> order() {
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("id", 7);
    order.put("status", "PAID");
    order.put("amount", new BigDecimal("12.50"));
    return order;
  }

  @Test
  void everyFormatRoundTripsAnOrder() throws Exception {
    for (FormatCodecs.Format format : FormatCodecs.Format.values()) {
      List<Map<String, Object>> back = codecs.read(format, codecs.write(format, List.of(order())));

      assertThat(back).as(format.name()).hasSize(1);
      assertThat(String.valueOf(back.get(0).get("status"))).as(format.name()).isEqualTo("PAID");
      assertThat(String.valueOf(back.get(0).get("id"))).as(format.name()).isEqualTo("7");
    }
  }

  @Test
  void csvHasAHeaderLineAndOneLinePerRow() throws Exception {
    String csv =
        new String(
            codecs.write(FormatCodecs.Format.CSV, List.of(order(), order())),
            StandardCharsets.UTF_8);

    assertThat(csv.split("\n")).containsExactly("id,status,amount", "7,PAID,12.50", "7,PAID,12.50");
  }

  @Test
  void theStartupRoundTripCoversEveryFormat() throws Exception {
    assertThat(codecs.prepare()).containsOnlyKeys("json", "xml", "yaml", "csv", "cbor", "smile");
  }

  @Test
  void anUnknownFormatIsABadRequest() {
    assertThatThrownBy(() -> FormatCodecs.Format.parse("pdf"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pdf");
    assertThat(FormatCodecs.Format.parse("Yaml")).isEqualTo(FormatCodecs.Format.YAML);
  }
}
