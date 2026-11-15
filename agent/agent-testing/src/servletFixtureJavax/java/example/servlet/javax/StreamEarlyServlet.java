/* SPDX-License-Identifier: Apache-2.0 */
package example.servlet.javax;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Asynchronous streaming in the order of Spring's {@code StreamingResponseBody} (F-A34, ADR-0010
 * C-127): the output stream is opened on the request thread before {@code startAsync()} ({@code
 * StreamingResponseBodyReturnValueHandler} calls {@code outputMessage.getBody()} before {@code
 * startCallableProcessing}), then a container thread ({@code AsyncContext.start}) writes and
 * flushes three chunks {@value #PAUSE_MS} ms apart on that stream and completes. The agent ends the
 * transaction at the first flush with the streaming flag. Test-only.
 */
public class StreamEarlyServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  /** Pause between the chunks. */
  static final long PAUSE_MS = 150;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setStatus(200);
    response.setContentType("text/plain;charset=UTF-8");
    final ServletOutputStream out = response.getOutputStream();
    final AsyncContext async = request.startAsync();
    async.setTimeout(10_000);
    async.start(
        new Runnable() {
          @Override
          public void run() {
            try {
              for (int chunk = 1; chunk <= 3; chunk++) {
                out.write(("chunk " + chunk + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(PAUSE_MS);
              }
            } catch (IOException e) {
              // The client went away: nothing more to write.
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              async.complete();
            }
          }
        });
  }
}
