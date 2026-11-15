/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.fixture;

import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Negative fixture: a {@code @Controller} (not {@code @RestController}) whose handlers write the
 * body themselves, with two overloads of which only the first has a role (never production code).
 */
@Controller
public class PlainControllerWithoutRole {

  /** Has a role. */
  @GetMapping("/plain/a")
  @ResponseBody
  @RequiresRole(Role.VIEWER)
  public String get() {
    return "";
  }

  /** The overload without one. */
  @GetMapping("/plain/b")
  @ResponseBody
  public String get(String id) {
    return id;
  }
}
