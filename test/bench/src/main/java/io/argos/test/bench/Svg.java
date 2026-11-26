/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * Minimal static SVG line charts for the report graphs (18 §7.6 part 7: CPU time series, latency
 * CDF). One y axis, thin 2 px lines, hairline grid, a legend; off runs in categorical slot 1 (blue)
 * and agent runs in slot 2 (orange) of the validated default palette, with dark-mode steps, and
 * agent lines dashed so that identity is not carried by color alone. The data of every graph is
 * also written as a TSV file next to it (the table view).
 */
final class Svg {
  private Svg() {}

  /** One line: {@code role} 0 = off (blue, solid), 1 = agent (orange, dashed). */
  record Series(String name, int role, List<double[]> points) {}

  private static final int WIDTH = 760;
  private static final int HEIGHT = 380;
  private static final int LEFT = 70;
  private static final int RIGHT = 24;
  private static final int TOP = 48;
  private static final int BOTTOM = 56;

  /**
   * A line chart of {@code series}; {@code logX} plots x on a log10 scale (values <= 0 are
   * dropped). Legend entries are {@code legend[role]}.
   */
  static String lineChart(
      String title,
      String xLabel,
      String yLabel,
      List<Series> series,
      boolean logX,
      List<String> legend) {
    double xMin = Double.POSITIVE_INFINITY;
    double xMax = Double.NEGATIVE_INFINITY;
    double yMin = 0;
    double yMax = Double.NEGATIVE_INFINITY;
    for (Series s : series) {
      for (double[] p : s.points()) {
        if (Double.isNaN(p[0]) || Double.isNaN(p[1]) || (logX && p[0] <= 0)) {
          continue;
        }
        double x = logX ? Math.log10(p[0]) : p[0];
        xMin = Math.min(xMin, x);
        xMax = Math.max(xMax, x);
        yMax = Math.max(yMax, p[1]);
        // The y axis starts at 0 unless a value is negative (trend of relative overheads).
        yMin = Math.min(yMin, p[1]);
      }
    }
    StringBuilder svg = new StringBuilder();
    svg.append(
            String.format(
                Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\""
                    + " height=\"%d\" role=\"img\" aria-labelledby=\"t d\">\n",
                WIDTH,
                HEIGHT,
                WIDTH,
                HEIGHT))
        .append("<title id=\"t\">")
        .append(escape(title))
        .append("</title>\n<desc id=\"d\">")
        .append(escape(yLabel + " by " + xLabel + "; the data is in the TSV file of the same name"))
        .append("</desc>\n")
        .append(STYLE)
        .append(
            String.format(
                Locale.ROOT, "<rect class=\"bg\" width=\"%d\" height=\"%d\"/>\n", WIDTH, HEIGHT))
        .append(String.format(Locale.ROOT, "<text class=\"title\" x=\"%d\" y=\"24\">", LEFT))
        .append(escape(title))
        .append("</text>\n");
    if (xMin > xMax) {
      svg.append(
              String.format(
                  Locale.ROOT,
                  "<text class=\"axis\" x=\"%d\" y=\"%d\">no data</text>\n",
                  LEFT,
                  HEIGHT / 2))
          .append("</svg>\n");
      return svg.toString();
    }
    if (xMax == xMin) {
      xMax = xMin + 1;
    }
    double[] yTicks = niceTicks(yMin, yMax <= yMin ? yMin + 1 : yMax, 5);
    yMin = yTicks[0];
    yMax = yTicks[yTicks.length - 1];
    double[] xTicks = logX ? decadeTicks(xMin, xMax) : niceTicks(xMin, xMax, 6);
    if (!logX) {
      xMin = Math.min(xMin, xTicks[0]);
      xMax = Math.max(xMax, xTicks[xTicks.length - 1]);
    }
    double plotW = WIDTH - LEFT - RIGHT;
    double plotH = HEIGHT - TOP - BOTTOM;
    double fxMin = xMin;
    double fxMax = xMax;
    double fyMin = yMin;
    double fyMax = yMax;
    DoubleUnaryOperator sx = x -> LEFT + (x - fxMin) / (fxMax - fxMin) * plotW;
    DoubleUnaryOperator sy = y -> TOP + plotH - (y - fyMin) / (fyMax - fyMin) * plotH;

    for (double t : yTicks) {
      double y = sy.applyAsDouble(t);
      svg.append(line("grid", LEFT, y, LEFT + plotW, y))
          .append(
              String.format(
                  Locale.ROOT,
                  "<text class=\"axis\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>\n",
                  LEFT - 8,
                  y + 4,
                  label(t)));
    }
    for (double t : xTicks) {
      if (t < xMin - 1e-9 || t > xMax + 1e-9) {
        continue;
      }
      double x = sx.applyAsDouble(t);
      svg.append(
          String.format(
              Locale.ROOT,
              "<text class=\"axis\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>\n",
              x,
              TOP + plotH + 18,
              label(logX ? Math.pow(10, t) : t)));
    }
    svg.append(line("base", LEFT, TOP + plotH, LEFT + plotW, TOP + plotH))
        .append(
            String.format(
                Locale.ROOT,
                "<text class=\"axis\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>\n",
                LEFT + plotW / 2,
                HEIGHT - 12,
                escape(xLabel)))
        .append(
            String.format(
                Locale.ROOT,
                "<text class=\"axis\" transform=\"translate(16 %.1f) rotate(-90)\""
                    + " text-anchor=\"middle\">%s</text>\n",
                TOP + plotH / 2,
                escape(yLabel)));
    for (Series s : series) {
      List<String> pts = new ArrayList<>();
      for (double[] p : s.points()) {
        if (Double.isNaN(p[0]) || Double.isNaN(p[1]) || (logX && p[0] <= 0)) {
          continue;
        }
        double x = logX ? Math.log10(p[0]) : p[0];
        pts.add(
            String.format(Locale.ROOT, "%.1f,%.1f", sx.applyAsDouble(x), sy.applyAsDouble(p[1])));
      }
      if (pts.isEmpty()) {
        continue;
      }
      svg.append("<polyline class=\"s")
          .append(s.role() == 0 ? "0" : "1")
          .append("\" points=\"")
          .append(String.join(" ", pts))
          .append("\"><title>")
          .append(escape(s.name()))
          .append("</title></polyline>\n");
    }
    // Legend above the plot, right-aligned entries: a short line sample and the text.
    double x = LEFT + plotW;
    for (int role = legend.size() - 1; role >= 0; role--) {
      String text = legend.get(role);
      double textWidth = 7.0 * text.length();
      double start = x - textWidth - 30;
      svg.append(line("s" + role, start, 36, start + 22, 36))
          .append(
              String.format(
                  Locale.ROOT,
                  "<text class=\"legend\" x=\"%.1f\" y=\"40\">%s</text>\n",
                  start + 28,
                  escape(text)));
      x = start - 16;
    }
    svg.append("</svg>\n");
    return svg.toString();
  }

  private static final String STYLE =
      """
      <style>
        svg { --surface: #fcfcfb; --ink: #0b0b0b; --muted: #52514e; --grid: #e1e0d9;
              --base: #c3c2b7; --s0: #2a78d6; --s1: #eb6834; font-family: system-ui, sans-serif; }
        @media (prefers-color-scheme: dark) {
          svg { --surface: #1a1a19; --ink: #f0efec; --muted: #c3c2b7; --grid: #2c2c2a;
                --base: #383835; --s0: #3987e5; --s1: #d95926; }
        }
        .bg { fill: var(--surface); }
        .title { fill: var(--ink); font-size: 15px; font-weight: 600; }
        .axis { fill: var(--muted); font-size: 12px; }
        .legend { fill: var(--muted); font-size: 12px; }
        .grid { stroke: var(--grid); stroke-width: 1; }
        .base { stroke: var(--base); stroke-width: 1; }
        .s0 { fill: none; stroke: var(--s0); stroke-width: 2; stroke-linejoin: round; }
        .s1 { fill: none; stroke: var(--s1); stroke-width: 2; stroke-linejoin: round;
              stroke-dasharray: 6 3; }
      </style>
      """;

  private static String line(String cls, double x1, double y1, double x2, double y2) {
    return String.format(
        Locale.ROOT,
        "<line class=\"%s\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\"/>\n",
        cls,
        x1,
        y1,
        x2,
        y2);
  }

  /** About {@code count} round tick values from below {@code min} to at least {@code max}. */
  static double[] niceTicks(double min, double max, int count) {
    double span = max - min;
    double raw = span / Math.max(1, count - 1);
    double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
    double step = magnitude;
    for (double m : new double[] {1, 2, 2.5, 5, 10}) {
      if (m * magnitude >= raw) {
        step = m * magnitude;
        break;
      }
    }
    double start = Math.floor(min / step) * step;
    double end = Math.ceil(max / step) * step;
    int n = (int) Math.round((end - start) / step) + 1;
    double[] ticks = new double[n];
    for (int i = 0; i < n; i++) {
      ticks[i] = start + i * step;
    }
    return ticks;
  }

  /** log10 values of the decades (1, 10, 100, ...) between {@code min} and {@code max}. */
  static double[] decadeTicks(double min, double max) {
    int from = (int) Math.ceil(min - 1e-9);
    int to = (int) Math.floor(max + 1e-9);
    if (to < from) {
      return new double[] {min, max};
    }
    double[] ticks = new double[to - from + 1];
    for (int i = 0; i < ticks.length; i++) {
      ticks[i] = from + i;
    }
    return ticks;
  }

  static String label(double v) {
    if (Math.abs(v) >= 1000 || v == Math.rint(v)) {
      return String.format(Locale.ROOT, "%,.0f", v);
    }
    if (Math.abs(v) >= 1) {
      return String.format(Locale.ROOT, "%.1f", v);
    }
    return String.format(Locale.ROOT, "%.2f", v);
  }

  static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
