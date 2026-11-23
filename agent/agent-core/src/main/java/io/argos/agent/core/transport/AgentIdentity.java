/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.metric.CgroupReader;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.ProtocolVersion;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.IpMode;
import io.argos.proto.v1.QueryStringMode;
import io.argos.proto.v1.SqlParamMode;
import io.argos.proto.v1.UserIdMode;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The facts of {@code AgentHello} (11 §4.2, §5; 10 §10.4), gathered once on {@code argos-sender}
 * (never on the premain thread) plus the instance values that later change ({@link AgentSession}
 * updates them; a new session's hello carries the latest ones).
 *
 * <ul>
 *   <li>configuration: {@code app.name}, {@code app.env}, {@code app.version}, {@code app.instance}
 *       (else {@code host:pid} until the connector port is known), {@code config_hash} (token
 *       excluded), {@code remote_config_allow}, the four capture modes;
 *   <li>runtime: agent version, {@code protocol_version} ({@code ProtocolVersion.CURRENT}), host
 *       name, pid, JVM version and vendor, OS, architecture, process start, {@code cgroup} ({@code
 *       v1}, {@code v2} or {@code none}, {@link CgroupReader#systemMode()}), {@code
 *       virtual_threads} (true once a transaction began on a virtual thread, reported by the
 *       tracer), loaded instrumentation modules, {@code plaintext};
 *   <li>per process: {@code agent_epoch} (random, C-3).
 * </ul>
 *
 * The host name comes from the environment or {@code /proc/sys/kernel/hostname}, never from a name
 * lookup (no DNS query, DSN-02-22). Strings are cut to their 11 §4 caps.
 */
final class AgentIdentity implements SessionClient.HelloSource {

  private final AgentHello.Builder base;
  private final String host;
  private final long pid;
  private volatile String instanceName;
  private volatile boolean virtualThreads;
  private volatile String appVersion;
  private volatile String configHash;

  AgentIdentity(
      AgentConfig config,
      String agentVersion,
      String configHash,
      List<String> modules,
      long agentEpoch,
      boolean plaintext) {
    this.host = hostName();
    RuntimeMXBean runtime = runtimeBean();
    this.pid = pid(runtime);
    long processStart = runtime == null ? 0L : runtime.getStartTime();
    String instance = config.appInstance();
    this.instanceName =
        instance == null || instance.trim().isEmpty() ? host + ":" + pid : instance.trim();
    this.appVersion = config.appVersion() == null ? "" : config.appVersion();
    this.configHash = configHash == null ? "" : configHash;
    int string = ProtocolLimits.DEFAULT_MAX_STRING_BYTES;
    AgentHello.Builder hello =
        AgentHello.newBuilder()
            .setAgentVersion(Utf8.cut(agentVersion, string))
            .setProtocolVersion(ProtocolVersion.CURRENT)
            .setAppName(Utf8.cut(config.appName(), ProtocolLimits.MAX_APP_NAME_BYTES))
            .setEnv(Utf8.cut(config.appEnv(), ProtocolLimits.MAX_ENV_BYTES))
            .setHost(Utf8.cut(host, string))
            .setPid(BatchAssembler.u32(pid))
            .setJvmVersion(Utf8.cut(System.getProperty("java.version"), string))
            .setJvmVendor(Utf8.cut(System.getProperty("java.vendor"), string))
            .setOs(
                Utf8.cut(
                    System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""),
                    string))
            .setArch(Utf8.cut(System.getProperty("os.arch"), string))
            .setCgroup(cgroup())
            .setIpMode(ipMode(config.captureRemoteIp()))
            .setUserIdMode(userIdMode(config.captureUserIdMode()))
            .setQueryStringMode(queryStringMode(config.captureQueryString()))
            .setSqlParamMode(sqlParamMode(config.captureSqlParameters()))
            .setAgentEpoch(agentEpoch)
            .setProcessStartMs(processStart)
            .setPlaintext(plaintext);
    for (String pattern : config.remoteConfigAllow()) {
      hello.addRemoteConfigAllow(Utf8.cut(pattern, string));
    }
    List<String> names = modules == null ? Collections.<String>emptyList() : modules;
    for (String module : names) {
      hello.addInstrumentationModules(Utf8.cut(module, string));
    }
    this.base = hello;
  }

  /** {@code v1}, {@code v2} or {@code none} of this machine (10 §11); {@code none} on a failure. */
  static String cgroup() {
    try {
      return CgroupReader.systemMode();
    } catch (Throwable t) {
      return CgroupReader.NONE;
    }
  }

  @Override
  public synchronized AgentHello hello() {
    return base.clone()
        .setInstanceName(instanceName())
        .setVirtualThreads(virtualThreads())
        .setAppVersion(appVersion())
        .setConfigHash(configHash())
        .setSentAtMs(System.currentTimeMillis())
        .build();
  }

  String host() {
    return host;
  }

  long pid() {
    return pid;
  }

  /** The current {@code instance_name} as a hello carries it (cut to its 11 §4 cap). */
  String instanceName() {
    return Utf8.cut(instanceName, ProtocolLimits.DEFAULT_MAX_STRING_BYTES);
  }

  /** The current {@code virtual_threads} as a hello carries it. */
  boolean virtualThreads() {
    return virtualThreads;
  }

  /** The current {@code app_version} as a hello carries it (cut to its 11 §4 cap). */
  String appVersion() {
    return Utf8.cut(appVersion, ProtocolLimits.DEFAULT_MAX_STRING_BYTES);
  }

  /** The current {@code config_hash} as a hello carries it (cut to its 11 §4 cap). */
  String configHash() {
    return Utf8.cut(configHash, ProtocolLimits.DEFAULT_MAX_STRING_BYTES);
  }

  void instanceName(String value) {
    instanceName = value;
  }

  void virtualThreads(boolean value) {
    virtualThreads = value;
  }

  void appVersion(String value) {
    appVersion = value == null ? "" : value;
  }

  void configHash(String value) {
    configHash = value == null ? "" : value;
  }

  static IpMode ipMode(String mode) {
    String value = lower(mode);
    if ("full".equals(value)) {
      return IpMode.IP_FULL;
    }
    if ("off".equals(value)) {
      return IpMode.IP_OFF;
    }
    return IpMode.IP_MASKED;
  }

  static UserIdMode userIdMode(String mode) {
    String value = lower(mode);
    if ("plain".equals(value)) {
      return UserIdMode.USER_ID_PLAIN;
    }
    if ("hashed".equals(value)) {
      return UserIdMode.USER_ID_HASHED;
    }
    return UserIdMode.USER_ID_OFF;
  }

  static QueryStringMode queryStringMode(String mode) {
    String value = lower(mode);
    if ("whitelist".equals(value)) {
      return QueryStringMode.QS_WHITELIST;
    }
    if ("full".equals(value)) {
      return QueryStringMode.QS_FULL;
    }
    return QueryStringMode.QS_OFF;
  }

  static SqlParamMode sqlParamMode(String mode) {
    String value = lower(mode);
    if ("partial".equals(value)) {
      return SqlParamMode.SQL_PARAM_PARTIAL;
    }
    if ("full".equals(value)) {
      return SqlParamMode.SQL_PARAM_FULL;
    }
    return SqlParamMode.SQL_PARAM_MASKED;
  }

  private static String lower(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static RuntimeMXBean runtimeBean() {
    try {
      return ManagementFactory.getRuntimeMXBean();
    } catch (RuntimeException | LinkageError e) {
      return null;
    }
  }

  /** The pid from {@code RuntimeMXBean.getName()} ({@code pid@host}), 0 when unknown. */
  static long pid(RuntimeMXBean runtime) {
    if (runtime == null) {
      return 0;
    }
    String name = runtime.getName();
    int at = name == null ? -1 : name.indexOf('@');
    if (at <= 0) {
      return 0;
    }
    try {
      return Long.parseLong(name.substring(0, at));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * The host name without a name lookup: environment, then the kernel's host name, else "unknown".
   */
  static String hostName() {
    List<String> candidates = new ArrayList<String>();
    candidates.add(System.getenv("COMPUTERNAME"));
    candidates.add(System.getenv("HOSTNAME"));
    candidates.add(firstLine(new File("/proc/sys/kernel/hostname")));
    candidates.add(firstLine(new File("/etc/hostname")));
    for (String candidate : candidates) {
      if (candidate != null && !candidate.trim().isEmpty()) {
        return candidate.trim();
      }
    }
    return "unknown";
  }

  private static String firstLine(File file) {
    if (!file.isFile()) {
      return null;
    }
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
      String line = reader.readLine();
      return line == null || line.length() > 255 ? null : line;
    } catch (Exception e) {
      return null;
    }
  }
}
