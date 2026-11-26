/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.load;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The resident set of another process (the server JVM), sampled by a background thread:
 *
 * <ul>
 *   <li>Linux: {@code VmRSS} and {@code VmHWM} of {@code /proc/<pid>/status} (the kernel's own
 *       peak);
 *   <li>Windows: one resident PowerShell process prints {@code WorkingSet64} and {@code
 *       PeakWorkingSet64} of the process every interval (no process start per sample);
 *   <li>elsewhere: not available ({@link #source()} says so).
 * </ul>
 *
 * <p>{@link #peakBytes()} is the larger of the sampled maximum and the operating system's peak
 * ({@code VmHWM}, {@code PeakWorkingSet64}). The report names the {@link #source()}: a Windows
 * working set is not the same quantity as a Linux RSS (it includes shared pages and may be
 * trimmed).
 */
public final class ResidentSetProbe implements AutoCloseable {

  private static final Pattern VM_RSS = Pattern.compile("(?m)^VmRSS:\\s+(\\d+)\\s+kB");
  private static final Pattern VM_HWM = Pattern.compile("(?m)^VmHWM:\\s+(\\d+)\\s+kB");
  private static final Pattern WINDOWS_LINE = Pattern.compile("^\\s*(\\d+)\\s+(\\d+)\\s*$");

  /** What the probe reads. */
  public enum Source {
    /** {@code /proc/<pid>/status} VmRSS and VmHWM. */
    LINUX_PROC("Linux /proc/<pid>/status VmRSS (sampled) and VmHWM (kernel peak)"),
    /** PowerShell {@code Get-Process} WorkingSet64 and PeakWorkingSet64. */
    WINDOWS_WORKING_SET(
        "Windows working set: Get-Process WorkingSet64 (sampled) and PeakWorkingSet64 (OS peak);"
            + " not identical to a Linux RSS"),
    /** No probe on this operating system. */
    UNAVAILABLE("not available on this operating system");

    private final String description;

    Source(String description) {
      this.description = description;
    }

    /** For the report. */
    public String description() {
      return description;
    }
  }

  private final long pid;
  private final Duration interval;
  private final Source source;
  private final AtomicLong last = new AtomicLong(-1);
  private final AtomicLong sampledMax = new AtomicLong(-1);
  private final AtomicLong osPeak = new AtomicLong(-1);
  private final AtomicLong samples = new AtomicLong();
  private volatile boolean running = true;
  private Thread thread;
  private Process powershell;

  private ResidentSetProbe(long pid, Duration interval, Source source) {
    this.pid = pid;
    this.interval = interval;
    this.source = source;
  }

  /** Starts sampling process {@code pid} every {@code interval}. */
  public static ResidentSetProbe start(long pid, Duration interval) throws IOException {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    Source source;
    if (os.contains("win")) {
      source = Source.WINDOWS_WORKING_SET;
    } else if (Files.isReadable(Paths.get("/proc", Long.toString(pid), "status"))) {
      source = Source.LINUX_PROC;
    } else {
      source = Source.UNAVAILABLE;
    }
    ResidentSetProbe probe = new ResidentSetProbe(pid, interval, source);
    probe.open();
    return probe;
  }

  /** What is read. */
  public Source source() {
    return source;
  }

  /** The last sample in bytes, empty before the first one. */
  public OptionalLong currentBytes() {
    long v = last.get();
    return v < 0 ? OptionalLong.empty() : OptionalLong.of(v);
  }

  /** The peak in bytes (sampled maximum or the OS peak, whichever is larger), empty if unknown. */
  public OptionalLong peakBytes() {
    long v = Math.max(sampledMax.get(), osPeak.get());
    return v < 0 ? OptionalLong.empty() : OptionalLong.of(v);
  }

  /** The largest sampled value only (without the OS peak). */
  public OptionalLong sampledMaxBytes() {
    long v = sampledMax.get();
    return v < 0 ? OptionalLong.empty() : OptionalLong.of(v);
  }

  /** Samples taken. */
  public long samples() {
    return samples.get();
  }

  /** Reads once more right now (Linux only; Windows returns the last printed sample). */
  public OptionalLong sampleNow() {
    if (source == Source.LINUX_PROC) {
      readProc();
    }
    return currentBytes();
  }

  private void open() throws IOException {
    switch (source) {
      case LINUX_PROC -> {
        thread =
            Thread.ofPlatform()
                .daemon()
                .name("rss-probe-" + pid)
                .start(
                    () -> {
                      while (running) {
                        if (!readProc()) {
                          return;
                        }
                        try {
                          TimeUnit.MILLISECONDS.sleep(interval.toMillis());
                        } catch (InterruptedException e) {
                          return;
                        }
                      }
                    });
      }
      case WINDOWS_WORKING_SET -> {
        // No double quotes in the script: Windows command-line quoting of ProcessBuilder breaks
        // them up, and PowerShell then fails to parse the script (no sample at all).
        String script =
            "$p = Get-Process -Id "
                + pid
                + "; while ($true) { $p.Refresh(); [Console]::Out.WriteLine([string]$p.WorkingSet64"
                + " + ' ' + [string]$p.PeakWorkingSet64); [Console]::Out.Flush(); Start-Sleep"
                + " -Milliseconds "
                + interval.toMillis()
                + " }";
        powershell =
            new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true)
                .start();
        thread =
            Thread.ofPlatform()
                .daemon()
                .name("rss-probe-" + pid)
                .start(
                    () -> {
                      try (BufferedReader lines =
                          new BufferedReader(
                              new InputStreamReader(
                                  powershell.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = lines.readLine()) != null) {
                          long[] pair = parseWindowsLine(line);
                          if (pair != null) {
                            record(pair[0], pair[1]);
                          }
                        }
                      } catch (IOException e) {
                        // the probe was closed
                      }
                    });
      }
      case UNAVAILABLE -> {
        // nothing to sample
      }
    }
  }

  private boolean readProc() {
    Path status = Paths.get("/proc", Long.toString(pid), "status");
    try {
      long[] pair = parseProcStatus(Files.readString(status, StandardCharsets.UTF_8));
      if (pair != null) {
        record(pair[0], pair[1]);
      }
      return true;
    } catch (IOException e) {
      return false; // the process ended
    }
  }

  private void record(long current, long peak) {
    last.set(current);
    sampledMax.accumulateAndGet(current, Math::max);
    if (peak >= 0) {
      osPeak.accumulateAndGet(peak, Math::max);
    }
    samples.incrementAndGet();
  }

  /**
   * {@code VmRSS} and {@code VmHWM} of a {@code /proc/<pid>/status} text in bytes ({@code VmHWM} -1
   * when absent); null without {@code VmRSS}.
   */
  public static long[] parseProcStatus(String status) {
    Matcher rss = VM_RSS.matcher(status);
    if (!rss.find()) {
      return null;
    }
    Matcher hwm = VM_HWM.matcher(status);
    long peak = hwm.find() ? Long.parseLong(hwm.group(1)) * 1024 : -1;
    return new long[] {Long.parseLong(rss.group(1)) * 1024, peak};
  }

  /** {@code "<WorkingSet64> <PeakWorkingSet64>"} in bytes; null for any other line. */
  public static long[] parseWindowsLine(String line) {
    Matcher m = WINDOWS_LINE.matcher(line);
    if (!m.matches()) {
      return null;
    }
    return new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2))};
  }

  /** Stops the sampling (never throws). */
  @Override
  public void close() {
    running = false;
    if (powershell != null) {
      powershell.destroyForcibly();
    }
    if (thread != null) {
      thread.interrupt();
    }
  }
}
