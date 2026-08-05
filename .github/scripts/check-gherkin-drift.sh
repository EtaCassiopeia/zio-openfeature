#!/usr/bin/env bash
# Detect drift between the vendored OpenFeature gherkin conformance assets and upstream.
#
# Two independent invariants, because they fail for different reasons and want different gates:
#
#   upstream  the vendored files match `specification/assets/gherkin/` in open-feature/spec at a
#             given ref. Drift here is upstream moving, not a PR author's doing, so the workflow
#             that runs this is scheduled, never a PR gate.
#   mirror    the two in-repo copies (conformance/ and conformance-zio-bdd/) are byte-identical to
#             each other. Breaking that IS a PR author's doing, so CI runs this one per PR.
#
# Usage:
#   check-gherkin-drift.sh mirror
#   check-gherkin-drift.sh upstream [<ref>] [--report <file>]
#
# `<ref>` defaults to the pin read from the conformance README (see `pinned_ref`). Pass `main` to
# ask "has upstream moved since the pin?".
# Exit 0 = no drift, 1 = drift found (report written), 2 = usage/fetch error.
#
# Exit 2 is deliberately distinct from 1: a GitHub API failure or an empty upstream listing means
# the check could not run, and reporting "no drift" for it would be the silent-fallback this whole
# job exists to prevent. The workflow treats 2 as a failure without filing a drift issue.

set -euo pipefail

UPSTREAM_REPO="open-feature/spec"
UPSTREAM_DIR="specification/assets/gherkin"

CUCUMBER_DIR="conformance/src/test/resources/zio/openfeature/conformance"
ZIOBDD_DIR="conformance-zio-bdd/src/test/resources/features/openfeature"

# Authoritative record of the vendored spec ref; parsed by `pinned_ref`.
README_PATH="$CUCUMBER_DIR/README.md"

# The vendoring contract is the gherkin *suites* — `*.feature` and nothing else. Upstream's
# gherkin directory also ships `README.md` (documentation of their assets; ours in the same
# directory documents our vendoring and is a local artifact that merely shares the name) and
# `test-flags.json` (fixture data our step definitions supply from `Fixtures.scala` instead).
# Both predate the current pin, so neither is new drift. Restricting the comparison to
# `*.feature` states that contract directly, and still catches a brand-new upstream suite —
# which is the case the file-set comparison exists for.
FEATURE_GLOB="*.feature"

# Feature files upstream ships that we deliberately do NOT vendor. `evaluation.feature` is
# entirely @deprecated and both runners exclude that tag, so vendoring it would add only dead
# scenarios. Without this list the upstream check would report it missing on every run.
EXCLUDED_FILES=("evaluation.feature")

die() {
  echo "error: $*" >&2
  exit 2
}

is_excluded() {
  local candidate="$1" excluded
  for excluded in "${EXCLUDED_FILES[@]}"; do
    [[ "$candidate" == "$excluded" ]] && return 0
  done
  return 1
}

# Single source of truth for the pin: the conformance README's "Pinned tag" line. The script parses
# it rather than keeping a second copy — two copies of the pin is how #336 happened, in a tool whose
# whole purpose is ending silent pin rot. Called lazily (only by `upstream` with no explicit ref) so
# `mirror` mode never depends on README formatting.
#
# Ambiguity dies as exit 2 ("could not check"), never as a guess: answering "does the pin still
# hold?" against a pin we failed to read is the silent fallback this script exists to prevent.
pinned_ref() {
  local claims matches
  # Count lines that *claim* to be the pin — however malformed — before parsing one. Counting only
  # well-formed matches would let a broken current pin sitting above a stale well-formed one resolve
  # silently to the stale ref: a wrong pin reported as fine, which is the failure this file exists
  # to prevent.
  #
  # The `|| die` is load-bearing, not belt-and-braces: BSD sed exits 1 on an unreadable file while
  # GNU sed exits 2, so leaving it to `set -e` would abort with 1 on macOS — and the scheduled
  # workflow reads 1 as "drift found" and files a bogus issue. This normalises both to 2.
  claims="$(sed -n '/^- Pinned tag:/p' "$README_PATH")" \
    || die "cannot read $README_PATH"
  [[ -n "$claims" ]] || die "no '- Pinned tag: **vX.Y.Z**' line found in $README_PATH"
  [[ "$(printf '%s\n' "$claims" | wc -l | tr -d ' ')" == "1" ]] \
    || die "multiple 'Pinned tag' lines in $README_PATH — cannot determine the pin"

  matches="$(printf '%s\n' "$claims" | sed -n 's/^- Pinned tag: \*\*\(v[0-9][0-9.]*\)\*\*.*/\1/p')" \
    || die "cannot parse the 'Pinned tag' line in $README_PATH"
  [[ -n "$matches" ]] \
    || die "unparseable '- Pinned tag' line in $README_PATH — expected '- Pinned tag: **vX.Y.Z**'"
  printf '%s\n' "$matches"
}

# --- mirror -----------------------------------------------------------------------------------
# Compares the two vendored copies against each other. The zio-bdd module holds exactly the
# OpenFeature files; its Optimizely-specific features live in sibling directories and are not
# part of this invariant.
check_mirror() {
  local drift=0 compared=0 name counterpart

  [[ -d "$CUCUMBER_DIR" ]] || die "missing directory: $CUCUMBER_DIR"
  [[ -d "$ZIOBDD_DIR" ]] || die "missing directory: $ZIOBDD_DIR"

  for path in "$CUCUMBER_DIR"/*.feature; do
    [[ -e "$path" ]] || continue
    compared=$((compared + 1))
    name="$(basename "$path")"
    counterpart="$ZIOBDD_DIR/$name"
    if [[ ! -f "$counterpart" ]]; then
      echo "MIRROR DRIFT: $name exists in $CUCUMBER_DIR but not in $ZIOBDD_DIR" >&2
      drift=1
    elif ! diff -q "$path" "$counterpart" >/dev/null; then
      echo "MIRROR DRIFT: $name differs between the two vendored copies" >&2
      diff -u "$path" "$counterpart" >&2 || true
      drift=1
    fi
  done

  for path in "$ZIOBDD_DIR"/*.feature; do
    [[ -e "$path" ]] || continue
    name="$(basename "$path")"
    if [[ ! -f "$CUCUMBER_DIR/$name" ]]; then
      echo "MIRROR DRIFT: $name exists in $ZIOBDD_DIR but not in $CUCUMBER_DIR" >&2
      drift=1
    fi
  done

  # A checker that passes because it found nothing to check is indistinguishable from a checker
  # that verified everything — the same vacuous-green shape this job exists to prevent.
  [[ $compared -gt 0 ]] || die "no .feature files in $CUCUMBER_DIR; refusing to report 'identical' vacuously"

  if [[ $drift -eq 0 ]]; then
    echo "mirror: $compared vendored copies are identical"
  fi
  return $drift
}

# --- upstream ---------------------------------------------------------------------------------
check_upstream() {
  local ref="$1" report="${2:-}"
  local tmp drift=0 name
  tmp="$(mktemp -d)" || die "mktemp failed"
  # EXIT, not RETURN: `die` ends in `exit`, and a RETURN trap does not fire on exit — so every
  # `die` path below would leak the temp dir. This is the only trap the script installs, so there
  # is nothing to clobber.
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT

  command -v gh >/dev/null || die "gh CLI is required"
  [[ -d "$CUCUMBER_DIR" ]] || die "missing directory: $CUCUMBER_DIR"

  # Compare the file SET as well as contents: a brand-new upstream suite is drift we must notice,
  # and a contents-only loop would never see it.
  if ! gh api "repos/$UPSTREAM_REPO/contents/$UPSTREAM_DIR?ref=$ref" \
    --jq '.[] | select(.type == "file") | .name' >"$tmp/all-names" 2>"$tmp/gh-err"; then
    die "failed to list $UPSTREAM_DIR at $ref: $(cat "$tmp/gh-err")"
  fi
  [[ -s "$tmp/all-names" ]] || die "upstream listing at $ref was empty"
  grep -E '\.feature$' "$tmp/all-names" >"$tmp/upstream-names" || true
  [[ -s "$tmp/upstream-names" ]] || die "no .feature files found upstream at $ref"

  # Guarded so an infrastructure failure (disk full, permissions) dies as exit 2 "could not check"
  # rather than falling out of `set -e` as exit 1, which the workflow reads as "drift found".
  : >"$tmp/report" || die "cannot write report scratch file"

  while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    if is_excluded "$name"; then
      echo "skip (excluded): $name"
      continue
    fi
    # Raw media type rather than the base64 `.content` field: `base64 --decode` is GNU-only
    # spelling (BSD/macOS wants -D), and this check must run identically on a dev machine and on
    # the ubuntu runner.
    if ! gh api "repos/$UPSTREAM_REPO/contents/$UPSTREAM_DIR/$name?ref=$ref" \
      -H "Accept: application/vnd.github.raw" >"$tmp/$name" 2>"$tmp/gh-err"; then
      die "failed to download $name at $ref: $(cat "$tmp/gh-err")"
    fi
    if [[ ! -f "$CUCUMBER_DIR/$name" ]]; then
      {
        echo "### $name — present upstream, not vendored"
        echo "A new upstream suite, or one dropped locally. Vendor it or add it to EXCLUDED_FILES."
      } >>"$tmp/report"
      drift=1
    elif ! diff -q "$tmp/$name" "$CUCUMBER_DIR/$name" >/dev/null; then
      {
        echo "### $name"
        echo '```diff'
        diff -u "$CUCUMBER_DIR/$name" "$tmp/$name" \
          --label "vendored/$name" --label "upstream@$ref/$name" || true
        echo '```'
      } >>"$tmp/report"
      drift=1
    else
      echo "ok: $name"
    fi
  done <"$tmp/upstream-names"

  # A vendored file that upstream no longer ships is drift too — it is dead weight the suites
  # still execute.
  for path in "$CUCUMBER_DIR"/*.feature; do
    [[ -e "$path" ]] || continue
    name="$(basename "$path")"
    if ! grep -qxF "$name" "$tmp/upstream-names"; then
      {
        echo "### $name — vendored, gone upstream"
        echo "Upstream no longer ships this file at $ref; the suites still run it."
      } >>"$tmp/report"
      drift=1
    fi
  done

  if [[ $drift -ne 0 ]]; then
    echo "UPSTREAM DRIFT against $UPSTREAM_REPO@$ref" >&2
    cat "$tmp/report" >&2
    if [[ -n "$report" ]]; then
      cp "$tmp/report" "$report" || die "cannot write report to $report"
    fi
  else
    echo "upstream: vendored assets match $UPSTREAM_REPO@$ref"
  fi
  return $drift
}

main() {
  local mode="${1:-}"
  case "$mode" in
    mirror)
      check_mirror
      ;;
    upstream)
      shift
      local ref="" report=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --report)
            [[ $# -ge 2 ]] || die "--report needs a file argument"
            report="$2"
            shift 2
            ;;
          *)
            ref="$1"
            shift
            ;;
        esac
      done
      # Resolved after parsing so an explicit ref never pays the README read — and so a malformed
      # README cannot fail a run that did not need the pin at all.
      [[ -n "$ref" ]] || ref="$(pinned_ref)"
      check_upstream "$ref" "$report"
      ;;
    *)
      die "usage: $(basename "$0") mirror | upstream [<ref>] [--report <file>]"
      ;;
  esac
}

main "$@"
