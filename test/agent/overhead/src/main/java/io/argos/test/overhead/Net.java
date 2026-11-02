/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Loopback ports and HTTP readiness checks. */
final class Net {
  private Net() {}

  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  /** True when something accepts a connection on 127.0.0.1:{@code port} (200 ms timeout). */
  static boolean portOpen(int port) {
    return portOpen(InetAddress.getLoopbackAddress(), port);
  }

  /** True when something accepts a connection on {@code host:port} (200 ms timeout). */
  static boolean portOpen(String host, int port) {
    try {
      return portOpen(InetAddress.getAllByName(host)[0], port);
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean portOpen(InetAddress address, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(address, port), 200);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** True when GET {@code url} answers 200 within 5 s. */
  static boolean answers200(String url) throws InterruptedException {
    try {
      HttpResponse<Void> response =
          CLIENT.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
              HttpResponse.BodyHandlers.discarding());
      return response.statusCode() == 200;
    } catch (IOException e) {
      return false;
    }
  }

  /** Polls {@code url} every 250 ms until it answers 200; false after {@code timeout}. */
  static boolean await200(String url, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (answers200(url)) {
        return true;
      }
      Thread.sleep(250);
    }
    return false;
  }
}
