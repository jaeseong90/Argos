/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Caching of the UI's hashed static files (ADR-0010 C-190, 15 §14, N-14): every file below {@value
 * #ASSETS_PATTERN} has a content hash in its name (Vite build, {@code ui/vite.config.ts}), so a
 * changed file is a new URL and a browser may keep a fetched one for a year without asking again:
 * {@code Cache-Control: max-age=31536000, public, immutable} (the directives of C-190; their order
 * has no meaning, RFC 9111 §5.2).
 *
 * <p>This is the only exception to "never cached" on the HTTP port, and it is narrow: the page
 * ({@code index.html} and the client routes, {@link IndexHtmlController}) carries a new nonce per
 * response and stays {@code no-store}, and every {@code /api/**} response stays {@code no-store}
 * whatever a handler sets ({@link io.argos.server.web.security.SecurityHeadersFilter}, SEC-45).
 * Other static files (for example {@code /licenses.txt}) keep Spring Boot's default handler without
 * a {@code Cache-Control} header. A missing file below {@value #ASSETS_PATTERN} is 404 without the
 * caching header (the resource handler sets it only for a file it serves).
 *
 * <p>Compression of these files is Spring Boot's response compression ({@code server.compression}
 * in {@code application.yml}: HTML, CSS, JavaScript and SVG of 2 KB or more; API JSON is not
 * compressed by it, C-190). There are no pre-compressed files.
 */
@Configuration(proxyBeanMethods = false)
public class StaticAssetsConfiguration implements WebMvcConfigurer {

  /** The hashed files of the UI build. */
  public static final String ASSETS_PATTERN = "/assets/**";

  /** Where the UI build puts them in the jar ({@code ui/dist/assets}, 19 §3.5). */
  static final String ASSETS_LOCATION = "classpath:/static/assets/";

  /** One year, shared caches allowed, never revalidated (C-190). */
  public static final CacheControl ASSETS_CACHE_CONTROL =
      CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler(ASSETS_PATTERN)
        .addResourceLocations(ASSETS_LOCATION)
        .setCacheControl(ASSETS_CACHE_CONTROL);
  }
}
