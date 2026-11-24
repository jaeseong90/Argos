/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The syslog audit sink (12 §8.1 {@code syslog://}, {@code syslog+tcp://}, {@code syslog+tls://};
 * 16 §11.4): every record as an RFC 5424 message ({@link SyslogFormat}) over UDP (RFC 5426, one
 * datagram of at most {@value #UDP_MAX_BYTES} bytes), TCP (RFC 6587 octet counting: {@code <length>
 * <message>}) or TLS (RFC 5425, octet counting, the JDK's trust store and host name verification).
 *
 * <p>It never blocks the audited request: {@link #write} puts the record on a bounded queue
 * ({@value #QUEUE_CAPACITY}) that the thread {@code argos-audit-syslog} sends; a full queue drops
 * the record, counted in {@code argos_audit_dropped_total{reason="syslog_queue_full"}}. A send
 * failure is counted in {@code argos_audit_write_failures_total} (the PostgreSQL row exists either
 * way) and logged at most once a minute without the record; after a failed connection the sink
 * waits {@value #RECONNECT_DELAY_MS} ms before the next attempt and counts the records of that time
 * as failures.
 */
public final class SyslogAuditSink implements AuditSink {

  private static final Logger LOG = LoggerFactory.getLogger(SyslogAuditSink.class);

  /**
   * Largest UDP message (RFC 5426 recommends at least 2,048 octets be accepted; 8 KiB is common).
   */
  static final int UDP_MAX_BYTES = 8 * 1024;

  /** Largest TCP or TLS message. */
  static final int STREAM_MAX_BYTES = 64 * 1024;

  /** Records waiting to be sent. */
  static final int QUEUE_CAPACITY = 1_000;

  static final long RECONNECT_DELAY_MS = 5_000;

  private static final int CONNECT_TIMEOUT_MS = 5_000;
  private static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  /** The transport of a syslog sink. */
  public enum Transport {
    /** {@code syslog://}, default port 514. */
    UDP(514),
    /** {@code syslog+tcp://}, default port 514. */
    TCP(514),
    /** {@code syslog+tls://}, default port 6514. */
    TLS(6514);

    private final int defaultPort;

    Transport(int defaultPort) {
      this.defaultPort = defaultPort;
    }

    /** The port when the address names none. */
    public int defaultPort() {
      return defaultPort;
    }
  }

  private final Transport transport;
  private final String host;
  private final int port;
  private final String hostname;
  private final String procId;
  private final BlockingQueue<AuditRecord> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final Counter dropped;
  private final Counter failures;
  private final Thread sender;
  private volatile boolean closed;
  private long lastWarn = Long.MIN_VALUE;
  private long nextConnectMs;
  private DatagramSocket udp;
  private Socket stream;
  private OutputStream out;

  /**
   * Starts the sink and its sender thread.
   *
   * @param transport UDP, TCP or TLS
   * @param host the receiver's host name or address
   * @param port the receiver's port
   * @param registry where the drop and failure counters go
   */
  public SyslogAuditSink(Transport transport, String host, int port, MeterRegistry registry) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.host = Objects.requireNonNull(host, "host");
    this.port = port;
    this.hostname = localHostName();
    this.procId = Long.toString(ProcessHandle.current().pid());
    this.dropped =
        Counter.builder(AsyncAuditDispatcher.DROPPED_METER)
            .description("Audit records dropped before they reached a sink")
            .tag("reason", "syslog_queue_full")
            .register(registry);
    this.failures =
        Counter.builder(DefaultAuditService.FAILURES_METER)
            .description("Audit records that could not be written to a sink")
            .register(registry);
    this.sender = new Thread(this::run, "argos-audit-syslog");
    this.sender.setDaemon(true);
    this.sender.start();
  }

  /** {@code udp://host:port} style description for logs (no secrets in a syslog address). */
  public String describe() {
    return transport.name().toLowerCase(Locale.ROOT) + "://" + host + ":" + port;
  }

  @Override
  public void write(AuditRecord record) {
    if (closed || !queue.offer(record)) {
      dropped.increment();
    }
  }

  /** Records waiting to be sent (tests). */
  int pending() {
    return queue.size();
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!queue.isEmpty() && System.nanoTime() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    sender.interrupt();
    try {
      sender.join(2_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    disconnect();
  }

  private void run() {
    while (!closed || !queue.isEmpty()) {
      AuditRecord record;
      try {
        record = queue.poll(500, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        if (queue.isEmpty()) {
          return;
        }
        continue;
      }
      if (record != null) {
        send(record);
      }
    }
  }

  private void send(AuditRecord record) {
    try {
      if (transport == Transport.UDP) {
        byte[] message =
            SyslogFormat.message(record, hostname, procId, UDP_MAX_BYTES)
                .getBytes(StandardCharsets.UTF_8);
        if (udp == null) {
          udp = new DatagramSocket();
        }
        InetSocketAddress target = new InetSocketAddress(host, port);
        if (target.isUnresolved()) {
          throw new UnknownHostException("unresolved");
        }
        udp.send(new DatagramPacket(message, message.length, target));
        return;
      }
      byte[] message =
          SyslogFormat.message(record, hostname, procId, STREAM_MAX_BYTES)
              .getBytes(StandardCharsets.UTF_8);
      if (out == null) {
        connect();
      }
      out.write((message.length + " ").getBytes(StandardCharsets.US_ASCII));
      out.write(message);
      out.flush();
    } catch (IOException | RuntimeException e) {
      failures.increment();
      disconnect();
      warn(e);
    }
  }

  private void connect() throws IOException {
    long now = System.currentTimeMillis();
    if (now < nextConnectMs) {
      throw new IOException("waiting before the next connection attempt");
    }
    nextConnectMs = now + RECONNECT_DELAY_MS;
    Socket socket;
    if (transport == Transport.TLS) {
      SSLSocket tls = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
      tls.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
      SSLParameters parameters = tls.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      tls.setSSLParameters(parameters);
      tls.startHandshake();
      socket = tls;
    } else {
      socket = new Socket();
      socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
    }
    socket.setKeepAlive(true);
    stream = socket;
    out = socket.getOutputStream();
    nextConnectMs = 0;
  }

  private void disconnect() {
    if (stream != null) {
      try {
        stream.close();
      } catch (IOException e) {
        // closing anyway
      }
    }
    stream = null;
    out = null;
    if (closed && udp != null) {
      udp.close();
      udp = null;
    }
  }

  private void warn(Exception e) {
    long now = System.nanoTime();
    if (lastWarn == Long.MIN_VALUE || now - lastWarn >= WARN_INTERVAL_NANOS) {
      lastWarn = now;
      LOG.warn(
          "Audit records could not be sent to syslog {} ({}); counted in"
              + " argos_audit_write_failures_total, further failures are not logged for a minute",
          describe(),
          e.getClass().getSimpleName());
    }
  }

  private static String localHostName() {
    for (String variable : new String[] {"HOSTNAME", "COMPUTERNAME"}) {
      String value = System.getenv(variable);
      if (value != null && !value.isBlank()) {
        return value.strip();
      }
    }
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException | RuntimeException e) {
      return "-";
    }
  }
}
