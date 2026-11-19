#!/usr/bin/env bash
# Fails when the main sources of a published module read the wall clock, sleep or park a thread, start threads, or use
# unseeded randomness, outside the allowlist. Each allowlist entry names one (file, token) pair and says why it is
# deliberate, so a new use is a reviewable diff, and an entry whose file no longer has the token fails as stale.
#
# usage: check-determinism.sh [repo root] [allowlist]
#
# A grep, not a proof: a token reached through an alias (an imported `nanoTime` used bare) is not seen.

set -euo pipefail

ROOT="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
ALLOW="${2:-$ROOT/.github/determinism-allowlist.txt}"

# The published modules. A depth-1 module with main sources that is not listed here fails the check, so a new published
# module cannot be skipped silently.
MODULES="core extras ofrep optimizely schema testkit"

TOKENS='System\.currentTimeMillis|System\.nanoTime|Instant\.now|LocalDateTime\.now|OffsetDateTime\.now|ZonedDateTime\.now|Thread\.sleep|LockSupport\.parkNanos|new (java\.lang\.)?Thread\(|Executors\.|java\.util\.Timer|new Timer\(|scala\.util\.Random|java\.util\.Random|new Random\(|ThreadLocalRandom|UUID\.randomUUID'

TAB="$(printf '\t')"
status=0
cd "$ROOT"

for dir in */src/main; do
  if [ -d "$dir" ]; then
    module="${dir%%/*}"
    case " $MODULES " in
      *" $module "*) ;;
      *)
        echo "module not covered: $module has main sources but is not in check-determinism.sh's module list"
        status=1
        ;;
    esac
  fi
done

# path<TAB>token<TAB>first line, one per (file, token). Lines that start as a comment (//, *, /*) are skipped.
hits=$(
  for m in $MODULES; do
    if [ -d "$m/src/main" ]; then
      find "$m/src/main" -type f \( -name '*.scala' -o -name '*.java' \)
    fi
  done | sort | while IFS= read -r f; do
    { grep -nE "$TOKENS" "$f" || true; } | { grep -vE '^[0-9]+:[[:space:]]*(//|\*|/\*)' || true; } |
      while IFS= read -r line; do
        n="${line%%:*}"
        printf '%s\n' "${line#*:}" | { grep -oE "$TOKENS" || true; } | sed -E 's/^new java\.lang\.Thread\(/new Thread(/' |
          while IFS= read -r tok; do printf '%s\t%s\t%s\n' "$f" "$tok" "$n"; done
      done | sort -t "$TAB" -k2,2 -k3,3n | awk -F '\t' '!seen[$1 FS $2]++'
  done
)

# path<TAB>token, one per entry: `<path> | <token> | <reason>`, # comments and blank lines ignored.
allowed=""
while IFS= read -r raw || [ -n "$raw" ]; do
  trimmed="$(printf '%s' "$raw" | sed -E 's/^[[:space:]]+|[[:space:]]+$//g')"
  case "$trimmed" in '' | \#*) continue ;; esac
  path="$(printf '%s' "$trimmed" | awk -F ' \\| ' '{print $1}')"
  token="$(printf '%s' "$trimmed" | awk -F ' \\| ' '{print $2}')"
  reason="$(printf '%s' "$trimmed" | awk -F ' \\| ' '{ s = $3; for (i = 4; i <= NF; i++) s = s " | " $i; print s }' |
    sed -E 's/^[[:space:]]+|[[:space:]]+$//g')"
  if [ -z "$path" ] || [ -z "$token" ] || [ -z "$reason" ]; then
    echo "allowlist entry needs '<path> | <token> | <reason>': $trimmed"
    status=1
    continue
  fi
  allowed="$allowed$path$TAB$token"$'\n'
done <"$ALLOW"

while IFS="$TAB" read -r path token line; do
  [ -z "$path" ] && continue
  if ! printf '%s' "$allowed" | grep -qxF "$path$TAB$token"; then
    echo "not allowlisted: $path | $token (line $line)"
    status=1
  fi
done <<<"$hits"

while IFS="$TAB" read -r path token; do
  [ -z "$path" ] && continue
  if ! printf '%s\n' "$hits" | cut -f1,2 | grep -qxF "$path$TAB$token"; then
    echo "stale allowlist entry: $path | $token (the file no longer uses it; delete the entry)"
    status=1
  fi
done <<<"$allowed"

if [ "$status" -ne 0 ]; then
  echo "Main sources of published modules may read the wall clock, sleep or start threads only where"
  echo "${ALLOW#"$ROOT"/} says why: add an entry '<path> | <token> | <reason>', or remove a stale one."
fi
exit "$status"
