/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.service;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.micrometer.observation.annotation.Observed;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.CodePageUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/**
 * Order reports as Excel workbooks, built in memory (no temporary file): .xlsx with Apache POI XSSF
 * (OOXML through XMLBeans) and legacy .xls with HSSF (BIFF8).
 */
@Service
@RateLimiter(name = SpreadsheetExporter.REPORTS)
@Observed(name = "heavy.reports")
public class SpreadsheetExporter {

  /** Resilience4j rate limiter of report generation (application.properties). */
  public static final String REPORTS = "reports";

  /** Rows of one sheet at most. */
  public static final int MAX_ROWS = 1000;

  /** Content type of the workbooks. */
  public static final String CONTENT_TYPE =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  /** Content type of the legacy (Excel 97-2003, BIFF8) workbooks. */
  public static final String LEGACY_CONTENT_TYPE = "application/vnd.ms-excel";

  /**
   * One sheet named {@code title}: a styled header row, one row per map (same keys) and, when there
   * is an {@code amount} column, a total row with an evaluated SUM formula.
   */
  public byte[] workbook(String title, List<Map<String, Object>> rows) throws IOException {
    return workbook(title, rows, false);
  }

  /** As {@link #workbook(String, List)}; {@code legacy} writes an .xls (HSSF) instead of .xlsx. */
  public byte[] workbook(String title, List<Map<String, Object>> rows, boolean legacy)
      throws IOException {
    if (rows.size() > MAX_ROWS) {
      throw new IllegalArgumentException("At most " + MAX_ROWS + " rows");
    }
    try (Workbook workbook = legacy ? new HSSFWorkbook() : new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(title);
      CellStyle header = workbook.createCellStyle();
      Font bold = workbook.createFont();
      bold.setBold(true);
      header.setFont(bold);
      header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
      Row head = sheet.createRow(0);
      for (int c = 0; c < columns.size(); c++) {
        Cell cell = head.createCell(c);
        cell.setCellValue(columns.get(c));
        cell.setCellStyle(header);
      }
      for (int r = 0; r < rows.size(); r++) {
        Row row = sheet.createRow(r + 1);
        for (int c = 0; c < columns.size(); c++) {
          Object value = rows.get(r).get(columns.get(c));
          Cell cell = row.createCell(c);
          if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
          } else if (value != null) {
            cell.setCellValue(String.valueOf(value));
          }
        }
      }
      int amountColumn = columns.indexOf("amount");
      if (amountColumn >= 0 && !rows.isEmpty()) {
        // Total row: a SUM formula, evaluated so that the file carries the cached result.
        Row total = sheet.createRow(rows.size() + 1);
        total.createCell(0).setCellValue("total");
        String column = CellReference.convertNumToColString(amountColumn);
        total
            .createCell(amountColumn)
            .setCellFormula("SUM(" + column + "2:" + column + (rows.size() + 1) + ")");
        workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
      }
      sheet.createFreezePane(0, 1);
      if (workbook instanceof HSSFWorkbook legacyWorkbook) {
        // Document properties (summary information streams) of the .xls file.
        legacyWorkbook.createInformationProperties();
        legacyWorkbook.getSummaryInformation().getFirstSection().setCodepage(CodePageUtil.CP_UTF8);
        legacyWorkbook
            .getDocumentSummaryInformation()
            .getFirstSection()
            .setCodepage(CodePageUtil.CP_UTF8);
        legacyWorkbook.getSummaryInformation().setTitle(title);
      }
      workbook.write(out);
      return out.toByteArray();
    }
  }

  /** Rows and columns of the first sheet of a workbook. */
  public Map<String, Object> describe(byte[] bytes) throws IOException {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      Sheet sheet = workbook.getSheetAt(0);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("format", workbook instanceof HSSFWorkbook ? "xls" : "xlsx");
      result.put("sheet", sheet.getSheetName());
      result.put("rows", sheet.getLastRowNum() + 1);
      result.put("columns", sheet.getRow(0) == null ? 0 : (int) sheet.getRow(0).getLastCellNum());
      result.put("bytes", bytes.length);
      Row last = sheet.getRow(sheet.getLastRowNum());
      if (last != null
          && last.getCell(0) != null
          && last.getCell(0).getCellType() == CellType.STRING
          && "total".equals(last.getCell(0).getStringCellValue())) {
        for (Cell cell : last) {
          if (cell.getCellType() == CellType.FORMULA) {
            result.put("total", cell.getNumericCellValue());
          }
        }
      }
      return result;
    }
  }

  /** Startup: a two-row workbook with its total written, evaluated and read back, xlsx and xls. */
  public Map<String, Object> prepare() throws IOException {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 1L);
    row.put("status", "NEW");
    row.put("amount", new BigDecimal("123.45"));
    Map<String, Object> result = new LinkedHashMap<>();
    for (boolean legacy : new boolean[] {false, true}) {
      Map<String, Object> described = describe(workbook("orders", List.of(row, row), legacy));
      if (!Integer.valueOf(4).equals(described.get("rows"))
          || !Double.valueOf(246.9).equals(described.get("total"))) {
        throw new IllegalStateException("Unexpected workbook: " + described);
      }
      result.put(String.valueOf(described.get("format")), described.get("bytes"));
    }
    return result;
  }
}
