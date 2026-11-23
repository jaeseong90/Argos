/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-BLD-011 (DSN-02-22, N-17, 10 §10.3): in the agent jar, network classes are referenced only by
 * the gRPC channel's stack and by the one advice that reads a JDK connection the application made,
 * so the agent's own code opens no socket outside that stack. Sees type references and reflective
 * names alike, because it searches the class bytes. This static check says which classes can
 * connect, not where they connect: that the channel connects to {@code server.address} only, and
 * not through a proxy of the application JVM, is checked at run time by {@code
 * ConnectionTransportTest} (T-BLD-011a).
 */
@Tag("req:N-17")
@Tag("req:DSN-02-22")
class NetworkReferencesTest {
  private static final List<String> NETWORK_CLASSES =
      List.of(
          "java/net/Socket",
          "java/net/ServerSocket",
          "java/net/DatagramSocket",
          "java/net/MulticastSocket",
          "java/net/URLConnection",
          "java/net/HttpURLConnection",
          "java/net/http/HttpClient",
          "java/nio/channels/SocketChannel",
          "java/nio/channels/ServerSocketChannel",
          "java/nio/channels/DatagramChannel",
          "javax/net/SocketFactory");

  /** What may hold a reference: the transport stack (relocated) and its helpers. */
  private static final List<String> ALLOWED_PREFIXES =
      List.of(
          "inst/io/argos/agent/shaded/io/netty/",
          "inst/io/argos/agent/shaded/io/grpc/",
          // ByteBuddy's agent attach support and class file locator; Guava's HostAndPort helpers.
          "inst/io/argos/agent/shaded/net/bytebuddy/",
          "inst/io/argos/agent/shaded/com/google/common/",
          // Instruments the application's own HttpURLConnection (F-A05): reads it, opens nothing.
          "inst/io/argos/agent/instrumentation/httpclient/jdk/HttpUrlConnectionAdvice.classdata",
          // Only the class name, to match it as a target of the instrumentation.
          "inst/io/argos/agent/instrumentation/httpclient/jdk/HttpClientJdkModule.classdata",
          // Only the class name, to let the instrumentation through the ignore list.
          "inst/io/argos/agent/core/boot/IgnoreMatchers.classdata",
          // WO-14 (F-A05, 10 §6.5), each reading a call the application made and opening nothing:
          // the failure classifier matches exception class names (java.net.SocketTimeoutException);
          "inst/io/argos/agent/core/trace/HttpFailures.classdata",
          // WO-16 (C-224 (1)): the OkHttp helper hands the classifier a new
          // java.net.SocketTimeoutException as the marker of a call that hit its callTimeout (the
          // exception object only, read by the substring match as java/net/Socket);
          "inst/io/argos/agent/instrumentation/httpclient/okhttp/OkHttpInterceptorHelper.classdata",
          // the JDK 11 client module names java.net.http.HttpClient in its witness, and its request
          // wrapper delegates to the application's request (HttpClient.Version in a signature);
          "inst/io/argos/agent/instrumentation/httpclient/jdk11/HttpClientJdk11Module.classdata",
          "inst/io/argos/agent/instrumentation/httpclient/jdk11/Jdk11RequestHelper.classdata",
          // the Reactor Netty advice takes HttpClientHandler as the JDK type it extends,
          // java.net.SocketAddress (read by the substring match as java/net/Socket).
          "inst/io/argos/agent/instrumentation/httpclient/reactornetty/ReactorNettyHandlerAdvice.classdata",
          "inst/io/argos/agent/instrumentation/httpclient/reactornetty/ReactorNettyHelper.classdata",
          "inst/io/argos/agent/instrumentation/httpclient/reactornetty/ReactorNettyRequestAdvice.classdata",
          "inst/io/argos/agent/instrumentation/httpclient/reactornetty/ReactorNettyRetryAdvice.classdata",
          // The channel's never-proxy detector (DSN-02-22): java.net.SocketAddress in the signature
          // of ProxyDetector.proxyFor, which the substring match reads as java/net/Socket; it
          // returns null and opens nothing.
          "inst/io/argos/agent/core/transport/GrpcTransport$NoProxy.classdata");

  @Test
  @DisplayName(
      "T-BLD-011 only the gRPC channel stack references network classes; the agent's own classes"
          + " open no connection outside it")
  void networkReferences() throws IOException {
    Set<String> violations = new TreeSet<>();
    List<String> referencing = new ArrayList<>();
    try (ZipFile zip = new ZipFile(TransportRuns.releaseJar().toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (!name.endsWith(".classdata") && !name.endsWith(".class")) {
          continue;
        }
        String bytes;
        try (InputStream in = zip.getInputStream(entry)) {
          bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        for (String network : NETWORK_CLASSES) {
          if (bytes.contains(network) || bytes.contains(network.replace('/', '.'))) {
            referencing.add(name);
            if (ALLOWED_PREFIXES.stream().noneMatch(name::startsWith)) {
              violations.add(name + " references " + network);
            }
          }
        }
      }
    }
    assertThat(referencing).as("the check sees the transport stack").isNotEmpty();
    assertThat(violations).isEmpty();
  }
}
