/* SPDX-License-Identifier: Apache-2.0 */
package example.servlet.jakarta;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Asynchronous streaming without Spring (what {@code StreamingResponseBody} and {@code SseEmitter}
 * do at the servlet layer, F-A34): {@code startAsync()}, then a container thread ({@code
 * AsyncContext.start}) writes and flushes three chunks {@value #PAUSE_MS} ms apart and completes.
 * The agent ends the transaction at the first flush with the streaming flag. Test-only.
 */
public class StreamServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  /** Pause between the chunks. */
  static final long PAUSE_MS = 150;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    response.setStatus(200);
    response.setContentType("text/plain;charset=UTF-8");
    AsyncContext async = request.startAsync();
    async.setTimeout(10_000);
    async.start(
        new Runnable() {
          @Override
          public void run() {
            try {
              ServletOutputStream out = async.getResponse().getOutputStream();
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
