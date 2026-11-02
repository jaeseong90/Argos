/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Configuration keys (10 §15). {@link #KNOWN} lists every documented key so that an unknown key can
 * be reported (02 §7 "알 수 없는 키는 WARN"); WO-02 reads only a subset, the rest arrives with WO-10
 * (ConfigLoader) and the modules that use them.
 */
public final class ConfigKeys {
  public static final String ENABLED = "enabled";
  public static final String AGENT_DISABLED = "agent.disabled";
  public static final String APP_NAME = "app.name";
  public static final String APP_ENV = "app.env";
  public static final String APP_VERSION = "app.version";
  public static final String SERVER_ADDRESS = "server.address";

  public static final String LOG_LEVEL = "log.level";
  public static final String LOG_DIR = "log.dir";
  public static final String LOG_MAX_FILE_MB = "log.max-file-mb";
  public static final String LOG_MAX_FILES = "log.max-files";

  public static final String INSTRUMENTATION_DISABLED = "instrumentation.disabled";
  public static final String INSTRUMENTATION_EXCLUDE_CLASSES = "instrumentation.exclude-classes";
  public static final String INSTRUMENTATION_ERROR_THRESHOLD = "instrumentation.error-threshold";
  public static final String INSTRUMENTATION_TRANSFORM_ERROR_THRESHOLD =
      "instrumentation.transform-error-threshold";
  public static final String INSTRUMENTATION_IGNORE_PATHS = "instrumentation.ignore-paths";
  public static final String INSTRUMENTATION_IGNORE_EXTENSIONS =
      "instrumentation.ignore-extensions";
  public static final String INSTRUMENTATION_IGNORE_USER_AGENTS =
      "instrumentation.ignore-user-agents";
  public static final String INSTRUMENTATION_IGNORE_METHODS = "instrumentation.ignore-methods";
  public static final String INSTRUMENTATION_SERVICE_DICTIONARY_MAX =
      "instrumentation.service-dictionary-max";

  public static final String CAPTURE_REMOTE_IP = "capture.remote-ip";
  public static final String CAPTURE_ERROR_MESSAGE_MAX = "capture.error-message-max-length";
  public static final String CAPTURE_STACK_DEPTH = "capture.stack-depth";

  /** ASYNC step per propagated executor task (10 §6.6, §15; default false). */
  public static final String ASYNC_STEP = "async.step";

  public static final String MAX_STEPS = "sampling.profile.max-steps";
  public static final String TRANSACTION_CAPACITY = "buffer.transaction.capacity";
  public static final String BUFFER_MAX_BYTES = "buffer.max-bytes";
  public static final String SENDER_FLUSH_INTERVAL_MS = "sender.flush-interval-ms";
  public static final String SENDER_BATCH_SIZE = "sender.batch-size";

  public static final String DEBUG_TRANSFORM_TIMING = "debug.transform-timing";

  /**
   * WO-02 PoC keys (not yet in 10 §15): exporter selection for the P-2/P-3 measurements and tests
   * ({@code grpc} default, {@code file} JSONL, {@code noop}), and the transformed-class list (10
   * §5.2 step 4, baseline diff, N-27).
   */
  public static final String EXPORTER = "exporter";

  public static final String EXPORTER_FILE_PATH = "exporter.file.path";
  public static final String EXPORTER_FILE_MAX_MB = "exporter.file.max-mb";
  public static final String DEBUG_TRANSFORM_LIST = "debug.transform-list";

  /**
   * Test-only fault injection (18 §5.x {@code -Dargos.test.faults=<spec>}, T-AG-040): {@code
   * advice-throw:<module id>[,...]}. Release builds will ignore {@code argos.test.*} (T-BLD-010,
   * not implemented in WO-02).
   */
  public static final String TEST_FAULTS = "test.faults";

  /** Path of an {@code argos-agent.yml}; YAML loading arrives with WO-10. */
  public static final String CONFIG_FILE = "config";

  /** Every documented key (10 §15 plus the WO-02 keys above). */
  public static final Set<String> KNOWN =
      Collections.unmodifiableSet(
          new LinkedHashSet<String>(
              Arrays.asList(
                  "server.address",
                  "server.tls",
                  "server.tls.ca-file",
                  "server.tls.insecure-skip-verify",
                  "server.token",
                  "server.token-file",
                  "server.connect-timeout-ms",
                  "server.deadline-ms",
                  "app.name",
                  "app.env",
                  "app.instance",
                  "app.version",
                  "app.group",
                  "enabled",
                  "agent.disabled",
                  "sampling.profile.slow-threshold-ms",
                  "sampling.profile.normal-rate",
                  "sampling.profile.max-steps",
                  "capture.query-string",
                  "capture.query-string.whitelist",
                  "capture.http-headers",
                  "capture.remote-ip",
                  "capture.trusted-proxies",
                  "capture.user-id.mode",
                  "capture.user-id.source",
                  "capture.sql-parameters",
                  "capture.sql-parameters.whitelist-columns",
                  "capture.mask-patterns",
                  "capture.error-message-max-length",
                  "capture.stack-depth",
                  "instrumentation.disabled",
                  "instrumentation.exclude-classes",
                  "instrumentation.ignore-paths",
                  "instrumentation.ignore-extensions",
                  "instrumentation.ignore-user-agents",
                  "instrumentation.ignore-methods",
                  "instrumentation.custom-methods",
                  "instrumentation.custom-methods.max-classes-per-pattern",
                  "instrumentation.app-packages",
                  "instrumentation.service-dictionary-max",
                  "instrumentation.error-threshold",
                  "instrumentation.transform-error-threshold",
                  "transaction.max-duration-ms",
                  "transaction.slow-stack.enabled",
                  "transaction.slow-stack.interval-ms",
                  "transaction.slow-stack.max-captures",
                  "transaction.slow-stack.max-threads-per-cycle",
                  "transaction.slow-stack.depth",
                  "propagation.exclude-hosts",
                  "propagation.argos-state.hosts",
                  "propagation.preserve-incoming",
                  "async.step",
                  "buffer.transaction.capacity",
                  "buffer.max-age-seconds",
                  "buffer.max-bytes",
                  "sender.flush-interval-ms",
                  "sender.batch-size",
                  "sender.max-in-flight",
                  "sender.compression",
                  "metrics.interval-seconds",
                  "metrics.max-series",
                  "stats.window-seconds",
                  "stats.max-keys",
                  "active.snapshot-interval-seconds",
                  "sql.max-length",
                  "sql.backslash-escape",
                  "sql.keep-hints",
                  "commands.env-dump.mask-patterns",
                  "commands.thread-dump.cooldown-seconds",
                  "commands.thread-dump.max-bytes",
                  "remote-config.allow",
                  "log.level",
                  "log.dir",
                  "log.max-file-mb",
                  "log.max-files",
                  "debug.transform-timing",
                  EXPORTER,
                  EXPORTER_FILE_PATH,
                  EXPORTER_FILE_MAX_MB,
                  DEBUG_TRANSFORM_LIST,
                  TEST_FAULTS,
                  CONFIG_FILE)));

  /** System property prefix ({@code -Dargos.<key>}). */
  public static final String PROPERTY_PREFIX = "argos.";

  private ConfigKeys() {}

  /**
   * The environment variable of {@code key}: {@code ARGOS_} + key with {@code .}/{@code -} as _.
   */
  public static String environmentName(String key) {
    return "ARGOS_" + key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
  }
}
