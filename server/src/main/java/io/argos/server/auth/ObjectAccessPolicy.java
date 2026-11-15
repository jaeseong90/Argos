/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Object-level permissions (16 §7.11, 14 §7.1, SEC-50): the role check of {@link RequiresRole} says
 * who may call an endpoint, this says whose object the call may touch. Used by the handlers of
 * identifier-typed resources (command results of WO-26, sessions of WO-24).
 *
 * <pre>{@code
 * UUID id = ObjectAccessPolicy.requireUuidV4(rawId, "command_id");
 * Command command = commands.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
 * ObjectAccessPolicy.requireOwnerOrAdmin(principal, command.requestedBy());
 * }</pre>
 */
public final class ObjectAccessPolicy {

  private static final Pattern UUID_V4 =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

  private ObjectAccessPolicy() {}

  /**
   * Allows the owner of an object and administrators.
   *
   * @param principal the requesting user
   * @param ownerId the owner of the object; null means nobody owns it (administrators only)
   * @throws ApiException 403 {@code not_owner} for anybody else
   */
  public static void requireOwnerOrAdmin(ArgosPrincipal principal, UUID ownerId) {
    Objects.requireNonNull(principal, "principal");
    if (principal.hasRole(Role.ADMIN) || principal.userId().equals(ownerId)) {
      return;
    }
    throw new ApiException(ErrorCode.NOT_OWNER);
  }

  /** Whether {@code value} is a UUID of version 4 in its canonical 36-character form. */
  public static boolean isUuidV4(String value) {
    return value != null && UUID_V4.matcher(value).matches();
  }

  /**
   * Parses a path identifier of an identifier-typed resource (16 §7.11). Stricter than Spring's
   * {@code UUID} conversion, which also takes other versions and shortened forms such as {@code
   * 1-1-1-1-1}: identifiers are never guessable sequences.
   *
   * @param value the identifier as received
   * @param field the parameter name in API spelling, for the error details
   * @return the identifier
   * @throws ApiException 400 {@code invalid_request} with the detail {@code invalid_format}; the
   *     value is not echoed
   */
  public static UUID requireUuidV4(String value, String field) {
    if (!isUuidV4(value)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "An identifier has an invalid format")
          .detail(field, "invalid_format");
    }
    return UUID.fromString(value);
  }
}
