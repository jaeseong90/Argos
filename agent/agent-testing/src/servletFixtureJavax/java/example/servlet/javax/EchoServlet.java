/* SPDX-License-Identifier: Apache-2.0 */
package example.servlet.javax;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Spring-free servlet fixture of agent-testing (T-AG-020, F-A02 "Spring 없는 앱"): one servlet class
 * registered under a prefix ({@code /items/*}), an exact ({@code /plain/exact}) and an extension
 * ({@code *.do}) mapping in {@code WEB-INF/web.xml}. It answers 200 with the servlet path and path
 * info; the agent names its transactions from the Servlet 4+ {@code HttpServletMapping} pattern.
 * Test-only, never shipped.
 */
public class EchoServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setStatus(200);
    response.setContentType("text/plain;charset=UTF-8");
    String body =
        "servlet="
            + getServletName()
            + " servletPath="
            + request.getServletPath()
            + " pathInfo="
            + request.getPathInfo()
            + "\n";
    response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
  }
}
