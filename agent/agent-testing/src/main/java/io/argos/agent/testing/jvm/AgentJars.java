/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Agent jar variants for tests. {@link #withVersion} makes a release-flavoured copy of a
 * development jar: the release-build marker of the agent is the manifest's {@code
 * Implementation-Version} without {@code -SNAPSHOT} (agent-core {@code AgentInfo.isReleaseBuild},
 * set by {@code -Pversion=X.Y.Z} on tag builds, 19 §7.2), so changing only that attribute shows
 * what a release build does with {@code argos.test.*} (T-BLD-010) without a tag build. The marker
 * fails closed: a packaged jar without the attribute also counts as a release build.
 */
public final class AgentJars {
  private AgentJars() {}

  /** The {@code Implementation-Version} of {@code jar}'s manifest (null when absent). */
  public static String version(Path jar) {
    try (JarFile file = new JarFile(jar.toFile(), false)) {
      Manifest manifest = file.getManifest();
      return manifest == null
          ? null
          : manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Copies {@code jar} to {@code target} with {@code Implementation-Version: version}; every other
   * entry and manifest attribute is copied unchanged.
   */
  public static Path withVersion(Path jar, Path target, String version) {
    try (JarFile source = new JarFile(jar.toFile(), false)) {
      Manifest manifest = new Manifest(source.getManifest());
      manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
      Files.createDirectories(target.toAbsolutePath().getParent());
      try (OutputStream file = Files.newOutputStream(target);
          JarOutputStream out = new JarOutputStream(file, manifest)) {
        Enumeration<JarEntry> entries = source.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          if (entry.getName().equalsIgnoreCase(JarFile.MANIFEST_NAME)) {
            continue;
          }
          out.putNextEntry(new JarEntry(entry.getName()));
          try (InputStream in = source.getInputStream(entry)) {
            in.transferTo(out);
          }
          out.closeEntry();
        }
      }
      return target;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
