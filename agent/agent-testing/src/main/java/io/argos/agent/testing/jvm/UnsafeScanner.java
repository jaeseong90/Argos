/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jvm;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@code sun.misc.Unsafe} references in an agent jar (T-BLD-004, F-A35, 18 §5.10): parses the
 * constant pool of every {@code .class} and {@code inst/**.classdata} entry and reports the classes
 * with a UTF-8 constant that names {@code sun/misc/Unsafe} (type and descriptor references) or
 * {@code sun.misc.Unsafe} (reflective lookup by name). These are the same two patterns that {@code
 * test/agent/check-shading.sh} searches for (19 §5.6), restricted to the constant pool where every
 * such reference lives.
 *
 * <p>Allow-list: one source of truth for both checks, {@code test/agent/unsafe-allowlist.txt}
 * (format and approval rule in that file; it is empty: ADR-0003 removes Unsafe users instead of
 * allowing them). 18 §5.10 names it {@code fixtures/unsafe-allowlist.txt}; agent-testing reads the
 * repository file through the system property {@code argos.test.unsafeAllowlist} so that no second
 * copy can drift.
 */
public final class UnsafeScanner {
  private static final String TYPE = "sun/misc/Unsafe";
  private static final String NAME = "sun.misc.Unsafe";

  private UnsafeScanner() {}

  /** Classes of {@code jar} that reference {@code sun.misc.Unsafe} (binary names, sorted). */
  public static Set<String> scan(Path jar) {
    Set<String> classes = new TreeSet<>();
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (entry.isDirectory() || !(name.endsWith(".class") || name.endsWith(".classdata"))) {
          continue;
        }
        byte[] bytes;
        try (InputStream in = zip.getInputStream(entry)) {
          bytes = in.readAllBytes();
        }
        if (referencesUnsafe(bytes)) {
          classes.add(className(name));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return classes;
  }

  /** {@link #scan} minus the allow-listed classes: the T-BLD-004 violations. */
  public static Set<String> violations(Path jar, Path allowlist) {
    Set<String> found = scan(jar);
    found.removeAll(readAllowlist(allowlist));
    return found;
  }

  /** True when the constant pool of {@code classFile} names {@code sun.misc.Unsafe}. */
  public static boolean referencesUnsafe(byte[] classFile) {
    for (String constant : utf8Constants(classFile)) {
      if (constant.contains(TYPE) || constant.contains(NAME)) {
        return true;
      }
    }
    return false;
  }

  /** The UTF-8 constants of a class file's constant pool. */
  static List<String> utf8Constants(byte[] classFile) {
    List<String> constants = new ArrayList<>();
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile))) {
      if (in.readInt() != 0xCAFEBABE) {
        throw new IllegalArgumentException("not a class file");
      }
      in.readUnsignedShort();
      in.readUnsignedShort();
      int count = in.readUnsignedShort();
      for (int index = 1; index < count; index++) {
        int tag = in.readUnsignedByte();
        switch (tag) {
          case 1 -> constants.add(in.readUTF());
          case 3, 4 -> in.skipNBytes(4);
          case 5, 6 -> {
            in.skipNBytes(8);
            index++;
          }
          case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
          case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
          case 15 -> in.skipNBytes(3);
          default -> throw new IllegalArgumentException("unknown constant pool tag " + tag);
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("truncated class file", e);
    }
    return constants;
  }

  /**
   * The allow-list in the format of {@code test/agent/check-shading.sh}: {@code #} comments, blank
   * lines ignored, entries with dots or slashes, with or without {@code .class}/{@code .classdata}.
   */
  public static Set<String> readAllowlist(Path allowlist) {
    Set<String> allowed = new TreeSet<>();
    try {
      for (String line : Files.readAllLines(allowlist, StandardCharsets.UTF_8)) {
        String entry = line.replace("\r", "");
        int comment = entry.indexOf('#');
        if (comment >= 0) {
          entry = entry.substring(0, comment);
        }
        entry = entry.strip();
        if (!entry.isEmpty()) {
          allowed.add(className(entry));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return allowed;
  }

  /** {@code inst/a/b/C.classdata} → {@code a.b.C}. */
  static String className(String entry) {
    String name = entry.startsWith("inst/") ? entry.substring("inst/".length()) : entry;
    name = name.replaceAll("\\.(class|classdata)$", "");
    return name.replace('/', '.');
  }
}
