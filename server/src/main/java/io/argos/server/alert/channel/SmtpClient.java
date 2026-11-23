/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * A minimal SMTP submission client for the e-mail channel (16 §8.5, SEC-54; no mail library): EHLO,
 * STARTTLS, AUTH PLAIN or LOGIN, one message per connection.
 *
 * <ul>
 *   <li>STARTTLS is required: a server that does not offer it, or a failed upgrade, fails the
 *       delivery ({@code starttls_unavailable}, {@code tls_failed}) unless the operator set {@code
 *       argos.alert.smtp.insecure=true} (plaintext and unverified certificates allowed, warned at
 *       every start).
 *   <li>The certificate is verified against the JDK trust store and its host name against the
 *       configured host ({@code check-server-identity}, default on).
 *   <li>Credentials are sent only over TLS (or with {@code insecure}); they are never logged.
 *   <li>The message is {@code text/plain; charset=UTF-8} in base64 (no dot-stuffing issue, no 8-bit
 *       transport needed), the subject RFC 2047 encoded.
 *   <li>Connect within 3 seconds; the whole exchange within {@link #TOTAL_TIMEOUT}.
 * </ul>
 */
public final class SmtpClient {

  /** Connect time limit. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

  /** Time limit of one whole delivery. */
  static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(30);

  private static final int MAX_LINE = 2048;
  private static final int MAX_REPLY_LINES = 100;

  /**
   * The SMTP connection settings. {@link #toString()} hides the password.
   *
   * @param host server host
   * @param port server port
   * @param username user, or null for no authentication
   * @param password password, or null
   * @param checkServerIdentity verify the certificate's host name
   * @param insecure allow plaintext and unverified certificates
   * @param from envelope and header sender
   * @param heloName name sent with EHLO
   */
  public record Settings(
      String host,
      int port,
      String username,
      String password,
      boolean checkServerIdentity,
      boolean insecure,
      String from,
      String heloName) {

    @Override
    public String toString() {
      return "Settings[host=" + host + ", port=" + port + ", insecure=" + insecure + "]";
    }
  }

  /** The outcome of an accepted message: the final reply code and the duration. */
  public record SmtpOutcome(int replyCode, long durationMs, boolean tls) {}

  private final ChannelTls tls;
  private final SSLContext insecureContext;
  private final HostResolver resolver;

  /**
   * The client.
   *
   * @param tls TLS of the outbound connections
   * @param insecureContext the context used with {@code insecure} (no certificate verification)
   * @param resolver name resolution of the SMTP host
   */
  public SmtpClient(ChannelTls tls, SSLContext insecureContext, HostResolver resolver) {
    this.tls = Objects.requireNonNull(tls, "tls");
    this.insecureContext = Objects.requireNonNull(insecureContext, "insecureContext");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
  }

  /**
   * Sends one message.
   *
   * @throws DeliveryException with a fixed code and the SMTP reply code when the server refused
   */
  public SmtpOutcome send(Settings s, List<String> recipients, String subject, String body)
      throws DeliveryException {
    long start = System.nanoTime();
    long deadline = start + TOTAL_TIMEOUT.toNanos();
    List<InetAddress> addresses;
    try {
      addresses = resolver.resolve(s.host());
    } catch (UnknownHostException e) {
      throw new DeliveryException("connect_failed");
    }
    if (addresses.isEmpty()) {
      throw new DeliveryException("connect_failed");
    }
    Socket socket = connect(addresses, s.port());
    try {
      boolean onTls = false;
      try {
        Conversation c = new Conversation(socket, deadline);
        c.expect(c.read(), 220, "smtp_rejected");
        List<String> extensions = c.ehlo(s.heloName());
        if (has(extensions, "STARTTLS")) {
          c.command("STARTTLS");
          c.expect(c.read(), 220, "starttls_unavailable");
          socket = upgrade(socket, s, deadline);
          c = new Conversation(socket, deadline);
          onTls = true;
          extensions = c.ehlo(s.heloName());
        } else if (!s.insecure()) {
          quietQuit(c);
          throw new DeliveryException("starttls_unavailable");
        }
        if (s.username() != null) {
          authenticate(c, s, extensions);
        }
        c.command("MAIL FROM:<" + s.from() + ">");
        c.expect(c.read(), 250, "smtp_rejected");
        for (String r : recipients) {
          c.command("RCPT TO:<" + r + ">");
          Reply reply = c.read();
          if (reply.code() != 250 && reply.code() != 251) {
            throw new DeliveryException("smtp_rejected", reply.code());
          }
        }
        c.command("DATA");
        c.expect(c.read(), 354, "smtp_rejected");
        c.raw(message(s.from(), recipients, subject, body));
        Reply accepted = c.read();
        c.expect(accepted, 250, "smtp_rejected");
        quietQuit(c);
        return new SmtpOutcome(accepted.code(), (System.nanoTime() - start) / 1_000_000L, onTls);
      } catch (SocketTimeoutException e) {
        throw new DeliveryException("timeout");
      } catch (SSLException e) {
        throw new DeliveryException("tls_failed");
      } catch (IOException e) {
        throw new DeliveryException("io_error");
      }
    } finally {
      try {
        socket.close();
      } catch (IOException ignored) {
        // closing
      }
    }
  }

  /** Connects to the first address that accepts, in resolver order. */
  private static Socket connect(List<InetAddress> addresses, int port) throws DeliveryException {
    String failure = "connect_failed";
    for (InetAddress address : addresses) {
      Socket socket = new Socket();
      try {
        socket.connect(new InetSocketAddress(address, port), (int) CONNECT_TIMEOUT.toMillis());
        return socket;
      } catch (SocketTimeoutException e) {
        failure = "connect_timeout";
      } catch (IOException e) {
        failure = "connect_failed";
      }
      try {
        socket.close();
      } catch (IOException ignored) {
        // closing a failed connection
      }
    }
    throw new DeliveryException(failure);
  }

  private Socket upgrade(Socket plain, Settings s, long deadline)
      throws IOException, DeliveryException {
    SSLContext context = s.insecure() ? insecureContext : tls.context();
    SSLSocket ssl =
        (SSLSocket) context.getSocketFactory().createSocket(plain, s.host(), s.port(), true);
    ssl.setSSLParameters(tls.clientParameters(s.host(), s.checkServerIdentity() && !s.insecure()));
    ssl.setSoTimeout(timeoutMs(deadline));
    try {
      ssl.startHandshake();
    } catch (SSLException e) {
      throw new DeliveryException("tls_failed");
    }
    return ssl;
  }

  private static void authenticate(Conversation c, Settings s, List<String> extensions)
      throws IOException, DeliveryException {
    String auth = extension(extensions, "AUTH");
    List<String> mechanisms =
        auth == null ? List.of() : List.of(auth.toUpperCase(Locale.ROOT).split("\\s+"));
    String password = s.password() == null ? "" : s.password();
    Base64.Encoder b64 = Base64.getEncoder();
    if (mechanisms.contains("PLAIN")) {
      String token = "\0" + s.username() + "\0" + password;
      c.secret("AUTH PLAIN " + b64.encodeToString(token.getBytes(StandardCharsets.UTF_8)));
      c.expect(c.read(), 235, "auth_failed");
    } else if (mechanisms.contains("LOGIN")) {
      c.command("AUTH LOGIN");
      c.expect(c.read(), 334, "auth_failed");
      c.secret(b64.encodeToString(s.username().getBytes(StandardCharsets.UTF_8)));
      c.expect(c.read(), 334, "auth_failed");
      c.secret(b64.encodeToString(password.getBytes(StandardCharsets.UTF_8)));
      c.expect(c.read(), 235, "auth_failed");
    } else {
      throw new DeliveryException("auth_failed");
    }
  }

  private static void quietQuit(Conversation c) {
    try {
      c.command("QUIT");
      c.read();
    } catch (IOException | DeliveryException | RuntimeException ignored) {
      // the message is accepted (or refused) already
    }
  }

  /** The DATA content: headers, blank line, base64 body, terminating dot. */
  static byte[] message(String from, List<String> recipients, String subject, String body) {
    StringBuilder sb = new StringBuilder(512 + body.length() * 2);
    sb.append("Date: ")
        .append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)))
        .append("\r\n");
    sb.append("From: ").append(from).append("\r\n");
    sb.append("To: ").append(String.join(", ", recipients)).append("\r\n");
    sb.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
    sb.append("Message-ID: <").append(UUID.randomUUID()).append("@argos>\r\n");
    sb.append("MIME-Version: 1.0\r\n");
    sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
    sb.append("Content-Transfer-Encoding: base64\r\n");
    sb.append("Auto-Submitted: auto-generated\r\n");
    sb.append("\r\n");
    String normalized = body.replace("\r\n", "\n").replace("\n", "\r\n");
    String encoded =
        Base64.getMimeEncoder(76, new byte[] {'\r', '\n'})
            .encodeToString(normalized.getBytes(StandardCharsets.UTF_8));
    sb.append(encoded).append("\r\n.\r\n");
    return sb.toString().getBytes(StandardCharsets.US_ASCII);
  }

  /** RFC 2047 {@code =?UTF-8?B?...?=} when the text is not plain printable ASCII. */
  static String encodeHeader(String text) {
    String one = ChannelMessage.oneLine(text);
    boolean ascii = one.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7f);
    if (ascii) {
      return one;
    }
    return "=?UTF-8?B?"
        + Base64.getEncoder().encodeToString(one.getBytes(StandardCharsets.UTF_8))
        + "?=";
  }

  private static boolean has(List<String> extensions, String name) {
    return extension(extensions, name) != null;
  }

  /** The parameters of an EHLO extension ({@code ""} without any), or null when absent. */
  private static String extension(List<String> extensions, String name) {
    for (String e : extensions) {
      String upper = e.toUpperCase(Locale.ROOT);
      if (upper.equals(name)) {
        return "";
      }
      if (upper.startsWith(name + " ") || upper.startsWith(name + "=")) {
        return e.substring(name.length() + 1).strip();
      }
    }
    return null;
  }

  private static int timeoutMs(long deadline) throws SocketTimeoutException {
    long left = (deadline - System.nanoTime()) / 1_000_000L;
    if (left <= 0) {
      throw new SocketTimeoutException("deadline");
    }
    return (int) Math.min(Integer.MAX_VALUE, left);
  }

  /** One SMTP reply: the code and its text lines. */
  record Reply(int code, List<String> lines) {}

  /** Line-oriented exchange over one socket. */
  private static final class Conversation {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final long deadline;

    Conversation(Socket socket, long deadline) throws IOException {
      this.socket = socket;
      this.in = new BufferedInputStream(socket.getInputStream(), 4096);
      this.out = socket.getOutputStream();
      this.deadline = deadline;
    }

    List<String> ehlo(String name) throws IOException, DeliveryException {
      command("EHLO " + name);
      Reply reply = read();
      expect(reply, 250, "smtp_rejected");
      return reply.lines().size() <= 1 ? List.of() : reply.lines().subList(1, reply.lines().size());
    }

    void command(String line) throws IOException {
      if (line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0) {
        throw new IllegalArgumentException("SMTP command with a line break");
      }
      raw((line + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    /** A command that carries a credential: same as {@link #command}, kept apart for clarity. */
    void secret(String line) throws IOException {
      command(line);
    }

    void raw(byte[] bytes) throws IOException {
      socket.setSoTimeout(timeoutMs(deadline));
      out.write(bytes);
      out.flush();
    }

    Reply read() throws IOException, DeliveryException {
      List<String> lines = new ArrayList<>();
      while (true) {
        String line = line();
        if (line.length() < 3) {
          throw new DeliveryException("protocol_error");
        }
        int code;
        try {
          code = Integer.parseInt(line.substring(0, 3));
        } catch (NumberFormatException e) {
          throw new DeliveryException("protocol_error");
        }
        lines.add(line.length() > 4 ? line.substring(4) : "");
        if (lines.size() > MAX_REPLY_LINES) {
          throw new DeliveryException("protocol_error");
        }
        if (line.length() == 3 || line.charAt(3) == ' ') {
          return new Reply(code, lines);
        }
        if (line.charAt(3) != '-') {
          throw new DeliveryException("protocol_error");
        }
      }
    }

    void expect(Reply reply, int code, String error) throws DeliveryException {
      if (reply.code() != code) {
        throw new DeliveryException(error, reply.code());
      }
    }

    private String line() throws IOException, DeliveryException {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
      while (true) {
        socket.setSoTimeout(timeoutMs(deadline));
        int b = in.read();
        if (b < 0) {
          throw new DeliveryException("protocol_error");
        }
        if (b == '\n') {
          String s = buffer.toString(StandardCharsets.UTF_8);
          return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
        }
        buffer.write(b);
        if (buffer.size() > MAX_LINE) {
          throw new DeliveryException("protocol_error");
        }
      }
    }
  }
}
