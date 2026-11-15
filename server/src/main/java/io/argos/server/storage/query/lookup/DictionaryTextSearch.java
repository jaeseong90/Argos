/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query.lookup;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.storage.query.DictionaryNames;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The text search of the global search (14 §4.11, F-U14): dictionary texts (service names, SQL)
 * that contain the searched text, case-insensitive, through the {@code pg_trgm} GIN index of V001
 * ({@code ix_dictionary_text_trgm}), ranked by trigram similarity ({@code argos.similarity}: the
 * extension lives in the schema {@code argos}, deploy/postgres/roles.sql). The text is matched
 * literally ({@code ILIKE} with its wildcards escaped). Texts shorter than {@value #MIN_CHARS}
 * characters cannot use the trigram index and find nothing.
 */
@Component
public class DictionaryTextSearch {

  /** Shortest searched text (a trigram). */
  public static final int MIN_CHARS = 3;

  /** Characters of a returned text (14 §2.10 names). */
  static final int TEXT_CHARS = 2048;

  private static final Logger LOG = LoggerFactory.getLogger(DictionaryTextSearch.class);

  private static final String SEARCH =
      "SELECT d.app_id, d.kind::text, d.hash64, left(d.text, "
          + TEXT_CHARS
          + "), argos.similarity(d.text, ?) AS score FROM argos.dictionary d"
          + " WHERE d.kind = ANY(?::argos.dict_kind[]) AND d.text ILIKE ? ESCAPE '\\'"
          + " AND (? = 0 OR d.app_id = ?)"
          + " AND (? = '' OR d.app_id IN (SELECT a.id FROM argos.apps a WHERE a.env = ?))"
          + " ORDER BY score DESC, d.app_id, d.hash64 LIMIT ?";

  /**
   * A found entry.
   *
   * @param appId application
   * @param kind the dictionary kind
   * @param hash the key (64 bits)
   * @param text the text (at most 2,048 characters)
   * @param score trigram similarity, 0 to 1
   */
  public record Hit(int appId, DictionaryNames.Kind kind, long hash, String text, double score) {}

  private final JdbcTemplate jdbc;

  public DictionaryTextSearch(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(5);
    this.jdbc = t;
  }

  /**
   * Entries of {@code kinds} whose text contains {@code text}.
   *
   * @param text the searched text (at least {@value #MIN_CHARS} characters, else no hit)
   * @param kinds the dictionary kinds
   * @param appId an application, or 0 for all
   * @param env an environment, or empty for all
   * @param limit most hits
   */
  public List<Hit> search(
      String text, Collection<DictionaryNames.Kind> kinds, int appId, String env, int limit) {
    Objects.requireNonNull(text, "text");
    List<Hit> out = new ArrayList<>();
    if (text.codePointCount(0, text.length()) < MIN_CHARS || kinds.isEmpty() || limit <= 0) {
      return out;
    }
    String pattern = "%" + escapeLike(text) + "%";
    String e = env == null ? "" : env;
    String[] kindNames = kinds.stream().map(DictionaryNames.Kind::pgName).toArray(String[]::new);
    try {
      jdbc.query(
          SEARCH,
          ps -> {
            ps.setString(1, text);
            Array array = ps.getConnection().createArrayOf("text", kindNames);
            ps.setArray(2, array);
            ps.setString(3, pattern);
            ps.setInt(4, appId);
            ps.setInt(5, appId);
            ps.setString(6, e);
            ps.setString(7, e);
            ps.setInt(8, limit);
          },
          rs -> {
            out.add(
                new Hit(
                    rs.getInt(1),
                    DictionaryNames.Kind.valueOf(
                        rs.getString(2).toUpperCase(java.util.Locale.ROOT)),
                    rs.getLong(3),
                    rs.getString(4),
                    rs.getDouble(5)));
          });
    } catch (DataAccessException ex) {
      LOG.warn("Dictionary text search failed: {}", ex.getClass().getName());
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
    return out;
  }

  /** {@code text} with the {@code LIKE} wildcards and the escape character escaped. */
  public static String escapeLike(String text) {
    StringBuilder out = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\' || c == '%' || c == '_') {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }
}
