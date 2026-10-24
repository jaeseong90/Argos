/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.dataformat.smile.databind.SmileMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.springframework.stereotype.Service;

/**
 * Export of shop data in every Jackson data format (JSON, XML, YAML, CSV, CBOR, Smile) with the
 * datatype modules (JDK 8, java.time, Guava, Joda, Hibernate 6, Blackbird, Kotlin) registered on
 * each mapper. The mappers are the app's own: Spring's JSON mapper of the shop endpoints is not
 * changed. Thread-safe once built.
 */
@Service
public class FormatCodecs {

  /** Supported formats and their content types. */
  public enum Format {
    JSON("application/json"),
    XML("application/xml"),
    YAML("application/yaml"),
    CSV("text/csv"),
    CBOR("application/cbor"),
    SMILE("application/x-jackson-smile");

    private final String contentType;

    Format(String contentType) {
      this.contentType = contentType;
    }

    public String contentType() {
      return contentType;
    }

    public static Format parse(String value) {
      try {
        return valueOf(value.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "format must be one of json, xml, yaml, csv, cbor, smile: " + value, e);
      }
    }
  }

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {};

  private final Map<Format, ObjectMapper> mappers = new LinkedHashMap<>();

  public FormatCodecs() {
    mappers.put(Format.JSON, configure(JsonMapper.builder().build()));
    mappers.put(Format.XML, configure(XmlMapper.builder().build()));
    mappers.put(Format.YAML, configure(YAMLMapper.builder().build()));
    mappers.put(Format.CSV, configure(CsvMapper.builder().build()));
    mappers.put(Format.CBOR, configure(CBORMapper.builder().build()));
    mappers.put(Format.SMILE, configure(SmileMapper.builder().build()));
  }

  private static ObjectMapper configure(ObjectMapper mapper) {
    List<Module> modules =
        List.of(
            new Jdk8Module(),
            new JavaTimeModule(),
            new GuavaModule(),
            new JodaModule(),
            new Hibernate6Module(),
            new BlackbirdModule(),
            new KotlinModule.Builder().build());
    mapper.registerModules(modules);
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    return mapper;
  }

  /**
   * Serializes rows (maps with the same keys) in {@code format}. CSV writes a header line and one
   * line per row; the other formats write the list.
   */
  public byte[] write(Format format, List<Map<String, Object>> rows)
      throws JsonProcessingException {
    ObjectMapper mapper = mappers.get(format);
    if (format == Format.CSV) {
      CsvSchema.Builder schema = CsvSchema.builder().setUseHeader(true);
      if (!rows.isEmpty()) {
        for (String column : rows.get(0).keySet()) {
          schema.addColumn(column);
        }
      }
      return ((CsvMapper) mapper).writer(schema.build()).writeValueAsBytes(rows);
    }
    return mapper.writeValueAsBytes(rows);
  }

  /** Reads rows written by {@link #write}. */
  public List<Map<String, Object>> read(Format format, byte[] bytes) throws IOException {
    ObjectMapper mapper = mappers.get(format);
    if (format == Format.CSV) {
      List<Map<String, Object>> rows = new ArrayList<>();
      try (var it =
          ((CsvMapper) mapper)
              .readerFor(MAP)
              .with(CsvSchema.emptySchema().withHeader())
              .<Map<String, Object>>readValues(bytes)) {
        while (it.hasNextValue()) {
          rows.add(it.nextValue());
        }
      }
      return rows;
    }
    return mapper.readValue(bytes, ROWS);
  }

  /**
   * Startup: one row with Guava, Joda, java.time and BigDecimal values through every format and
   * back.
   */
  public Map<String, Object> prepare() throws IOException {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 1);
    row.put("status", "NEW");
    row.put("amount", new BigDecimal("123.45"));
    row.put("tags", ImmutableList.of("heavy", "sample"));
    row.put("createdAt", new DateTime(2026, 1, 15, 0, 0, DateTimeZone.UTC));
    row.put("shippedAt", LocalDate.of(2026, 1, 16));
    Map<String, Object> sizes = new LinkedHashMap<>();
    for (Format format : Format.values()) {
      Map<String, Object> flat = new LinkedHashMap<>(row);
      if (format == Format.CSV) {
        flat.put("tags", String.join("|", ImmutableList.of("heavy", "sample")));
      }
      byte[] bytes = write(format, List.of(flat));
      List<Map<String, Object>> back = read(format, bytes);
      if (back.size() != 1 || !"NEW".equals(String.valueOf(back.get(0).get("status")))) {
        throw new IllegalStateException(format + " round trip failed: " + back);
      }
      sizes.put(format.name().toLowerCase(Locale.ROOT), bytes.length);
    }
    return sizes;
  }
}
