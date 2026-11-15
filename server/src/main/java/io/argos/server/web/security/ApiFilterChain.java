/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The {@link ApiFilter} beans as one filter of the {@code /api/**} security chain: runs them in
 * ascending {@link ApiFilter#position()}, once per request (not on error or async dispatches), then
 * continues with the rest of the Spring Security chain.
 */
final class ApiFilterChain extends OncePerRequestFilter {

  private final List<ApiFilter> filters;

  /**
   * @throws IllegalStateException when two filters claim the same position (the start fails with
   *     both class names)
   */
  ApiFilterChain(List<ApiFilter> filters) {
    Map<Integer, ApiFilter> byPosition = new TreeMap<>();
    List<String> clashes = new ArrayList<>();
    for (ApiFilter filter : filters) {
      ApiFilter previous = byPosition.putIfAbsent(filter.position(), filter);
      if (previous != null) {
        clashes.add(
            filter.position()
                + ": "
                + previous.getClass().getName()
                + ", "
                + filter.getClass().getName());
      }
    }
    if (!clashes.isEmpty()) {
      throw new IllegalStateException("API filters share a FilterOrder position: " + clashes);
    }
    List<ApiFilter> sorted = new ArrayList<>(filters);
    sorted.sort(Comparator.comparingInt(ApiFilter::position));
    this.filters = List.copyOf(sorted);
  }

  /** The filters in execution order. */
  List<ApiFilter> filters() {
    return filters;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    new Step(filters, chain).doFilter(request, response);
  }

  /** The virtual chain over the API filters, then the original chain. */
  private static final class Step implements FilterChain {

    private final List<ApiFilter> filters;
    private final FilterChain next;
    private int index;

    Step(List<ApiFilter> filters, FilterChain next) {
      this.filters = filters;
      this.next = next;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response)
        throws IOException, ServletException {
      if (index < filters.size()) {
        ApiFilter filter = filters.get(index++);
        filter.doFilter((HttpServletRequest) request, (HttpServletResponse) response, this);
      } else {
        next.doFilter(request, response);
      }
    }
  }
}
