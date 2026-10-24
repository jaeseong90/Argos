/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A criterion or an expectation of a query file: {@code record} (measured only) or comma-separated
 * terms {@code <metric> <op> <number>}, e.g. {@code warm_ms <= 1000} or {@code rows == 0}. Metrics
 * are named values of a result (timing: {@code warm_ms}, {@code cold_ms}; consistency: {@code
 * rows}; procedures: their own names).
 *
 * @param text the source text
 * @param terms the terms (empty for {@code record})
 */
public record Criterion(String text, List<Term> terms) {

  private static final Pattern TERM =
      Pattern.compile("([a-z][a-z0-9_]*)\\s*(<=|>=|==|!=|<|>)\\s*(-?[0-9]+(?:\\.[0-9]+)?)");

  /** The criterion {@code record}: measured and reported, no pass or fail. */
  public static final Criterion RECORD = new Criterion("record", List.of());

  public Criterion {
    terms = List.copyOf(terms);
  }

  /** Parses {@code record} or {@code term[, term...]}. */
  public static Criterion parse(String text) {
    String t = text.strip();
    if (t.equals("record")) {
      return RECORD;
    }
    List<Term> terms = new ArrayList<>();
    for (String part : t.split(",", -1)) {
      Matcher m = TERM.matcher(part.strip());
      if (!m.matches()) {
        throw new IllegalArgumentException(
            "Invalid criterion term '" + part.strip() + "' in: " + t);
      }
      terms.add(new Term(m.group(1), m.group(2), Double.parseDouble(m.group(3))));
    }
    return new Criterion(t, terms);
  }

  /** True for {@code record}. */
  public boolean isRecord() {
    return terms.isEmpty();
  }

  /**
   * Evaluates the terms against measured metrics: {@link Verdict#RECORDED} for {@code record},
   * {@link Verdict#INVALID} when a metric is missing or not a number, otherwise PASS or FAIL.
   */
  public Verdict evaluate(Map<String, Double> metrics) {
    if (isRecord()) {
      return Verdict.RECORDED;
    }
    for (Term term : terms) {
      Double value = metrics.get(term.metric());
      if (value == null || value.isNaN()) {
        return Verdict.INVALID;
      }
      if (!term.test(value)) {
        return Verdict.FAIL;
      }
    }
    return Verdict.PASS;
  }

  /** The terms that a set of metrics violates, for the report ("warm_ms 1530 > 1000"). */
  public List<String> violations(Map<String, Double> metrics) {
    List<String> out = new ArrayList<>();
    for (Term term : terms) {
      Double value = metrics.get(term.metric());
      if (value == null || value.isNaN()) {
        out.add(term.metric() + " missing");
      } else if (!term.test(value)) {
        out.add(
            term.metric() + " = " + String.format(Locale.ROOT, "%.4g", value) + " (" + term + ")");
      }
    }
    return out;
  }

  @Override
  public String toString() {
    return text;
  }

  /**
   * One comparison.
   *
   * @param metric metric name
   * @param op comparison operator
   * @param value right-hand side
   */
  public record Term(String metric, String op, double value) {

    /** Applies the comparison to {@code actual}. */
    public boolean test(double actual) {
      return switch (op) {
        case "<=" -> actual <= value;
        case ">=" -> actual >= value;
        case "<" -> actual < value;
        case ">" -> actual > value;
        case "==" -> actual == value;
        case "!=" -> actual != value;
        default -> throw new IllegalStateException("Unknown operator " + op);
      };
    }

    @Override
    public String toString() {
      String number =
          value == Math.rint(value) && Math.abs(value) < 1e15
              ? Long.toString((long) value)
              : Double.toString(value);
      return metric + " " + op + " " + number;
    }
  }
}
