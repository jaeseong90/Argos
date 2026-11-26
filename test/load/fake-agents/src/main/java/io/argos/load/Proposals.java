/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.server.testing.load.Jcmd;
import io.argos.storage.clickhouse.MemoryBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The proposal sections of the load report (WO-29 step A2), computed from the end-of-run facts:
 * HdrHistogram bytes per (app, service) window extrapolated to the 12 §11 cap (12 open item 3),
 * pinned virtual threads (12 open item 4), and the memory budget use with the 17 §7.3 ratio
 * proposal (17 open item 9). Proposals only: the design documents change by PM decision.
 */
final class Proposals {

  /** 12 §11: apps × top services per app of the alert windows. */
  static final int DESIGN_APPS = 30;

  /** 12 §11: services per app kept in the windows. */
  static final int DESIGN_SERVICES = 500;

  /** 10 % of the 4 GiB heap of 12 §11: the share the alert windows may take. */
  static final long HDR_SHARE_OF_4G = 4 * MemoryBudget.GIB / 10;

  /** A component is near its budget above this share of it. */
  static final double NEAR_BUDGET = 0.85;

  private Proposals() {}

  /** HdrHistogram bytes per (app, service), or -1. */
  static long hdrBytesPerWindow(long hdrBytes, int apps, int services) {
    long windows = (long) apps * Math.min(services, DESIGN_SERVICES);
    return hdrBytes < 0 || windows <= 0 ? -1 : hdrBytes / windows;
  }

  /** The proposal for one component: its peak against its budget. */
  static String budgetProposal(long peak, long budget) {
    if (peak < 0 || budget <= 0) {
      return "not measured";
    }
    double share = (double) peak / budget;
    if (share > 1.0) {
      return String.format(Locale.ROOT, "over budget (%.0f %%): raise its ratio", share * 100);
    }
    if (share > NEAR_BUDGET) {
      return String.format(
          Locale.ROOT, "near budget (%.0f %%): raise its ratio or lower its settings", share * 100);
    }
    return String.format(Locale.ROOT, "within budget (%.0f %%): keep the ratio", share * 100);
  }

  /** The sections; {@code heapPerSession} from the idle-session phase (-1 when it did not run). */
  static List<ReportSection> sections(RunReport r, long heapPerSession) {
    List<ReportSection> out = new ArrayList<>();
    out.add(new ReportSection(ReportSection.PLANNED.get(3), hdrAndPinning(r)));
    out.add(new ReportSection(ReportSection.PLANNED.get(4), budgets(r, heapPerSession)));
    return out;
  }

  private static String hdrAndPinning(RunReport r) {
    StringBuilder md = new StringBuilder();
    LoadOptions o = r.options;
    Jcmd.ClassHistogram h = r.classHistogram;
    long hdr = h == null ? -1 : h.bytes(n -> n.startsWith("org.HdrHistogram."));
    int apps = o == null ? 0 : o.apps();
    int services = o == null ? 0 : o.services();
    long perWindow = hdrBytesPerWindow(hdr, apps, services);
    md.append("**12 open item 3 (HdrHistogram memory, assumption: a few hundred MB).** ");
    if (hdr < 0) {
      md.append("No class histogram was taken.\n\n");
    } else {
      md.append(
          String.format(
              Locale.ROOT,
              "End of run: %s in HdrHistogram objects for %d apps x %d services (%s per (app,"
                  + " service) window, every window of the run assumed alive). Extrapolated to"
                  + " 12 §11's %d apps x %d services: %s.",
              Format.bytes(hdr),
              apps,
              Math.min(services, DESIGN_SERVICES),
              Format.bytes(perWindow),
              DESIGN_APPS,
              DESIGN_SERVICES,
              perWindow < 0 ? "n/a" : Format.bytes(perWindow * DESIGN_APPS * DESIGN_SERVICES)));
      md.append(
          hdr == 0
              ? " No HdrHistogram object was alive at the end: the service windows exist only"
                  + " while alert rules evaluate them, so this run gives no figure; a run with the"
                  + " recommended rules applied is needed (a human run).\n\n"
              : perWindow >= 0 && perWindow * DESIGN_APPS * DESIGN_SERVICES <= HDR_SHARE_OF_4G
                  ? " Proposal: the cap of 500 services per app holds (the extrapolation stays"
                      + " below 10 % of the 4 GiB heap of 12 §11).\n\n"
                  : " Proposal: the extrapolation exceeds 10 % of the 4 GiB heap of 12 §11; the"
                      + " cap of 500 services per app (or the window's slot layout) needs a PM"
                      + " decision.\n\n");
    }
    md.append("**12 open item 4 (virtual thread pinning in Tomcat REST/WS).** ");
    if (r.pinning == null) {
      md.append("No JFR recording was read.\n");
    } else {
      md.append(
          String.format(
              Locale.ROOT,
              "JFR `jdk.VirtualThreadPinned` (20 ms threshold) over the run with %d dashboard"
                  + " users: %,d events, longest %d ms. Proposal: %s\n",
              o == null ? 0 : o.users(),
              r.pinning.events(),
              r.pinning.longest().toMillis(),
              r.pinning.events() == 0
                  ? "the assumption \"none (JDK 21+)\" holds for this load; confirm on the target"
                      + " machine."
                  : "inspect the top frames above before relying on virtual threads for REST/WS."));
    }
    return md.toString();
  }

  private static String budgets(RunReport r, long heapPerSession) {
    LoadOptions o = r.options;
    StringBuilder md = new StringBuilder();
    ResourceSampler.Peaks p = r.sampler == null ? null : r.sampler.peaks();
    long heap = o == null ? -1 : LoadOptions.size(o.serverHeap(), -1);
    long serverBudget = heap < 0 ? -1 : heap + MemoryBudget.GIB;
    long chBudget = o == null ? -1 : o.clickHouseMemoryBytes();
    long pgBudget = o == null ? -1 : o.postgresMemoryBytes();
    long chPeak = p == null ? -1 : p.chContainer;
    long pgPeak = p == null ? -1 : p.pgContainer;
    List<List<String>> rows = new ArrayList<>();
    rows.add(List.of("Component", "Budget of this run", "Peak", "Use", "Proposal"));
    rows.add(
        List.of(
            "argos-server (heap + 1 GiB off-heap, 12 §11)",
            Format.bytes(serverBudget),
            Format.bytes(r.rssPeak) + " resident",
            share(r.rssPeak, serverBudget),
            budgetProposal(r.rssPeak, serverBudget)));
    rows.add(
        List.of(
            "ClickHouse container",
            Format.bytes(chBudget),
            Format.bytes(chPeak)
                + " (MemoryTracking "
                + Format.bytes(p == null ? -1 : p.chMemoryTracking)
                + ")",
            share(chPeak, chBudget),
            budgetProposal(chPeak, chBudget)));
    rows.add(
        List.of(
            "PostgreSQL container",
            Format.bytes(pgBudget),
            Format.bytes(pgPeak),
            share(pgPeak, pgBudget),
            budgetProposal(pgPeak, pgBudget)));
    md.append(PhaseSupport.table(rows)).append('\n');
    Jcmd.NativeMemory nmt = r.nativeMemory;
    md.append(
        String.format(
            Locale.ROOT,
            "Server off-heap (NMT committed outside the Java heap, end of run): %s against the 1"
                + " GiB of 12 §11 (direct buffer peak %s, threads peak %s). Heap per idle"
                + " session %s, %s for the 5,000-session cap (C-157 (1)).%n%n",
            nmt == null ? "n/a" : Format.bytes(nmt.committedOffHeapKb() * 1024),
            Format.bytes(p == null ? -1 : p.directUsed),
            p == null || p.threads < 0 ? "n/a" : Long.toString(p.threads),
            heapPerSession < 0 ? "n/a" : Format.bytes(heapPerSession),
            heapPerSession < 0 ? "n/a" : Format.bytes(heapPerSession * SessionPhase.SERVER_CAP)));
    md.append(
        "**17 open item 9 / 17 §7.3 ratios (ClickHouse 0.47, server heap 0.25, PostgreSQL"
            + " shared_buffers 0.03 of RAM).** At 16 GiB they give ClickHouse 7.5 GiB, server 4 +"
            + " 1 GiB, PostgreSQL 512 MB x 3; at 32 GiB 15 GiB, 8 + 1 GiB and 1 GiB x 3. Proposal"
            + " rule: a component whose peak at full scale stays within 85 % of its budget keeps"
            + " its ratio; one above it takes the difference from ClickHouse first (17 §7.3"
            + " check). This run's peaks are ");
    md.append(
        o != null && o.fullScale()
            ? "a full-scale measurement and the table above is the proposal; the PM decides the"
                + " design change.\n"
            : "from profile `"
                + (o == null ? "?" : o.profile().id())
                + "` on a shared machine with reduced budgets: INVALID, no ratio change is"
                + " proposed from them. The full run on 13 §7.1 hardware (16 GiB) and a 32 GiB run"
                + " are needed (a human run on that hardware).\n");
    return md.toString();
  }

  private static String share(long peak, long budget) {
    return peak < 0 || budget <= 0
        ? "n/a"
        : String.format(Locale.ROOT, "%.0f %%", 100.0 * peak / budget);
  }
}
