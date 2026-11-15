/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.fixture;

import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Negative fixture: one handler with a role, one without (never production code). */
@RestController
public class ControllerWithoutRole {

  /** Has a role. */
  @GetMapping("/annotated")
  @RequiresRole(Role.VIEWER)
  public String annotated() {
    return "";
  }

  /** Has none. */
  @GetMapping("/forgotten")
  public String forgotten() {
    return "";
  }

  /** Not a handler. */
  public String helper() {
    return "";
  }
}
