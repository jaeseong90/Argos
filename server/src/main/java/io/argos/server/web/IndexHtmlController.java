/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.web.security.CspPolicy;
import io.argos.server.web.security.IndexHtmlTemplate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the single-page UI (15 §3, 16 §9.9): {@code static/index.html} of the jar, for {@code /}
 * and the client routes, as a template with a new nonce per request, and the
 * Content-Security-Policy that names that nonce. The page is public: the UI itself asks the API for
 * the session, and the page holds no data. It is never cached (a cached page would reuse a nonce).
 *
 * <p>A client route is any path whose first segment is not reserved for the server ({@value
 * #RESERVED}) and whose last segment has no file extension. So {@code /api/**}, {@code
 * /actuator/**} and {@code /ws} never get the page, nor does a request for a file: those are
 * answered by their handlers or by the static resource handler, and a missing file is 404 {@code
 * not_found}.
 *
 * <p>A build without the UI ({@code -Pargos.ui.skip=true}) has no page: 404.
 */
@RestController
@RequiresRole(Role.PUBLIC)
public class IndexHtmlController {

  /** First path segments that are never a client route. */
  static final String RESERVED = "api|actuator|ws|error|assets|v3|swagger-ui";

  /** A first path segment of a client route: not reserved, no file extension. */
  private static final String FIRST_SEGMENT = "{first:^(?!(?:" + RESERVED + ")$)[^.]+$}";

  private static final String PAGE = "static/index.html";

  private final CspPolicy policy;
  private final Optional<IndexHtmlTemplate> template;

  @Autowired
  public IndexHtmlController(CspPolicy policy) {
    this(policy, new ClassPathResource(PAGE));
  }

  /** A controller of the page in {@code page} (tests). */
  IndexHtmlController(CspPolicy policy, Resource page) {
    this.policy = policy;
    this.template = load(page);
  }

  /**
   * The page with a new nonce and its policy. {@code /index.html} is mapped too: the static
   * resource handler would serve the bare file, without nonces and without the policy.
   */
  @GetMapping({"/", "/index.html", "/" + FIRST_SEGMENT + "/{*rest}"})
  public ResponseEntity<byte[]> page(@PathVariable(name = "rest", required = false) String rest) {
    if (rest != null && hasFileExtension(rest)) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    IndexHtmlTemplate page = template.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    String nonce = CspPolicy.newNonce();
    return ResponseEntity.ok()
        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
        .cacheControl(CacheControl.noStore())
        .header(CspPolicy.HEADER, policy.header(nonce))
        .header(CspPolicy.REPORTING_ENDPOINTS_HEADER, CspPolicy.REPORTING_ENDPOINTS)
        .body(page.render(nonce));
  }

  /** Whether the last segment of {@code path} looks like a file name. */
  static boolean hasFileExtension(String path) {
    return path.substring(path.lastIndexOf('/') + 1).contains(".");
  }

  private static Optional<IndexHtmlTemplate> load(Resource page) {
    if (!page.exists()) {
      return Optional.empty();
    }
    try {
      return Optional.of(IndexHtmlTemplate.parse(page.getContentAsString(StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the UI page " + PAGE, e);
    }
  }
}
