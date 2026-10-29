#!/usr/bin/env bash
# Detect drift between the vendored OpenFeature provider TCK and upstream.
#
# The vendored copy (conformance-zio-bdd/src/test/resources/features/provider-tck) holds upstream's
# six `gherkin/*.feature` files and `flags/canonical-flags.json`, unchanged. This compares them with
# `specification/assets/provider-tck` in open-feature/spec at a ref: every vendored file must match
# byte for byte, and upstream must ship no feature file the copy lacks.
#
# Usage:
#   check-provider-tck-drift.sh print-pin
#   check-provider-tck-drift.sh upstream [<ref>] [--report <file>]
#
# `<ref>` defaults to the pin in the vendored README. Pass `main` to ask "has upstream moved since
# the pin?".
# Exit 0 = no drift, 1 = drift found (report written), 2 = usage/fetch error. A failed fetch or an
# empty upstream listing is 2, never 0: "could not check" must not read as "no drift".

set -euo pipefail

UPSTREAM_REPO="open-feature/spec"
UPSTREAM_DIR="specification/assets/provider-tck"
VENDORED_DIR="conformance-zio-bdd/src/test/resources/features/provider-tck"
README_PATH="$VENDORED_DIR/README.md"

die() { echo "error: $*" >&2; exit 2; }

pinned_ref() {
  local pin
  # shellcheck disable=SC2016 # the backticks are literal Markdown, not a command substitution
  pin=$(sed -n 's/^Pinned: `\([^`]*\)`.*/\1/p' "$README_PATH" | head -1)
  [ -n "$pin" ] || die "no 'Pinned: \`<ref>\`' line in $README_PATH"
  echo "$pin"
}

# Print the decoded content of one upstream file at a ref; fail (exit 2) if it cannot be fetched.
fetch() {
  local path=$1 ref=$2
  gh api "repos/$UPSTREAM_REPO/contents/$UPSTREAM_DIR/$path?ref=$ref" --jq .content | base64 -d \
    || die "could not fetch $UPSTREAM_DIR/$path at $ref"
}

upstream() {
  local ref=$1 report=$2 drift=0
  local listing
  listing=$(gh api "repos/$UPSTREAM_REPO/contents/$UPSTREAM_DIR/gherkin?ref=$ref" --jq '.[].name') \
    || die "could not list $UPSTREAM_DIR/gherkin at $ref"
  [ -n "$listing" ] || die "empty upstream listing of $UPSTREAM_DIR/gherkin at $ref"

  WORK=$(mktemp -d)
  trap 'rm -rf "$WORK"' EXIT
  [ -n "$report" ] || report="$WORK/report.md"
  : > "$report"

  # Upstream feature files the vendored copy lacks.
  local name
  while IFS= read -r name; do
    case "$name" in *.feature) ;; *) continue ;; esac
    if [ ! -f "$VENDORED_DIR/$name" ]; then
      echo "- \`gherkin/$name\` is new upstream and not vendored" >> "$report"
      drift=1
    fi
  done <<< "$listing"

  # Every vendored feature file must still be upstream and match it.
  local file
  for file in "$VENDORED_DIR"/*.feature; do
    name=$(basename "$file")
    if ! grep -qx "$name" <<< "$listing"; then
      echo "- \`gherkin/$name\` is vendored but no longer upstream" >> "$report"
      drift=1
      continue
    fi
    fetch "gherkin/$name" "$ref" > "$WORK/$name"
    if ! cmp -s "$file" "$WORK/$name"; then
      echo "- \`gherkin/$name\` differs from the vendored copy" >> "$report"
      drift=1
    fi
  done

  # So must the flag set.
  local flags
  flags=$(gh api "repos/$UPSTREAM_REPO/contents/$UPSTREAM_DIR/flags?ref=$ref" --jq '.[].name') \
    || die "could not list $UPSTREAM_DIR/flags at $ref"
  if ! grep -qx "canonical-flags.json" <<< "$flags"; then
    echo "- \`flags/canonical-flags.json\` is vendored but no longer upstream" >> "$report"
    drift=1
  else
    fetch "flags/canonical-flags.json" "$ref" > "$WORK/canonical-flags.json"
    if ! cmp -s "$VENDORED_DIR/canonical-flags.json" "$WORK/canonical-flags.json"; then
      echo "- \`flags/canonical-flags.json\` differs from the vendored copy" >> "$report"
      drift=1
    fi
  fi

  if [ "$drift" -eq 1 ]; then
    echo "drift against $UPSTREAM_REPO@$ref:"
    cat "$report"
    return 1
  fi
  echo "no drift against $UPSTREAM_REPO@$ref"
}

main() {
  [ $# -ge 1 ] || die "usage: $0 print-pin | upstream [<ref>] [--report <file>]"
  local mode=$1; shift
  case "$mode" in
    print-pin) pinned_ref ;;
    upstream)
      local ref="" report=""
      while [ $# -gt 0 ]; do
        case "$1" in
          --report) [ $# -ge 2 ] || die "--report needs a file"; report=$2; shift 2 ;;
          *) [ -z "$ref" ] || die "unexpected argument: $1"; ref=$1; shift ;;
        esac
      done
      [ -n "$ref" ] || ref=$(pinned_ref)
      upstream "$ref" "$report"
      ;;
    *) die "unknown mode: $mode" ;;
  esac
}

main "$@"
