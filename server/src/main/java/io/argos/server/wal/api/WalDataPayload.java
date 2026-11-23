/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal.api;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import io.argos.server.session.api.ApdexOverrides;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.UserIdMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The payload of a WAL data record: the session values of {@link SessionContext} that the record
 * header does not carry, followed by the serialized batch exactly as it was acked (after quota
 * trimming and the removal of items with an unknown required enum, DSN-12-04, ADR-0010 C-143 (1)).
 *
 * <p>With the header ({@code instance_id}, {@code epoch}, {@code received_ms}, {@code
 * ts_correction_ms}) a record is self-contained: recovery and replay rebuild the identical {@link
 * SessionContext} ({@link #context}) and decode the batch without any session or database lookup,
 * so a replayed record stores the same rows as the original path (D-13, ADR-0010 C-129 (3)).
 *
 * <p>Format version 1, big-endian:
 *
 * <pre>
 * u8  format (1)
 * u32 app_id
 * u16 length, UTF-8 env
 * u64 session_id most significant bits, u64 least significant bits
 * u8  ip_mode (IpMode.enum8()), u8 user_id_mode (UserIdMode.enum8())
 * u32 apdex_t_ms
 * u16 length, UTF-8 app_version
 * ... the batch (TransactionBatch, ProfileBatch, MetricBatch or StatBatch by the record type) to
 *     the end of the payload
 * </pre>
 *
 * <p>Format version 2 (WO-27) is format 1 with the app's service Apdex overrides at receipt ({@link
 * SessionContext#apdexOverrides()}, D-17 forward only) between {@code app_version} and the batch:
 *
 * <pre>
 * u16 n, the number of overrides (at most ApdexOverrides.MAX_PER_APP)
 * n x (u64 service_id, u32 apdex_t_ms), service ids in unsigned order
 * </pre>
 *
 * A payload without overrides is written as format 1, byte for byte what the previous version
 * wrote; both formats are read.
 *
 * @param appId {@link SessionContext#appId()}
 * @param env {@link SessionContext#env()}, at most 65,535 UTF-8 bytes (11 §4: 32)
 * @param sessionId {@link SessionContext#sessionId()}
 * @param ipMode {@link SessionContext#ipMode()}
 * @param userIdMode {@link SessionContext#userIdMode()}
 * @param apdexTMs {@link SessionContext#apdexTMs()}
 * @param appVersion {@link SessionContext#appVersion()}, at most 65,535 UTF-8 bytes (11 §4: 2 KiB)
 * @param apdexOverrides {@link SessionContext#apdexOverrides()}
 * @param batch the serialized protobuf batch
 */
public record WalDataPayload(
    int appId,
    String env,
    UUID sessionId,
    IpMode ipMode,
    UserIdMode userIdMode,
    int apdexTMs,
    String appVersion,
    ApdexOverrides apdexOverrides,
    ByteString batch) {

  /** The format byte of a payload without service Apdex overrides. */
  public static final int FORMAT = 1;

  /** The format byte of a payload with service Apdex overrides (WO-27). */
  public static final int FORMAT_WITH_OVERRIDES = 2;

  private static final int MAX_TEXT_BYTES = 0xFFFF;

  /** Validates the components. */
  public WalDataPayload {
    Objects.requireNonNull(env, "env");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(ipMode, "ipMode");
    Objects.requireNonNull(userIdMode, "userIdMode");
    Objects.requireNonNull(appVersion, "appVersion");
    Objects.requireNonNull(apdexOverrides, "apdexOverrides");
    Objects.requireNonNull(batch, "batch");
    if (apdexTMs < 0) {
      throw new IllegalArgumentException("apdexTMs < 0: " + apdexTMs);
    }
  }

  /** A payload without service Apdex overrides. */
  public WalDataPayload(
      int appId,
      String env,
      UUID sessionId,
      IpMode ipMode,
      UserIdMode userIdMode,
      int apdexTMs,
      String appVersion,
      ByteString batch) {
    this(
        appId,
        env,
        sessionId,
        ipMode,
        userIdMode,
        apdexTMs,
        appVersion,
        ApdexOverrides.NONE,
        batch);
  }

  /** The payload of a batch received in {@code session}. */
  public static WalDataPayload of(SessionContext session, ByteString batch) {
    return new WalDataPayload(
        session.appId(),
        session.env(),
        session.sessionId(),
        session.ipMode(),
        session.userIdMode(),
        session.apdexTMs(),
        session.appVersion(),
        session.apdexOverrides(),
        batch);
  }

  /**
   * The bytes of the record payload ({@link WalWriter#append}).
   *
   * @throws IllegalArgumentException when {@code env} or {@code appVersion} is longer than 65,535
   *     UTF-8 bytes (the collector's limits stop such values earlier)
   */
  public byte[] encode() {
    byte[] envBytes = text(env, "env");
    byte[] versionBytes = text(appVersion, "appVersion");
    int n = apdexOverrides.size();
    boolean withOverrides = n > 0;
    ByteBuffer out =
        ByteBuffer.allocate(
            1
                + 4
                + 2
                + envBytes.length
                + 16
                + 1
                + 1
                + 4
                + 2
                + versionBytes.length
                + (withOverrides ? 2 + n * 12 : 0)
                + batch.size());
    out.put((byte) (withOverrides ? FORMAT_WITH_OVERRIDES : FORMAT));
    out.putInt(appId);
    out.putShort((short) envBytes.length);
    out.put(envBytes);
    out.putLong(sessionId.getMostSignificantBits());
    out.putLong(sessionId.getLeastSignificantBits());
    out.put((byte) ipMode.enum8());
    out.put((byte) userIdMode.enum8());
    out.putInt(apdexTMs);
    out.putShort((short) versionBytes.length);
    out.put(versionBytes);
    if (withOverrides) {
      out.putShort((short) n);
      for (int i = 0; i < n; i++) {
        out.putLong(apdexOverrides.serviceId(i));
        out.putInt(apdexOverrides.tMs(i));
      }
    }
    batch.copyTo(out);
    return out.array();
  }

  /**
   * Reads a payload written by {@link #encode()}. The batch shares {@code payload} (no copy):
   * {@code payload} must not change afterwards.
   *
   * @throws IllegalArgumentException for an unknown format, a truncated payload or an invalid
   *     override list (the record is skipped and counted in {@code
   *     argos_ingest_errors_total{stage="wal_payload"}})
   */
  public static WalDataPayload decode(byte[] payload) {
    ByteBuffer in = ByteBuffer.wrap(payload);
    try {
      int format = in.get() & 0xFF;
      if (format != FORMAT && format != FORMAT_WITH_OVERRIDES) {
        throw new IllegalArgumentException("Unknown WAL payload format " + format);
      }
      int appId = in.getInt();
      String env = readText(in);
      UUID sessionId = new UUID(in.getLong(), in.getLong());
      IpMode ipMode = IpMode.fromEnum8(in.get());
      UserIdMode userIdMode = UserIdMode.fromEnum8(in.get());
      int apdexTMs = in.getInt();
      String appVersion = readText(in);
      ApdexOverrides overrides =
          format == FORMAT_WITH_OVERRIDES ? readOverrides(in) : ApdexOverrides.NONE;
      ByteString batch = UnsafeByteOperations.unsafeWrap(payload, in.position(), in.remaining());
      return new WalDataPayload(
          appId, env, sessionId, ipMode, userIdMode, apdexTMs, appVersion, overrides, batch);
    } catch (java.nio.BufferUnderflowException e) {
      throw new IllegalArgumentException(
          "Truncated WAL payload of " + payload.length + " bytes", e);
    }
  }

  /**
   * The context of the record: these values with the record header's {@code instance_id}, {@code
   * epoch}, {@code received_ms} and {@code ts_correction_ms}.
   */
  public SessionContext context(
      int instanceId, long agentEpoch, long receivedMs, long tsCorrectionMs) {
    return new SessionContext(
        appId,
        instanceId,
        env,
        sessionId,
        agentEpoch,
        ipMode,
        userIdMode,
        apdexTMs,
        receivedMs,
        appVersion,
        tsCorrectionMs,
        apdexOverrides);
  }

  private static byte[] text(String value, String name) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_TEXT_BYTES) {
      throw new IllegalArgumentException(name + " longer than " + MAX_TEXT_BYTES + " bytes");
    }
    return bytes;
  }

  private static ApdexOverrides readOverrides(ByteBuffer in) {
    int n = in.getShort() & 0xFFFF;
    if (n == 0 || n > ApdexOverrides.MAX_PER_APP) {
      throw new IllegalArgumentException("WAL payload with " + n + " service Apdex overrides");
    }
    Map<Long, Integer> values = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      long serviceId = in.getLong();
      int t = in.getInt();
      if (values.put(serviceId, t) != null) {
        throw new IllegalArgumentException("WAL payload with a repeated service Apdex override");
      }
    }
    return ApdexOverrides.of(values);
  }

  private static String readText(ByteBuffer in) {
    int length = in.getShort() & 0xFFFF;
    byte[] bytes = new byte[length];
    in.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
