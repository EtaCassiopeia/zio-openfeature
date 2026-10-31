#!/usr/bin/env bash
# Enforce RELEASING.md's binary compatibility policy on the MiMa and TASTy-MiMa filters in build.sbt.
#
# A minor or patch release may only excuse a break in a symbol nothing outside the library can use. Each filter target
# (`ProblemFilters.exclude[...]("...")` or `ProblemMatcher.make(..., "...")`, one per line) passes when:
#   - it is under an `internal` package (`.internal.` in the target), or
#   - its line carries a `// non-public: private[<pkg>]` marker: the symbol is `private[...]` in Scala source, which
#     Scala 2.13 still emits as public bytecode (the reviewer checks the marker against the source), or
#   - its line carries a `// signature-only: <reason>` marker and the filter is for `IncompatibleSignatureProblem`: only
#     the Java generic signature changed, which neither the JVM linker nor a Scala caller reads.
# Any other filter is a break of the public API, allowed only on the way to a major release: when the highest `v*` tag
# has a higher major than the `mimaPreviousArtifacts` baseline (a `v2.0.0-RC1` tag unlocks breaking filters for 2.0).
#
# Usage:
#   check-mima-filters.sh [<build file>]
#
# `<build file>` defaults to build.sbt. `LATEST_TAG` overrides the tag read from git (the fixture test sets it).
# Exit 0 = every filter allowed, 1 = a filter breaks the policy, 2 = usage error or nothing to check.

set -euo pipefail

BUILD_FILE="${1:-build.sbt}"

die() { echo "error: $*" >&2; exit 2; }

[ -f "$BUILD_FILE" ] || die "no build file at $BUILD_FILE"

baseline=$(sed -n 's/.*mimaPreviousArtifacts[^"]*"\([0-9][0-9.]*\)".*/\1/p' "$BUILD_FILE" | head -1)
[ -n "$baseline" ] || die "no mimaPreviousArtifacts version in $BUILD_FILE"
baseline_major="${baseline%%.*}"

# The highest `v*` tag by version, not `git describe`: CI checks out shallow, where no tag is reachable from HEAD.
latest_tag="${LATEST_TAG:-$(git tag --list 'v*' --sort=-v:refname 2>/dev/null | head -1 || true)}"
latest_major=""
if [ -n "$latest_tag" ]; then
  latest_major="${latest_tag#v}"
  latest_major="${latest_major%%.*}"
fi

major_ahead=false
if [ -n "$latest_major" ] && [ "$latest_major" -gt "$baseline_major" ] 2>/dev/null; then
  major_ahead=true
fi

checked=0
violations=0
lineno=0
while IFS= read -r line || [ -n "$line" ]; do
  lineno=$((lineno + 1))
  code="${line%%//*}"
  case "$code" in
    *ProblemFilters.exclude\[*|*ProblemMatcher.make\(*) ;;
    *) continue ;;
  esac
  target=$(printf '%s' "$code" | sed -n 's/^[^"]*"\([^"]*\)".*/\1/p')
  [ -n "$target" ] || die "$BUILD_FILE:$lineno: a filter without a quoted target on its own line: $line"
  kind=$(printf '%s' "$code" | sed -n -e 's/.*ProblemFilters\.exclude\[\([A-Za-z]*\)\].*/\1/p' \
    -e 's/.*ProblemMatcher\.make(ProblemKind\.\([A-Za-z]*\),.*/\1/p')
  checked=$((checked + 1))

  case "$target" in
    *.internal.*) continue ;;
  esac
  case "$line" in
    *"// non-public: private["*"]"*) continue ;;
    *"// signature-only: "?*)
      if [ "$kind" = "IncompatibleSignatureProblem" ]; then continue; fi
      echo "$BUILD_FILE:$lineno: \`// signature-only:\` is only for IncompatibleSignatureProblem, not ${kind:-any kind}: $target"
      violations=$((violations + 1))
      continue
      ;;
  esac
  if [ "$major_ahead" = true ]; then
    echo "$BUILD_FILE:$lineno: allowed on the way to ${latest_tag}: $target"
    continue
  fi
  echo "$BUILD_FILE:$lineno: filter on a public symbol in a ${baseline_major}.x release: $target"
  violations=$((violations + 1))
done < "$BUILD_FILE"

[ "$checked" -gt 0 ] || die "no MiMa or TASTy-MiMa filter found in $BUILD_FILE; is the parse still right?"

if [ "$violations" -gt 0 ]; then
  echo "$violations filter(s) break the binary compatibility policy (RELEASING.md). Excuse only a symbol under" \
    "\`internal\` or one that is private[...] in Scala source, with the \`// non-public: private[<pkg>]\` marker."
  exit 1
fi
echo "OK: $checked filter(s), all within the binary compatibility policy (baseline $baseline)."
