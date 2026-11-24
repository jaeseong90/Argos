/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.check;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigLoader;
import io.argos.agent.core.config.LoadedConfig;
import io.argos.agent.core.config.TokenHolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Items 1, 2 and 8 of 10 §13.3 from the configuration as the agent would load it ({@link
 * ConfigLoader}, the same sources and the same messages as at premain):
 *
 * <ul>
 *   <li>1 {@code config}: FAIL for a missing required key ({@code server.address}, {@code
 *       app.name}) or a {@code --config} file that does not exist; WARN for unknown keys, invalid
 *       values and a missing {@code app.env}; otherwise PASS.
 *   <li>2 {@code token}: FAIL when there is no token or it is not {@code argos_} plus 43 characters
 *       (16 §2.1); WARN for a plaintext {@code server.token} (16 §2.6); PASS for a token file or
 *       {@code ARGOS_TOKEN}. Shown as {@value TokenHolder#MASK} only (C-94).
 *   <li>8 {@code log-dir}: the directory the agent would log to is writable or can be created (FAIL
 *       for a configured {@code log.dir} that cannot be written: the agent would fall back to
 *       stderr); {@code token-file}: WARN when group or others have any permission on the file
 *       (0600 recommended), INFO with the reason on a file system without POSIX permissions.
 * </ul>
 */
final class ConfigChecks {
  /**
   * Reads a file's POSIX permissions ({@code rw-------}), or null when the file system has none.
   */
  interface PermissionReader {
    String posixPermissions(File file) throws IOException;
  }

  static final PermissionReader POSIX =
      new PermissionReader() {
        @Override
        public String posixPermissions(File file) throws IOException {
          PosixFileAttributeView view =
              Files.getFileAttributeView(file.toPath(), PosixFileAttributeView.class);
          if (view == null) {
            return null;
          }
          return PosixFilePermissions.toString(view.readAttributes().permissions());
        }
      };

  private ConfigChecks() {}

  /** Item 1. {@code yaml} is the file that was read (null for none). */
  static CheckRow config(LoadedConfig loaded, File yaml, boolean yamlRequested) {
    List<String> notes = new ArrayList<String>();
    String source =
        yaml == null
            ? "no argos-agent.yml (system properties and environment only)"
            : yaml.getPath();
    if (yamlRequested && yaml != null && !yaml.isFile()) {
      notes.add("--config " + yaml.getPath() + " does not exist or is not a file");
      return new CheckRow(
          1, "config", CheckRow.Status.FAIL, "configuration file not found: " + source, notes);
    }
    for (String error : loaded.errors()) {
      notes.add(error);
    }
    int errors = notes.size();
    for (String warning : loaded.warnings()) {
      if (!tokenMessage(warning)) {
        notes.add(warning);
      }
    }
    if (errors > 0) {
      return new CheckRow(
          1,
          "config",
          CheckRow.Status.FAIL,
          source + ": " + errors + " required key(s) missing",
          notes);
    }
    if (!notes.isEmpty()) {
      return new CheckRow(
          1, "config", CheckRow.Status.WARN, source + ": " + notes.size() + " warning(s)", notes);
    }
    AgentConfig config = loaded.config();
    return new CheckRow(
        1,
        "config",
        CheckRow.Status.PASS,
        source + ": app '" + config.appName() + "', env '" + config.appEnv() + "'",
        notes);
  }

  /** Warnings about the token, reported by item 2 (and the permission one by item 8). */
  static boolean tokenMessage(String warning) {
    return warning.startsWith("server.token-file ")
        || warning.equals(ConfigLoader.PLAINTEXT_TOKEN_WARNING)
        || warning.equals(ConfigLoader.PLAINTEXT_PROPERTY_TOKEN_WARNING)
        || warning.contains("for server.token ");
  }

  /** Item 2. */
  static CheckRow token(LoadedConfig loaded) {
    TokenHolder token = loaded.token();
    List<String> notes = new ArrayList<String>();
    for (String warning : loaded.warnings()) {
      if (tokenMessage(warning) && !warning.contains("can be read by group or others")) {
        notes.add(warning);
      }
    }
    if (!token.isPresent()) {
      return new CheckRow(
          2,
          "token",
          CheckRow.Status.FAIL,
          "no token: set server.token-file (recommended) or ARGOS_TOKEN",
          notes);
    }
    String from = source(token.source(), loaded.config());
    if (!token.hasValidFormat()) {
      return new CheckRow(
          2,
          "token",
          CheckRow.Status.FAIL,
          TokenHolder.MASK
              + " from "
              + from
              + ": not a token of the form argos_ plus 43 characters",
          notes);
    }
    if (token.source() == TokenHolder.Source.PLAIN) {
      return new CheckRow(
          2,
          "token",
          CheckRow.Status.WARN,
          TokenHolder.MASK
              + " from "
              + from
              + ": stored in plaintext; prefer server.token-file or ARGOS_TOKEN",
          notes);
    }
    return new CheckRow(
        2, "token", CheckRow.Status.PASS, TokenHolder.MASK + " from " + from, notes);
  }

  private static String source(TokenHolder.Source source, AgentConfig config) {
    switch (source) {
      case FILE:
        return "server.token-file " + config.serverTokenFile();
      case ENVIRONMENT:
        return "ARGOS_TOKEN";
      case PLAIN:
        return "server.token";
      default:
        return "nowhere";
    }
  }

  /** Item 8, the log directory: {@code log.dir}, else {@code <jar dir>/../logs}, else temp. */
  static CheckRow logDirectory(AgentConfig config, File agentJar, String tmpDir) {
    String configured = config.logDir();
    if (configured != null) {
      File dir = new File(configured);
      String verdict = writable(dir);
      if (verdict == null) {
        return CheckRow.of(8, "log-dir", CheckRow.Status.PASS, describe(dir));
      }
      return CheckRow.of(
          8,
          "log-dir",
          CheckRow.Status.FAIL,
          "log.dir "
              + dir.getPath()
              + " "
              + verdict
              + "; the agent would log to stderr (10 lines per minute) instead");
    }
    File jarDirectory = agentJar == null ? null : agentJar.getAbsoluteFile().getParentFile();
    File besideJar =
        jarDirectory == null || jarDirectory.getParentFile() == null
            ? null
            : new File(jarDirectory.getParentFile(), "logs");
    if (besideJar != null && writable(besideJar) == null) {
      return CheckRow.of(8, "log-dir", CheckRow.Status.PASS, describe(besideJar) + " (default)");
    }
    File temp = new File(tmpDir == null ? "." : tmpDir, "argos-agent-<pid>");
    return CheckRow.of(
        8,
        "log-dir",
        CheckRow.Status.INFO,
        "log.dir is not set and "
            + (besideJar == null ? "the jar's ../logs" : besideJar.getPath())
            + " is not writable: the agent logs to "
            + temp.getPath());
  }

  private static String describe(File dir) {
    return dir.isDirectory()
        ? dir.getPath() + " is writable"
        : dir.getPath() + " does not exist yet; the agent creates it (0700)";
  }

  /**
   * Null when {@code dir} is a writable directory, or does not exist and its nearest existing
   * parent is a writable directory; else why not. Creates nothing.
   */
  static String writable(File dir) {
    File current = dir.getAbsoluteFile();
    if (current.exists()) {
      if (!current.isDirectory()) {
        return "is not a directory";
      }
      return Files.isWritable(current.toPath()) ? null : "is not writable";
    }
    File parent = current.getParentFile();
    for (int i = 0; parent != null && i < 64; i++) {
      if (parent.exists()) {
        if (!parent.isDirectory()) {
          return "cannot be created (" + parent.getPath() + " is not a directory)";
        }
        return Files.isWritable(parent.toPath())
            ? null
            : "cannot be created (" + parent.getPath() + " is not writable)";
      }
      parent = parent.getParentFile();
    }
    return "cannot be created";
  }

  /** Item 8, the token file permissions (16 §2.6, ST-06-3). */
  static CheckRow tokenFile(AgentConfig config, PermissionReader reader) {
    String path = config.serverTokenFile();
    if (path == null || path.trim().isEmpty()) {
      return CheckRow.of(8, "token-file", CheckRow.Status.SKIP, "server.token-file is not set");
    }
    File file = new File(path.trim());
    if (!file.isFile()) {
      return CheckRow.of(
          8, "token-file", CheckRow.Status.SKIP, file.getPath() + " does not exist (see item 2)");
    }
    String permissions;
    try {
      permissions = reader.posixPermissions(file);
    } catch (IOException | RuntimeException e) {
      return CheckRow.of(
          8,
          "token-file",
          CheckRow.Status.INFO,
          file.getPath()
              + ": permissions not checked ("
              + e.getClass().getSimpleName()
              + " reading them)");
    }
    if (permissions == null) {
      return CheckRow.of(
          8,
          "token-file",
          CheckRow.Status.INFO,
          file.getPath()
              + ": permissions not checked: the file system has no POSIX permissions (restrict"
              + " the file to the agent's user with the OS tools)");
    }
    Set<PosixFilePermission> set = PosixFilePermissions.fromString(permissions);
    boolean others = false;
    for (PosixFilePermission permission : set) {
      String name = permission.name();
      if (name.startsWith("GROUP_") || name.startsWith("OTHERS_")) {
        others = true;
      }
    }
    if (others) {
      return CheckRow.of(
          8,
          "token-file",
          CheckRow.Status.WARN,
          file.getPath()
              + " is "
              + permissions
              + ": group or others have access; restrict it to the owner (chmod 600)");
    }
    return CheckRow.of(
        8,
        "token-file",
        CheckRow.Status.PASS,
        file.getPath() + " is " + permissions + " (owner only)");
  }
}
