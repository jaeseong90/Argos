/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * The report outputs of shop-heavy: HTML (Thymeleaf), Excel, Word and the composed mail. In the
 * integration layer: the first test pays for loading POI, XMLBeans, Bouncy Castle and Jakarta Mail
 * (about 2 s, 18 §2.2: tests over 1 s leave the unit layer). No network.
 */
@Tag("integration")
class ReportsTest {

  private static ReportRenderer renderer() {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    return new ReportRenderer(engine);
  }

  private static Map<String, Object> order(Object status) {
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("id", 3L);
    order.put("status", status);
    order.put("amount", new BigDecimal("10.25"));
    return order;
  }

  @Test
  void theHtmlReportEscapesFieldValues() {
    String html = renderer().render(order("<script>window.__xss=1</script>ARGOS_SEED"));

    assertThat(html)
        .contains("<h1>Order 3</h1>")
        .contains("&lt;script&gt;window.__xss=1&lt;/script&gt;ARGOS_SEED")
        .doesNotContain("<script>");
  }

  @Test
  void workbooksCarryAnEvaluatedTotalInBothFormats() throws Exception {
    SpreadsheetExporter spreadsheets = new SpreadsheetExporter();
    for (boolean legacy : new boolean[] {false, true}) {
      byte[] bytes = spreadsheets.workbook("orders", List.of(order("NEW"), order("PAID")), legacy);

      assertThat(spreadsheets.describe(bytes))
          .containsEntry("format", legacy ? "xls" : "xlsx")
          .containsEntry("rows", 4)
          .containsEntry("columns", 3)
          .containsEntry("total", 20.5);
    }
  }

  @Test
  void workbooksAreBounded() {
    List<Map<String, Object>> rows =
        Collections.nCopies(SpreadsheetExporter.MAX_ROWS + 1, order("NEW"));

    assertThatThrownBy(() -> new SpreadsheetExporter().workbook("orders", rows))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theWordReportHasATitleAndOneTableRowPerField() throws Exception {
    DocumentExporter documents = new DocumentExporter();

    assertThat(documents.describe(documents.document(order("NEW"))))
        .containsEntry("title", "Order 3 (NEW)")
        .containsEntry("tableRows", 3);
  }

  @Test
  void theMailIsComposedSignedAndEncryptedWithoutBeingSent() throws Exception {
    ReportMailer mailer =
        new ReportMailer(new JavaMailSenderImpl(), renderer(), new SpreadsheetExporter());

    assertThat(mailer.compose(order("NEW")))
        .containsEntry("subject", "Order report 3")
        .containsEntry("parts", 2)
        .containsEntry("signatureVerified", true)
        .containsEntry("provider", "BC")
        .containsKeys("sha3", "encryptedBytes");
  }
}
