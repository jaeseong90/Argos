/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A hosts file for one application JVM ({@code -Djdk.net.hosts.file=<file>}, JDK 9+): with that
 * property the JDK resolves names from this file only, so a scenario can make the collector's name
 * unresolvable and resolvable again without administrator rights or the system's {@code /etc/hosts}
 * (18 §11.1 T-CH-006 "/etc/hosts 제거"). The file always maps {@code localhost} and this machine's
 * host name to the loopback address (the JVM and the agent look them up); the scenario adds and
 * removes its own names. Writes replace the file atomically.
 *
 * <p>The JDK caches lookups ({@code networkaddress.cache.ttl}, negative 10 s): the JVM gets {@link
 * #JVM_OPTIONS} so that a change is seen within about a second.
 */
public final class HostsFile {

  /** A host name: labels of letters, digits and hyphens. */
  static final Pattern NAME =
      Pattern.compile(
          "[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*");

  private final Path file;
  private final Map<String, String> entries = new LinkedHashMap<>();

  /** A hosts file at {@code file} with the base entries, written at once. */
  public HostsFile(Path file) {
    this.file = file;
    entries.put("localhost", "127.0.0.1");
    String self = localHostName();
    if (self != null && !self.equalsIgnoreCase("localhost")) {
      // As the OS spells it and in lower case: the JDK's hosts file lookup may match
      // case-sensitively.
      entries.put(self, "127.0.0.1");
      entries.put(self.toLowerCase(Locale.ROOT), "127.0.0.1");
    }
    write();
  }

  /** The JVM options of an application that resolves names through this file. */
  public List<String> jvmOptions() {
    List<String> options = new ArrayList<>();
    options.add("-Djdk.net.hosts.file=" + file);
    options.addAll(JVM_OPTIONS);
    return options;
  }

  /** Short DNS cache times (system properties the JDK reads when the security ones are unset). */
  static final List<String> JVM_OPTIONS =
      List.of("-Dsun.net.inetaddr.ttl=1", "-Dsun.net.inetaddr.negative.ttl=1");

  /** The file. */
  public Path file() {
    return file;
  }

  /** Maps {@code name} to {@code address} and rewrites the file. */
  public synchronized void put(String name, String address) {
    requireName(name);
    entries.put(name.toLowerCase(Locale.ROOT), address);
    write();
  }

  /** Removes {@code name} and rewrites the file. */
  public synchronized void remove(String name) {
    entries.remove(name.toLowerCase(Locale.ROOT));
    write();
  }

  /** Whether {@code name} is mapped now. */
  public synchronized boolean contains(String name) {
    return entries.containsKey(name.toLowerCase(Locale.ROOT));
  }

  /** The file's text: one {@code <address> <name>} line per entry. */
  public synchronized String text() {
    StringBuilder b = new StringBuilder("# argos chaos test hosts file (jdk.net.hosts.file)\n");
    entries.forEach((name, address) -> b.append(address).append(' ').append(name).append('\n'));
    return b.toString();
  }

  private void write() {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, text(), StandardCharsets.UTF_8);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void requireName(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("not a host name: " + name);
    }
  }

  /** This machine's host name as the JVM sees it, or null. */
  static String localHostName() {
    try {
      String name = InetAddress.getLocalHost().getHostName();
      return name != null && NAME.matcher(name).matches() ? name : null;
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
