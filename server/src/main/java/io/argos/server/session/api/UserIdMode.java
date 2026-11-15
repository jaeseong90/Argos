/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

import java.util.Locale;

/**
 * The {@code user_id} capture mode of a session (DSN-11-10, SEC-32, 16 §6.3), as the server stores
 * it: the ClickHouse {@code xlog.user_id_mode Enum8('off' = 0, 'plain' = 1, 'hashed' = 2)} and the
 * PostgreSQL {@code instances.user_id_mode} text.
 *
 * <p>Mapped <b>by name</b> from the protocol enum (proto/README.md). ADR-0010 C-143 (1): {@code
 * USER_ID_MODE_UNSPECIFIED} or an unknown value maps to {@link #OFF}, and a session in {@link #OFF}
 * never stores a {@code user_id} (the writer stores {@code ''}), whatever the agent sent.
 */
public enum UserIdMode {
  /** Not collected; {@code user_id} is stored as {@code ''}. */
  OFF(0),
  /** Plain value (at most 128 bytes, 11 §4.4); stored as sent. */
  PLAIN(1),
  /**
   * HMAC by the agent (first 16 characters of base64url, 16 §6.3, ADR-0010 C-163); stored as sent.
   */
  HASHED(2);

  private final int enum8;

  UserIdMode(int enum8) {
    this.enum8 = enum8;
  }

  /** The value of the ClickHouse {@code Enum8} (RowBinary writes it as one signed byte). */
  public int enum8() {
    return enum8;
  }

  /**
   * The ClickHouse {@code Enum8} name and the PostgreSQL text: {@code off}, {@code plain}, {@code
   * hashed}.
   */
  public String storedName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Whether rows of a session in this mode keep the {@code user_id} the agent sent. */
  public boolean storesUserId() {
    return this != OFF;
  }

  /**
   * The server mode of an {@code AgentHello.user_id_mode} value. {@code USER_ID_MODE_UNSPECIFIED}
   * and unknown numbers are {@link #OFF} (ADR-0010 C-143 (1)); the caller counts them ({@link
   * #isKnown}).
   */
  public static UserIdMode fromProto(io.argos.proto.v1.UserIdMode mode) {
    return switch (mode) {
      case USER_ID_PLAIN -> PLAIN;
      case USER_ID_HASHED -> HASHED;
      case USER_ID_OFF -> OFF;
      case USER_ID_MODE_UNSPECIFIED, UNRECOGNIZED -> OFF;
    };
  }

  /**
   * Whether {@code mode} is a defined capture mode; {@code false} for {@code
   * USER_ID_MODE_UNSPECIFIED} and unknown numbers, which the collector counts in {@code
   * argos_collector_unknown_enum_total{field="user_id_mode"}} (11 §4.4).
   */
  public static boolean isKnown(io.argos.proto.v1.UserIdMode mode) {
    return mode != io.argos.proto.v1.UserIdMode.USER_ID_MODE_UNSPECIFIED
        && mode != io.argos.proto.v1.UserIdMode.UNRECOGNIZED;
  }

  /**
   * The mode of a stored name ({@link #storedName()}, any case); {@code null}, blank or unknown
   * text is {@link #OFF}.
   */
  public static UserIdMode fromStoredName(String name) {
    if (name != null) {
      for (UserIdMode mode : values()) {
        if (mode.storedName().equalsIgnoreCase(name.strip())) {
          return mode;
        }
      }
    }
    return OFF;
  }

  /** The mode of an {@code Enum8} value; an unknown value is {@link #OFF}. */
  public static UserIdMode fromEnum8(int value) {
    for (UserIdMode mode : values()) {
      if (mode.enum8 == value) {
        return mode;
      }
    }
    return OFF;
  }
}
