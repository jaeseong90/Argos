/* SPDX-License-Identifier: Apache-2.0 */
package example.servlet.javax;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Forwards to {@code /plain/exact}, or to the init parameter {@code target} (a FORWARD dispatch
 * without Spring, D-04): one transaction, named after this servlet's own mapping ({@code
 * /forward/*}, {@code /forward-page}), not the forward target. Test-only.
 */
public class ForwardServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    String target = getInitParameter("target");
    request
        .getRequestDispatcher(target == null ? "/plain/exact" : target)
        .forward(request, response);
  }
}
