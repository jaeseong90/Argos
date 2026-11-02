/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Static facts of an agent jar: size, class counts, compressed bytes per shaded library, classes
 * that reference {@code sun/misc/Unsafe} or the JUL entry points ({@code java/util/logging/Logger},
 * {@code LogManager}), own-code string constants rewritten into the relocated namespace, and the
 * check-shading result.
 */
record JarFacts(
    Path jar,
    long size,
    int classes,
    int ownClasses,
    int shadedClasses,
    Map<String, long[]> libraries,
    List<String> unsafeClasses,
    List<String> julClasses,
    String checkShading,
    List<String> checkShadingFailures,
    int ownRelocatedLiterals,
    List<String> ownRelocatedLiteralLines) {

  private static final String SHADED = "inst/io/argos/agent/shaded/";

  static JarFacts of(Path jar) throws IOException {
    return of(jar, jar.getParent().resolve("reports"));
  }

  /** Facts of {@code jar}; check-shading output is read from {@code reports/check-shading.txt}. */
  static JarFacts of(Path jar, Path reports) throws IOException {
    int classes = 0;
    int own = 0;
    int shaded = 0;
    Map<String, long[]> libraries = new TreeMap<>();
    List<String> unsafe = new ArrayList<>();
    List<String> jul = new ArrayList<>();
    List<String> literals = new ArrayList<>();
    byte[] unsafeBytes = "sun/misc/Unsafe".getBytes(StandardCharsets.US_ASCII);
    // JUL entry points (Level and LogRecord alone do not initialise the LogManager).
    byte[] julBytes = "java/util/logging/Logger".getBytes(StandardCharsets.US_ASCII);
    byte[] logManagerBytes = "java/util/logging/LogManager".getBytes(StandardCharsets.US_ASCII);
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName();
        boolean isClass = name.endsWith(".class") || name.endsWith(".classdata");
        String library = library(name);
        long[] totals = libraries.computeIfAbsent(library, k -> new long[3]);
        totals[0] += Math.max(0, entry.getCompressedSize());
        totals[1] += Math.max(0, entry.getSize());
        if (!isClass) {
          continue;
        }
        totals[2]++;
        classes++;
        if (name.startsWith(SHADED)) {
          shaded++;
        } else {
          own++;
        }
        byte[] bytes;
        try (InputStream in = zip.getInputStream(entry)) {
          bytes = in.readAllBytes();
        }
        String className = className(name);
        if (contains(bytes, unsafeBytes)) {
          unsafe.add(className);
        }
        if (contains(bytes, julBytes) || contains(bytes, logManagerBytes)) {
          jul.add(className);
        }
        // ADR-0003 C-23 side effect: own literals that Shadow rewrote into the relocated namespace.
        if (!name.startsWith(SHADED)) {
          for (String constant : ClassConstants.strings(bytes)) {
            if (constant.startsWith("io.argos.agent.shaded.")) {
              literals.add(name + ": \"" + constant + "\"");
            }
          }
        }
      }
    }
    String shading = "-";
    List<String> failures = new ArrayList<>();
    Path shadingFile = reports.resolve("check-shading.txt");
    if (Files.exists(shadingFile)) {
      List<String> lines = Files.readAllLines(shadingFile, StandardCharsets.UTF_8);
      for (String line : lines) {
        if (line.startsWith("FAIL [")) {
          failures.add(line);
        }
        if (line.startsWith("exit=")) {
          shading = line.equals("exit=0") ? "PASS" : "FAIL (" + line + ")";
        }
      }
    }
    return new JarFacts(
        jar,
        Files.size(jar),
        classes,
        own,
        shaded,
        libraries,
        List.copyOf(new TreeSet<>(unsafe)),
        List.copyOf(new TreeSet<>(jul)),
        shading,
        failures,
        literals.size(),
        List.copyOf(new TreeSet<>(literals)));
  }

  /** Library group of an entry: the first package levels below the shaded prefix, or "own". */
  static String library(String name) {
    if (!name.startsWith(SHADED)) {
      if (name.startsWith("inst/io/argos/agent/transportpoc/proto/")) {
        return "generated protocol (proto snapshot)";
      }
      if (name.startsWith("inst/io/argos/agent/transportpoc/")
          || name.startsWith("inst/io/argos/agent/core/transport/")) {
        return "poc sender";
      }
      if (name.startsWith("inst/io/argos/agent/") || name.startsWith("io/argos/agent/")) {
        return "argos (bootstrap, core, modules)";
      }
      return "META-INF and resources";
    }
    String rest = name.substring(SHADED.length());
    String[] parts = rest.split("/", -1);
    if (parts.length < 3) {
      return "shaded " + parts[0];
    }
    String top = parts[0] + "." + parts[1];
    if (top.equals("com.google")) {
      return "shaded " + top + "." + parts[2];
    }
    if (top.equals("io.grpc") && (parts[2].equals("netty") || parts[2].equals("okhttp"))) {
      return "shaded " + top + "." + parts[2];
    }
    return "shaded " + top;
  }

  static String className(String entry) {
    String name = entry.startsWith("inst/") ? entry.substring(5) : entry;
    name =
        name.endsWith(".classdata")
            ? name.substring(0, name.length() - 10)
            : name.substring(0, name.length() - 6);
    return name.replace('/', '.');
  }

  private static boolean contains(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }
}
