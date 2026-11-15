/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

import java.util.Locale;

/**
 * The {@code remote_ip} capture mode of a session (DSN-11-10, SEC-31, 16 §6.2), as the server
 * stores it: the ClickHouse {@code xlog.ip_mode Enum8('off' = 0, 'masked' = 1, 'full' = 2)} and the
 * PostgreSQL {@code instances.ip_mode} text.
 *
 * <p>The protocol enum is mapped <b>by name</b> (proto/README.md "Mapping to the ClickHouse V001
 * columns"): the proto numbers and the {@code Enum8} numbers differ. ADR-0010 C-143 (1): an {@code
 * IP_MODE_UNSPECIFIED} or unknown value maps to {@link #OFF}, and a session in {@link #OFF} never
 * stores a {@code remote_ip} (the writer stores {@code ::}), whatever the agent sent. The server
 * never widens the mode the agent reported (ADR-0010 C-117, C-101).
 */
public enum IpMode {
  /** Not collected; {@code remote_ip} is stored as {@code ::}. */
  OFF(0),
  /** Masked by the agent (16 §6.2); stored as sent. */
  MASKED(1),
  /** Full address; stored as sent. */
  FULL(2);

  private final int enum8;

  IpMode(int enum8) {
    this.enum8 = enum8;
  }

  /** The value of the ClickHouse {@code Enum8} (RowBinary writes it as one signed byte). */
  public int enum8() {
    return enum8;
  }

  /**
   * The ClickHouse {@code Enum8} name and the PostgreSQL text: {@code off}, {@code masked}, {@code
   * full}.
   */
  public String storedName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Whether rows of a session in this mode keep the {@code remote_ip} the agent sent. */
  public boolean storesRemoteIp() {
    return this != OFF;
  }

  /**
   * The server mode of an {@code AgentHello.ip_mode} value. {@code IP_MODE_UNSPECIFIED} and unknown
   * numbers ({@code UNRECOGNIZED}) are {@link #OFF} (ADR-0010 C-143 (1)); the caller counts them
   * ({@link #isKnown}).
   */
  public static IpMode fromProto(io.argos.proto.v1.IpMode mode) {
    return switch (mode) {
      case IP_FULL -> FULL;
      case IP_MASKED -> MASKED;
      case IP_OFF -> OFF;
      case IP_MODE_UNSPECIFIED, UNRECOGNIZED -> OFF;
    };
  }

  /**
   * Whether {@code mode} is a defined capture mode; {@code false} for {@code IP_MODE_UNSPECIFIED}
   * and unknown numbers, which the collector counts in {@code
   * argos_collector_unknown_enum_total{field="ip_mode"}} (11 §4.4).
   */
  public static boolean isKnown(io.argos.proto.v1.IpMode mode) {
    return mode != io.argos.proto.v1.IpMode.IP_MODE_UNSPECIFIED
        && mode != io.argos.proto.v1.IpMode.UNRECOGNIZED;
  }

  /**
   * The mode of a stored name ({@link #storedName()}, any case); {@code null}, blank or unknown
   * text is {@link #OFF} (the privacy-safe side, as C-143 (1)).
   */
  public static IpMode fromStoredName(String name) {
    if (name != null) {
      for (IpMode mode : values()) {
        if (mode.storedName().equalsIgnoreCase(name.strip())) {
          return mode;
        }
      }
    }
    return OFF;
  }

  /** The mode of an {@code Enum8} value; an unknown value is {@link #OFF}. */
  public static IpMode fromEnum8(int value) {
    for (IpMode mode : values()) {
      if (mode.enum8 == value) {
        return mode;
      }
    }
    return OFF;
  }
}
