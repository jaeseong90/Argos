/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread names of a {@code jcmd <pid> Thread.print} dump (N-26 "에이전트 스레드 ≤ 6", 10 §14 thread
 * budget): every thread header line starts with the quoted thread name.
 */
public final class ThreadCounter {
  private static final Pattern HEADER = Pattern.compile("^\"(.*)\"(?: #\\d+)?.*$");

  private ThreadCounter() {}

  /** Names of every thread in the dump, in dump order. */
  public static List<String> names(String threadDump) {
    List<String> names = new ArrayList<>();
    for (String line : threadDump.split("\\R", -1)) {
      Matcher matcher = HEADER.matcher(line);
      if (matcher.matches()) {
        names.add(matcher.group(1));
      }
    }
    return names;
  }

  /** Names of the threads whose name starts with {@code prefix} ({@code argos-}). */
  public static List<String> namesStartingWith(String threadDump, String prefix) {
    List<String> matching = new ArrayList<>();
    for (String name : names(threadDump)) {
      if (name.startsWith(prefix)) {
        matching.add(name);
      }
    }
    return matching;
  }
}
