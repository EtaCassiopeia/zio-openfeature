#!/usr/bin/env bash
# Fixture test for check-determinism.sh: each case is a small source tree, an allowlist, and the exit code the check
# must give them.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/check-determinism.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

passed=0
failed=0

CLEAN='package zio.openfeature

object Clean {
  val answer: Int = 42
}'

# tree <name>: a fresh tree with one clean core source; prints its root.
tree() {
  local root="$TMP/$1"
  mkdir -p "$root/core/src/main/scala"
  printf '%s\n' "$CLEAN" >"$root/core/src/main/scala/Clean.scala"
  : >"$root/allow.txt"
  printf '%s' "$root"
}

# expect <name> <exit code> <root> [text the output must contain]
expect() {
  local name="$1" want="$2" root="$3" needle="${4:-}" got=0 out
  out="$(bash "$CHECK" "$root" "$root/allow.txt" 2>&1)" || got=$?
  if [ "$got" -ne "$want" ]; then
    echo "FAIL: $name: exit $got, want $want"
    printf '%s\n' "$out" | sed 's/^/    /'
    failed=$((failed + 1))
  elif [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then
    echo "FAIL: $name: output does not contain '$needle'"
    printf '%s\n' "$out" | sed 's/^/    /'
    failed=$((failed + 1))
  else
    passed=$((passed + 1))
  fi
}

r="$(tree clean)"
expect "a clean tree passes" 0 "$r"

r="$(tree sleep)"
printf 'object Slow {\n  def f(): Unit = Thread.sleep(10)\n}\n' >"$r/core/src/main/scala/Slow.scala"
expect "a new Thread.sleep in core fails and names the file and token" 1 "$r" \
  "not allowlisted: core/src/main/scala/Slow.scala | Thread.sleep (line 2)"

r="$(tree comments)"
printf 'object Doc {\n  // Thread.sleep would block here\n  /** System.nanoTime is wall-clock\n    * Executors.newFixedThreadPool too\n    */\n  val x = 1\n}\n' \
  >"$r/core/src/main/scala/Doc.scala"
expect "a token only in a comment or ScalaDoc line passes" 0 "$r"

r="$(tree allowed)"
printf 'object Slow {\n  def f(): Unit = Thread.sleep(10)\n}\n' >"$r/core/src/main/scala/Slow.scala"
printf '# a comment\n\ncore/src/main/scala/Slow.scala | Thread.sleep | the delay is the point\n' >"$r/allow.txt"
expect "an allowlisted (file, token) passes" 0 "$r"

r="$(tree other-token)"
printf 'object Slow {\n  def f(): Unit = Thread.sleep(10)\n  def t: Long = System.nanoTime()\n}\n' >"$r/core/src/main/scala/Slow.scala"
printf 'core/src/main/scala/Slow.scala | Thread.sleep | the delay is the point\n' >"$r/allow.txt"
expect "another token in an allowlisted file fails" 1 "$r" "not allowlisted: core/src/main/scala/Slow.scala | System.nanoTime"

r="$(tree stale)"
printf 'core/src/main/scala/Clean.scala | Thread.sleep | it used to sleep\n' >"$r/allow.txt"
expect "a stale entry fails" 1 "$r" "stale allowlist entry: core/src/main/scala/Clean.scala | Thread.sleep"

r="$(tree no-reason)"
printf 'object Slow {\n  def f(): Unit = Thread.sleep(10)\n}\n' >"$r/core/src/main/scala/Slow.scala"
printf 'core/src/main/scala/Slow.scala | Thread.sleep | \n' >"$r/allow.txt"
expect "an entry without a reason fails" 1 "$r" "allowlist entry needs"

r="$(tree java-lang-thread)"
printf 'object T {\n  val t = new java.lang.Thread(() => ())\n}\n' >"$r/core/src/main/scala/T.scala"
expect "new java.lang.Thread( is matched as new Thread(" 1 "$r" "not allowlisted: core/src/main/scala/T.scala | new Thread("

r="$(tree java-source)"
mkdir -p "$r/optimizely/src/main/java"
printf 'class J {\n  long t() { return System.currentTimeMillis(); }\n}\n' >"$r/optimizely/src/main/java/J.java"
expect "a Java source of a published module is checked" 1 "$r" "System.currentTimeMillis"

r="$(tree unlisted-module)"
mkdir -p "$r/foo/src/main/scala"
printf '%s\n' "$CLEAN" >"$r/foo/src/main/scala/Clean.scala"
expect "a module with main sources that the check does not list fails" 1 "$r" "module not covered: foo"

r="$(tree zio-random)"
printf 'import zio._\n\nobject R {\n  val n = Random.nextLong\n}\n' >"$r/core/src/main/scala/R.scala"
expect "zio.Random passes" 0 "$r"

r="$(tree scala-random)"
printf 'object R {\n  val n = scala.util.Random.nextLong()\n}\n' >"$r/core/src/main/scala/R.scala"
expect "scala.util.Random fails" 1 "$r" "scala.util.Random"

echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
