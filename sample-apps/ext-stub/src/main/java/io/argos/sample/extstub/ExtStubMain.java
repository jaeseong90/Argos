/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.extstub;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

/**
 * {@code java -jar ext-stub.jar [--port 18080] [--bind 127.0.0.1] [--threads 64]}. Environment:
 * EXT_STUB_PORT, EXT_STUB_BIND (the container image binds 0.0.0.0). Runs until stopped.
 */
public final class ExtStubMain {

  private ExtStubMain() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    int port = Integer.parseInt(env("EXT_STUB_PORT", Integer.toString(ExtStub.DEFAULT_PORT)));
    String bind = env("EXT_STUB_BIND", "127.0.0.1");
    int threads = 64;
    for (int i = 0; i < args.length; i += 2) {
      String value = i + 1 < args.length ? args[i + 1] : null;
      switch (args[i]) {
        case "--port" -> port = Integer.parseInt(required(args[i], value));
        case "--bind" -> bind = required(args[i], value);
        case "--threads" -> threads = Integer.parseInt(required(args[i], value));
        default ->
            throw new IllegalArgumentException(
                "Unknown option " + args[i] + " (--port, --bind, --threads)");
      }
    }
    ExtStub stub = ExtStub.start(new InetSocketAddress(bind, port), threads);
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  stub.close();
                  stopped.countDown();
                },
                "ext-stub-shutdown"));
    System.out.println("ext-stub listening on http://" + bind + ":" + stub.port());
    stopped.await();
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String required(String option, String value) {
    if (value == null) {
      throw new IllegalArgumentException(option + " needs a value");
    }
    return value;
  }
}
