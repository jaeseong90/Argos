/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The minimum role of a REST handler (DSN-12-11, 12 §7.3, SEC-49): required on every handler
 * method, directly or through its controller class (a method annotation wins over the class one).
 *
 * <ul>
 *   <li>At run time {@link RoleAuthorizationInterceptor} enforces it before the handler runs: no
 *       session for a non-{@code PUBLIC} handler is 401, a lower role is 403 {@code forbidden}.
 *   <li>At start {@code RoleMatrix} fails the start when a handler has no role (WO-24), so a
 *       handler that a later work order adds without this annotation never serves a request.
 *   <li>The OpenAPI document carries it as {@code x-argos-role} (14 §2.8), from which the
 *       permission-matrix test is generated.
 * </ul>
 *
 * There is no URL pattern matrix: the annotation is the only source of the required role.
 *
 * <p>Only a {@code @RequestMapping} method can carry it. Every other kind of handler on the HTTP
 * port (a bean whose name is a URL, an {@code HttpRequestHandler}, an MVC {@code Controller}, a
 * router function) fails the start ({@code RoleMatrix}) and is refused at run time; the exceptions
 * are static resources, Spring Boot's welcome page of {@code /} (never reached, {@code
 * IndexHtmlController} maps {@code /}) and a {@link SelfAuthenticatingHandler}, none of them under
 * {@code /api}. The ArchUnit rules of the server keep such handlers out of the code.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresRole {

  /** The minimum role; higher roles are included ({@link Role#includes(Role)}). */
  Role value();
}
