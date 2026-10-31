#!/usr/bin/env bash
# Fixture test for check-mima-filters.sh: each case is a small build file and the exit code the check must give it.
# shellcheck disable=SC2016 # the fixtures are literal build-file text: `$Open$` is a JVM class name, not an expansion

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/check-mima-filters.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

passed=0
failed=0

# expect <name> <exit code> <latest tag> <filter lines...>
expect() {
  local name="$1" want="$2" tag="$3"
  shift 3
  local file="$TMP/$name.sbt"
  {
    echo 'mimaPreviousArtifacts := Set(organization.value %% moduleName.value % "1.2.1"),'
    printf '%s\n' "$@"
  } > "$file"
  local got=0
  LATEST_TAG="$tag" bash "$CHECK" "$file" > "$TMP/$name.out" 2>&1 || got=$?
  if [ "$got" = "$want" ]; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    echo "FAIL $name: exit $got, expected $want"
    sed 's/^/  /' "$TMP/$name.out"
  fi
}

expect internal-filter 0 v1.2.1 \
  'ProblemFilters.exclude[Problem]("zio.openfeature.internal.Foo*"),'
expect tasty-internal-glob 0 v1.2.1 \
  'ProblemMatcher.make("zio.openfeature.internal.*"),'
expect non-public-marker 0 v1.2.1 \
  'ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.extras.CircuitState#Open.copy"), // non-public: private[extras]'
expect tasty-non-public-marker 0 v1.2.1 \
  'ProblemMatcher.make(ProblemKind.MissingTermMember, "zio.openfeature.FeatureFlagsLive.<init>") // non-public: private[openfeature]'
expect signature-only-on-signature-problem 0 v1.2.1 \
  'ProblemFilters.exclude[IncompatibleSignatureProblem]("zio.openfeature.FeatureFlags.fromProvider"), // signature-only: the erased descriptor is unchanged'
expect public-filter 1 v1.2.1 \
  'ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.FeatureFlags.build"),'
expect public-tasty-filter 1 v1.2.1 \
  'ProblemMatcher.make(ProblemKind.MissingTermMember, "zio.openfeature.FeatureFlags.build")'
expect marker-without-package 1 v1.2.1 \
  'ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.FeatureFlags.build"), // non-public'
expect signature-only-on-another-kind 1 v1.2.1 \
  'ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.FeatureFlags.build"), // signature-only: no'
expect commented-out-filter-is-ignored 0 v1.2.1 \
  'ProblemFilters.exclude[Problem]("zio.openfeature.internal.Foo*"),' \
  '// ProblemFilters.exclude[Problem]("zio.openfeature.FeatureFlags.build"),'
expect public-filter-before-a-major 0 v2.0.0-RC1 \
  'ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.FeatureFlags.build"),'
expect public-filter-after-a-patch-tag 1 v1.3.0 \
  'ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.FeatureFlags.build"),'
expect one-bad-among-good 1 v1.2.1 \
  'ProblemFilters.exclude[Problem]("zio.openfeature.internal.Foo*"),' \
  'ProblemFilters.exclude[MissingClassProblem]("zio.openfeature.FlagType"),'
expect no-filters-is-an-error 2 v1.2.1 \
  'name := "x"'
expect marker-line-covers-its-group 0 v1.2.1 \
  '    // non-public: private[extras]' \
  '    ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.extras.CircuitState#Open.copy"),' \
  '    ProblemFilters.exclude[MissingTypesProblem]("zio.openfeature.extras.CircuitState$Open$")'
expect marker-line-ends-at-the-group 1 v1.2.1 \
  '    // non-public: private[extras]' \
  '    ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.extras.CircuitState#Open.copy")' \
  '  ),' \
  '    ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.FeatureFlags.build")'
expect wrapped-target-is-read 1 v1.2.1 \
  '      ProblemFilters.exclude[DirectMissingMethodProblem](' \
  '        "zio.openfeature.FeatureFlags.build"' \
  '      ),'
# A wrapped filter takes its marker on a line of its own: the closing line comes after the target is read.
expect wrapped-target-trailing-marker-fails-closed 1 v1.2.1 \
  '        ProblemMatcher.make(' \
  '          ProblemKind.MissingTermMember,' \
  '          "zio.openfeature.extras.CircuitBreaker.trip"' \
  '        ) // non-public: private[extras]'
expect wrapped-target-with-marker-line 0 v1.2.1 \
  '        // non-public: private[extras]' \
  '        ProblemMatcher.make(' \
  '          ProblemKind.MissingTermMember,' \
  '          "zio.openfeature.extras.CircuitBreaker.trip"' \
  '        )'
expect wrapped-target-never-found 2 v1.2.1 \
  'ProblemFilters.exclude[Problem](' \
  ')' \
  'x' \
  'y' \
  'z'

echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
