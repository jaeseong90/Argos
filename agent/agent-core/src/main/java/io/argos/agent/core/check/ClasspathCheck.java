/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.check;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Item 7 of 10 §13.3: the libraries of the application's class path ({@code --classpath} with
 * directories, jars, Spring Boot jars or WARs) against the {@link CompatibilityTable}. Libraries
 * are found by jar file name ({@code spring-boot-3.5.16.jar}), inside a Spring Boot jar ({@code
 * BOOT-INF/lib/}) or a WAR ({@code WEB-INF/lib/}) by entry name, and by the manifest ({@code
 * Implementation-Version}, {@code Bundle-Version}) of a jar whose name has no version ({@code
 * catalina.jar} of a Tomcat {@code lib} directory); a Spring Boot jar's {@code Spring-Boot-Version}
 * attribute names the Boot version. Without a servlet API jar, the Servlet API of a detected Tomcat
 * line is shown too. One row per component and version: supported PASS, unverified WARN,
 * unsupported FAIL. Reads only; every scan is bounded.
 */
final class ClasspathCheck {
  static final int ITEM = 7;

  /** Upper bound of libraries collected over all paths. */
  static final int MAX_LIBRARIES = 4096;

  /** Upper bound of entries read from one archive, of files listed in one directory. */
  static final int MAX_ENTRIES = 50_000;

  /** Upper bound of jars opened only to read their manifest (unversioned names). */
  static final int MAX_MANIFEST_READS = 256;

  /** Upper bound of component rows. */
  static final int MAX_ROWS = 40;

  /** A library found on the class path. */
  static final class Library {
    final String artifact;
    final String version;
    final String origin;

    Library(String artifact, String version, String origin) {
      this.artifact = artifact;
      this.version = version;
      this.origin = origin;
    }

    @Override
    public String toString() {
      return artifact + ":" + version + " (" + origin + ")";
    }
  }

  private ClasspathCheck() {}

  /** The item 7 rows; SKIP without {@code --classpath}. */
  static List<CheckRow> rows(List<File> paths, CompatibilityTable table, int jdkFeature) {
    List<CheckRow> rows = new ArrayList<CheckRow>();
    if (paths.isEmpty()) {
      rows.add(
          CheckRow.of(
              ITEM,
              "classpath",
              CheckRow.Status.SKIP,
              "no --classpath given (pass the application's jar, WAR or lib directory)"));
      return rows;
    }
    List<String> problems = new ArrayList<String>();
    List<Library> libraries = scan(paths, problems);
    for (String problem : problems) {
      rows.add(CheckRow.of(ITEM, "classpath", CheckRow.Status.WARN, problem));
    }
    Map<String, CheckRow> byKey = new LinkedHashMap<String, CheckRow>();
    boolean servletApi = false;
    for (Library library : libraries) {
      CompatibilityTable.Component component = table.find(library.artifact);
      if (component == null) {
        continue;
      }
      if (component.id.equals("servlet-api")) {
        servletApi = true;
      }
      String key = component.id + "@" + library.version;
      if (!byKey.containsKey(key) && byKey.size() < MAX_ROWS) {
        byKey.put(key, row(component, library.version, library.origin, jdkFeature));
      }
    }
    if (!servletApi) {
      CompatibilityTable.Component servlet = table.byId("servlet-api");
      for (Library library : libraries) {
        CompatibilityTable.Component component = table.find(library.artifact);
        String api = component == null ? null : servletApiOf(component.id, library.version);
        if (servlet != null && api != null && byKey.size() < MAX_ROWS) {
          String key = "servlet-api@" + api;
          if (!byKey.containsKey(key)) {
            byKey.put(
                key,
                row(
                    servlet,
                    api,
                    "from Tomcat " + library.version + ", " + library.origin,
                    jdkFeature));
          }
        }
      }
    }
    rows.addAll(byKey.values());
    if (byKey.isEmpty()) {
      rows.add(
          CheckRow.of(
              ITEM,
              "classpath",
              CheckRow.Status.INFO,
              "no library of the compatibility table found in "
                  + paths.size()
                  + " path(s) ("
                  + libraries.size()
                  + " libraries read)"));
    }
    return rows;
  }

  private static CheckRow row(
      CompatibilityTable.Component component, String version, String origin, int jdkFeature) {
    CompatibilityTable.Verdict verdict = component.verdict(version);
    String shown =
        component.label
            + " "
            + (version == null ? "(version unknown)" : version)
            + " ("
            + origin
            + ")";
    if (verdict == CompatibilityTable.Verdict.SUPPORTED
        && component.id.equals("spring-boot")
        && CompatibilityTable.matches("2.7", CompatibilityTable.parse(version))
        && jdkFeature >= 25) {
      // 01 §8: Boot 2.7 x JDK 25 is unverified.
      return CheckRow.of(
          ITEM,
          "classpath",
          CheckRow.Status.WARN,
          shown + ": unverified on JDK " + jdkFeature + " (Spring Boot 2.7 x JDK 25)");
    }
    switch (verdict) {
      case SUPPORTED:
        return CheckRow.of(ITEM, "classpath", CheckRow.Status.PASS, shown + ": supported");
      case UNSUPPORTED:
        return CheckRow.of(ITEM, "classpath", CheckRow.Status.FAIL, shown + ": unsupported");
      default:
        return CheckRow.of(ITEM, "classpath", CheckRow.Status.WARN, shown + ": unverified");
    }
  }

  /** The Servlet API a Tomcat line implements, or null. */
  static String servletApiOf(String componentId, String tomcatVersion) {
    if (!componentId.equals("tomcat")) {
      return null;
    }
    int[] v = CompatibilityTable.parse(tomcatVersion);
    if (v.length == 0) {
      return null;
    }
    if (v[0] == 8 && v.length > 1 && v[1] == 5) {
      return "3.1";
    }
    if (v[0] == 9) {
      return "4.0";
    }
    if (v[0] == 10 && v.length > 1 && v[1] == 0) {
      return "5.0";
    }
    if (v[0] == 10) {
      return "6.0";
    }
    if (v[0] == 11) {
      return "6.1";
    }
    return null;
  }

  /** The libraries of {@code paths}; unreadable or missing paths go to {@code problems}. */
  static List<Library> scan(List<File> paths, List<String> problems) {
    List<Library> out = new ArrayList<Library>();
    int[] manifestReads = {0};
    for (File path : paths) {
      if (!path.exists()) {
        problems.add("--classpath " + path.getPath() + " does not exist");
      } else if (path.isDirectory()) {
        scanDirectory(path, out, problems, manifestReads);
        for (String sub : new String[] {"WEB-INF/lib", "BOOT-INF/lib", "lib"}) {
          File nested = new File(path, sub);
          if (nested.isDirectory()) {
            scanDirectory(nested, out, problems, manifestReads);
          }
        }
      } else {
        scanArchive(path, out, problems);
      }
    }
    return out;
  }

  private static void scanDirectory(
      File dir, List<Library> out, List<String> problems, int[] manifestReads) {
    String[] names = dir.list();
    if (names == null) {
      problems.add("--classpath " + dir.getPath() + " cannot be listed");
      return;
    }
    Arrays.sort(names);
    for (int i = 0; i < names.length && i < MAX_ENTRIES && out.size() < MAX_LIBRARIES; i++) {
      String name = names[i];
      if (!isArchive(name)) {
        continue;
      }
      File file = new File(dir, name);
      Library library = fromFileName(name, dir.getName() + "/" + name);
      if (library.version == null && file.isFile() && manifestReads[0] < MAX_MANIFEST_READS) {
        manifestReads[0]++;
        String version = manifestVersion(file);
        library = new Library(library.artifact, version, library.origin);
      }
      out.add(library);
    }
  }

  private static void scanArchive(File file, List<Library> out, List<String> problems) {
    try (JarFile jar = new JarFile(file, false)) {
      Library self = fromFileName(file.getName(), file.getName());
      Manifest manifest = jar.getManifest();
      Attributes main = manifest == null ? null : manifest.getMainAttributes();
      if (self.version == null && main != null) {
        self = new Library(self.artifact, versionOf(main), self.origin);
      }
      out.add(self);
      String boot = main == null ? null : main.getValue("Spring-Boot-Version");
      if (boot != null && !boot.trim().isEmpty()) {
        out.add(new Library("spring-boot", boot.trim(), file.getName() + " manifest"));
      }
      Enumeration<? extends ZipEntry> entries = jar.entries();
      int seen = 0;
      while (entries.hasMoreElements() && seen < MAX_ENTRIES && out.size() < MAX_LIBRARIES) {
        seen++;
        String name = entries.nextElement().getName();
        String prefix = null;
        if (name.startsWith("BOOT-INF/lib/")) {
          prefix = "BOOT-INF/lib/";
        } else if (name.startsWith("WEB-INF/lib/")) {
          prefix = "WEB-INF/lib/";
        }
        if (prefix == null) {
          continue;
        }
        String simple = name.substring(prefix.length());
        if (simple.indexOf('/') >= 0 || !isArchive(simple)) {
          continue;
        }
        out.add(fromFileName(simple, file.getName()));
      }
    } catch (IOException | RuntimeException e) {
      problems.add(
          "--classpath "
              + file.getPath()
              + " cannot be read as a jar ("
              + e.getClass().getSimpleName()
              + ")");
    }
  }

  private static boolean isArchive(String name) {
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    return lower.endsWith(".jar") || lower.endsWith(".war");
  }

  /**
   * Artifact and version of a jar file name: {@code tomcat-embed-core-10.1.55.jar} is {@code
   * tomcat-embed-core} and {@code 10.1.55} (the version starts after the first {@code -} that a
   * digit follows); {@code catalina.jar} has no version.
   */
  static Library fromFileName(String fileName, String origin) {
    String name = fileName;
    int dot = name.lastIndexOf('.');
    if (dot > 0) {
      name = name.substring(0, dot);
    }
    for (int i = 1; i + 1 < name.length(); i++) {
      if (name.charAt(i) == '-' && Character.isDigit(name.charAt(i + 1))) {
        return new Library(name.substring(0, i), name.substring(i + 1), origin);
      }
    }
    return new Library(name, null, origin);
  }

  private static String manifestVersion(File file) {
    try (JarFile jar = new JarFile(file, false)) {
      Manifest manifest = jar.getManifest();
      return manifest == null ? null : versionOf(manifest.getMainAttributes());
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static String versionOf(Attributes main) {
    for (String key : new String[] {"Implementation-Version", "Bundle-Version"}) {
      String value = main.getValue(key);
      if (value != null && !value.trim().isEmpty()) {
        return value.trim();
      }
    }
    return null;
  }
}
