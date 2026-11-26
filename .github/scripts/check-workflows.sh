#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-workflows.sh - policy lint for GitHub Actions workflows (O-S05, SEC-73, docs/19 section 6.6,
# docs/16 section 10.5).
#
# Usage: check-workflows.sh [repository-root]
#        check-workflows.sh --simulate <pull_request|push|schedule|workflow_dispatch>
#                           [--ref <ref>] [--fork] [repository-root]
#
# Scans <root>/.github/workflows/*.yml|*.yaml and local composite actions
# (<root>/.github/actions/**/action.yml|action.yaml). The check is line based on purpose: it is
# meant to be strict and simple, not a full YAML parser (actionlint covers syntax and semantics).
# Keys are recognised in block form (`key: value`, `- key: value`) and inside flow mappings
# (`{ a: 1, key: value }`); a construct the checker cannot attribute is a violation, not a pass.
#
# Rules
#   W1  `pull_request_target` must not appear in any workflow. Keys must be written literally: a
#       double-quoted key containing a YAML escape (\x, \u, \U) or an explicit key (`? key`) is a
#       violation in every file, because it can spell a forbidden trigger or key (for example
#       "pull_request_\x74arget" or "u\x73es") without containing it.
#   W2  A workflow that can be triggered by anything other than push, schedule, workflow_dispatch
#       or release (for example pull_request, workflow_run or workflow_call) must run every job on
#       `runs-on: ubuntu-latest` and must not mention `self-hosted`. Fork pull requests must never
#       reach a self-hosted runner. W8 narrows the remaining triggers for self-hosted jobs.
#   W3  Every `uses:` (block form or inside a flow mapping such as `- { name: x, uses: a/b@v1 }`)
#       is a local path (./...), a docker:// image pinned by @sha256 digest, or
#       owner/repo[/path]@<40-hex commit SHA> on a line that ends with a `# <version>` comment.
#   W4  Every workflow declares a top-level `permissions:` that grants read access only:
#       `read-all`, `{}` or `<scope>: read|none` entries (`write-all` and `<scope>: write`, including
#       `*: write`, are violations). At job level `write-all` is a violation, and `<scope>: write` is
#       allowed only when (workflow file, job id, scope) is listed in PERMISSION_ALLOWLIST below.
#       `permissions:` inside a flow mapping (for example a flow-style job) is a violation.
#   W5  A workflow_run workflow runs with base-repository privileges, so it must not fetch or check
#       out pull request code ("pwn request"): no actions/checkout, and no run script line with
#       git clone/fetch/checkout/switch/pull, gh pr checkout, or head_sha/head_branch (pass such
#       values through env: and validate them; never interpolate them into a run script).
#   W6  Container images are pinned by digest: `docker run` lines (unless the image comes from a
#       variable), `image:` keys and *IMAGE* variables must contain @sha256:<64 hex>.
#   W7  Security jobs of the pull request workflow (.github/workflows/pr.yml only; T-SEC-93, SEC-70,
#       SEC-71, 16 section 10.2, ADR-0010 C-246 (4)): a job other than build-java runs
#       `./gradlew securityTest` in a run script; build-java never runs securityTest (the suite
#       stays out of check and build, T-BLD-013); a job `sbom-scan` runs test/release/check-sbom.sh
#       with `--scanner "bash test/release/trivy-scan.sh"` and `--require agent,server,ui`.
#   W8  Self-hosted jobs (docs/19 sections 6.3 and 6.6, 16 section 10.5, WO-47). A job is
#       self-hosted unless every `runs-on` label is a GitHub-hosted image label (ubuntu-*,
#       windows-*, macos-*): `self-hosted`, `argos-*` labels, a `group:`/`labels:` mapping and
#       expressions all count as self-hosted. For every self-hosted job:
#       - the workflow triggers are a subset of push, schedule and workflow_dispatch (release,
#         workflow_call, workflow_run, pull_request and every other trigger are violations);
#       - push is filtered by `branches:` with only main and release/** (or release/*) and/or by
#         `tags:` with only v*; an unfiltered push, branches-ignore and tags-ignore are violations;
#       - the job has a job-level `if:` equal (with or without `${{ }}`) to one expression of
#         TRUSTED_REF_GUARDS below, so that a manual run on a feature branch never reaches the
#         runner ("check out only commits of main and release/*", docs/19 section 6.3);
#       - actions/checkout sets `persist-credentials: false` and sets neither `ref:` nor
#         `repository:`;
#       - a `secrets.` reference (in the job or at workflow level) or `secrets:` needs the job-level
#         `environment: trusted` (docs/19 section 6.6);
#       - job-level write scopes are forbidden, even when PERMISSION_ALLOWLIST lists them; writes
#         (for example opening a pull request) belong in a GitHub-hosted follow-up job;
#       - the job and its steps are written in block form (no flow-style job or `steps: [...]`).
#
# Simulation (--simulate, T-BLD-027): evaluates which jobs one event would start, using the same
# parser. One TSV line per job of every triggered workflow:
#   <workflow file>\t<job id>\t<github-hosted|self-hosted>\t<runs|skipped:<reason>|may-run>
# Only triggers, branch/tag filters (paths filters count as matching), `needs:` and the
# TRUSTED_REF_GUARDS `if:` expressions are evaluated; any other job-level `if:` is `may-run`.
# --ref is the pushed ref (push), the branch a manual or scheduled run uses (workflow_dispatch,
# schedule) or the base branch of the pull request (pull_request; the job context then sees
# refs/pull/1/merge). Default refs/heads/main. workflow_run follow-ups of triggered workflows are
# listed as well: they run on the default branch with base-repository privileges whatever started
# them. --fork (pull_request only) names the fork pull request case; the simulation never trusts a
# pull request author, so the result is the same as for a pull request from the repository.
# pull_request_target (forbidden by W1) counts as triggered by every pull request and, like GitHub,
# runs with the base branch as github.ref.
#
# Exit status: 0 = no violations (simulation: always 0), 1 = violations found, 2 = usage error.
set -euo pipefail

# W4: job-level write permissions that are allowed, one per line:
#   <workflow path relative to the repository root>|<job id>|<scope>|<reason>
PERMISSION_ALLOWLIST='.github/workflows/codeql.yml|analyze|security-events|CodeQL uploads its results to code scanning
.github/workflows/pr-labeler.yml|label|pull-requests|applies path labels; workflow_run, never checks out pull request code
.github/workflows/release.yml|images|packages|pushes the release images to GHCR with the job token (tag push only)
.github/workflows/release.yml|images|id-token|cosign keyless image signatures and provenance attestations (GitHub OIDC)
.github/workflows/release.yml|images|attestations|stores the provenance attestations of the release images
.github/workflows/release.yml|sign|id-token|cosign keyless signatures and provenance attestations of the release assets (GitHub OIDC)
.github/workflows/release.yml|sign|attestations|stores the provenance attestations of the release assets
.github/workflows/release.yml|draft|contents|creates the draft GitHub pre-release with its assets
.github/workflows/sbom-rescan.yml|report|issues|opens or updates the kind/security issue when the weekly rescan fails
.github/workflows/bench.yml|publish|contents|GitHub-hosted follow-up of the self-hosted bench job; pushes the bench/weekly-* report branch (docs/bench only)
.github/workflows/bench.yml|publish|pull-requests|GitHub-hosted follow-up of the self-hosted bench job; opens the weekly report pull request
.github/workflows/nightly.yml|publish|contents|GitHub-hosted follow-up of the nightly report job; pushes the matrix/nightly-* branch (docs/compatibility-matrix.md only, ADR-0010 C-256 (4))
.github/workflows/nightly.yml|publish|pull-requests|GitHub-hosted follow-up of the nightly report job; opens the compatibility matrix pull request (ADR-0010 C-256 (4))'

# W8: job-level `if:` expressions that restrict a self-hosted job to trusted refs, one per line:
#   <kind>|<expression>
# kind `branch` = refs/heads/main and refs/heads/release/*; kind `tag` = refs/tags/v* (the simulation
# evaluates the expression by its kind). Compared after removing `${{ }}` and collapsing spaces.
TRUSTED_REF_GUARDS="branch|github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/heads/release/')
tag|startsWith(github.ref, 'refs/tags/v')"

usage() {
  echo "usage: check-workflows.sh [repository-root]" >&2
  echo "       check-workflows.sh --simulate <pull_request|push|schedule|workflow_dispatch> [--ref <ref>] [--fork] [repository-root]" >&2
  exit 2
}

mode=check
sim_event=""
sim_ref=""
sim_ref_set=0
sim_fork=0
positional=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --simulate)
      [[ $# -ge 2 ]] || usage
      mode=simulate
      sim_event=$2
      shift 2
      ;;
    --ref)
      [[ $# -ge 2 ]] || usage
      sim_ref=$2
      sim_ref_set=1
      shift 2
      ;;
    --fork)
      sim_fork=1
      shift
      ;;
    -*)
      usage
      ;;
    *)
      positional+=("$1")
      shift
      ;;
  esac
done
if [[ ${#positional[@]} -gt 1 ]]; then
  usage
fi
if [[ "$mode" == check ]]; then
  [[ $sim_ref_set -eq 0 && $sim_fork -eq 0 ]] || usage
else
  case "$sim_event" in
    pull_request | push | schedule | workflow_dispatch) ;;
    *) usage ;;
  esac
  [[ $sim_fork -eq 0 || "$sim_event" == pull_request ]] || usage
  [[ -n "$sim_ref" ]] || sim_ref=refs/heads/main
  case "$sim_ref" in
    refs/heads/?* | refs/tags/?*) ;;
    *) usage ;;
  esac
  [[ "$sim_event" != pull_request || "$sim_ref" == refs/heads/?* ]] || usage
  [[ "$sim_event" != schedule || "$sim_ref" == refs/heads/?* ]] || usage
fi
root=${positional[0]:-.}
if [[ ! -d "$root/.github" ]]; then
  echo "check-workflows: '$root/.github' not found" >&2
  exit 2
fi

files=()
while IFS= read -r f; do
  files+=("$f")
done < <(
  {
    find "$root/.github/workflows" -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \) 2>/dev/null || true
    find "$root/.github/actions" -type f \( -name 'action.yml' -o -name 'action.yaml' \) 2>/dev/null || true
  } | LC_ALL=C sort
)

if [[ ${#files[@]} -eq 0 ]]; then
  if [[ "$mode" == check ]]; then
    echo "check-workflows: no workflow or action files under $root/.github"
  fi
  exit 0
fi

# The awk program prints one line per violation: "<file>:<line>: [Wn] <message>" (check mode), or
# tab-separated simulation records (N name, T triggered, F workflow_run sources, J job) that the
# second program below combines. It is written for POSIX awk (mawk, gawk, busybox): no regex
# intervals, no gawk extensions.
read -r -d '' AWK_PROGRAM <<'AWK' || true
function strip_comment(s,    i, c, q, out, prev) {
  q = ""; out = ""; prev = " "
  for (i = 1; i <= length(s); i++) {
    c = substr(s, i, 1)
    if (q == "") {
      if (c == "#" && (prev == " " || prev == "\t")) break
      if (c == "\"" || c == "'") q = c
    } else if (c == q) {
      q = ""
    }
    out = out c
    prev = c
  }
  sub(/[ \t]+$/, "", out)
  return out
}
function unquote(s) {
  gsub(/^[ \t]+|[ \t]+$/, "", s)
  if (s ~ /^".*"$/ || s ~ /^'.*'$/) s = substr(s, 2, length(s) - 2)
  return s
}
function is_hex(s, n) {
  return length(s) == n && s ~ /^[0-9a-f]+$/
}
function has_digest(s,    i, d) {
  i = index(s, "@sha256:")
  if (i == 0) return 0
  d = substr(s, i + 8, 64)
  return is_hex(d, 64)
}
function add_trigger(t) {
  t = unquote(t)
  sub(/:.*$/, "", t)
  gsub(/[][ \t{}]/, "", t)
  if (t != "") triggers[t] = 1
  return t
}
function violation(line, rule, msg) {
  if (mode == "simulate") return
  printf "%s:%d: [%s] %s\n", FILENAME, line, rule, msg
}
function key_name(s) {
  sub(/^[ \t]*(-[ \t]+)?/, "", s)
  sub(/[ \t]*:.*$/, "", s)
  return unquote(s)
}
function value_of(s) {
  if (index(s, ":") == 0) return ""
  sub(/^[^:]*:[ \t]*/, "", s)
  return s
}
# key_values(s, key, vals): finds every `key:` in s, at the start of the line (block form) or after
# `{` or `,` (flow mapping), stores the values in vals[1..n] and returns n. In a flow mapping a
# value ends at the next `,` or `}` (unless it is a flow sequence or a ${{ }} expression).
function key_values(s, key, vals,    n, re, v, cut) {
  n = 0
  re = "(^[ \t]*(-[ \t]+)?|[{,][ \t]*)[\"']?" key "[\"']?[ \t]*:"
  while (match(s, re)) {
    s = substr(s, RSTART + RLENGTH)
    v = s
    sub(/^[ \t]+/, "", v)
    if (v !~ /^\[/ && v !~ /^\$\{\{/) {
      cut = match(v, /[,}]/)
      if (cut > 0) v = substr(v, 1, cut - 1)
    }
    vals[++n] = unquote(v)
  }
  return n
}
# list_add(v, arr, cnt, key): appends the elements of a value (`[a, "b"]` or a scalar, which ends at
# `,` or `}` inside a flow mapping) to arr[key, 1..cnt[key]].
function list_add(v, arr, cnt, key,    n, parts, k, cut, p) {
  sub(/^[ \t]+/, "", v)
  if (v ~ /^\[/) {
    cut = index(v, "]")
    v = (cut > 0) ? substr(v, 2, cut - 2) : substr(v, 2)
    n = split(v, parts, ",")
  } else {
    cut = match(v, /[,}]/)
    if (cut > 0) v = substr(v, 1, cut - 1)
    n = 1
    parts[1] = v
  }
  for (k = 1; k <= n; k++) {
    p = unquote(parts[k])
    if (p != "") arr[key, ++cnt[key]] = p
  }
}
# flow_filters(t, v): filters of a trigger written as a flow mapping, e.g. `push: { tags: [v*] }`.
function flow_filters(t, v,    keys, nk, k, re) {
  nk = split("branches branches-ignore tags tags-ignore paths paths-ignore workflows types", keys, " ")
  for (k = 1; k <= nk; k++) {
    re = "[{,][ \t]*[\"']?" keys[k] "[\"']?[ \t]*:[ \t]*"
    if (match(v, re)) {
      tf[t, keys[k]] = 1
      list_add(substr(v, RSTART + RLENGTH), tv, tc, t SUBSEP keys[k])
    }
  }
}
function norm_expr(s) {
  s = unquote(s)
  if (substr(s, 1, 3) == "${{" && substr(s, length(s) - 1) == "}}") s = substr(s, 4, length(s) - 5)
  gsub(/[ \t]+/, " ", s)
  gsub(/^ | $/, "", s)
  return s
}
# runs_on(job, v): records the labels of a runs-on value (scalar, flow sequence or mapping).
function runs_on(job, v,    n, parts, k) {
  v = unquote(v)
  jro[job] = 1
  if (v ~ /^\{/) {
    jself[job] = 1
    return
  }
  if (v ~ /^\[/) {
    gsub(/[][]/, "", v)
    n = split(v, parts, ",")
    for (k = 1; k <= n; k++) runner_label(job, parts[k])
  } else {
    runner_label(job, v)
  }
}
function runner_label(job, l) {
  l = unquote(l)
  if (l == "") return
  if (l !~ /^(ubuntu|windows|macos)-[A-Za-z0-9._-]+$/) jself[job] = 1
}
# glob_re(p): GitHub filter pattern -> anchored regex (`**` any characters, `*` and `?` not `/`).
function glob_re(p,    i, c, out) {
  out = "^"
  for (i = 1; i <= length(p); i++) {
    c = substr(p, i, 1)
    if (c == "*") {
      if (substr(p, i + 1, 1) == "*") {
        out = out ".*"
        i++
      } else {
        out = out "[^/]*"
      }
    } else if (c == "?") {
      out = out "[^/]"
    } else if (index(".+^$(){}|\\[]", c) > 0) {
      out = out "\\" c
    } else {
      out = out c
    }
  }
  return out "$"
}
function filter_match(key, name,    k, p, neg, r) {
  r = 0
  for (k = 1; k <= tc[key]; k++) {
    p = tv[key, k]
    neg = (substr(p, 1, 1) == "!")
    if (neg) p = substr(p, 2)
    if (name ~ glob_re(p)) r = !neg
  }
  return r
}
# ref_filter(t, ref): do the branches/tags filters of trigger t let ref through?
function ref_filter(t, ref,    name) {
  if (t in tflow) return 1
  if (index(ref, "refs/heads/") == 1) {
    name = substr(ref, 12)
    if ((t, "branches") in tf) return filter_match(t SUBSEP "branches", name)
    if ((t, "branches-ignore") in tf) return !filter_match(t SUBSEP "branches-ignore", name)
    if (((t, "tags") in tf) || ((t, "tags-ignore") in tf)) return 0
    return 1
  }
  name = substr(ref, 11)
  if ((t, "tags") in tf) return filter_match(t SUBSEP "tags", name)
  if ((t, "tags-ignore") in tf) return !filter_match(t SUBSEP "tags-ignore", name)
  if (((t, "branches") in tf) || ((t, "branches-ignore") in tf)) return 0
  return 1
}
function sim_triggered(ev, ref) {
  if (ev == "pull_request") {
    if ("pull_request_target" in triggers) return 1
    if (!("pull_request" in triggers)) return 0
    return ref_filter("pull_request", ref)
  }
  if (!(ev in triggers)) return 0
  if (ev == "push") return ref_filter("push", ref)
  return 1
}
function guard_holds(kind, ref) {
  if (kind == "branch") return ref == "refs/heads/main" || index(ref, "refs/heads/release/") == 1
  if (kind == "tag") return index(ref, "refs/tags/v") == 1
  return 0
}
# resolve(ref, st): status of every job when github.ref is ref.
function resolve(ref, st,    jx, j, g, pass, k, dep, s, pending, own) {
  split("", st)
  for (pass = 0; pass <= njobs; pass++) {
    for (jx = 1; jx <= njobs; jx++) {
      j = jorder[jx]
      if (j in st) continue
      own = "runs"
      if (j in jflow) own = "may-run"
      if (j in jif) {
        g = norm_expr(jif[j])
        if (g in guards) own = guard_holds(guards[g], ref) ? "runs" : "skipped:ref-guard"
        else own = "may-run"
      }
      if (own != "runs") {
        st[j] = own
        continue
      }
      s = "runs"; pending = 0
      for (k = 1; k <= jnc[j]; k++) {
        dep = jneeds[j, k]
        if (!(dep in jseen)) { s = "may-run"; continue }
        if (!(dep in st)) { pending = 1; continue }
        if (st[dep] ~ /^skipped/) { s = "skipped:needs-" dep; break }
        if (st[dep] == "may-run") s = "may-run"
      }
      if (s ~ /^skipped/ || !pending) st[j] = s
      else if (pass == njobs) st[j] = "may-run"
    }
  }
}
# perm_entries(text, ln): checks `<scope>: <level>` entries of the permissions block that is being
# read (perm_ctx = "top", "job" or "other"; perm_job = job id). Flow braces and commas are accepted.
function perm_entries(t, ln,    n, parts, k, p, c, scope, level) {
  gsub(/[{}]/, "", t)
  n = split(t, parts, ",")
  for (k = 1; k <= n; k++) {
    p = parts[k]
    sub(/^[ \t]*(-[ \t]+)?/, "", p)
    sub(/[ \t]+$/, "", p)
    if (p == "") continue
    c = index(p, ":")
    if (c == 0) {
      violation(ln, "W4", "cannot read permissions entry '" p "'; use '<scope>: read|write|none'")
      continue
    }
    scope = unquote(substr(p, 1, c - 1))
    level = unquote(substr(p, c + 1))
    if (level == "read" || level == "none") continue
    if (level != "write") {
      violation(ln, "W4", "unsupported permission level '" level "' for '" scope "'")
    } else if (perm_ctx == "job") {
      if (!(perm_job in jwrite)) jwrite[perm_job] = ln
      if (!((rel "|" perm_job "|" scope) in allowed))
        violation(ln, "W4", "job '" perm_job "' requests '" scope ": write', which is not in PERMISSION_ALLOWLIST of check-workflows.sh")
    } else if (perm_ctx == "top") {
      violation(ln, "W4", "top-level permissions must be read-only (found '" scope ": write'); grant write per job")
    } else {
      violation(ln, "W4", "permissions outside the top level and the job level must be read-only (found '" scope ": write')")
    }
  }
}
BEGIN {
  in_on = 0; base_ind = -1; has_permissions = 0; cur_t = ""; wf_name = ""
  na = split(ENVIRON["ARGOS_PERMISSION_ALLOWLIST"], al, "\n")
  for (i = 1; i <= na; i++) {
    if (split(al[i], af, "|") >= 3) allowed[af[1] "|" af[2] "|" af[3]] = 1
  }
  ng = split(ENVIRON["ARGOS_TRUSTED_REF_GUARDS"], gl, "\n")
  for (i = 1; i <= ng; i++) {
    c = index(gl[i], "|")
    if (c > 0) guards[norm_expr(substr(gl[i], c + 1))] = substr(gl[i], 1, c - 1)
  }
}
{
  raw = $0
  sub(/\r$/, "", raw)
  line = strip_comment(raw)
  lines[NR] = line
  raws[NR] = raw

  if (in_on) {
    if (line ~ /^[^ \t]/) {
      in_on = 0
    } else if (line ~ /[^ \t]/) {
      match(line, /^[ \t]*/)
      ind = RLENGTH
      if (base_ind < 0) base_ind = ind
      if (ind == base_ind) {
        t = line
        sub(/^[ \t]*(-[ \t]+)?/, "", t)
        cur_t = add_trigger(t)
        filt_ind = -1; cur_f = ""; f_open = 0
        v = value_of(t)
        if (v ~ /^\{/) flow_filters(cur_t, v)
        else if (v != "" && v != "null" && v != "~") tflow[cur_t] = 1
      } else if (ind > base_ind && cur_t != "") {
        # Filters of the current trigger: `branches: [a, b]`, or `branches:` and `- a` items.
        if (filt_ind < 0) filt_ind = ind
        item = (line ~ /^[ \t]*-([ \t]|$)/)
        if (ind > filt_ind || (ind == filt_ind && item && f_open)) {
          if (cur_f != "" && item) {
            x = line
            sub(/^[ \t]*-[ \t]*/, "", x)
            list_add(x, tv, tc, cur_t SUBSEP cur_f)
          }
        } else if (ind == filt_ind) {
          if (item) {
            cur_f = ""; f_open = 0
          } else {
            cur_f = key_name(line)
            tf[cur_t, cur_f] = 1
            v = value_of(line)
            f_open = (v == "")
            if (v != "") list_add(v, tv, tc, cur_t SUBSEP cur_f)
          }
        }
      }
    }
  }
  if (line ~ /^("on"|'on'|on|true)[ \t]*:/) {
    v = line
    sub(/^[^:]*:[ \t]*/, "", v)
    if (v ~ /[^ \t]/) {
      # Inline triggers (`on: push`, `on: [push, schedule]`) have no filters.
      gsub(/[][{}]/, "", v)
      n = split(v, parts, ",")
      for (i = 1; i <= n; i++) {
        t = add_trigger(parts[i])
        if (index(parts[i], ":") > 0) tflow[t] = 1
      }
    } else {
      in_on = 1
      base_ind = -1
    }
  }
  if (line ~ /^["']?permissions["']?[ \t]*:/) has_permissions = 1
  if (line ~ /^["']?name["']?[ \t]*:/) wf_name = unquote(value_of(line))
}
END {
  is_workflow = (FILENAME !~ /\/action\.ya?ml$/)

  # W2 keeps fork pull requests away from self-hosted runners; W8 (below) restricts the triggers
  # that remain trusted here (push, schedule, workflow_dispatch, release) for self-hosted jobs.
  untrusted = 0
  ntrig = 0
  for (t in triggers) {
    ntrig++
    if (t != "push" && t != "schedule" && t != "workflow_dispatch" && t != "release") untrusted = 1
  }
  if (is_workflow && ntrig == 0) untrusted = 1
  workflow_run = (is_workflow && ("workflow_run" in triggers))
  pr_workflow = (is_workflow && rel == ".github/workflows/pr.yml")

  if (is_workflow && !has_permissions)
    violation(1, "W4", "missing top-level 'permissions:' block (use 'permissions: contents: read' and widen per job)")

  in_jobs = 0; job = ""; job_ind = -1; job_body_ind = -1
  perm_active = 0; block_active = 0
  njobs = 0; jb_key = ""; step_ind = -1; nsteps = 0; cur_step = ""; top_secrets = 0
  for (i = 1; i <= NR; i++) {
    line = lines[i]
    raw = raws[i]

    if (is_workflow && line ~ /pull_request_target/)
      violation(i, "W1", "pull_request_target is forbidden; use pull_request (and workflow_run for privileged follow-ups)")
    if (line ~ /"[^"]*\\[xuU][^"]*"[ \t]*:/)
      violation(i, "W1", "YAML escape sequences (\\x, \\u, \\U) in keys are forbidden; write keys literally")
    if (line ~ /^[ \t]*(-[ \t]+)?\?([ \t]|$)/)
      violation(i, "W1", "explicit YAML keys ('? key') are forbidden; write keys literally")

    if (is_workflow && untrusted && line ~ /self-hosted/)
      violation(i, "W2", "self-hosted runners are allowed only in workflows triggered by push/schedule/workflow_dispatch/release")
    if (is_workflow && untrusted) {
      n = key_values(line, "runs-on", vals)
      for (k = 1; k <= n; k++)
        if (vals[k] != "ubuntu-latest")
          violation(i, "W2", "jobs in workflows reachable from pull requests must use 'runs-on: ubuntu-latest' (found '" vals[k] "')")
    }

    n = key_values(line, "uses", vals)
    if (n > 0) {
      tail = substr(raw, length(line) + 1)
      version_comment = (tail ~ /^[ \t]*#[ \t]*[^ \t]/)
    }
    for (k = 1; k <= n; k++) {
      v = vals[k]
      if (v ~ /^\.\//) {
        # local action or reusable workflow: pinned by the repository itself
      } else if (v ~ /^docker:\/\//) {
        if (!has_digest(v)) violation(i, "W3", "docker:// action must be pinned by @sha256 digest: " v)
      } else {
        at = index(v, "@")
        ref = (at > 0) ? substr(v, at + 1) : ""
        if (at == 0 || !is_hex(ref, 40)) {
          violation(i, "W3", "action must be pinned to a full 40-character commit SHA: " v)
        } else if (!version_comment) {
          violation(i, "W3", "pinned action needs a trailing version comment, e.g. '# v4.2.0': " v)
        }
        if (workflow_run && v ~ /^actions\/checkout@/)
          violation(i, "W5", "workflow_run workflows must not check out code (pwn request risk)")
      }
    }

    if (line ~ /docker[ \t]+run[ \t]/ && line !~ /\$/ && !has_digest(line))
      violation(i, "W6", "docker run must use an image pinned by @sha256 digest")

    if (line ~ /^[ \t]*(-[ \t]+)?image[ \t]*:/ || line ~ /^[ \t]*[A-Za-z0-9_]*IMAGE[A-Za-z0-9_]*[ \t]*:/) {
      v = line
      sub(/^[^:]*:/, "", v)
      v = unquote(v)
      if (v != "" && v !~ /\$\{\{/ && !has_digest(v))
        violation(i, "W6", "container image must be pinned by @sha256 digest: " v)
    }

    # W8 bookkeeping: secrets referenced in a job (run scripts included) or at workflow level.
    if (is_workflow && (line ~ /secrets\./ || line ~ /secrets\[/ || line ~ /secrets[ \t]*\)/)) {
      if (in_jobs && job != "") {
        if (!(job in jsecrets)) jsecrets[job] = i
      } else if (!top_secrets) {
        top_secrets = i
      }
    }

    # Structure: jobs, permissions blocks and run scripts (block scalars are skipped as content).
    if (line !~ /[^ \t]/) continue
    match(line, /^[ \t]*/)
    ind = RLENGTH

    in_run = 0
    if (block_active) {
      if (ind > block_ind) {
        in_run = block_is_run
      } else {
        block_active = 0
      }
    }
    if (!block_active) {
      if (perm_active) {
        if (ind > perm_ind) perm_entries(line, i)
        else perm_active = 0
      }
      if (ind == 0) {
        in_jobs = (line ~ /^["']?jobs["']?[ \t]*:/)
        job = ""; job_ind = -1; job_body_ind = -1
      } else if (in_jobs) {
        if (job_ind < 0) job_ind = ind
        if (ind == job_ind) {
          job = key_name(line); job_body_ind = -1
        } else if (ind > job_ind && job != "" && job_body_ind < 0) {
          job_body_ind = ind
        }
      }

      # W8 and simulation bookkeeping: job-level keys (runs-on, if, needs, environment, steps).
      if (is_workflow && in_jobs && job != "") {
        if (!(job in jseen)) {
          jseen[job] = 1; jorder[++njobs] = job; jline[job] = i
          jb_key = ""
        }
        item = (line ~ /^[ \t]*-([ \t]|$)/)
        if (ind == job_ind) {
          v = value_of(line)
          if (v != "") {
            # Flow-style job, e.g. `a: { runs-on: x, steps: [...] }`.
            jflow[job] = i
            n = key_values(v, "runs-on", vals)
            for (k = 1; k <= n; k++) runs_on(job, vals[k])
            if (!(job in jro)) jro[job] = 1
          }
          jb_key = ""
        } else if (ind == job_body_ind && !item) {
          jb_key = key_name(line); jb_ind = ind
          v = value_of(line)
          if (jb_key == "runs-on") {
            jrl[job] = i
            if (v != "") runs_on(job, v)
            else jro[job] = 1
          } else if (jb_key == "if") {
            jif[job] = v
          } else if (jb_key == "needs") {
            if (v != "") list_add(v, jneeds, jnc, job)
          } else if (jb_key == "environment") {
            if (v ~ /^\{/) {
              n = key_values(v, "name", vals)
              if (n > 0) jenv[job] = vals[1]
            } else if (v != "") {
              jenv[job] = unquote(v)
            }
          } else if (jb_key == "steps") {
            step_ind = -1
            if (v != "") jflowsteps[job] = i
          } else if (jb_key == "secrets") {
            if (!(job in jsecrets)) jsecrets[job] = i
          }
        } else if (jb_key != "" && (ind > job_body_ind || (ind == job_body_ind && item))) {
          x = line
          sub(/^[ \t]*(-[ \t]+)?/, "", x)
          if (jb_key == "runs-on") {
            if (item) runs_on(job, x)
            else jself[job] = 1
          } else if (jb_key == "needs") {
            if (item) list_add(x, jneeds, jnc, job)
          } else if (jb_key == "environment") {
            if (key_name(line) == "name") jenv[job] = unquote(value_of(line))
          } else if (jb_key == "steps") {
            if (item && (step_ind < 0 || ind == step_ind)) {
              step_ind = ind
              cur_step = ++nsteps
              sjob[cur_step] = job
            }
            if (cur_step != "" && sjob[cur_step] == job) {
              n = key_values(line, "uses", vals)
              for (k = 1; k <= n; k++)
                if (vals[k] ~ /^actions\/checkout@/) scheckout[cur_step] = i
              n = key_values(line, "persist-credentials", vals)
              if (n > 0) spc[cur_step] = vals[1]
              if (key_values(line, "ref", vals) > 0 && !(cur_step in sref)) sref[cur_step] = i
              if (key_values(line, "repository", vals) > 0 && !(cur_step in srepo)) srepo[cur_step] = i
            }
          }
        }
      }

      if (is_workflow && line ~ /^[ \t]*(-[ \t]+)?["']?permissions["']?[ \t]*:/) {
        if (ind == 0) perm_ctx = "top"
        else if (in_jobs && job != "" && ind == job_body_ind) perm_ctx = "job"
        else perm_ctx = "other"
        perm_job = job
        v = line
        sub(/^[^:]*:[ \t]*/, "", v)
        if (v == "" || v ~ /^\{/) {
          perm_active = 1
          perm_ind = ind
          perm_entries(v, i)
        } else {
          v = unquote(v)
          if (v == "write-all") {
            violation(i, "W4", "'permissions: write-all' is forbidden; grant single scopes per job")
            if (perm_ctx == "job" && !(perm_job in jwrite)) jwrite[perm_job] = i
          } else if (v != "read-all") {
            violation(i, "W4", "unsupported permissions value '" v "'; use read-all, {} or a mapping of scopes")
          }
        }
      } else if (is_workflow && line ~ /[{,][ \t]*["']?permissions["']?[ \t]*:/) {
        violation(i, "W4", "permissions inside a flow mapping cannot be checked; write the job and its permissions in block form")
      }

      if (line ~ /^[ \t]*(-[ \t]+)?["']?run["']?[ \t]*:/) {
        # A run value continues on deeper lines (block scalar or multi-line plain scalar).
        in_run = 1
        match(line, /^[ \t]*(-[ \t]+)?/)
        block_active = 1; block_ind = RLENGTH; block_is_run = 1
      } else if (line ~ /[{,][ \t]*["']?run["']?[ \t]*:/) {
        in_run = 1
      } else if (line ~ /:[ \t]+[|>][-+0-9]*$/) {
        match(line, /^[ \t]*(-[ \t]+)?/)
        block_active = 1; block_ind = RLENGTH; block_is_run = 0
      }
    }

    if (workflow_run && in_run) {
      if (line ~ /(^|[^A-Za-z0-9_.-])git([ \t]+[^ \t|;&]+)*[ \t]+(clone|fetch|checkout|switch|pull)([ \t;|&)]|$)/ || line ~ /gh[ \t]+pr[ \t]+checkout/)
        violation(i, "W5", "workflow_run workflows must not fetch or check out code in run scripts (pwn request risk)")
      if (line ~ /head_sha|head_branch/)
        violation(i, "W5", "workflow_run workflows must not use head_sha/head_branch in run scripts; pass them through env: and validate them")
    }

    # W7 bookkeeping: what the run scripts of each job of pr.yml do.
    if (pr_workflow && in_jobs && job != "") {
      if (!(job in job_line)) job_line[job] = i
      if (in_run) {
        if (line ~ /gradlew/ && line ~ /(^|[ \t])securityTest([ \t;&|]|$)/) root_security[job] = 1
        if (line ~ /securityTest/) any_security[job] = 1
        if (line ~ /test\/release\/check-sbom\.sh/) sbom_check[job] = 1
        if (line ~ /--scanner[ \t]+"bash test\/release\/trivy-scan\.sh"/) sbom_trivy[job] = 1
        if (line ~ /--require[ \t]+agent,server,ui([ \t]|$)/) sbom_parts[job] = 1
      }
    }
  }

  if (pr_workflow) {
    found = 0
    for (j in root_security) if (j != "build-java") found = 1
    if (!found)
      violation(1, "W7", "pr.yml must have a job (not build-java) whose run script runs './gradlew securityTest' (T-SEC-93)")
    if ("build-java" in any_security)
      violation(job_line["build-java"], "W7", "build-java must not run securityTest; the security suite runs in its own job, outside check and build (T-BLD-013)")
    if (!("sbom-scan" in job_line)) {
      violation(1, "W7", "pr.yml must have the job 'sbom-scan' (SBOM vulnerability gate, SEC-71)")
    } else if (!(("sbom-scan" in sbom_check) && ("sbom-scan" in sbom_trivy) && ("sbom-scan" in sbom_parts))) {
      violation(job_line["sbom-scan"], "W7", "sbom-scan must run test/release/check-sbom.sh --scanner \"bash test/release/trivy-scan.sh\" --require agent,server,ui (SEC-70, SEC-71)")
    }
  }

  # W8: self-hosted jobs.
  for (jx = 1; is_workflow && jx <= njobs; jx++) {
    j = jorder[jx]
    if (!(j in jself)) continue
    ln = (j in jrl) ? jrl[j] : jline[j]
    who = "self-hosted job '" j "'"
    if (j in jflow)
      violation(jflow[j], "W8", who " must be written in block form (flow-style jobs cannot be checked)")
    if (j in jflowsteps)
      violation(jflowsteps[j], "W8", who " must list its steps in block form")
    if (ntrig == 0)
      violation(ln, "W8", who " is in a workflow without readable triggers")
    for (t in triggers) {
      if (t != "push" && t != "schedule" && t != "workflow_dispatch")
        violation(ln, "W8", who " is in a workflow triggered by '" t "'; self-hosted jobs allow only push (main, release/**, tags v*), schedule and workflow_dispatch")
    }
    if ("push" in triggers) {
      if ("push" in tflow) {
        violation(ln, "W8", who ": write the push filters of this workflow in block form (on: push: branches: [...] / tags: [...])")
      } else {
        if ((("push", "branches-ignore") in tf) || (("push", "tags-ignore") in tf))
          violation(ln, "W8", who ": push filters must not use branches-ignore or tags-ignore; list main, release/** or tags v*")
        if (tc["push", "branches"] + tc["push", "tags"] == 0)
          violation(ln, "W8", who ": push must be filtered by branches (main, release/**) or tags (v*)")
        for (k = 1; k <= tc["push", "branches"]; k++) {
          b = tv["push", "branches", k]
          if (b != "main" && b != "release/**" && b != "release/*")
            violation(ln, "W8", who ": push branch filter '" b "' is not a trusted branch (main, release/**)")
        }
        for (k = 1; k <= tc["push", "tags"]; k++) {
          b = tv["push", "tags", k]
          if (b != "v*")
            violation(ln, "W8", who ": push tag filter '" b "' is not the release tag pattern v*")
        }
      }
    }
    if (!(j in jif)) {
      violation(ln, "W8", who " needs a job-level 'if:' trusted-ref guard (TRUSTED_REF_GUARDS of check-workflows.sh)")
    } else if (!(norm_expr(jif[j]) in guards)) {
      violation(ln, "W8", who ": 'if: " jif[j] "' is not a trusted-ref guard (TRUSTED_REF_GUARDS of check-workflows.sh)")
    }
    for (s = 1; s <= nsteps; s++) {
      if (sjob[s] != j || !(s in scheckout)) continue
      if (!(s in spc) || spc[s] != "false")
        violation(scheckout[s], "W8", who ": actions/checkout needs 'persist-credentials: false'")
      if (s in sref)
        violation(sref[s], "W8", who ": actions/checkout must not set 'ref:' (check out the triggering commit of main, release/* or a v* tag only)")
      if (s in srepo)
        violation(srepo[s], "W8", who ": actions/checkout must not set 'repository:'")
    }
    if (((j in jsecrets) || top_secrets) && (!(j in jenv) || jenv[j] != "trusted"))
      violation((j in jsecrets) ? jsecrets[j] : top_secrets, "W8", who " uses secrets and needs the job-level 'environment: trusted'")
    if (j in jwrite)
      violation(jwrite[j], "W8", who " must not have write permissions (PERMISSION_ALLOWLIST does not apply); write from a GitHub-hosted follow-up job")
  }

  if (mode == "simulate" && is_workflow) {
    printf "N\t%s\t%s\n", rel, (wf_name != "" ? wf_name : rel)
    if (sim_triggered(sim_event, sim_ref)) {
      printf "T\t%s\n", rel
      # A pull request run sees refs/pull/<n>/merge; pull_request_target runs on the base branch.
      gref = sim_ref
      if (sim_event == "pull_request" && !("pull_request_target" in triggers)) gref = "refs/pull/1/merge"
      resolve(gref, st_ev)
    }
    if ("workflow_run" in triggers) {
      list = ""
      for (k = 1; k <= tc["workflow_run", "workflows"]; k++)
        list = list "|" tv["workflow_run", "workflows", k]
      printf "F\t%s\t%s|\n", rel, list
    }
    resolve("refs/heads/main", st_fu)
    for (jx = 1; jx <= njobs; jx++) {
      j = jorder[jx]
      printf "J\t%s\t%s\t%s\t%s\t%s\n", rel, j, ((j in jself) ? "self-hosted" : "github-hosted"),
        ((j in st_ev) ? st_ev[j] : "-"), st_fu[j]
    }
  }
}
AWK

# Combines the simulation records: jobs of triggered workflows (status under the event) and of
# their workflow_run follow-ups, transitively (status on the default branch).
read -r -d '' SIM_PROGRAM <<'AWK' || true
BEGIN { FS = "\t"; OFS = "\t" }
$1 == "N" { name[$2] = $3 }
$1 == "T" { trig[$2] = 1 }
$1 == "F" { listen[$2] = $3 }
$1 == "J" { nj++; jrel[nj] = $2; jjob[nj] = $3; jhost[nj] = $4; jev[nj] = $5; jfu[nj] = $6 }
END {
  for (r in trig) started[name[r]] = 1
  do {
    changed = 0
    for (r in listen) {
      if ((r in trig) || (r in follow)) continue
      n = split(listen[r], ws, "|")
      for (k = 1; k <= n; k++) {
        if (ws[k] != "" && (ws[k] in started)) {
          follow[r] = 1
          started[name[r]] = 1
          changed = 1
          break
        }
      }
    }
  } while (changed)
  for (i = 1; i <= nj; i++) {
    if (jrel[i] in trig) print jrel[i], jjob[i], jhost[i], jev[i]
    else if (jrel[i] in follow) print jrel[i], jjob[i], jhost[i], jfu[i]
  }
}
AWK

violations=0
report=""
records=""
for f in "${files[@]}"; do
  rel=${f#"$root"/}
  out=$(ARGOS_PERMISSION_ALLOWLIST="$PERMISSION_ALLOWLIST" ARGOS_TRUSTED_REF_GUARDS="$TRUSTED_REF_GUARDS" LC_ALL=C \
    awk -v rel="$rel" -v mode="$mode" -v sim_event="$sim_event" -v sim_ref="$sim_ref" "$AWK_PROGRAM" "$f")
  if [[ "$mode" == simulate ]]; then
    records+="$out"$'\n'
  elif [[ -n "$out" ]]; then
    report+="$out"$'\n'
    violations=$((violations + $(printf '%s\n' "$out" | wc -l)))
  fi
done

if [[ "$mode" == simulate ]]; then
  printf '%s' "$records" | LC_ALL=C awk "$SIM_PROGRAM" | LC_ALL=C sort
  exit 0
fi

if [[ $violations -gt 0 ]]; then
  printf '%s' "$report"
  echo "check-workflows: ${violations} violation(s) in ${#files[@]} file(s)" >&2
  exit 1
fi
echo "check-workflows: ${#files[@]} file(s) clean"
