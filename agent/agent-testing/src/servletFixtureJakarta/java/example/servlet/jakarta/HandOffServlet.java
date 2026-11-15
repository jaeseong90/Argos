/* SPDX-License-Identifier: Apache-2.0 */
package example.servlet.jakarta;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Runs a page servlet of its own for every dispatch it serves, the way Jasper's {@code JspServlet}
 * runs a JSP page: {@code JspServletWrapper} calls {@code Servlet.service(ServletRequest,
 * ServletResponse)} of the generated page, whose base class {@code HttpJspBase} overrides only the
 * HTTP {@code service} method. One dispatch thus enters {@code HttpServlet.service} twice with the
 * same request object, and still counts once in {@code dispatch_count} (F-A28, T-AG-011). The
 * staged Tomcat has no JSP compiler (the sample-app staging drops {@code lib/ecj-*.jar},
 * docs/licenses/dev-only.md), so this servlet stands in for {@code JspServlet}. The page of {@code
 * /page/including} first includes {@code /page/part}, as {@code <jsp:include>} does. Test-only.
 */
public class HandOffServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private final transient Servlet page = new PageServlet();

  @Override
  public void init(ServletConfig config) throws ServletException {
    super.init(config);
    page.init(config);
  }

  @Override
  protected void service(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    page.service(request, response);
  }

  @Override
  public void destroy() {
    page.destroy();
    super.destroy();
  }

  /** The page: overrides only the HTTP {@code service} method, like {@code HttpJspBase}. */
  static final class PageServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
      // An included request keeps the including request's path info: only the REQUEST includes.
      DispatcherType type = request.getDispatcherType();
      if (type == DispatcherType.REQUEST && "/including".equals(request.getPathInfo())) {
        request.getRequestDispatcher("/page/part").include(request, response);
      }
      response.setContentType("text/plain;charset=UTF-8");
      response.getWriter().write("page " + type + "\n");
    }
  }
}
