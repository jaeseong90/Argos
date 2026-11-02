/* SPDX-License-Identifier: Apache-2.0 */
package example.preload;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Test-only Java agent placed before the Argos agent on the command line (attach test, P-6): its
 * premain loads {@code java.util.concurrent.ThreadPoolExecutor} and {@code
 * sun.net.www.protocol.http.HttpURLConnection}, so that both JDK classes are already defined when
 * the Argos premain runs and must be retransformed (10 §4.3 P6), with the JPMS read edge added to
 * java.base after the class was defined. No thread is started, no connection is made, nothing is
 * printed.
 */
public final class PreloadAgent {
  private PreloadAgent() {}

  public static void premain(String args, Instrumentation inst) throws Exception {
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    pool.shutdown();
    // openConnection() only creates the connection object; nothing is sent.
    new URL("http://127.0.0.1:1/").openConnection();
  }
}
