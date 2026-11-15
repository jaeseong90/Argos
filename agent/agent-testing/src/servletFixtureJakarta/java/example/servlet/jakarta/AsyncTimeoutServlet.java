/* SPDX-License-Identifier: Apache-2.0 */
package example.servlet.jakarta;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * An async request that times out (10 §6.7 "타임아웃 시 상태"): {@code startAsync()} with a {@value
 * #TIMEOUT_MS} ms timeout and nothing else (no {@code complete()}, no {@code dispatch()}, no
 * listener). The container times it out, answers 500 (this application has no error page: Tomcat's
 * error report) and completes it. Test-only.
 */
public class AsyncTimeoutServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  /** The async timeout. */
  static final long TIMEOUT_MS = 300;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    request.startAsync().setTimeout(TIMEOUT_MS);
  }
}
