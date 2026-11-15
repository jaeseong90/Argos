/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The UI page as a template (16 §9.9): {@code index.html} parsed once at start into the literal
 * text between the places a nonce goes, rendered for every request with a new nonce. Those places:
 *
 * <ul>
 *   <li>every {@code <script>} and {@code <style>} tag;
 *   <li>{@code <link rel="modulepreload">} and {@code <link rel="preload">}: under {@code
 *       'strict-dynamic'} the {@code 'self'} source of {@code script-src} no longer applies, so a
 *       module preload without the nonce would be a violation;
 *   <li>{@code <meta name="csp-nonce" content="...">}, where the UI reads the nonce for the style
 *       tags of its component library. When the page has no such tag, one is added first thing in
 *       {@code <head>}.
 * </ul>
 *
 * A {@code nonce} attribute already in the file is replaced. The file comes from the UI build, not
 * from a request; no request data ever reaches the output, and the nonce is base64url.
 */
public final class IndexHtmlTemplate {

  private static final Pattern TAG =
      Pattern.compile("<(script|style|link|meta|head)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern NONCE_ATTRIBUTE =
      Pattern.compile(
          "\\snonce(\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+))?", Pattern.CASE_INSENSITIVE);
  private static final Pattern PRELOAD =
      Pattern.compile("\\srel\\s*=\\s*[\"']?(modulepreload|preload)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern CSP_NONCE_META =
      Pattern.compile("\\sname\\s*=\\s*[\"']?csp-nonce\\b", Pattern.CASE_INSENSITIVE);

  /** Literal parts; a nonce goes between each two. */
  private final List<String> parts;

  private IndexHtmlTemplate(List<String> parts) {
    this.parts = List.copyOf(parts);
  }

  /** Parses the page. */
  public static IndexHtmlTemplate parse(String html) {
    boolean hasMeta = false;
    Matcher scan = TAG.matcher(html);
    while (scan.find()) {
      if (scan.group(1).equalsIgnoreCase("meta") && CSP_NONCE_META.matcher(scan.group()).find()) {
        hasMeta = true;
      }
    }
    List<String> parts = new ArrayList<>();
    StringBuilder literal = new StringBuilder();
    boolean addMeta = !hasMeta;
    int position = 0;
    Matcher tags = TAG.matcher(html);
    while (tags.find()) {
      literal.append(html, position, tags.start());
      position = tags.end();
      String tag = tags.group();
      String name = tags.group(1).toLowerCase(Locale.ROOT);
      switch (name) {
        case "head" -> {
          literal.append(tag);
          if (addMeta) {
            literal.append("<meta name=\"csp-nonce\" content=\"");
            parts.add(literal.toString());
            literal.setLength(0);
            literal.append("\">");
            addMeta = false;
          }
        }
        case "meta" -> {
          if (CSP_NONCE_META.matcher(tag).find()) {
            literal.append("<meta name=\"csp-nonce\" content=\"");
            parts.add(literal.toString());
            literal.setLength(0);
            literal.append("\">");
          } else {
            literal.append(tag);
          }
        }
        case "link" -> {
          if (PRELOAD.matcher(tag).find()) {
            appendWithNonce(tag, name, parts, literal);
          } else {
            literal.append(tag);
          }
        }
        default -> appendWithNonce(tag, name, parts, literal);
      }
    }
    literal.append(html, position, html.length());
    parts.add(literal.toString());
    return new IndexHtmlTemplate(parts);
  }

  private static void appendWithNonce(
      String tag, String name, List<String> parts, StringBuilder literal) {
    String withoutNonce = NONCE_ATTRIBUTE.matcher(tag).replaceAll("");
    int afterName = 1 + name.length();
    literal.append(withoutNonce, 0, afterName).append(" nonce=\"");
    parts.add(literal.toString());
    literal.setLength(0);
    literal.append('"').append(withoutNonce, afterName, withoutNonce.length());
  }

  /** The page with {@code nonce} at every place. */
  public byte[] render(String nonce) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) {
      if (i > 0) {
        out.append(nonce);
      }
      out.append(parts.get(i));
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** How many times a rendered page contains the nonce. */
  public int nonceCount() {
    return parts.size() - 1;
  }
}
