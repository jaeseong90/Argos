/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.http1;

import io.argos.agent.transportpoc.proto.Ack;
import io.argos.agent.transportpoc.proto.TransactionBatch;
import io.argos.agent.transportpoc.sender.PocSettings;
import io.argos.agent.transportpoc.sender.PocTransport;
import io.argos.agent.transportpoc.sender.TlsTrust;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Own HTTP/1.1 + protobuf transport (P-3 option 3): one persistent connection over a JDK {@link
 * SSLSocket} (TLS 1.2/1.3 without ALPN, so any Java 8 update works), blocking I/O on the {@code
 * argos-sender} thread (no extra thread, no NIO, no direct buffers), {@code POST
 * /argos.v1.AgentService/SendTransactions} with an {@code application/x-protobuf} body and a
 * protobuf {@code Ack} response with {@code Content-Length}. A failed exchange closes the socket;
 * the next batch reconnects. Not covered: server-to-agent messages (11 §3 Session stream), which
 * HTTP/1.1 could only offer as long polling on another connection and thread.
 */
public final class Http1Transport implements PocTransport {
  static final String PATH = "/argos.v1.AgentService/SendTransactions";
  static final String CONTENT_TYPE = "application/x-protobuf";
  private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
  private static final int MAX_HEADER_LINE = 8 * 1024;

  private final PocSettings settings;
  private SSLSocketFactory factory;
  private Socket socket;
  private InputStream in;
  private OutputStream out;
  private String session = "not connected";

  public Http1Transport(PocSettings settings) {
    this.settings = settings;
  }

  @Override
  public void start() throws Exception {
    if (settings.tls()) {
      factory = TlsTrust.context(settings.caFile()).getSocketFactory();
    }
  }

  @Override
  public Ack send(TransactionBatch batch) throws IOException {
    connect();
    try {
      byte[] body = batch.toByteArray();
      String head =
          "POST "
              + PATH
              + " HTTP/1.1\r\nHost: "
              + settings.host()
              + ":"
              + settings.port()
              + "\r\nContent-Type: "
              + CONTENT_TYPE
              + "\r\nContent-Length: "
              + body.length
              + "\r\nUser-Agent: argos-agent-poc\r\n\r\n";
      out.write(head.getBytes(StandardCharsets.US_ASCII));
      out.write(body);
      out.flush();
      return Ack.parseFrom(readResponse());
    } catch (IOException | RuntimeException e) {
      disconnect();
      throw e;
    }
  }

  private void connect() throws IOException {
    if (socket != null) {
      return;
    }
    Socket raw = new Socket();
    try {
      raw.connect(
          new InetSocketAddress(settings.host(), settings.port()), settings.connectTimeoutMillis());
      raw.setTcpNoDelay(true);
      raw.setSoTimeout(settings.deadlineMillis());
      Socket connected = raw;
      if (factory != null) {
        SSLSocket ssl =
            (SSLSocket) factory.createSocket(raw, settings.host(), settings.port(), true);
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(parameters);
        ssl.startHandshake();
        session = ssl.getSession().getProtocol() + " " + ssl.getSession().getCipherSuite();
        connected = ssl;
      } else {
        session = "plaintext";
      }
      socket = connected;
      in = new BufferedInputStream(connected.getInputStream(), 8192);
      out = connected.getOutputStream();
    } catch (IOException | RuntimeException e) {
      closeQuietly(raw);
      throw e;
    }
  }

  private byte[] readResponse() throws IOException {
    String status = readLine();
    if (!status.startsWith("HTTP/1.1 ") && !status.startsWith("HTTP/1.0 ")) {
      throw new IOException("not an HTTP response: " + truncate(status));
    }
    int code = Integer.parseInt(status.substring(9, Math.min(12, status.length())).trim());
    long length = -1;
    boolean close = status.startsWith("HTTP/1.0 ");
    for (String line = readLine(); !line.isEmpty(); line = readLine()) {
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      if (name.equals("content-length")) {
        length = Long.parseLong(value);
      } else if (name.equals("connection") && value.equalsIgnoreCase("close")) {
        close = true;
      } else if (name.equals("transfer-encoding")) {
        throw new IOException("unsupported transfer-encoding " + truncate(value));
      }
    }
    if (length < 0 || length > MAX_RESPONSE_BYTES) {
      throw new IOException("bad content-length " + length);
    }
    byte[] body = new byte[(int) length];
    int read = 0;
    while (read < body.length) {
      int n = in.read(body, read, body.length - read);
      if (n < 0) {
        throw new EOFException("response body truncated");
      }
      read += n;
    }
    if (close) {
      disconnect();
    }
    if (code != 200) {
      throw new IOException("HTTP " + code);
    }
    return body;
  }

  private String readLine() throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream(64);
    while (true) {
      int b = in.read();
      if (b < 0) {
        throw new EOFException("connection closed by the server");
      }
      if (b == '\n') {
        break;
      }
      if (b != '\r') {
        if (line.size() >= MAX_HEADER_LINE) {
          throw new IOException("header line too long");
        }
        line.write(b);
      }
    }
    return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
  }

  private static String truncate(String text) {
    return text.length() <= 64 ? text : text.substring(0, 64) + "...";
  }

  private void disconnect() {
    closeQuietly(socket);
    socket = null;
    in = null;
    out = null;
  }

  private static void closeQuietly(Socket target) {
    if (target == null) {
      return;
    }
    try {
      target.close();
    } catch (IOException ignored) {
      // Nothing to do: the next batch reconnects.
    }
  }

  @Override
  public String connectionInfo() {
    return session + " alpn=none";
  }

  @Override
  public void close() {
    disconnect();
  }

  @Override
  public String name() {
    return "http1";
  }
}
