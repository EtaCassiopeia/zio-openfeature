# Releasing

Publishing is driven by [sbt-ci-release]. Two workflows call `sbt ci-release`, which publishes a real release when
the commit carries a `v*` tag and a `-SNAPSHOT` otherwise:

- **Releases** — pushing a `v*` tag triggers [`.github/workflows/release.yml`](.github/workflows/release.yml),
  which runs the tests on both Scala versions, publishes to Maven Central, and creates a GitHub Release.
- **Snapshots** — every commit on `main` triggers [`.github/workflows/snapshot.yml`](.github/workflows/snapshot.yml)
  *after* CI passes, publishing to the Sonatype Central snapshots repository under a single moving
  `<next>-SNAPSHOT` coordinate (the latest tag with its final segment bumped, e.g. `1.0.0-RC3-SNAPSHOT` after
  `v1.0.0-RC2`; see the `version` override in [`build.sbt`](build.sbt)). It tracks the next release automatically —
  no manual version bump — and `workflow_dispatch` allows an on-demand snapshot. Consumer instructions live in the
  [README's "Snapshot builds" section](README.md#snapshot-builds).

**The tag is the release.** Nothing is published until `v<x.y.z>` is pushed — so the CHANGELOG and README must
never describe a version as released before its tag exists. This checklist keeps the docs from getting ahead of
Maven Central (the drift that motivated #269).

## Pre-release checklist

Run through this on `main` (or the release branch) **before** tagging:

- [ ] **CHANGELOG** — move the `## [Unreleased]` / `## [x.y.z] — unreleased` entries under a dated
      `## [x.y.z] — YYYY-MM-DD` heading, and drop the "not yet published" note. Confirm every referenced PR/issue
      number is correct.
- [ ] **README compatibility table** — update the row(s) in [`README.md`](README.md#version-compatibility) so the
      "latest published" line matches the version you are about to tag, with the correct OpenFeature Spec and Java
      SDK versions (`openFeatureSdkVersion` in `build.sbt`). Remove any stale "upcoming" row that this release
      fulfils.
- [ ] **Version references** — grep docs for the previous version string and update any hardcoded mentions.
- [ ] **Green `main`** — the full build matrix must be green on the commit you are about to tag.

## Binary compatibility policy

`versionScheme` is `semver-spec`, so sbt's eviction treats every 1.x release as a drop-in replacement for any earlier
one. The build has to make that true:

- **Minor and patch releases are binary compatible on both Scala versions.** `sbt +mimaReportBinaryIssues` checks the
  bytecode against the last release, and `sbt tastyMiMaReportIssues` checks the Scala 3 artifacts' TASTy.
- **A filter in a minor or patch release may only excuse a symbol nothing outside the library can use.** That is a
  symbol under an `internal` package, or one that is `private[...]` in Scala source (public bytecode on Scala 2.13,
  and still checked by TASTy-MiMa). Mark the second kind on the filter's line with `// non-public: private[<pkg>]`.
  The one other exception is an `IncompatibleSignatureProblem` on a widened or narrowed generic signature whose
  erased descriptor is unchanged, marked `// signature-only: <reason>`. Any other filter is a break and needs a major
  release. [`check-mima-filters.sh`](.github/scripts/check-mima-filters.sh) enforces this in CI: it rejects an
  unmarked filter on a public symbol unless the latest `v*` tag already has a higher major than the baseline (a
  `v2.0.0-RC1` tag unlocks them for 2.0).
- **No new fields on the public configuration case classes for the rest of 1.x.** A field changes `apply`, `copy` and
  the constructor (1.1.0 and 1.2.0 both broke `FeatureFlagsConfig` this way, and 1.2.0 also
  `OptimizelyProviderConfig`). A knob that cannot wait for 2.0 needs a shape that keeps those descriptors.
- **Some breaks are invisible to MiMa** and still count: a changed result type inside `ZIO` or `ZLayer` (1.2.0's OFREP
  `make`/`layer`/`scoped`), or a change to an aliased SDK interface (`MultiProviderStrategy.Strategy` with SDK 1.23.0).
  Such a change waits for a major, and its CHANGELOG entry says it breaks.

After each release, move `mimaPreviousArtifacts` to the new version and delete every filter it no longer needs: a
filter kept past the release it excused stops excusing anything and silently pre-authorises a future break.

## First stable release (`v1.0.0`) — additional steps

- [ ] **Enable MiMa** — in [`build.sbt`](build.sbt), set
      `mimaPreviousArtifacts := Set(organization.value %% moduleName.value % "<last-release>")` (baseline against the
      just-published `1.0.0`, or the previous patch on later releases). It is intentionally `Set.empty` while pre-1.0
      because the RC line still makes deliberate breaking changes; only turn it on once the API is frozen. After
      enabling, `sbt +mimaReportBinaryIssues` gates every PR; whitelist an intentional break with a
      `mimaBinaryIssueFilters` rule scoped to the specific symbol.

## Cutting the release

1. Complete the checklist above and merge those doc changes to `main`.
2. Confirm the build matrix is green on the target commit.
3. Tag and push:
   ```sh
   git tag v<x.y.z>
   git push origin v<x.y.z>
   ```
4. Watch [`release.yml`](.github/workflows/release.yml) go green (tests on both Scala versions → `ci-release` →
   GitHub Release).
5. Verify the artifacts appear on Maven Central and that the badge in the README resolves to the new version.

## Post-release

- [ ] Open a fresh `## [Unreleased]` section at the top of the CHANGELOG.
- [ ] Move the MiMa baseline to the new version and delete the filters it no longer needs (see the
      [binary compatibility policy](#binary-compatibility-policy)).
- [ ] Add an "upcoming" README compatibility row if the next version already targets a newer OpenFeature SDK.

[sbt-ci-release]: https://github.com/sbt/sbt-ci-release
