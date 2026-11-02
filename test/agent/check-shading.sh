#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-shading.sh: verifies the agent single jar (docs/19-repo-build-release-design.md §5.6).
#
#   check            test ID      rule
#   packages         T-BLD-002    every class (root *.class, inst/**/*.classdata) is below io/argos/agent/
#   root-exposure    T-BLD-002    root classes only in io/argos/agent/bootstrap/ (+ the Premain-Class),
#                                 no plain *.class below inst/
#   root-entries     T-BLD-002    files outside inst/ only as in 10 §4.1: io/argos/agent/bootstrap/**.class,
#                                 the Premain-Class, META-INF/MANIFEST.MF, META-INF/{LICENSE,NOTICE,
#                                 THIRD-PARTY-NOTICES,argos-deps.txt}, META-INF/sbom/agent.cdx.json,
#                                 argos-agent-default.yml (no root META-INF/services/*, *.properties,
#                                 module-info.class, META-INF/versions/**, ... visible to the application)
#   meta-inf         T-BLD-003    META-INF/{LICENSE,NOTICE,THIRD-PARTY-NOTICES,argos-deps.txt,
#                                 sbom/agent.cdx.json} present and well-formed; MANIFEST.MF has
#                                 Premain-Class and Can-Retransform-Classes, and no Add-Opens,
#                                 Launcher-Agent-Class or Boot-Class-Path
#   signatures       T-BLD-002    no META-INF/*.SF|*.DSA|*.RSA|*.EC (root or inst/)
#   major-version    T-BLD-001    class file bytes 7-8 == 0x0034 (52, Java 8) for the agent's own classes,
#                                 <= 52 for shaded third-party classes below inst/io/argos/agent/shaded/
#                                 (ADR-0003 C-22)
#   unsafe           T-BLD-004    no sun/misc/Unsafe reference outside unsafe-allowlist.txt
#   native           T-BLD-002    no *.so, *.dll, *.dylib, *.jnilib, META-INF/native/
#   size             T-BLD-006    jar <= 15 MB = 15,000,000 bytes (10^6 bytes per MB; warning above
#                                 12 MB = 12,000,000 bytes)
#   duplicates       T-BLD-002    no class name (or entry) present twice, no entries whose names differ
#                                 only in case (they overwrite each other on case-insensitive file systems)
#
# Usage:   test/agent/check-shading.sh <argos-agent.jar>
# Env:     ARGOS_UNSAFE_ALLOWLIST  allow-list file (default: unsafe-allowlist.txt next to this script)
#          JAVA_HOME               JDK whose `jar` tool lists and extracts the jar (default: PATH)
# Exit:    0 when every check passes, 1 otherwise (usage errors included).
#
# Runs with bash 4+ on Linux (CI) and Git Bash on Windows. Needs a JDK (`jar`), coreutils, grep,
# awk and sed. major-version and unsafe read every class entry from the jar itself with perl's
# IO::Uncompress::Unzip (core Perl: Git Bash, GitHub runners; Debian/Ubuntu package "perl", not
# "perl-base") when it is available, so the result does not depend on the file system; otherwise
# they read the extracted files (od when perl is missing). The duplicates check fails on every jar
# whose extraction could overwrite an entry.
# Root-exposure exception: 10 §4.1 places the agent entry point io/argos/agent/AgentPremain.class
# at the jar root while 19 §5.6 allows only io/argos/agent/bootstrap/. The class named by the
# manifest Premain-Class is therefore accepted at the root, and only when it is directly in
# io/argos/agent/ (open design question recorded in the WO-00 report).

set -euo pipefail
export LC_ALL=C

# 19 §5.6, 18 T-BLD-006, Gate 0 P-3: "15MB (warning 12MB)", decimal megabytes (10^6 bytes). This is
# also below 15 MiB, so the limit holds under either reading.
readonly MAX_BYTES=15000000
readonly WARN_BYTES=12000000
# Shaded third-party classes (ADR-0003 C-22: major version <= 52 instead of == 52).
readonly SHADED_PREFIX="inst/io/argos/agent/shaded/"
readonly REQUIRED_META_INF=(
  "META-INF/MANIFEST.MF"
  "META-INF/LICENSE"
  "META-INF/NOTICE"
  "META-INF/THIRD-PARTY-NOTICES"
  "META-INF/argos-deps.txt"
  "META-INF/sbom/agent.cdx.json"
)

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage_error() {
  echo "check-shading: $*" >&2
  echo "usage: $0 <argos-agent.jar>" >&2
  exit 1
}

[[ $# -eq 1 ]] || usage_error "expected exactly one argument"
jar_arg="$1"
[[ -f "$jar_arg" ]] || usage_error "jar not found: $jar_arg"
jar_file="$(cd "$(dirname "$jar_arg")" && pwd)/$(basename "$jar_arg")"

allowlist="${ARGOS_UNSAFE_ALLOWLIST:-$script_dir/unsafe-allowlist.txt}"
[[ -f "$allowlist" ]] || usage_error "unsafe allow-list not found: $allowlist"

# The JDK `jar` tool (JAVA_HOME first; Git Bash needs the POSIX form of a Windows JAVA_HOME).
jar_tool=""
if [[ -n "${JAVA_HOME:-}" ]]; then
  java_home="$JAVA_HOME"
  if command -v cygpath >/dev/null 2>&1; then java_home="$(cygpath -u "$java_home")"; fi
  if [[ -f "$java_home/bin/jar" || -f "$java_home/bin/jar.exe" ]]; then
    jar_tool="$java_home/bin/jar"
  fi
fi
if [[ -z "$jar_tool" ]]; then
  jar_tool="$(command -v jar || true)"
fi
[[ -n "$jar_tool" ]] || usage_error "the JDK jar tool was not found (set JAVA_HOME)"

work="$(mktemp -d "${TMPDIR:-/tmp}/argos-check-shading.XXXXXX")"
trap 'rm -rf "$work"' EXIT

failures=()

# fail <check> <message> [detail-file]: records a failed check and prints the detail lines indented.
fail() {
  local check="$1" message="$2" details="${3:-}"
  failures+=("$check")
  echo "FAIL [$check] $message"
  if [[ -n "$details" && -s "$details" ]]; then sed 's/^/    /' "$details"; fi
}

pass() {
  echo "ok   [$1] $2"
}

# --- inventory -------------------------------------------------------------------------------
# `jar tf` prints CRLF line ends on Windows.
"$jar_tool" tf "$jar_file" | tr -d '\r' >"$work/entries.txt"
grep -v '/$' "$work/entries.txt" >"$work/files.txt" || true

mkdir "$work/x"
(cd "$work/x" && "$jar_tool" xf "$jar_file")

grep -E '\.class$' "$work/files.txt" | grep -v '^inst/' >"$work/root-classes.txt" || true
grep -E '^inst/.*\.class$' "$work/files.txt" >"$work/inst-classes.txt" || true
grep -E '^inst/.*\.classdata$' "$work/files.txt" >"$work/inst-classdata.txt" || true

# Class name (path without inst/ and extension) for every class-like entry.
{
  sed -E 's/\.class$//' "$work/root-classes.txt"
  sed -E 's#^inst/##; s/\.class$//' "$work/inst-classes.txt"
  sed -E 's#^inst/##; s/\.classdata$//' "$work/inst-classdata.txt"
} >"$work/class-names.txt"
class_count="$(wc -l <"$work/class-names.txt" | tr -d ' ')"

# Manifest with continuation lines unfolded (lines starting with one space continue the previous).
manifest="$work/x/META-INF/MANIFEST.MF"
if [[ -f "$manifest" ]]; then
  tr -d '\r' <"$manifest" | awk '
    /^ / { sub(/^ /, ""); line = line $0; next }
    { if (NR > 1) print line; line = $0 }
    END { print line }' >"$work/manifest.txt"
else
  : >"$work/manifest.txt"
fi
premain_class="$(sed -n 's/^Premain-Class:[[:space:]]*//p' "$work/manifest.txt" | head -n 1)"
premain_path=""
if [[ "$premain_class" =~ ^io\.argos\.agent\.[A-Za-z_$][A-Za-z0-9_$]*$ ]]; then
  premain_path="${premain_class//.//}"
fi

# --- packages (T-BLD-002) --------------------------------------------------------------------
grep -v '^io/argos/agent/' "$work/class-names.txt" >"$work/foreign.txt" || true
if [[ -s "$work/foreign.txt" ]]; then
  # Package of each class: the path without its last segment; "(default package)" if none.
  awk '{ if (index($0, "/") == 0) print "(default package)"; else { sub(/\/[^\/]*$/, ""); print } }' \
    "$work/foreign.txt" | sort -u >"$work/foreign-packages.txt"
  fail packages "classes outside io/argos/agent/ (io.argos.agent.shaded for third-party code); packages:" \
    "$work/foreign-packages.txt"
else
  pass packages "$class_count classes, all below io/argos/agent/"
fi

# --- root exposure (T-BLD-002) ---------------------------------------------------------------
{
  grep -v '^io/argos/agent/bootstrap/' "$work/root-classes.txt" |
    grep -v -x -F "${premain_path:-<none>}.class" |
    sed 's/$/  (root class outside io\/argos\/agent\/bootstrap\/)/' || true
  sed 's/$/  (plain .class below inst\/, expected .classdata)/' "$work/inst-classes.txt"
} >"$work/exposed.txt"
if [[ -s "$work/exposed.txt" ]]; then
  fail root-exposure "classes visible to the bootstrap/system class loader that must live in inst/:" \
    "$work/exposed.txt"
else
  pass root-exposure "root classes only in io/argos/agent/bootstrap/${premain_path:+ (+ Premain-Class $premain_class)}"
fi

# --- root entries (T-BLD-002, 10 §4.1) -------------------------------------------------------
# Everything outside inst/ is visible to the application (ServiceLoader, getResource): only the
# files of the 10 §4.1 layout may be there.
grep -v '^inst/' "$work/files.txt" |
  grep -v -E '^io/argos/agent/bootstrap/.+\.class$' |
  grep -v -x -F "${premain_path:-<none>}.class" |
  grep -v -x -E 'META-INF/(MANIFEST\.MF|LICENSE|NOTICE|THIRD-PARTY-NOTICES|argos-deps\.txt|sbom/agent\.cdx\.json)' |
  grep -v -x -F 'argos-agent-default.yml' |
  sed 's/$/  (not allowed outside inst\/)/' >"$work/root-entries.txt" || true
if [[ -s "$work/root-entries.txt" ]]; then
  fail root-entries "entries outside inst/ that are not part of the agent jar layout (10 §4.1); resources belong in inst/:" \
    "$work/root-entries.txt"
else
  pass root-entries "outside inst/ only bootstrap classes, the Premain-Class, META-INF notices/SBOM, manifest"
fi

# --- META-INF files and manifest (T-BLD-003) -------------------------------------------------
: >"$work/meta.txt"
for required in "${REQUIRED_META_INF[@]}"; do
  if ! grep -q -x -F "$required" "$work/files.txt"; then
    echo "missing $required" >>"$work/meta.txt"
  elif [[ ! -s "$work/x/$required" ]]; then
    echo "empty $required" >>"$work/meta.txt"
  fi
done
if [[ -s "$work/x/META-INF/LICENSE" ]] && ! grep -q 'Apache License' "$work/x/META-INF/LICENSE"; then
  echo "META-INF/LICENSE is not the Apache License text" >>"$work/meta.txt"
fi
deps="$work/x/META-INF/argos-deps.txt"
if [[ -s "$deps" ]]; then
  # group:artifact:version:license (SPDX expression), one per line; '#' starts a comment line.
  tr -d '\r' <"$deps" | grep -v -E '^[[:space:]]*(#|$)' |
    grep -v -E '^[^:[:space:]]+:[^:[:space:]]+:[^:[:space:]]+:[^:]+$' |
    sed 's/^/argos-deps.txt malformed line: /' >>"$work/meta.txt" || true
  tr -d '\r' <"$deps" | grep -E ':UNKNOWN[[:space:]]*$' |
    sed 's/^/argos-deps.txt license unknown: /' >>"$work/meta.txt" || true
fi
sbom="$work/x/META-INF/sbom/agent.cdx.json"
if [[ -s "$sbom" ]] && ! grep -q -E '"bomFormat"[[:space:]]*:[[:space:]]*"CycloneDX"' "$sbom"; then
  echo "META-INF/sbom/agent.cdx.json is not a CycloneDX JSON document" >>"$work/meta.txt"
fi
for attribute in Premain-Class Can-Retransform-Classes; do
  if ! grep -q -i -E "^$attribute:[[:space:]]*[^[:space:]]" "$work/manifest.txt"; then
    echo "MANIFEST.MF lacks $attribute" >>"$work/meta.txt"
  fi
done
for attribute in Add-Opens Launcher-Agent-Class Boot-Class-Path; do
  if grep -q -i -E "^$attribute:" "$work/manifest.txt"; then
    echo "MANIFEST.MF must not declare $attribute (DSN-02-03)" >>"$work/meta.txt"
  fi
done
if [[ -s "$work/meta.txt" ]]; then
  fail meta-inf "required META-INF content:" "$work/meta.txt"
else
  pass meta-inf "LICENSE, NOTICE, THIRD-PARTY-NOTICES, argos-deps.txt, sbom/agent.cdx.json, manifest"
fi

# --- signature files -------------------------------------------------------------------------
grep -i -E '^(inst/)?META-INF/[^/]+\.(SF|DSA|RSA|EC)$' "$work/files.txt" >"$work/signatures.txt" || true
if [[ -s "$work/signatures.txt" ]]; then
  fail signatures "signature files must be stripped when shading:" "$work/signatures.txt"
else
  pass signatures "no signature files"
fi

# --- class file scan (T-BLD-001, T-BLD-004) --------------------------------------------------
# Writes "<major> <entry>" to major-raw.txt for every class whose major version is not 52 ("MAGIC
# <entry>" for entries that are not class files), and to unsafe-raw.txt every class entry whose
# bytes contain sun/misc/Unsafe (type reference) or sun.misc.Unsafe (reflective lookup by name).
#
# Preferred: read each entry from the jar (perl IO::Uncompress::Unzip), independent of the file
# system. Fallback: the extracted files (a case-insensitive file system can merge entries there; the
# duplicates check below fails on every such jar).
read -r -d '' scan_jar_pl <<'PERL' || true
use strict;
use warnings;
use IO::Uncompress::Unzip qw($UnzipError);
my ($jar, $major_file, $unsafe_file) = @ARGV;
open(my $major_out, ">", $major_file) or die "check-shading: $major_file: $!\n";
open(my $unsafe_out, ">", $unsafe_file) or die "check-shading: $unsafe_file: $!\n";
my $zip = IO::Uncompress::Unzip->new($jar) or die "check-shading: cannot read $jar: $UnzipError\n";
my $status = 1;
while ($status > 0) {
  my $name = $zip->getHeaderInfo()->{Name};
  my ($data, $buf, $read) = ("", "");
  while (($read = $zip->read($buf)) > 0) { $data .= $buf; }
  die "check-shading: cannot read $name: $UnzipError\n" if $read < 0;
  if ($name =~ /\.(class|classdata)$/) {
    my ($magic, $minor, $major) = length($data) >= 8 ? unpack("Nnn", $data) : (0, 0, 0);
    if ($magic != 0xCAFEBABE) { print $major_out "MAGIC $name\n"; }
    elsif ($major != 52) { print $major_out "$major $name\n"; }
    if (index($data, "sun/misc/Unsafe") >= 0 || index($data, "sun.misc.Unsafe") >= 0) {
      print $unsafe_out "$name\n";
    }
  }
  $status = $zip->nextStream();
}
die "check-shading: cannot read $jar: $UnzipError\n" if $status < 0;
close($major_out) or die "check-shading: $major_file: $!\n";
close($unsafe_out) or die "check-shading: $unsafe_file: $!\n";
PERL

check_headers_perl() {
  perl -e '
    local $/ = "\0";
    while (my $f = <STDIN>) {
      chomp $f;
      my $buf = "";
      if (!open(my $fh, "<:raw", $f)) { print "UNREADABLE $f\n"; next }
      else { read($fh, $buf, 8); close $fh }
      if (length($buf) < 8) { print "MAGIC $f\n"; next }
      my ($magic, $minor, $major) = unpack("Nnn", $buf);
      if ($magic != 0xCAFEBABE) { print "MAGIC $f\n"; next }
      print "$major $f\n" if $major != 52;
    }'
}
check_headers_od() {
  local f header
  while IFS= read -r -d '' f; do
    header="$(od -An -tx1 -N8 "$f" | tr -d ' \n')"
    if [[ ${#header} -lt 16 || "${header:0:8}" != "cafebabe" ]]; then
      echo "MAGIC $f"
    elif [[ "${header:12:4}" != "0034" ]]; then
      echo "$((16#${header:12:4})) $f"
    fi
  done
}
if command -v perl >/dev/null 2>&1 && perl -MIO::Uncompress::Unzip -e 1 >/dev/null 2>&1; then
  scan_mode="jar entries"
  perl -e "$scan_jar_pl" "$jar_file" "$work/major-raw.txt" "$work/unsafe-raw.txt"
else
  scan_mode="extracted files"
  (
    cd "$work/x"
    find . -type f \( -name '*.class' -o -name '*.classdata' \) -print0 |
      if command -v perl >/dev/null 2>&1; then check_headers_perl; else check_headers_od; fi
  ) | sed 's# \./# #' >"$work/major-raw.txt"
  (
    cd "$work/x"
    grep -r -l -a -F -e 'sun/misc/Unsafe' -e 'sun.misc.Unsafe' \
      --include='*.class' --include='*.classdata' . || true
  ) | sed 's#^\./##' >"$work/unsafe-raw.txt"
fi

# --- major version (T-BLD-001, ADR-0003 C-22) ------------------------------------------------
# Own classes must have major version 52 exactly; shaded third-party classes
# (inst/io/argos/agent/shaded/) 52 or lower. major-raw.txt lists every class that is not 52.
awk -v shaded="$SHADED_PREFIX" -v older_file="$work/shaded-older.txt" '
  $1 ~ /^[0-9]+$/ && index($2, shaded) == 1 {
    if ($1 + 0 <= 52) { older++; next }
    print $1 " " $2 "  (major " $1 ", shaded classes must be <= 52)"; next
  }
  $1 ~ /^[0-9]+$/ { print $1 " " $2 "  (major " $1 ")"; next }
  { print }
  END { print older + 0 > older_file }' "$work/major-raw.txt" |
  sort >"$work/major.txt"
shaded_older="$(tr -d '\r\n ' <"$work/shaded-older.txt")"
if [[ -s "$work/major.txt" ]]; then
  sed -E 's/^(MAGIC|UNREADABLE) (.*)$/\2  (not a readable class file)/; s/^[0-9]+ //' \
    "$work/major.txt" >"$work/major-report.txt"
  fail major-version "class files must have major version 52 (own) or <= 52 (shaded) (Java 8, F-A31, C-22):" \
    "$work/major-report.txt"
else
  pass major-version "$class_count classes: own major 52, shaded <= 52 (${shaded_older:-0} shaded below 52; read from $scan_mode)"
fi

# --- sun.misc.Unsafe (T-BLD-004) -------------------------------------------------------------
# Byte search over the constant pools (class file scan above).
sed -E 's#^inst/##; s/\.(class|classdata)$//; s#/#.#g' "$work/unsafe-raw.txt" | sort -u >"$work/unsafe.txt"
tr -d '\r' <"$allowlist" | sed -E 's/#.*$//; s/^[[:space:]]+//; s/[[:space:]]+$//' |
  grep -v '^$' | sed -E 's/\.(class|classdata)$//; s#/#.#g' | sort -u >"$work/unsafe-allowed.txt" || true
comm -23 "$work/unsafe.txt" "$work/unsafe-allowed.txt" >"$work/unsafe-violations.txt"
if [[ -s "$work/unsafe-violations.txt" ]]; then
  fail unsafe "classes referencing sun.misc.Unsafe outside $(basename "$allowlist") (F-A35):" \
    "$work/unsafe-violations.txt"
else
  pass unsafe "no sun.misc.Unsafe reference outside the allow-list ($(wc -l <"$work/unsafe-allowed.txt" | tr -d ' ') entries)"
fi

# --- native libraries ------------------------------------------------------------------------
grep -E '\.(so|dll|dylib|jnilib)$|\.so\.[0-9]|(^|/)META-INF/native/' "$work/files.txt" \
  >"$work/native.txt" || true
if [[ -s "$work/native.txt" ]]; then
  fail native "native libraries must not be bundled (F-A35):" "$work/native.txt"
else
  pass native "no native libraries"
fi

# --- size (T-BLD-006) ------------------------------------------------------------------------
size="$(wc -c <"$jar_file" | tr -d ' ')"
if ((size > MAX_BYTES)); then
  echo "$size bytes > $MAX_BYTES bytes (15 MB, 10^6 bytes per MB)" >"$work/size.txt"
  fail size "agent jar too large:" "$work/size.txt"
elif ((size > WARN_BYTES)); then
  echo "WARN [size] $size bytes is above the 12 MB warning threshold ($WARN_BYTES bytes, 10^6 bytes per MB)"
  pass size "$size bytes <= 15 MB ($MAX_BYTES bytes)"
else
  pass size "$size bytes <= 15 MB ($MAX_BYTES bytes)"
fi

# --- duplicates ------------------------------------------------------------------------------
# Entries whose names differ only in case overwrite each other when the jar is extracted on a
# case-insensitive file system (Windows, macOS): never allowed.
{
  sort "$work/class-names.txt" | uniq -d | sed 's/$/  (class present more than once, e.g. root and inst\/)/'
  sort "$work/files.txt" | uniq -d | sed 's/$/  (duplicate jar entry)/'
  sort -u "$work/files.txt" | sort -f | uniq -D -i | sed 's/$/  (case-insensitive duplicate entries)/'
} >"$work/duplicates.txt"
if [[ -s "$work/duplicates.txt" ]]; then
  fail duplicates "names present more than once:" "$work/duplicates.txt"
else
  pass duplicates "no duplicate classes or entries"
fi

# --- summary ---------------------------------------------------------------------------------
if ((${#failures[@]} > 0)); then
  echo "check-shading: FAILED ${#failures[@]} check(s): ${failures[*]} ($jar_arg)"
  exit 1
fi
echo "check-shading: OK ($class_count classes, $size bytes, $jar_arg)"
