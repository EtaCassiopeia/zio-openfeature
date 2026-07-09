# Releasing

Publishing is driven by [sbt-ci-release]: pushing a `v*` tag triggers
[`.github/workflows/release.yml`](.github/workflows/release.yml), which runs the tests on both Scala versions
and then `sbt ci-release` to publish to Maven Central and create a GitHub Release. A plain `main` push publishes
a `-SNAPSHOT`; only a `v*` **tag** publishes a real version.

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
- [ ] Add an "upcoming" README compatibility row if the next version already targets a newer OpenFeature SDK.

[sbt-ci-release]: https://github.com/sbt/sbt-ci-release
