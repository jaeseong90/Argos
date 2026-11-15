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
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

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
 * <p>Start-path statistics (ADR-0009 C-79, 10 §4.3): the loader counts the classes it defines, the
 * class bytes it reads and the time spent reading and defining them, and records the names of the
 * classes defined on the premain thread until the start sequence ends ({@link
 * #startRecording(Thread)}, {@link #stopRecording()}; at most {@value #MAX_RECORDED} names, dropped
 * when the recording stops). The core reads them through {@link
 * AgentControl#classLoaderStatistics()} and {@link AgentControl#stopPremainRecording()} for {@code
 * debug.transform-timing} and the premain class budget test.
 *
 * <p>This class lives in the bootstrap package because a loader cannot load its own class (10 §2
 * lists it under core.boot; WO-02 report D-1, kept by ADR-0010 C-93). It is package-private: only
 * {@link AgentBootstrap} creates it, and applications cannot instantiate it.
 */
final class AgentClassLoader extends ClassLoader {
  static final String INST = "inst/";
  static final String CLASSDATA = ".classdata";
  private static final String BOOTSTRAP_PATH = "io/argos/agent/bootstrap/";

  /** True when the JVM registered this loader as parallel capable (always on Java 7+). */
  static final boolean PARALLEL_CAPABLE;

  static {
    // One lock per class name instead of the loader: agent threads that load classes while the
    // premain thread is still starting (argos-sender at P7) and application threads that meet
    // their first transformation do not wait for each other's unrelated classes (C-79, N-04).
    PARALLEL_CAPABLE = ClassLoader.registerAsParallelCapable();
  }

  /** Upper bound of the class names recorded on the premain thread (bounded memory, 10 §14). */
  static final int MAX_RECORDED = 8192;

  /** Upper bound of one class file or resource read from {@code inst/} (bounded memory). */
  static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;

  /** What {@link #stopRecording()} returns once the recording was dropped. */
  private static final String[] NO_NAMES = new String[0];

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

  private final AtomicLong definedClasses = new AtomicLong();
  private final AtomicLong classBytes = new AtomicLong();
  private final AtomicLong readNanos = new AtomicLong();
  private final AtomicLong defineNanos = new AtomicLong();

  /** The thread whose definitions are recorded, null when not recording. */
  private volatile Thread recordingThread;

  /**
   * The recorded names: written by {@link #recordingThread} only, read and dropped by it in {@link
   * #stopRecording()}; null afterwards, so the array and the names are not kept for the JVM's
   * lifetime (bounded memory, 10 §14).
   */
  private String[] recorded;

  private int recordedCount;
  private int recordedOverflow;

  AgentClassLoader(JarFile jar, File jarPath, ClassLoader parent) throws MalformedURLException {
    this(jar, jarPath, parent, MAX_RECORDED);
  }

  /** {@code recordLimit}: upper bound of recorded names (tests use a small one). */
  AgentClassLoader(JarFile jar, File jarPath, ClassLoader parent, int recordLimit)
      throws MalformedURLException {
    super(parent);
    this.jar = jar;
    this.recorded = new String[Math.max(0, recordLimit)];
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
    long start = System.nanoTime();
    byte[] bytes;
    try {
      bytes = read(entry);
    } catch (IOException e) {
      throw new ClassNotFoundException(name, e);
    }
    long read = System.nanoTime();
    definePackageOf(name);
    Class<?> defined = defineClass(name, bytes, 0, bytes.length, domain);
    readNanos.addAndGet(read - start);
    defineNanos.addAndGet(System.nanoTime() - read);
    definedClasses.incrementAndGet();
    classBytes.addAndGet(bytes.length);
    if (recordingThread == Thread.currentThread()) {
      String[] names = recorded; // null once stopRecording() dropped it: nothing is recorded
      if (names != null) {
        if (recordedCount < names.length) {
          names[recordedCount++] = name;
        } else {
          recordedOverflow++;
        }
      }
    }
    return defined;
  }

  /**
   * {@code {classes defined, class bytes read, nanoseconds reading, nanoseconds defining}} since
   * this loader was created (C-79). Defining is parsing; verification happens when a class is
   * linked and is not included.
   */
  long[] statistics() {
    return new long[] {definedClasses.get(), classBytes.get(), readNanos.get(), defineNanos.get()};
  }

  /** The manifest of the agent jar, read from the jar this loader keeps open (null when none). */
  Manifest manifest() throws IOException {
    return jar.getManifest();
  }

  /**
   * Records the names of the classes this loader defines on {@code thread} (the premain thread).
   */
  void startRecording(Thread thread) {
    recordingThread = thread;
  }

  /**
   * Stops recording and returns the recorded names in definition order; when more than {@value
   * #MAX_RECORDED} were defined, a last element {@code "... <n> more"} follows. Called on the
   * recording thread. The recording is dropped (bounded memory, 10 §14): classes defined later are
   * never recorded, even after another {@link #startRecording(Thread)}, and a second call returns
   * an empty array.
   */
  String[] stopRecording() {
    recordingThread = null;
    String[] kept = recorded;
    int count = recordedCount;
    int overflow = recordedOverflow;
    recorded = null;
    recordedCount = 0;
    recordedOverflow = 0;
    if (kept == null) {
      return NO_NAMES;
    }
    String[] names = new String[count + (overflow > 0 ? 1 : 0)];
    System.arraycopy(kept, 0, names, 0, count);
    if (overflow > 0) {
      names[count] = "... " + overflow + " more";
    }
    return names;
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

  /**
   * The bytes of {@code entry}: straight into an array of the entry's size when the jar records it
   * (every entry of the agent jar does: no buffer, no copy, C-79), else through a buffer. At most
   * {@value #MAX_CLASS_BYTES} bytes (bounded memory, 10 §14).
   */
  private byte[] read(JarEntry entry) throws IOException {
    try (InputStream in = jar.getInputStream(entry)) {
      long size = entry.getSize();
      if (size > MAX_CLASS_BYTES) {
        throw new IOException(entry.getName() + " is larger than " + MAX_CLASS_BYTES + " bytes");
      }
      if (size >= 0) {
        byte[] bytes = new byte[(int) size];
        int offset = 0;
        while (offset < bytes.length) {
          int n = in.read(bytes, offset, bytes.length - offset);
          if (n < 0) {
            throw new IOException(entry.getName() + " is shorter than its recorded size");
          }
          offset += n;
        }
        if (in.read() >= 0) {
          throw new IOException(entry.getName() + " is longer than its recorded size");
        }
        return bytes;
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) >= 0) {
        if (out.size() + n > MAX_CLASS_BYTES) {
          throw new IOException(entry.getName() + " is larger than " + MAX_CLASS_BYTES + " bytes");
        }
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
