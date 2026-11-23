/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.alert;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * An in-process SMTP server for the e-mail channel tests (WO-25; SEC-54, 18 §19): 127.0.0.1 on a
 * free port, EHLO with or without {@code STARTTLS} (server certificate of a test PKI), {@code AUTH
 * PLAIN} and {@code AUTH LOGIN} against one account, and an inbox of accepted messages with the TLS
 * state of their session. Nothing is relayed.
 */
public final class FakeSmtpServer implements AutoCloseable {

  /**
   * An accepted message.
   *
   * @param from {@code MAIL FROM} address
   * @param recipients {@code RCPT TO} addresses
   * @param data the DATA content (headers and body, CRLF, without the final dot)
   * @param tls whether the session was upgraded with STARTTLS
   * @param authenticatedUser the user of a successful AUTH, or null
   */
  public record Message(
      String from, List<String> recipients, String data, boolean tls, String authenticatedUser) {

    /** A header value of the message, or null. */
    public String header(String name) {
      for (String line : data.split("\r\n", -1)) {
        if (line.isEmpty()) {
          return null;
        }
        int colon = line.indexOf(':');
        if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
          return line.substring(colon + 1).strip();
        }
      }
      return null;
    }

    /** The body decoded from base64 (the e-mail channel writes base64). */
    public String decodedBody() {
      int split = data.indexOf("\r\n\r\n");
      String body = split < 0 ? "" : data.substring(split + 4);
      return new String(Base64.getMimeDecoder().decode(body), StandardCharsets.UTF_8);
    }
  }

  private final ServerSocket server;
  private volatile boolean offerStartTls;
  private final SSLContext tls;
  private final String user;
  private final String password;
  private final List<Message> inbox = new CopyOnWriteArrayList<>();
  private final List<String> transcript = new CopyOnWriteArrayList<>();
  private final Thread acceptor;
  private volatile boolean closed;

  /**
   * Starts the server.
   *
   * @param offerStartTls whether EHLO offers STARTTLS
   * @param tls the server context for STARTTLS (required when offered)
   * @param user the account user, or null for no AUTH
   * @param password the account password
   */
  public FakeSmtpServer(boolean offerStartTls, SSLContext tls, String user, String password) {
    this.offerStartTls = offerStartTls;
    this.tls = tls;
    this.user = user;
    this.password = password;
    try {
      this.server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    this.acceptor = Thread.ofPlatform().daemon().name("fake-smtp").start(this::acceptLoop);
  }

  /** Whether the following sessions offer STARTTLS (a TLS context must have been given). */
  public FakeSmtpServer offerStartTls(boolean value) {
    this.offerStartTls = value;
    return this;
  }

  /** The port. */
  public int port() {
    return server.getLocalPort();
  }

  /** The accepted messages. */
  public List<Message> inbox() {
    return new ArrayList<>(inbox);
  }

  /**
   * Every command line the server read, credentials replaced by {@code <credential>} (for asserting
   * that no AUTH happened over plaintext).
   */
  public List<String> transcript() {
    return new ArrayList<>(transcript);
  }

  /** Waits until {@code count} messages arrived. */
  public List<Message> await(int count, Duration timeout) {
    long end = System.nanoTime() + timeout.toNanos();
    while (inbox.size() < count) {
      if (System.nanoTime() > end) {
        throw new AssertionError("expected " + count + " mails, got " + inbox.size());
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted", e);
      }
    }
    return inbox();
  }

  @Override
  public void close() {
    closed = true;
    try {
      server.close();
    } catch (IOException ignored) {
      // closing
    }
    acceptor.interrupt();
  }

  private void acceptLoop() {
    while (!closed) {
      try {
        Socket socket = server.accept();
        Thread.ofPlatform().daemon().name("fake-smtp-session").start(() -> session(socket));
      } catch (IOException e) {
        if (!closed) {
          transcript.add("accept failed: " + e.getClass().getSimpleName());
        }
        return;
      }
    }
  }

  private void session(Socket plain) {
    Socket socket = plain;
    boolean onTls = false;
    String authenticated = null;
    String from = null;
    List<String> recipients = new ArrayList<>();
    try {
      socket.setSoTimeout(30_000);
      BufferedReader in = reader(socket);
      OutputStream out = socket.getOutputStream();
      reply(out, "220 fake.smtp ESMTP");
      while (true) {
        String line = in.readLine();
        if (line == null) {
          return;
        }
        String upper = line.toUpperCase(Locale.ROOT);
        transcript.add(upper.startsWith("AUTH PLAIN ") ? "AUTH PLAIN <credential>" : line);
        if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
          List<String> lines = new ArrayList<>(List.of("fake.smtp"));
          if (offerStartTls && !onTls) {
            lines.add("STARTTLS");
          }
          if (user != null) {
            lines.add("AUTH PLAIN LOGIN");
          }
          lines.add("8BITMIME");
          for (int i = 0; i < lines.size(); i++) {
            reply(out, "250" + (i == lines.size() - 1 ? " " : "-") + lines.get(i));
          }
        } else if (upper.equals("STARTTLS") && offerStartTls && !onTls) {
          reply(out, "220 ready to start TLS");
          SSLSocket ssl =
              (SSLSocket) tls.getSocketFactory().createSocket(socket, null, socket.getPort(), true);
          ssl.setUseClientMode(false);
          ssl.startHandshake();
          socket = ssl;
          onTls = true;
          in = reader(socket);
          out = socket.getOutputStream();
        } else if (upper.startsWith("AUTH PLAIN ")) {
          String[] parts =
              new String(
                      Base64.getDecoder().decode(line.substring(11).strip()),
                      StandardCharsets.UTF_8)
                  .split("\0", -1);
          if (parts.length == 3 && parts[1].equals(user) && parts[2].equals(password)) {
            authenticated = parts[1];
            reply(out, "235 authenticated");
          } else {
            reply(out, "535 authentication failed");
          }
        } else if (upper.equals("AUTH LOGIN")) {
          reply(out, "334 VXNlcm5hbWU6");
          String u = decode(in.readLine());
          transcript.add("<credential>");
          reply(out, "334 UGFzc3dvcmQ6");
          String p = decode(in.readLine());
          transcript.add("<credential>");
          if (u.equals(user) && p.equals(password)) {
            authenticated = u;
            reply(out, "235 authenticated");
          } else {
            reply(out, "535 authentication failed");
          }
        } else if (upper.startsWith("MAIL FROM:")) {
          from = address(line.substring(10));
          recipients.clear();
          reply(out, "250 ok");
        } else if (upper.startsWith("RCPT TO:")) {
          recipients.add(address(line.substring(8)));
          reply(out, "250 ok");
        } else if (upper.equals("DATA")) {
          reply(out, "354 end with .");
          StringBuilder data = new StringBuilder();
          while (true) {
            String d = in.readLine();
            if (d == null) {
              return;
            }
            if (d.equals(".")) {
              break;
            }
            data.append(d.startsWith("..") ? d.substring(1) : d).append("\r\n");
          }
          String content = data.length() >= 2 ? data.substring(0, data.length() - 2) : "";
          inbox.add(new Message(from, List.copyOf(recipients), content, onTls, authenticated));
          reply(out, "250 queued");
        } else if (upper.equals("QUIT")) {
          reply(out, "221 bye");
          return;
        } else if (upper.equals("RSET") || upper.equals("NOOP")) {
          reply(out, "250 ok");
        } else {
          reply(out, "502 not implemented");
        }
      }
    } catch (IOException | RuntimeException e) {
      transcript.add("session ended: " + e.getClass().getSimpleName());
    } finally {
      try {
        socket.close();
      } catch (IOException ignored) {
        // closing
      }
    }
  }

  private static BufferedReader reader(Socket socket) throws IOException {
    return new BufferedReader(
        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
  }

  private static void reply(OutputStream out, String line) throws IOException {
    out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static String decode(String b64) {
    return b64 == null
        ? ""
        : new String(Base64.getDecoder().decode(b64.strip()), StandardCharsets.UTF_8);
  }

  private static String address(String text) {
    String t = text.strip();
    int lt = t.indexOf('<');
    int gt = t.indexOf('>');
    return lt >= 0 && gt > lt ? t.substring(lt + 1, gt) : t;
  }
}
