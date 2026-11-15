/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a secret: a password, an access, refresh or setup token, a JWT key or any other credential
 * (16 §7.9 "로그", SEC-48).
 *
 * <ul>
 *   <li>its value never appears in {@code toString()}: a class or record with a sensitive component
 *       overrides {@code toString()} and prints {@code ****} (or leaves the component out);
 *   <li>it is never passed to a logger, never written to an audit {@code before}/{@code after} map
 *       and never returned in a response, except where 14 says "once" (setup and token creation);
 *   <li>it is compared in constant time ({@link java.security.MessageDigest#isEqual}).
 * </ul>
 *
 * An ArchUnit rule checks the annotated fields and components (WO-24, 16 §7.9 {@code ST-48-4}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
  ElementType.FIELD,
  ElementType.RECORD_COMPONENT,
  ElementType.PARAMETER,
  ElementType.METHOD,
  ElementType.LOCAL_VARIABLE
})
public @interface Sensitive {}
