/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.smoke;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Jar and class counts of a packaged app (WO-01 report, N-04/N-27 baseline): dependency jars in
 * BOOT-INF/lib or WEB-INF/lib, classes in those jars plus the app's own classes. For a WAR the jars
 * of the Tomcat distribution (lib/) are counted separately.
 */
final class ArtifactStats {

  private ArtifactStats() {}

  static Map<String, Object> of(SmokeOptions options) throws IOException {
    Map<String, Object> stats = new LinkedHashMap<>();
    if (options.artifact == null) {
      return stats;
    }
    String libPrefix;
    String classesPrefix;
    switch (options.kind) {
      case "boot-jar" -> {
        libPrefix = "BOOT-INF/lib/";
        classesPrefix = "BOOT-INF/classes/";
      }
      case "war" -> {
        libPrefix = "WEB-INF/lib/";
        classesPrefix = "WEB-INF/classes/";
      }
      default -> {
        libPrefix = null;
        classesPrefix = "";
      }
    }
    int jars = 0;
    long libraryClasses = 0;
    long appClasses = 0;
    try (ZipFile zip = new ZipFile(options.artifact.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (libPrefix != null && name.startsWith(libPrefix) && name.endsWith(".jar")) {
          jars++;
          try (InputStream in = zip.getInputStream(entry)) {
            libraryClasses += countClasses(in);
          }
        } else if (name.startsWith(classesPrefix) && isClass(name)) {
          appClasses++;
        }
      }
    }
    stats.put("artifact", options.artifact.getFileName().toString());
    stats.put("artifactBytes", Files.size(options.artifact));
    stats.put("dependencyJars", jars);
    stats.put("dependencyClasses", libraryClasses);
    stats.put("appClasses", appClasses);
    stats.put("totalClasses", libraryClasses + appClasses);
    if (options.tomcatHome != null) {
      int tomcatJars = 0;
      long tomcatClasses = 0;
      for (String dir : new String[] {"lib", "bin"}) {
        Path path = options.tomcatHome.resolve(dir);
        if (!Files.isDirectory(path)) {
          continue;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(path, "*.jar")) {
          for (Path jar : files) {
            tomcatJars++;
            try (InputStream in = Files.newInputStream(jar)) {
              tomcatClasses += countClasses(in);
            }
          }
        }
      }
      stats.put("containerJars", tomcatJars);
      stats.put("containerClasses", tomcatClasses);
    }
    return stats;
  }

  /** Counts class entries of a jar read as a stream (nested jars are stored in the archive). */
  static long countClasses(InputStream jar) throws IOException {
    long count = 0;
    ZipInputStream zip = new ZipInputStream(jar);
    ZipEntry entry;
    while ((entry = zip.getNextEntry()) != null) {
      if (isClass(entry.getName())) {
        count++;
      }
    }
    return count;
  }

  private static boolean isClass(String name) {
    return name.endsWith(".class")
        && !name.endsWith("module-info.class")
        && !name.endsWith("package-info.class");
  }
}
