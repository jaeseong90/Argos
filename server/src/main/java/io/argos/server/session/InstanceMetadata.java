/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.QueryStringMode;
import io.argos.proto.v1.SqlParamMode;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.UserIdMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The metadata columns of an {@code instances} row (PostgreSQL V001, 13 §12) as an {@code
 * AgentHello} reports them (11 §4.2, §4.4 "서버는 모드를 AgentHello로 알고 인스턴스 메타에 기록한다"). Identity and
 * metadata only: no state column (ADR-0010 C-128).
 *
 * <p>Values are as sent; the repository cuts text to the column sizes (V001 is narrower than some
 * 11 caps, reported to the PM). Absent text is {@code null}.
 *
 * @param host {@code host}
 * @param pid {@code pid}
 * @param jvmVersion {@code jvm_version}
 * @param jvmVendor {@code jvm_vendor}
 * @param os {@code os}
 * @param cgroup {@code cgroup} ({@code v1}, {@code v2}, {@code none})
 * @param virtualThreads {@code virtual_threads}
 * @param agentVersion {@code agent_version}
 * @param protocolVersion {@code protocol_version}
 * @param versionTag {@code version_tag}: {@code AgentHello.app_version} (F-A21)
 * @param ipMode {@code ip_mode} after the C-143 (1) mapping ({@code off} for an unknown mode)
 * @param userIdMode {@code user_id_mode} after the C-143 (1) mapping
 * @param queryStringMode {@code query_string_mode} ({@code off}, {@code whitelist}, {@code full}),
 *     {@code null} when not reported
 * @param sqlParamMode {@code sql_param_mode} ({@code masked}, {@code partial}, {@code full}),
 *     {@code null} when not reported
 * @param configHash {@code config_hash}
 * @param remoteConfigAllow {@code remote_config_allow} (SEC-23)
 * @param startedAt {@code started_at}: {@code AgentHello.process_start_ms} (agent clock), {@code
 *     null} when 0
 */
public record InstanceMetadata(
    String host,
    Integer pid,
    String jvmVersion,
    String jvmVendor,
    String os,
    String cgroup,
    Boolean virtualThreads,
    String agentVersion,
    Integer protocolVersion,
    String versionTag,
    String ipMode,
    String userIdMode,
    String queryStringMode,
    String sqlParamMode,
    String configHash,
    List<String> remoteConfigAllow,
    Instant startedAt) {

  /** Copies the list. */
  public InstanceMetadata {
    remoteConfigAllow = remoteConfigAllow == null ? List.of() : List.copyOf(remoteConfigAllow);
  }

  /**
   * The metadata of {@code hello}, with the capture modes already mapped by the session ({@code
   * ipMode}, {@code userIdMode}).
   */
  public static InstanceMetadata of(AgentHello hello, IpMode ipMode, UserIdMode userIdMode) {
    Objects.requireNonNull(ipMode, "ipMode");
    Objects.requireNonNull(userIdMode, "userIdMode");
    return new InstanceMetadata(
        blankToNull(hello.getHost()),
        hello.getPid() == 0
            ? null
            : (int) Math.min(Integer.MAX_VALUE, hello.getPid() & 0xFFFF_FFFFL),
        blankToNull(hello.getJvmVersion()),
        blankToNull(hello.getJvmVendor()),
        blankToNull(hello.getOs()),
        blankToNull(hello.getCgroup()),
        hello.getVirtualThreads(),
        blankToNull(hello.getAgentVersion()),
        hello.getProtocolVersion() < 0 ? null : hello.getProtocolVersion(),
        blankToNull(hello.getAppVersion()),
        ipMode.storedName(),
        userIdMode.storedName(),
        queryStringMode(hello.getQueryStringMode()),
        sqlParamMode(hello.getSqlParamMode()),
        blankToNull(hello.getConfigHash()),
        hello.getRemoteConfigAllowList(),
        hello.getProcessStartMs() <= 0 ? null : Instant.ofEpochMilli(hello.getProcessStartMs()));
  }

  /** The stored name of a query-string mode; {@code null} for an unspecified or unknown mode. */
  static String queryStringMode(QueryStringMode mode) {
    return switch (mode) {
      case QS_OFF -> "off";
      case QS_WHITELIST -> "whitelist";
      case QS_FULL -> "full";
      case QUERY_STRING_MODE_UNSPECIFIED, UNRECOGNIZED -> null;
    };
  }

  /** The stored name of a SQL parameter mode; {@code null} for an unspecified or unknown mode. */
  static String sqlParamMode(SqlParamMode mode) {
    return switch (mode) {
      case SQL_PARAM_MASKED -> "masked";
      case SQL_PARAM_PARTIAL -> "partial";
      case SQL_PARAM_FULL -> "full";
      case SQL_PARAM_MODE_UNSPECIFIED, UNRECOGNIZED -> null;
    };
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
