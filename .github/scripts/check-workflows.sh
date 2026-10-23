#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-workflows.sh - policy lint for GitHub Actions workflows (O-S05, SEC-73, docs/19 section 6.6).
#
# Usage: check-workflows.sh [repository-root]
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
#       reach a self-hosted runner.
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
#
# Exit status: 0 = no violations, 1 = violations found, 2 = usage error.
set -euo pipefail

# W4: job-level write permissions that are allowed, one per line:
#   <workflow path relative to the repository root>|<job id>|<scope>|<reason>
PERMISSION_ALLOWLIST='.github/workflows/codeql.yml|analyze|security-events|CodeQL uploads its results to code scanning
.github/workflows/pr-labeler.yml|label|pull-requests|applies path labels; workflow_run, never checks out pull request code'

if [[ $# -gt 1 ]]; then
  echo "usage: check-workflows.sh [repository-root]" >&2
  exit 2
fi
root=${1:-.}
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
  echo "check-workflows: no workflow or action files under $root/.github"
  exit 0
fi

# The awk program prints one line per violation: "<file>:<line>: [Wn] <message>".
# It is written for POSIX awk (mawk, gawk, busybox): no regex intervals, no gawk extensions.
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
}
function violation(line, rule, msg) {
  printf "%s:%d: [%s] %s\n", FILENAME, line, rule, msg
}
function key_name(s) {
  sub(/^[ \t]*(-[ \t]+)?/, "", s)
  sub(/[ \t]*:.*$/, "", s)
  return unquote(s)
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
  in_on = 0; base_ind = -1; has_permissions = 0
  na = split(ENVIRON["ARGOS_PERMISSION_ALLOWLIST"], al, "\n")
  for (i = 1; i <= na; i++) {
    if (split(al[i], af, "|") >= 3) allowed[af[1] "|" af[2] "|" af[3]] = 1
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
        add_trigger(t)
      }
    }
  }
  if (line ~ /^("on"|'on'|on|true)[ \t]*:/) {
    v = line
    sub(/^[^:]*:[ \t]*/, "", v)
    if (v ~ /[^ \t]/) {
      gsub(/[][{}]/, "", v)
      n = split(v, parts, ",")
      for (i = 1; i <= n; i++) add_trigger(parts[i])
    } else {
      in_on = 1
      base_ind = -1
    }
  }
  if (line ~ /^["']?permissions["']?[ \t]*:/) has_permissions = 1
}
END {
  is_workflow = (FILENAME !~ /\/action\.ya?ml$/)

  # TODO(WO-47): every push trigger counts as trusted here. When self-hosted workflows arrive
  # (nightly, bench, release), allow self-hosted runners only for push to protected branches
  # (main, release/**), schedule, workflow_dispatch and release.
  untrusted = 0
  ntrig = 0
  for (t in triggers) {
    ntrig++
    if (t != "push" && t != "schedule" && t != "workflow_dispatch" && t != "release") untrusted = 1
  }
  if (is_workflow && ntrig == 0) untrusted = 1
  workflow_run = (is_workflow && ("workflow_run" in triggers))

  if (is_workflow && !has_permissions)
    violation(1, "W4", "missing top-level 'permissions:' block (use 'permissions: contents: read' and widen per job)")

  in_jobs = 0; job = ""; job_ind = -1; job_body_ind = -1
  perm_active = 0; block_active = 0
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
          if (v == "write-all")
            violation(i, "W4", "'permissions: write-all' is forbidden; grant single scopes per job")
          else if (v != "read-all")
            violation(i, "W4", "unsupported permissions value '" v "'; use read-all, {} or a mapping of scopes")
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
  }
}
AWK

violations=0
report=""
for f in "${files[@]}"; do
  rel=${f#"$root"/}
  out=$(ARGOS_PERMISSION_ALLOWLIST="$PERMISSION_ALLOWLIST" LC_ALL=C awk -v rel="$rel" "$AWK_PROGRAM" "$f")
  if [[ -n "$out" ]]; then
    report+="$out"$'\n'
    violations=$((violations + $(printf '%s\n' "$out" | wc -l)))
  fi
done

if [[ $violations -gt 0 ]]; then
  printf '%s' "$report"
  echo "check-workflows: ${violations} violation(s) in ${#files[@]} file(s)" >&2
  exit 1
fi
echo "check-workflows: ${#files[@]} file(s) clean"
