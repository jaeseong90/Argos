/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import io.argos.storage.clickhouse.ArgosClickHouse;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.testcontainers.containers.Container.ExecResult;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Files inside a running ClickHouse container: the merged ("preprocessed") configuration that the
 * server actually uses, the metadata of the system tables and the listening sockets. Read as the
 * {@code clickhouse} user. The preprocessed users.xml contains the password hashes: callers assert
 * on element names only and never record the content.
 */
final class ServerFiles {

  private static final String PREPROCESSED = "/var/lib/clickhouse/preprocessed_configs/";

  /** Docker's embedded DNS resolver of user-defined networks (127.0.0.11), not ClickHouse. */
  private static final String DOCKER_DNS = "0B00007F";

  private ServerFiles() {}

  /** Root element of a merged configuration file (config.xml or users.xml). */
  static Element preprocessed(ArgosClickHouse ch, String file) {
    return parse(read(ch, PREPROCESSED + file));
  }

  /** Text of a file in the container. */
  static String read(ArgosClickHouse ch, String path) {
    ExecResult result = ch.execAsClickHouse("cat", path);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException("cat " + path + ": " + result.getStderr());
    }
    return result.getStdout();
  }

  /** Output of a shell command in the container (exit code 0 expected). */
  static String shell(ArgosClickHouse ch, String command) {
    ExecResult result = ch.execAsClickHouse("sh", "-c", command);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(command + ": " + result.getStderr());
    }
    return result.getStdout();
  }

  /** The child elements of {@code parent}. */
  static List<Element> children(Element parent) {
    List<Element> children = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e) {
        children.add(e);
      }
    }
    return children;
  }

  /** The first child element named {@code name}, or null. */
  static Element child(Element parent, String name) {
    for (Element e : children(parent)) {
      if (e.getTagName().equals(name)) {
        return e;
      }
    }
    return null;
  }

  /**
   * TCP ports in state LISTEN inside the container network namespace (/proc/net/tcp and tcp6),
   * without Docker's embedded DNS resolver.
   */
  static Set<Integer> listeningPorts(ArgosClickHouse ch) {
    String table = shell(ch, "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null || true");
    Set<Integer> ports = new TreeSet<>();
    for (String line : table.lines().toList()) {
      String[] f = line.trim().split("\\s+", -1);
      // sl local_address rem_address st ...; st 0A = LISTEN
      if (f.length < 4 || !f[3].equals("0A") || !f[1].contains(":")) {
        continue;
      }
      String address = f[1].substring(0, f[1].indexOf(':'));
      if (address.equals(DOCKER_DNS)) {
        continue;
      }
      ports.add(Integer.parseInt(f[1].substring(f[1].indexOf(':') + 1), 16));
    }
    return ports;
  }

  private static Element parse(String xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      return factory
          .newDocumentBuilder()
          .parse(new InputSource(new StringReader(xml)))
          .getDocumentElement();
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new IllegalStateException("Cannot parse the preprocessed configuration", e);
    }
  }
}
