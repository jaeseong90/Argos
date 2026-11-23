/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * The HTTP client of webhook and Slack deliveries (16 §8.1 steps 6 and 7, SEC-51): HTTP/1.1 {@code
 * POST} over a JDK socket to the <em>pinned</em> address of a {@link Destination}, never a new name
 * lookup (DNS rebinding). For https the TLS session uses the original host name for SNI and for the
 * certificate's host name check, and the {@code Host} header carries it too.
 *
 * <ul>
 *   <li>Redirects are never followed: a 3xx answer is the failure {@code redirect}.
 *   <li>Connect within 3 seconds, everything (connect, TLS, request, response head) within the
 *       total time limit ({@code argos.alert.webhook.timeout}, 5 seconds).
 *   <li>The response body is read and discarded, at most 64 KB; it is never stored, logged or
 *       shown. Only the status code and the duration are reported ({@link HttpOutcome}).
 *   <li>One request per connection ({@code Connection: close}).
 * </ul>
 */
public final class PinnedHttpClient {

  /** Connect time limit (16 §8.1 step 7). */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

  /** Response bytes read at most (16 §8.1 step 7). */
  static final int MAX_BODY_BYTES = 64 * 1024;

  /** Response head bytes accepted at most. */
  static final int MAX_HEAD_BYTES = 16 * 1024;

  private final ChannelTls tls;
  private final Duration total;
  private final String userAgent;

  /**
   * The client.
   *
   * @param tls TLS of the outbound connections
   * @param total total time limit of one request
   * @param userAgent {@code User-Agent} value
   */
  public PinnedHttpClient(ChannelTls tls, Duration total, String userAgent) {
    this.tls = Objects.requireNonNull(tls, "tls");
    this.total = Objects.requireNonNull(total, "total");
    this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
  }

  /** The outcome of a request that got a 2xx answer. */
  public record HttpOutcome(int status, long durationMs) {}

  /**
   * Posts {@code body} to {@code destination}.
   *
   * @param headers extra request headers (names and values without CR or LF)
   * @param contentType the body's media type
   * @return status and duration of a 2xx answer
   * @throws DeliveryException for every other outcome, with the status when the peer answered
   */
  public HttpOutcome post(
      Destination destination, Map<String, String> headers, String contentType, byte[] body)
      throws DeliveryException {
    long start = System.nanoTime();
    long deadline = start + total.toNanos();
    byte[] head = requestHead(destination, headers, contentType, body.length);
    Socket raw = new Socket();
    Socket socket = raw;
    try {
      try {
        int connectMs =
            (int) Math.max(1, Math.min(CONNECT_TIMEOUT.toMillis(), remainingMs(deadline)));
        raw.connect(new InetSocketAddress(destination.pinned(), destination.port()), connectMs);
      } catch (SocketTimeoutException e) {
        throw new DeliveryException("connect_timeout");
      } catch (IOException e) {
        throw new DeliveryException("connect_failed");
      }
      if (destination.https()) {
        try {
          SSLSocket ssl =
              (SSLSocket)
                  tls.context()
                      .getSocketFactory()
                      .createSocket(raw, destination.host(), destination.port(), true);
          ssl.setSSLParameters(tls.clientParameters(destination.host(), true));
          ssl.setSoTimeout(timeoutMs(deadline));
          socket = ssl;
          ssl.startHandshake();
        } catch (SocketTimeoutException e) {
          throw new DeliveryException("timeout");
        } catch (SSLException e) {
          throw new DeliveryException("tls_failed");
        } catch (IOException e) {
          throw new DeliveryException("tls_failed");
        }
      }
      int status;
      try {
        socket.setSoTimeout(timeoutMs(deadline));
        OutputStream out = socket.getOutputStream();
        out.write(head);
        out.write(body);
        out.flush();
        InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
        status = readStatus(socket, in, deadline);
        discardBody(socket, in, deadline);
      } catch (SocketTimeoutException e) {
        throw new DeliveryException("timeout");
      } catch (SSLException e) {
        throw new DeliveryException("tls_failed");
      } catch (IOException e) {
        throw new DeliveryException("io_error");
      }
      long durationMs = (System.nanoTime() - start) / 1_000_000L;
      if (status >= 200 && status < 300) {
        return new HttpOutcome(status, durationMs);
      }
      if (status >= 300 && status < 400) {
        throw new DeliveryException("redirect", status);
      }
      throw new DeliveryException("http_status", status);
    } finally {
      try {
        socket.close();
      } catch (IOException ignored) {
        // closing a failed connection
      }
    }
  }

  private byte[] requestHead(
      Destination destination, Map<String, String> headers, String contentType, int length) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("POST ").append(destination.requestTarget()).append(" HTTP/1.1\r\n");
    header(sb, "Host", destination.hostHeader());
    header(sb, "User-Agent", userAgent);
    header(sb, "Content-Type", contentType);
    header(sb, "Content-Length", Integer.toString(length));
    header(sb, "Accept", "*/*");
    header(sb, "Connection", "close");
    for (Map.Entry<String, String> e : headers.entrySet()) {
      header(sb, e.getKey(), e.getValue());
    }
    sb.append("\r\n");
    return sb.toString().getBytes(StandardCharsets.US_ASCII);
  }

  private static void header(StringBuilder sb, String name, String value) {
    if (!safe(name) || !safe(value) || name.indexOf(':') >= 0) {
      throw new IllegalArgumentException("header with a control character");
    }
    sb.append(name).append(": ").append(value).append("\r\n");
  }

  private static boolean safe(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x20 || c >= 0x7f) {
        return false;
      }
    }
    return true;
  }

  /** Reads response heads until a final (non-1xx) status; the head is bounded. */
  private static int readStatus(Socket socket, InputStream in, long deadline)
      throws IOException, DeliveryException {
    while (true) {
      String head = readHead(socket, in, deadline);
      int lineEnd = head.indexOf("\r\n");
      String statusLine = lineEnd < 0 ? head : head.substring(0, lineEnd);
      String[] parts = statusLine.split(" ", 3);
      if (parts.length < 2 || !parts[0].toUpperCase(Locale.ROOT).startsWith("HTTP/1.")) {
        throw new DeliveryException("protocol_error");
      }
      int status;
      try {
        status = Integer.parseInt(parts[1]);
      } catch (NumberFormatException e) {
        throw new DeliveryException("protocol_error");
      }
      if (status < 100 || status > 599) {
        throw new DeliveryException("protocol_error");
      }
      if (status >= 200 || status == 101) {
        return status;
      }
    }
  }

  private static String readHead(Socket socket, InputStream in, long deadline)
      throws IOException, DeliveryException {
    ByteArrayOutputStream head = new ByteArrayOutputStream(512);
    int matched = 0;
    while (true) {
      socket.setSoTimeout(timeoutMs(deadline));
      int b = in.read();
      if (b < 0) {
        throw new DeliveryException("protocol_error");
      }
      head.write(b);
      if (head.size() > MAX_HEAD_BYTES) {
        throw new DeliveryException("protocol_error");
      }
      matched =
          switch (matched) {
            case 0, 2 -> b == '\r' ? matched + 1 : 0;
            case 1, 3 -> b == '\n' ? matched + 1 : (b == '\r' ? 1 : 0);
            default -> 0;
          };
      if (matched == 4) {
        return head.toString(StandardCharsets.ISO_8859_1);
      }
    }
  }

  /** Reads and drops at most {@link #MAX_BODY_BYTES}; a slow or endless body only ends the read. */
  private static void discardBody(Socket socket, InputStream in, long deadline) {
    byte[] buffer = new byte[8192];
    int read = 0;
    try {
      while (read < MAX_BODY_BYTES) {
        long left = remainingMs(deadline);
        if (left <= 0) {
          return;
        }
        socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, left));
        int n = in.read(buffer, 0, Math.min(buffer.length, MAX_BODY_BYTES - read));
        if (n < 0) {
          return;
        }
        read += n;
      }
    } catch (IOException e) {
      // the status is what counts; the body is dropped anyway
    }
  }

  private static long remainingMs(long deadline) {
    return (deadline - System.nanoTime()) / 1_000_000L;
  }

  private static int timeoutMs(long deadline) throws SocketTimeoutException {
    long left = remainingMs(deadline);
    if (left <= 0) {
      throw new SocketTimeoutException("deadline");
    }
    return (int) Math.min(Integer.MAX_VALUE, left);
  }
}
