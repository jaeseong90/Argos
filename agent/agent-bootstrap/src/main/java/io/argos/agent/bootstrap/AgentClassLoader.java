/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Isolated class loader of the agent (10 §4.2, DSN-10-01): defines the classes stored as {@code
 * inst/<binary name with slashes>.classdata} in the agent jar, straight from the jar (no temporary
 * files, DSN-02-02).
 *
 * <ul>
 *   <li>Parent: the platform class loader (JDK 9+) or the bootstrap class loader (JDK 8), so the
 *       bootstrap API ({@code io.argos.agent.bootstrap.*}) and platform modules such as {@code
 *       java.sql} are visible, the application class path is not.
 *   <li>Delegation: parent first, except for the agent's own packages ({@code core}, {@code
 *       instrumentation}, {@code shaded}), which only this loader defines.
 *   <li>Resources: {@code name} is looked up as {@code inst/name}; a {@code .class} resource maps
 *       to its {@code .classdata} entry so ByteBuddy can read advice and helper class files. The
 *       bootstrap API's class files ({@code io/argos/agent/bootstrap/*.class}, jar root) are served
 *       as a fallback when the parent does not find them (JDK 8).
 * </ul>
 *
 * <p>This class lives in the bootstrap package because a loader cannot load its own class (10 §2
 * lists it under core.boot; see the WO-02 report). It is package-private: only {@link
 * AgentBootstrap} creates it, and applications cannot instantiate it.
 */
final class AgentClassLoader extends ClassLoader {
  static final String INST = "inst/";
  static final String CLASSDATA = ".classdata";
  private static final String BOOTSTRAP_PATH = "io/argos/agent/bootstrap/";

  /** Packages that only the agent class loader may define (never delegated to the parent). */
  private static final String[] OWN_PACKAGES = {
    "io.argos.agent.core.", "io.argos.agent.instrumentation.", "io.argos.agent.shaded."
  };

  static {
    registerAsParallelCapable();
  }

  private final JarFile jar;
  private final String jarUrlPrefix;
  private final ProtectionDomain domain;

  AgentClassLoader(JarFile jar, File jarPath, ClassLoader parent) throws MalformedURLException {
    super(parent);
    this.jar = jar;
    URL location = jarPath.toURI().toURL();
    this.jarUrlPrefix = "jar:" + location.toExternalForm() + "!/";
    // Dynamic domain: under a security manager the policy grants permissions to the jar.
    this.domain =
        new ProtectionDomain(new CodeSource(location, (Certificate[]) null), null, this, null);
  }

  /** True for classes of the agent's own packages (core, instrumentation, shaded). */
  static boolean isOwnClass(String name) {
    for (String prefix : OWN_PACKAGES) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (!isOwnClass(name)) {
      return super.loadClass(name, resolve);
    }
    synchronized (getClassLoadingLock(name)) {
      Class<?> loaded = findLoadedClass(name);
      if (loaded == null) {
        loaded = findClass(name);
      }
      if (resolve) {
        resolveClass(loaded);
      }
      return loaded;
    }
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    JarEntry entry = jar.getJarEntry(INST + name.replace('.', '/') + CLASSDATA);
    if (entry == null) {
      throw new ClassNotFoundException(name);
    }
    byte[] bytes;
    try {
      bytes = read(entry);
    } catch (IOException e) {
      throw new ClassNotFoundException(name, e);
    }
    definePackageOf(name);
    return defineClass(name, bytes, 0, bytes.length, domain);
  }

  private void definePackageOf(String className) {
    int dot = className.lastIndexOf('.');
    if (dot < 0) {
      return;
    }
    String packageName = className.substring(0, dot);
    if (getPackage(packageName) != null) {
      return;
    }
    try {
      definePackage(packageName, null, null, null, null, null, null, null);
    } catch (IllegalArgumentException alreadyDefined) {
      // Another thread defined the package first.
    }
  }

  /** The {@code inst/} jar entry name of a resource, or null for names outside the agent. */
  static String entryName(String resource) {
    String name = resource.startsWith("/") ? resource.substring(1) : resource;
    if (name.isEmpty()) {
      return null;
    }
    if (name.endsWith(".class")) {
      return INST + name.substring(0, name.length() - ".class".length()) + CLASSDATA;
    }
    return INST + name;
  }

  /**
   * Class files of the bootstrap API at the jar root ({@code io/argos/agent/bootstrap/*.class}).
   */
  static boolean isBootstrapClassFile(String resource) {
    String name = resource.startsWith("/") ? resource.substring(1) : resource;
    return name.startsWith(BOOTSTRAP_PATH) && name.endsWith(".class");
  }

  @Override
  protected URL findResource(String name) {
    String entry = entryName(name);
    if (entry == null) {
      return null;
    }
    if (jar.getJarEntry(entry) == null) {
      // JDK 8 does not expose jars appended to the bootstrap search path as bootstrap resources;
      // ByteBuddy's type pool still needs the bootstrap API class files to read advice classes.
      if (!isBootstrapClassFile(name)) {
        return null;
      }
      entry = name.startsWith("/") ? name.substring(1) : name;
      if (jar.getJarEntry(entry) == null) {
        return null;
      }
    }
    try {
      return new URL(jarUrlPrefix + entry);
    } catch (MalformedURLException e) {
      return null;
    }
  }

  @Override
  protected Enumeration<URL> findResources(String name) {
    URL url = findResource(name);
    return url == null
        ? Collections.<URL>emptyEnumeration()
        : Collections.enumeration(Collections.singletonList(url));
  }

  /**
   * Parent first like {@link ClassLoader#getResource}; resources of this jar are read from the
   * already open {@link JarFile} instead of a {@code jar:} URL connection.
   */
  @Override
  public InputStream getResourceAsStream(String name) {
    URL url = getResource(name);
    if (url == null) {
      return null;
    }
    String external = url.toExternalForm();
    try {
      if (external.startsWith(jarUrlPrefix)) {
        JarEntry entry = jar.getJarEntry(external.substring(jarUrlPrefix.length()));
        return entry == null ? null : jar.getInputStream(entry);
      }
      return url.openStream();
    } catch (IOException e) {
      return null;
    }
  }

  private byte[] read(JarEntry entry) throws IOException {
    try (InputStream in = jar.getInputStream(entry)) {
      long size = entry.getSize();
      ByteArrayOutputStream out = new ByteArrayOutputStream(size > 0 ? (int) size : 8192);
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) >= 0) {
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    }
  }

  @Override
  public String toString() {
    return "ArgosAgentClassLoader";
  }
}
