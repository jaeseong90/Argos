/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request body limit of the HTTP port (14 §7.2): at most {@link #MAX_BODY_BYTES} bytes for every
 * content type. A declared {@code Content-Length} above the limit is answered at once with 400
 * {@code payload_too_large} (14 §2.6, §7.2) without reading the body. A body without a declared
 * length (chunked) is cut off by an input stream that throws {@link PayloadTooLargeException} (an
 * {@link IOException}) to whoever reads past the limit. This filter does not turn that exception
 * into a response: mapping it, also when it arrives as the cause of Spring's {@code
 * HttpMessageNotReadableException}, to 400 {@code payload_too_large} is the job of the global API
 * exception handling of WO-24. Until then Spring's default handling applies: a {@code @RequestBody}
 * argument fails with {@code HttpMessageNotReadableException} (400 in Spring Boot's error format,
 * not the 14 §2.6 schema), other readers may end in a 5xx. The Tomcat connector limits form posts
 * and swallowed uploads to the same size ({@code application.yml}).
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

  /** 1 MiB (14 §7.2). */
  public static final long MAX_BODY_BYTES = 1024L * 1024L;

  private final long limit;

  /** The limit of 14 §7.2. */
  public RequestBodyLimitFilter() {
    this(MAX_BODY_BYTES);
  }

  RequestBodyLimitFilter(long limit) {
    this.limit = limit;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > limit) {
      ErrorResponses.write(
          request,
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          "payload_too_large",
          "The request body exceeds the limit of 1 MB");
      return;
    }
    chain.doFilter(new LimitedRequest(request, limit), response);
  }

  /** The body passed the limit while it was read. */
  public static final class PayloadTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    PayloadTooLargeException(long limit) {
      super("The request body exceeds the limit of " + limit + " bytes");
    }
  }

  private static final class LimitedRequest extends HttpServletRequestWrapper {

    private final long limit;
    private ServletInputStream stream;

    LimitedRequest(HttpServletRequest request, long limit) {
      super(request);
      this.limit = limit;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (stream == null) {
        stream = new LimitedInputStream(super.getInputStream(), limit);
      }
      return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }

  private static final class LimitedInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long limit;
    private long count;

    LimitedInputStream(ServletInputStream delegate, long limit) {
      this.delegate = delegate;
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      int b = delegate.read();
      if (b >= 0) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = delegate.read(buffer, offset, length);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(int n) throws PayloadTooLargeException {
      count += n;
      if (count > limit) {
        throw new PayloadTooLargeException(limit);
      }
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      delegate.setReadListener(listener);
    }

    @Override
    public int available() throws IOException {
      return delegate.available();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
