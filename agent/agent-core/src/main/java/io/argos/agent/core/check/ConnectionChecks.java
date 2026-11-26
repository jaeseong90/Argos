/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.check;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.transport.CheckProbe;
import io.argos.proto.v1.ServerHello;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLException;

/**
 * Items 3 to 5 of 10 §13.3 from one name lookup and one {@link CheckProbe} session round:
 *
 * <ul>
 *   <li>3 {@code dns}: the host of {@code server.address} resolves (an IP address needs no lookup);
 *       {@code tcp}: the connection is made within 5 s; {@code tls}: the handshake verifies the
 *       chain ({@code server.tls.ca-file} or the JDK trust) and the host name, and the server's
 *       certificates are valid for more than 30 days (WARN within 30 days, FAIL when expired).
 *       {@code insecure-skip-verify} is WARN "TLS verification disabled" (16 §2: warning grade, so
 *       the exit code stays 0), {@code server.tls: false} is WARN "plaintext".
 *   <li>4 {@code hello}: the server accepted the {@code AgentHello} (server version, protocol, the
 *       agent versions it accepts, app and instance ids), or why it refused it (token, app
 *       approval, version, plaintext, limits) with what to do. The Hello is check-only ({@code
 *       AgentHello.check_only}, ADR-0010 C-240 (1)): a server that knows it registers no instance
 *       for it; an older server registers {@code <instance or host>-check} as an instance and the
 *       check ends that session with {@code AgentBye}.
 *   <li>5 {@code clock}: the offset {@code server_time_ms - (sent + received) / 2} of the hello
 *       round; WARN at 1 s or more (11 §9, F-S28).
 * </ul>
 *
 * A check that cannot run because an earlier one failed is SKIP.
 */
final class ConnectionChecks {
  /** WARN when a server certificate expires within this many days (10 §13.3 item 3). */
  static final long EXPIRY_WARN_DAYS = 30;

  /** WARN at this clock offset or more (11 §9, F-S28). */
  static final long CLOCK_WARN_MILLIS = 1_000;

  static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

  /** Resolves a host name to addresses; throws {@link UnknownHostException} when it cannot. */
  interface Resolver {
    List<String> resolve(String host) throws UnknownHostException;
  }

  /** Runs the session round of {@link CheckProbe#run}. */
  interface Prober {
    CheckProbe.Result probe(AgentConfig config, String instanceName);
  }

  /** Where a failed connection broke. */
  enum Stage {
    DNS,
    TCP,
    TLS,
    OTHER
  }

  /** A classified connection failure. */
  static final class Failure {
    final Stage stage;
    final String message;

    Failure(Stage stage, String message) {
      this.stage = stage;
      this.message = message;
    }
  }

  private ConnectionChecks() {}

  /**
   * Host and port of {@code server.address} ({@code host:port}, {@code [v6]:port}, default 9100).
   */
  static String[] hostAndPort(String address) {
    String value = address.trim();
    if (value.startsWith("[")) {
      int close = value.indexOf(']');
      if (close > 0) {
        String host = value.substring(1, close);
        String rest = value.substring(close + 1);
        return new String[] {host, rest.startsWith(":") ? rest.substring(1) : "9100"};
      }
    }
    int colon = value.indexOf(':');
    if (colon < 0) {
      return new String[] {value, "9100"};
    }
    if (value.indexOf(':', colon + 1) >= 0) {
      return new String[] {value, "9100"}; // a bare IPv6 literal
    }
    return new String[] {value.substring(0, colon), value.substring(colon + 1)};
  }

  /** True for an IPv4 or IPv6 literal (no lookup needed). */
  static boolean ipLiteral(String host) {
    if (host.indexOf(':') >= 0) {
      return true;
    }
    return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
  }

  /** Rows of items 3, 4 and 5 (dns, tcp, tls, hello, clock). */
  static List<CheckRow> rows(
      AgentConfig config,
      String instanceName,
      Resolver resolver,
      Prober prober,
      long nowMillis,
      List<String> sideEffects) {
    List<CheckRow> rows = new ArrayList<CheckRow>();
    String address = config.serverAddress();
    if (address == null || address.trim().isEmpty()) {
      String why = "server.address is not set (item 1)";
      rows.add(CheckRow.of(3, "dns", CheckRow.Status.SKIP, why));
      rows.add(CheckRow.of(3, "tcp", CheckRow.Status.SKIP, why));
      rows.add(CheckRow.of(3, "tls", CheckRow.Status.SKIP, why));
      rows.add(CheckRow.of(4, "hello", CheckRow.Status.SKIP, why));
      rows.add(CheckRow.of(5, "clock", CheckRow.Status.SKIP, why));
      return rows;
    }
    String[] hp = hostAndPort(address);
    String host = hp[0];
    String target = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + hp[1];
    if (ipLiteral(host)) {
      rows.add(CheckRow.of(3, "dns", CheckRow.Status.PASS, host + " is an IP address (no lookup)"));
    } else {
      try {
        List<String> addresses = resolver.resolve(host);
        rows.add(CheckRow.of(3, "dns", CheckRow.Status.PASS, host + " -> " + join(addresses)));
      } catch (UnknownHostException e) {
        rows.add(
            CheckRow.of(
                3,
                "dns",
                CheckRow.Status.FAIL,
                "cannot resolve " + host + ": check server.address and the DNS of this host"));
        String why = "the name does not resolve";
        rows.add(CheckRow.of(3, "tcp", CheckRow.Status.SKIP, why));
        rows.add(CheckRow.of(3, "tls", CheckRow.Status.SKIP, why));
        rows.add(CheckRow.of(4, "hello", CheckRow.Status.SKIP, why));
        rows.add(CheckRow.of(5, "clock", CheckRow.Status.SKIP, why));
        return rows;
      }
    }
    CheckProbe.Result result = prober.probe(config, instanceName);
    rows.addAll(probeRows(config, target, result, nowMillis));
    if (CheckProbe.checkOnlyAnswer(result.hello)) {
      sideEffects.add(
          "the hello check was a check-only session as instance '"
              + result.instanceName
              + "': the server registered no instance for it");
    } else if (result.hello != null && result.hello.getAccepted()) {
      sideEffects.add(
          "the hello check opened a session as instance '"
              + result.instanceName
              + "' and ended it with AgentBye; the server lists that instance as stopped (this"
              + " server is older than this agent and does not know check-only sessions)");
    } else if (result.hello != null || answeredByServer(result)) {
      sideEffects.add(
          "the hello check sent a check-only AgentHello as instance '"
              + result.instanceName
              + "'; the server registers nothing for it (a server older than this agent may"
              + " record a refused hello, for example in the approval queue)");
    }
    return rows;
  }

  /** The dns-independent rows of a probe result (tcp, tls, hello, clock). */
  static List<CheckRow> probeRows(
      AgentConfig config, String target, CheckProbe.Result result, long nowMillis) {
    List<CheckRow> rows = new ArrayList<CheckRow>();
    boolean tls = config.serverTls();
    if (result.setupError != null) {
      if (result.tlsUnsupported) {
        rows.add(CheckRow.of(3, "tcp", CheckRow.Status.SKIP, "TLS cannot be set up"));
        rows.add(
            CheckRow.of(
                3,
                "tls",
                CheckRow.Status.FAIL,
                "this JVM has no TLS ALPN (Java 8 before update 252): the agent cannot connect;"
                    + " use Java 8u252 or later"));
      } else if (tls) {
        rows.add(CheckRow.of(3, "tcp", CheckRow.Status.SKIP, "TLS cannot be set up"));
        rows.add(
            CheckRow.of(
                3,
                "tls",
                CheckRow.Status.FAIL,
                "TLS cannot be set up (check server.tls.ca-file): " + result.setupError));
      } else {
        rows.add(
            CheckRow.of(
                3,
                "tcp",
                CheckRow.Status.FAIL,
                "connection cannot be set up: " + result.setupError));
        rows.add(CheckRow.of(3, "tls", CheckRow.Status.SKIP, "no connection"));
      }
      rows.add(CheckRow.of(4, "hello", CheckRow.Status.SKIP, "no connection"));
      rows.add(CheckRow.of(5, "clock", CheckRow.Status.SKIP, "no connection"));
      return rows;
    }
    boolean reached = result.hello != null || answeredByServer(result);
    Failure failure = reached ? null : classify(result);
    // tcp
    if (reached || failure.stage == Stage.TLS) {
      rows.add(CheckRow.of(3, "tcp", CheckRow.Status.PASS, "connected to " + target));
    } else {
      rows.add(CheckRow.of(3, "tcp", CheckRow.Status.FAIL, target + ": " + failure.message));
    }
    // tls
    if (!tls) {
      rows.add(
          reached
              ? CheckRow.of(
                  3,
                  "tls",
                  CheckRow.Status.WARN,
                  "plaintext connection (server.tls: false): the token and the data are not"
                      + " encrypted")
              : CheckRow.of(3, "tls", CheckRow.Status.SKIP, "no connection"));
    } else if (reached) {
      rows.add(tlsRow(config, result, nowMillis));
    } else if (failure.stage == Stage.TLS) {
      rows.add(CheckRow.of(3, "tls", CheckRow.Status.FAIL, failure.message));
    } else {
      rows.add(CheckRow.of(3, "tls", CheckRow.Status.SKIP, "no connection"));
    }
    // hello and clock
    rows.add(helloRow(result, reached));
    rows.add(clockRow(result));
    return rows;
  }

  /** The server answered on the stream (headers, a hello, or a refusal status). */
  static boolean answeredByServer(CheckProbe.Result result) {
    if (result.serverAnswered) {
      return true;
    }
    String code = result.statusCode;
    return "UNAUTHENTICATED".equals(code)
        || "PERMISSION_DENIED".equals(code)
        || "FAILED_PRECONDITION".equals(code)
        || "RESOURCE_EXHAUSTED".equals(code)
        || "INVALID_ARGUMENT".equals(code)
        || "UNIMPLEMENTED".equals(code);
  }

  @SuppressWarnings("JavaUtilDate") // X509Certificate exposes java.util.Date
  static CheckRow tlsRow(AgentConfig config, CheckProbe.Result result, long nowMillis) {
    String protocol = result.security == null ? "TLS" : result.security;
    String validity = "";
    CheckRow.Status status = CheckRow.Status.PASS;
    if (!result.certificates.isEmpty()) {
      long notAfter = Long.MAX_VALUE;
      long notBefore = Long.MIN_VALUE;
      for (X509Certificate certificate : result.certificates) {
        notAfter = Math.min(notAfter, certificate.getNotAfter().getTime());
        notBefore = Math.max(notBefore, certificate.getNotBefore().getTime());
      }
      CheckRow.Status expiry = expiryStatus(notBefore, notAfter, nowMillis);
      long days = Math.floorDiv(notAfter - nowMillis, DAY_MILLIS);
      if (expiry == CheckRow.Status.FAIL) {
        validity =
            nowMillis < notBefore
                ? ", certificate not valid before " + date(notBefore)
                : ", certificate expired on " + date(notAfter);
      } else if (expiry == CheckRow.Status.WARN) {
        validity = ", certificate expires on " + date(notAfter) + " (in " + days + " days)";
      } else {
        validity = ", certificate valid until " + date(notAfter);
      }
      status = expiry;
    }
    if (config.serverTlsInsecureSkipVerify()) {
      return CheckRow.of(
          3,
          "tls",
          status == CheckRow.Status.FAIL ? CheckRow.Status.FAIL : CheckRow.Status.WARN,
          "TLS verification disabled (insecure-skip-verify=true): " + protocol + validity);
    }
    String trust =
        config.serverTlsCaFile() == null
            ? "chain (JDK trust store) and host name verified"
            : "chain (server.tls.ca-file) and host name verified";
    return CheckRow.of(3, "tls", status, protocol + ", " + trust + validity);
  }

  /** FAIL when not yet valid or expired, WARN within {@value #EXPIRY_WARN_DAYS} days, else PASS. */
  static CheckRow.Status expiryStatus(long notBefore, long notAfter, long nowMillis) {
    if (nowMillis < notBefore || nowMillis > notAfter) {
      return CheckRow.Status.FAIL;
    }
    if (notAfter - nowMillis <= EXPIRY_WARN_DAYS * DAY_MILLIS) {
      return CheckRow.Status.WARN;
    }
    return CheckRow.Status.PASS;
  }

  static CheckRow helloRow(CheckProbe.Result result, boolean reached) {
    ServerHello hello = result.hello;
    if (hello != null) {
      String server =
          "server "
              + orUnknown(hello.getServerVersion())
              + " (protocol "
              + hello.getProtocolVersion()
              + versions(hello)
              + ")";
      if (CheckProbe.checkOnlyAnswer(hello)) {
        return CheckRow.of(
            4,
            "hello",
            CheckRow.Status.PASS,
            "accepted by "
                + server
                + ": app id "
                + hello.getAppId()
                + ", check-only (no instance registered), instance '"
                + result.instanceName
                + "'");
      }
      if (hello.getAccepted()) {
        return CheckRow.of(
            4,
            "hello",
            CheckRow.Status.PASS,
            "accepted by "
                + server
                + ": app id "
                + hello.getAppId()
                + ", instance id "
                + hello.getInstanceId()
                + ", instance '"
                + result.instanceName
                + "'");
      }
      String reason = hello.getRejectReason().name();
      List<String> notes = new ArrayList<String>();
      if (!hello.getRejectMessage().isEmpty()) {
        notes.add("server message: " + cut(hello.getRejectMessage(), 300));
      }
      if (hello.getRetryAfterS() > 0) {
        notes.add("the server asks to retry after " + hello.getRetryAfterS() + " s");
      }
      return new CheckRow(
          4,
          "hello",
          CheckRow.Status.FAIL,
          "refused by " + server + ": " + reason + ": " + advice(reason, hello),
          notes);
    }
    String code = result.statusCode;
    if (code != null && !"OK".equals(code) && reached) {
      String reason = result.rejectReasonTrailer;
      String what = reason != null ? reason : code;
      String advice;
      if ("UNAUTHENTICATED".equals(code)) {
        advice = advice(reason == null ? "TOKEN_INVALID" : reason, null);
      } else if ("PERMISSION_DENIED".equals(code)) {
        advice = "the token does not belong to this application: check app.name";
      } else if ("FAILED_PRECONDITION".equals(code)) {
        advice =
            reason != null
                ? advice(reason, null)
                : "the server refused the session (plaintext not allowed or an unsupported"
                    + " version)";
      } else if ("RESOURCE_EXHAUSTED".equals(code)) {
        advice = "the server limits sessions or connections now; retry later";
      } else {
        advice = "the server ended the session";
      }
      List<String> notes = new ArrayList<String>();
      if (result.statusDescription != null && !result.statusDescription.isEmpty()) {
        notes.add("server status: " + code + ": " + cut(result.statusDescription, 300));
      }
      return new CheckRow(
          4, "hello", CheckRow.Status.FAIL, "refused: " + what + ": " + advice, notes);
    }
    if (result.timedOut && reached) {
      return CheckRow.of(
          4,
          "hello",
          CheckRow.Status.FAIL,
          "no ServerHello within " + (CheckProbe.HELLO_TIMEOUT_MILLIS / 1000) + " s");
    }
    return CheckRow.of(4, "hello", CheckRow.Status.SKIP, "no connection");
  }

  private static String versions(ServerHello hello) {
    String min = hello.getMinAgentVersion();
    String max = hello.getMaxAgentVersion();
    if (min.isEmpty() && max.isEmpty()) {
      return "";
    }
    return ", accepts agents " + orUnknown(min) + " to " + orUnknown(max);
  }

  /** What to do about a {@code RejectReason} (11 §5). */
  static String advice(String reason, ServerHello hello) {
    if (reason == null) {
      return "the server refused the session";
    }
    switch (reason) {
      case "TOKEN_INVALID":
        return "the token is not accepted: issue a token for this app (argos-admin token create)"
            + " and put it in server.token-file";
      case "TOKEN_EXPIRED":
        return "the token has expired: issue a new token (argos-admin token create)";
      case "TOKEN_REVOKED":
        return "the token was revoked: issue a new token (argos-admin token create)";
      case "APP_MISMATCH":
        return "the token belongs to another application: check app.name";
      case "APP_NOT_REGISTERED":
        return "the application waits for approval (auto-registration is off): approve it in"
            + " the admin screen, then run the check again";
      case "AGENT_VERSION_TOO_NEW":
        return "this agent is newer than the server accepts"
            + (hello == null ? "" : versions(hello))
            + ": upgrade the server or use an older agent";
      case "AGENT_VERSION_TOO_OLD":
        return "this agent is older than the server accepts"
            + (hello == null ? "" : versions(hello))
            + ": upgrade the agent";
      case "PROTOCOL_UNSUPPORTED":
        return "the server does not speak this agent's protocol version";
      case "PLAINTEXT_NOT_ALLOWED":
        return "the server requires TLS: set server.tls: true";
      case "INSTANCE_LIMIT":
        return "the application has reached its instance limit";
      case "RATE_LIMITED":
        return "too many sessions right now: retry later";
      case "SERVER_SHUTTING_DOWN":
        return "the server is shutting down: retry later";
      default:
        return "the server refused the session";
    }
  }

  static CheckRow clockRow(CheckProbe.Result result) {
    ServerHello hello = result.hello;
    if (hello == null || hello.getServerTimeMs() <= 0 || result.helloReceivedAtMs <= 0) {
      return CheckRow.of(5, "clock", CheckRow.Status.SKIP, "no ServerHello");
    }
    long rtt = Math.max(0, result.helloReceivedAtMs - result.helloSentAtMs);
    long offset =
        clockOffset(hello.getServerTimeMs(), result.helloSentAtMs, result.helloReceivedAtMs);
    String text =
        "offset "
            + (offset >= 0 ? "+" : "")
            + offset
            + " ms (server minus this host, round trip "
            + rtt
            + " ms)";
    if (Math.abs(offset) >= CLOCK_WARN_MILLIS) {
      return CheckRow.of(
          5,
          "clock",
          CheckRow.Status.WARN,
          text + ": synchronize this host's clock (NTP); times are stored as the agent reads them");
    }
    return CheckRow.of(5, "clock", CheckRow.Status.PASS, text);
  }

  /** {@code server - (sent + received) / 2} (11 §9 first estimate, corrected by half the rtt). */
  static long clockOffset(long serverTimeMs, long sentAtMs, long receivedAtMs) {
    return serverTimeMs - (sentAtMs + (receivedAtMs - sentAtMs) / 2);
  }

  /**
   * Classifies a stream that ended (or timed out) before the server answered: DNS, TCP or TLS, from
   * the cause chain of the gRPC status (class names are compared by their simple names, since the
   * agent's Netty is relocated).
   */
  static Failure classify(CheckProbe.Result result) {
    if (result.timedOut && result.statusCode == null) {
      return new Failure(
          Stage.TCP,
          "no answer within "
              + ((CheckProbe.CONNECT_TIMEOUT_MILLIS + CheckProbe.HELLO_TIMEOUT_MILLIS) / 1000)
              + " s");
    }
    return classify(result.statusCode, result.statusDescription, result.cause);
  }

  static Failure classify(String code, String description, Throwable cause) {
    Throwable tls = null;
    Throwable connect = null;
    boolean unknownHost = false;
    Throwable current = cause;
    for (int depth = 0; current != null && depth < 12; depth++) {
      String name = current.getClass().getSimpleName();
      if (current instanceof UnknownHostException) {
        unknownHost = true;
      } else if (tls == null
          && (current instanceof SSLException
              || current instanceof CertificateException
              || name.contains("Ssl")
              || name.contains("CertPath"))) {
        tls = current;
      } else if (connect == null
          && (current instanceof ConnectException
              || name.contains("ConnectTimeout")
              || name.equals("NoRouteToHostException"))) {
        connect = current;
      }
      current = current.getCause();
    }
    if (unknownHost) {
      return new Failure(Stage.DNS, "the host name does not resolve");
    }
    if (tls != null) {
      return new Failure(Stage.TLS, tlsMessage(cause));
    }
    if (connect != null) {
      String name = connect.getClass().getSimpleName();
      String message = String.valueOf(connect.getMessage()).toLowerCase(Locale.ROOT);
      if (name.contains("Timeout") || message.contains("timed out")) {
        return new Failure(
            Stage.TCP,
            "connection timed out after "
                + (CheckProbe.CONNECT_TIMEOUT_MILLIS / 1000)
                + " s (firewall, or wrong host or port)");
      }
      if (message.contains("refused")) {
        return new Failure(
            Stage.TCP, "connection refused (no collector listening on this port; default 9100)");
      }
      return new Failure(Stage.TCP, "connection failed (" + name + ")");
    }
    String text = description == null ? "" : description;
    return new Failure(
        Stage.TCP,
        "connection failed ("
            + (code == null ? "unknown" : code)
            + (text.isEmpty() ? "" : ": " + cut(text, 200))
            + "); if the server uses TLS, check server.tls");
  }

  /** The TLS failure in words: host name, expiry, trust, or not TLS at all. */
  static String tlsMessage(Throwable cause) {
    StringBuilder all = new StringBuilder();
    boolean expired = false;
    boolean notYetValid = false;
    Throwable current = cause;
    for (int depth = 0; current != null && depth < 12; depth++) {
      if (current instanceof CertificateExpiredException) {
        expired = true;
      }
      if (current instanceof CertificateNotYetValidException) {
        notYetValid = true;
      }
      all.append(current.getClass().getSimpleName())
          .append(": ")
          .append(current.getMessage())
          .append(" | ");
      current = current.getCause();
    }
    String text = all.toString().toLowerCase(Locale.ROOT);
    if (text.contains("no subject alternative") || text.contains("no name matching")) {
      return "the server certificate does not match the host name of server.address (check the"
          + " certificate's subject alternative names)";
    }
    if (expired || text.contains("validity check failed") || text.contains("expired")) {
      return "the server certificate has expired";
    }
    if (notYetValid) {
      return "the server certificate is not valid yet (check this host's clock)";
    }
    if (text.contains("pkix")
        || text.contains("certification path")
        || text.contains("certpath")
        || text.contains("unable to find valid")) {
      return "the server certificate is not trusted: set server.tls.ca-file to the CA that"
          + " signed it";
    }
    if (text.contains("not an ssl/tls record") || text.contains("unrecognized ssl message")) {
      return "the server does not speak TLS on this port: check server.tls and the port";
    }
    String first =
        cause == null ? "unknown" : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    return "TLS handshake failed (" + cut(first, 200) + ")";
  }

  /** {@code yyyy-MM-dd} (UTC). */
  static String date(long millis) {
    return Instant.ofEpochMilli(millis).toString().substring(0, 10);
  }

  private static String join(List<String> values) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < values.size() && i < 4; i++) {
      if (i > 0) {
        b.append(", ");
      }
      b.append(values.get(i));
    }
    if (values.size() > 4) {
      b.append(", ...");
    }
    return b.length() == 0 ? "(no address)" : b.toString();
  }

  private static String orUnknown(String value) {
    return value == null || value.isEmpty() ? "unknown" : value;
  }

  static String cut(String text, int max) {
    return text.length() > max ? text.substring(0, max) + "..." : text;
  }
}
