/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import io.argos.storage.clickhouse.Account;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * One parsed query file ({@code test/storage/queries/Qnn.sql} or {@code params.sql}).
 *
 * @param path source file
 * @param id {@code Q01}..{@code Q20}, or {@code params}
 * @param title one-line title (report)
 * @param source design references (13 §6.3 (n), §15 Qn, requirement ids)
 * @param kind timing, consistency or procedure
 * @param account default account of the sections
 * @param criterion default criterion of the variants (13 §15)
 * @param settings default ClickHouse settings, sent as request parameters
 * @param uses variants of other files that a procedure runs ({@code Q01/1h})
 * @param description the free comment at the top of the file
 * @param probes parameter probes, in order
 * @param variants timed or checked statements
 * @param steps statements of the procedure
 */
public record QueryFile(
    Path path,
    String id,
    String title,
    String source,
    Kind kind,
    Account account,
    Criterion criterion,
    Map<String, String> settings,
    List<String> uses,
    String description,
    List<Section> probes,
    List<Section> variants,
    List<Section> steps) {

  public QueryFile {
    settings = Map.copyOf(settings);
    uses = List.copyOf(uses);
    probes = List.copyOf(probes);
    variants = List.copyOf(variants);
    steps = List.copyOf(steps);
  }

  /** What a file measures. */
  public enum Kind {
    /** Timed queries with a time criterion (Q1..Q11). */
    TIMING,
    /** Consistency checks whose criterion is a row count (Q12, Q13). */
    CONSISTENCY,
    /** A procedure implemented by the runner with the file's steps (Q14..Q20). */
    PROCEDURE,
    /** The shared parameter probes ({@code params.sql}). */
    PARAMETERS
  }

  /** The variant called {@code name}. */
  public Section variant(String name) {
    return variants.stream()
        .filter(v -> v.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException(id + " has no variant " + name));
  }

  /** The step called {@code name}. */
  public Section step(String name) {
    return steps.stream()
        .filter(s -> s.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException(id + " has no step " + name));
  }

  /**
   * A probe, variant or step: one SQL statement with its attributes.
   *
   * @param type probe, variant or step
   * @param name name, unique per type within the file
   * @param params literal parameter values of the variant line ({@code days=7})
   * @param outputs declared output columns of a probe (its parameters)
   * @param account account, or null for the file's
   * @param criterion criterion, or null for the file's
   * @param expect expectation on the result (e.g. {@code rows >= 1}), or null
   * @param settings additional settings
   * @param optional for a probe: why the data may have no value for it ({@code @optional}); the
   *     variants that need its parameters are then not applicable instead of failing; else null
   * @param sql the statement (comments removed, no trailing semicolon)
   */
  public record Section(
      SectionType type,
      String name,
      Map<String, String> params,
      List<String> outputs,
      Account account,
      Criterion criterion,
      Criterion expect,
      Map<String, String> settings,
      String optional,
      String sql) {

    public Section {
      params = Map.copyOf(params);
      outputs = List.copyOf(outputs);
      settings = Map.copyOf(settings);
    }
  }

  /** Section types. */
  public enum SectionType {
    /** Returns one row whose columns become parameters. */
    PROBE,
    /** A statement measured or checked against the criterion. */
    VARIANT,
    /** A statement run by a procedure. */
    STEP
  }
}
