/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.server.testing.fakeagent.AgentTokens;

/**
 * One app (name, env) of the load and its agent token (generated at run time, stored hashed in
 * PostgreSQL). {@link #toString()} never shows the token.
 *
 * @param index app index ({@code 0..apps-1})
 * @param name app name
 * @param env environment ({@code prod} or {@code stg})
 * @param token the issued agent token (its {@code appId} is the app's id)
 */
public record LoadApp(int index, String name, String env, AgentTokens.IssuedToken token) {

  /** The app id. */
  public int appId() {
    return token.appId();
  }

  /** The app name of index {@code i}: two envs share one name ({@code load-app-0} prod, stg). */
  public static String nameOf(int index) {
    return "load-app-" + index / 2;
  }

  @Override
  public String toString() {
    return "LoadApp[" + index + " " + name + "/" + env + " id=" + appId() + "]";
  }
}
