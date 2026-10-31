#!/usr/bin/env bash
# Enforce RELEASING.md's binary compatibility policy on the MiMa and TASTy-MiMa filters in build.sbt.
#
# A minor or patch release may only excuse a break in a symbol nothing outside the library can use. Each filter target
# (`ProblemFilters.exclude[...]("...")` or `ProblemMatcher.make(..., "...")`, its target possibly wrapped onto the next
# line by scalafmt) passes when:
#   - it is under an `internal` package (`.internal.` in the target), or
#   - it is marked `// non-public: private[<pkg>]`: the symbol is `private[...]` in Scala source, which Scala 2.13 still
#     emits as public bytecode (the reviewer checks the marker against the source), or
#   - it is marked `// signature-only: <reason>` and the filter is for `IncompatibleSignatureProblem`: only the Java
#     generic signature changed, which neither the JVM linker nor a Scala caller reads.
# A marker is a comment at the end of a one-line filter, or on a line of its own: then it marks every filter below it
# up to the first line that is neither a filter nor a comment (one marker for a group of filters on the same symbol).
# A filter scalafmt wrapped takes the second form.
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

# classify <line number> <filter text, its lines joined> <marker comment of its group, or empty>
classify() {
  local at="$1" text="$2" marker="$3"
  local code="${text%%//*}"
  local target kind
  target=$(printf '%s' "$code" | sed -n 's/^[^"]*"\([^"]*\)".*/\1/p')
  kind=$(printf '%s' "$code" | sed -n -e 's/.*ProblemFilters\.exclude\[\([A-Za-z]*\)\].*/\1/p' \
    -e 's/.*ProblemMatcher\.make( *ProblemKind\.\([A-Za-z]*\),.*/\1/p')
  checked=$((checked + 1))

  case "$target" in
    *.internal.*) return ;;
  esac
  case "$text $marker" in
    *"// non-public: private["*"]"*) return ;;
    *"// signature-only: "?*)
      if [ "$kind" = "IncompatibleSignatureProblem" ]; then return; fi
      echo "$BUILD_FILE:$at: \`// signature-only:\` is only for IncompatibleSignatureProblem, not ${kind:-any kind}: $target"
      violations=$((violations + 1))
      return
      ;;
  esac
  if [ "$major_ahead" = true ]; then
    echo "$BUILD_FILE:$at: allowed on the way to ${latest_tag}: $target"
    return
  fi
  echo "$BUILD_FILE:$at: filter on a public symbol in a ${baseline_major}.x release: $target"
  violations=$((violations + 1))
}

has_target() { case "${1%%//*}" in *'"'*'"'*) return 0 ;; *) return 1 ;; esac; }

lineno=0
marker=""   # a marker comment on its own line, applying to the filters below it
pending=""  # a filter whose target scalafmt wrapped onto a later line
pending_at=0
while IFS= read -r line || [ -n "$line" ]; do
  lineno=$((lineno + 1))
  if [ -n "$pending" ]; then
    pending="$pending ${line#"${line%%[![:space:]]*}"}"
    if has_target "$pending"; then
      classify "$pending_at" "$pending" "$marker"
      pending=""
    elif [ $((lineno - pending_at)) -gt 3 ]; then
      die "$BUILD_FILE:$pending_at: a filter whose quoted target was not found within 3 lines"
    fi
    continue
  fi
  trimmed="${line#"${line%%[![:space:]]*}"}"
  case "$trimmed" in
    "// non-public: "* | "// signature-only: "*)
      marker="$trimmed"
      continue
      ;;
    //*) continue ;;
  esac
  case "${line%%//*}" in
    *ProblemFilters.exclude\[* | *ProblemMatcher.make\(*)
      if has_target "$line"; then classify "$lineno" "$trimmed" "$marker"; else
        pending="$trimmed"
        pending_at=$lineno
      fi
      ;;
    *) marker="" ;;
  esac
done < "$BUILD_FILE"
[ -z "$pending" ] || die "$BUILD_FILE:$pending_at: a filter whose quoted target was not found"

[ "$checked" -gt 0 ] || die "no MiMa or TASTy-MiMa filter found in $BUILD_FILE; is the parse still right?"

if [ "$violations" -gt 0 ]; then
  echo "$violations filter(s) break the binary compatibility policy (RELEASING.md). Excuse only a symbol under" \
    "\`internal\` or one that is private[...] in Scala source, with the \`// non-public: private[<pkg>]\` marker."
  exit 1
fi
echo "OK: $checked filter(s), all within the binary compatibility policy (baseline $baseline)."
