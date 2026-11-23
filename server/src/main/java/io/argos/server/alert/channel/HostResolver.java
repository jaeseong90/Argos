/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Name resolution of notification destinations (16 §8.1 step 4): every A and AAAA address of a host
 * name, with a time limit. Tests replace it so that no lookup leaves the machine.
 */
@FunctionalInterface
public interface HostResolver {

  /**
   * Every address of {@code host}.
   *
   * @throws UnknownHostException when the name has no address or the lookup did not finish in time
   */
  List<InetAddress> resolve(String host) throws UnknownHostException;

  /**
   * The system resolver ({@link InetAddress#getAllByName}) on a virtual thread, abandoned after
   * {@code timeout} (the JDK lookup has no time limit of its own).
   */
  static HostResolver system(Duration timeout) {
    return host -> {
      Future<InetAddress[]> lookup;
      try {
        lookup =
            java.util.concurrent.CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return InetAddress.getAllByName(host);
                  } catch (UnknownHostException e) {
                    throw new java.util.concurrent.CompletionException(e);
                  }
                },
                task -> Thread.ofVirtual().name("argos-notify-dns").start(task));
      } catch (RuntimeException e) {
        throw new UnknownHostException("lookup not started");
      }
      try {
        return List.of(lookup.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
      } catch (TimeoutException e) {
        lookup.cancel(true);
        throw new UnknownHostException("lookup timed out");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new UnknownHostException("lookup interrupted");
      } catch (ExecutionException e) {
        throw new UnknownHostException("no address");
      }
    };
  }
}
