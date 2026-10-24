/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.service;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.micrometer.observation.annotation.Observed;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.text.StringSubstitutor;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

/**
 * Order reports as Word documents (Apache POI XWPF, WordprocessingML through XMLBeans), built in
 * memory: a title from a text template (Commons Text), a header line (Guava) and a two-column table
 * of the order's fields.
 */
@Service
@RateLimiter(name = SpreadsheetExporter.REPORTS)
@Observed(name = "heavy.reports")
public class DocumentExporter {

  /** Content type of the documents. */
  public static final String CONTENT_TYPE =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  private static final String TITLE = "Order ${id} (${status})";

  public byte[] document(Map<String, Object> order) throws IOException {
    Map<String, Object> fields =
        ImmutableSortedMap.copyOf(Maps.filterValues(order, Objects::nonNull));
    try (XWPFDocument document = new XWPFDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      XWPFParagraph title = document.createParagraph();
      title.setAlignment(ParagraphAlignment.CENTER);
      XWPFRun titleRun = title.createRun();
      titleRun.setBold(true);
      titleRun.setFontSize(16);
      titleRun.setText(new StringSubstitutor(order).replace(TITLE));
      XWPFParagraph header = document.createParagraph();
      header.createRun().setText(Joiner.on(", ").join(fields.keySet()));
      XWPFTable table = document.createTable(fields.size(), 2);
      int row = 0;
      for (Map.Entry<String, Object> field : fields.entrySet()) {
        XWPFTableRow tableRow = table.getRow(row++);
        tableRow.getCell(0).setText(field.getKey());
        tableRow.getCell(1).setText(String.valueOf(field.getValue()));
      }
      document.write(out);
      return out.toByteArray();
    }
  }

  /** Paragraphs and table rows of a document. */
  public Map<String, Object> describe(byte[] bytes) throws IOException {
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("title", document.getParagraphs().get(0).getText());
      result.put("paragraphs", document.getParagraphs().size());
      result.put(
          "tableRows",
          document.getTables().isEmpty() ? 0 : document.getTables().get(0).getNumberOfRows());
      result.put("bytes", bytes.length);
      return result;
    }
  }

  /** Startup: a sample order document written and read back. */
  public Map<String, Object> prepare() throws IOException {
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("id", 0L);
    order.put("status", "NEW");
    order.put("amount", "0.00");
    Map<String, Object> described = describe(document(order));
    if (!"Order 0 (NEW)".equals(described.get("title"))
        || !Integer.valueOf(3).equals(described.get("tableRows"))) {
      throw new IllegalStateException("Unexpected document: " + described);
    }
    return described;
  }
}
