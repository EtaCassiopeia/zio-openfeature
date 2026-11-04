# Releasing

Publishing is driven by [sbt-ci-release]. Two workflows call `sbt ci-release`, which publishes a real release when
the commit carries a `v*` tag and a `-SNAPSHOT` otherwise:

- **Releases** — pushing a `v*` tag triggers [`.github/workflows/release.yml`](.github/workflows/release.yml),
  which runs the tests on both Scala versions, publishes to Maven Central, and creates a GitHub Release.
- **Snapshots** — every commit on `main` triggers [`.github/workflows/snapshot.yml`](.github/workflows/snapshot.yml)
  *after* CI passes, publishing to the Sonatype Central snapshots repository under a single moving
  `<next>-SNAPSHOT` coordinate (the latest `v*` tag with its final number bumped, e.g. `1.2.2-SNAPSHOT` after
  `v1.2.1`; see the `version` override in [`build.sbt`](build.sbt)). It tracks the next release automatically —
  no manual version bump — and `workflow_dispatch` allows an on-demand snapshot. Consumer instructions live in
  [`docs/getting-started.md`](docs/getting-started.md) ("Trying unreleased changes").

> **Pushing a `v*` tag publishes.** `release.yml` runs on any tag that starts with `v`, a pre-release such as
> `v2.0.0-RC1` or a mistyped one included, and uploads that exact version to Maven Central, where it cannot be taken
> back. Create a `v*` tag only for a release you mean to publish, and never use the prefix for anything else.

**The tag is the release.** Nothing is published until `v<x.y.z>` is pushed — so the CHANGELOG and the docs must
never describe a version as released before its tag exists. This checklist keeps the docs from getting ahead of
Maven Central (the drift that motivated #269).

## Snapshots while `main` is the 2.0 line

`main` is the 2.0 line, but the latest `v*` tag is still `v1.2.1`, so every commit on `main` publishes
`1.2.2-SNAPSHOT`, and that snapshot has the 2.0 API. Someone who resolves `1.2.2-SNAPSHOT` expecting a 1.x patch gets
2.0. The CHANGELOG says snapshots from `main` can break between builds, and `docs/getting-started.md` shows the
`1.2.2-SNAPSHOT` example.

Pushing a pre-release tag such as `v2.0.0-RC1` would change the snapshot coordinate to `2.0.0-RC2-SNAPSHOT`, and would
also publish `2.0.0-RC1` to Maven Central (see the warning above). Whether to cut a pre-release tag before `v2.0.0` is
the owner's decision; nothing here assumes either choice. With no baseline, the filter check rejects every filter
whichever tag is latest, so a pre-release tag does not change it today.

## Pre-release checklist

Run through this on `main` (or the release branch) **before** tagging:

- [ ] **CHANGELOG** — move the `## [Unreleased]` / `## [x.y.z] — unreleased` entries under a dated
      `## [x.y.z] — YYYY-MM-DD` heading. Add the link at the bottom,
      `[x.y.z]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v<previous>...v<x.y.z>`, and point
      `[Unreleased]` at `compare/v<x.y.z>...HEAD`. Confirm every referenced PR/issue number is correct.
- [ ] **Spec compliance table** — in [`docs/spec-compliance.md`](docs/spec-compliance.md), give the row for the version
      you are about to tag its "(latest published: x.y.z)" label in place of "(unreleased)", and remove that label from
      the previous latest row. Check the OpenFeature Spec and Java SDK versions against `openFeatureSdkVersion` in
      `build.sbt`. An unreleased version has a row marked "(unreleased)" until this step.
- [ ] **Version references** — grep `docs/`, `README.md` and `mkdocs.yml` for the previous version string and update any
      hardcoded mention. The snapshot example in `docs/getting-started.md` names the version after the latest tag
      (`1.2.2-SNAPSHOT` after `v1.2.1`); after this release it is the next patch of the new tag.
- [ ] **Green `main`** — the full build matrix must be green on the commit you are about to tag.

## Binary compatibility policy

`versionScheme` is `semver-spec`, so sbt's eviction treats every 1.x release as a drop-in replacement for any earlier
one. The build has to make that true.

**While 2.0 is in development** `main` has no baseline and the rules below are suspended: no release comes before 2.0.0,
so nothing on `main` has to link against 1.x. `mimaPreviousArtifacts` is `Set.empty` with a `// no baseline:` marker, and
the check rejects any filter, since none can excuse anything. After 2.0.0 is published, set the baseline to it and remove
the marker (see [2.0.0: additional steps](#200-additional-steps)); the rules below then apply to 2.x:

- **Minor and patch releases are binary compatible on both Scala versions.** `sbt +mimaReportBinaryIssues` checks the
  bytecode against the last release, and `sbt tastyMiMaReportIssues` checks the Scala 3 artifacts' TASTy.
- **A filter in a minor or patch release may only excuse a symbol nothing outside the library can use.** That is a
  symbol under an `internal` package, or one that is `private[...]` in Scala source (public bytecode on Scala 2.13,
  and still checked by TASTy-MiMa). Mark the second kind on the filter's line with `// non-public: private[<pkg>]`.
  The one other exception is an `IncompatibleSignatureProblem` on a widened or narrowed generic signature whose
  erased descriptor is unchanged, marked `// signature-only: <reason>`. Any other filter is a break and needs a major
  release. [`check-mima-filters.sh`](.github/scripts/check-mima-filters.sh) enforces this in CI: it rejects an
  unmarked filter on a public symbol unless the latest `v*` tag already has a higher major than the baseline (a
  `v2.0.0-RC1` tag unlocks them for 2.0 once a 1.x baseline exists).
- **A new configuration knob is one `with*` method and a private field.** The configuration classes are final classes
  with private constructors, so adding a field changes no public descriptor. (Until 2.0 they were case classes, and a
  new field broke `apply`, `copy` and the constructor: 1.1.0 and 1.2.0 both did this to `FeatureFlagsConfig`.)
- **Some breaks are invisible to MiMa** and still count: a changed result type inside `ZIO` or `ZLayer` (1.2.0's OFREP
  `make`/`layer`/`scoped`), or a change to an aliased SDK interface (`MultiProviderStrategy.Strategy` with SDK 1.23.0).
  Such a change waits for a major, and its CHANGELOG entry says it breaks.

After each release, move `mimaPreviousArtifacts` to the new version and delete every filter it no longer needs: a
filter kept past the release it excused stops excusing anything and silently pre-authorises a future break.

## 2.0.0: additional steps

2.0.0 is the first release with no baseline to check against, and the first for `schema`. The order matters: the
baseline names an artifact that exists only after the tag has been published, so it cannot be set before it.

Before tagging, in addition to the checklist above:

- [ ] **Upgrade guide** — review [`docs/upgrading.md`](docs/upgrading.md) against the CHANGELOG's Removed and Changed
      entries for 2.0.0: every break has a before and after, and the code in it matches the API at the commit you tag.
- [ ] **Remove the "2.0 is unreleased" notes** from `docs/index.md`, `docs/upgrading.md` and `docs/getting-started.md`
      (the note at the top of `docs/index.md`, the first paragraph of `docs/upgrading.md`, and the paragraph "Until a
      2.0 tag exists" in `docs/getting-started.md`; keep that page's "Trying unreleased changes" note itself), so the site no longer says 2.0 is not out. This
      goes in the same change as the spec compliance row above; the site deploys from `main` on every push, so tag right
      after it merges.
- [ ] **CHANGELOG** — the entry that says `main` is the 2.0 line, has no baseline and publishes breaking snapshots
      describes the development period. Reword or drop it for the release. Keep the "Upgrading from 1.x" pointer at the
      top of the 2.0.0 entry.
- [ ] **Public surface** — once the baseline is set, a public symbol cannot be removed without a major. Settle what 2.0
      should expose before tagging.

Tag and publish as under [Cutting the release](#cutting-the-release). No baseline is needed for that commit: with none
set, the compatibility jobs compare nothing and the filter check passes when the build has no filters.

After the artifacts of every published module are on Maven Central (step 5 of the release), in a follow-up commit:

- [ ] **Set the baseline** — in `commonSettings` in [`build.sbt`](build.sbt), replace
      `mimaPreviousArtifacts := Set.empty, // no baseline: 2.0 in development` with
      `mimaPreviousArtifacts := Set(organization.value %% moduleName.value % "2.0.0")`. The filter script reads the
      first `mimaPreviousArtifacts` line that has a quoted `x.y.z`, and it exits with an error when the build has
      neither that nor the `// no baseline:` marker, so the marker goes in the same change. The TASTy baseline is
      derived from it for the Scala 3 artifacts. Setting it before the tag would name an artifact that does not exist
      yet, which would not resolve. Every module published in 2.0.0, `schema` included, is covered, since 2.0.0 is the
      first release of all of them.
- [ ] **Update the comments** in `build.sbt` that say there is no baseline (the MiMa note above `mimaFailOnNoPrevious`
      and the one in `commonSettings`), and delete the "While 2.0 is in development" paragraph in the
      [binary compatibility policy](#binary-compatibility-policy). Also delete the "Snapshots while `main` is the 2.0
      line" section, update the snapshot example at the top of this file, and remove the no-baseline wording from
      [`docs/development.md`](docs/development.md) and the `mima` job's comment in `ci.yml`.
- [ ] **Teach the filter check about a baseline with no filters** — with a baseline set and no filters,
      `.github/scripts/check-mima-filters.sh` exits 2 ("no MiMa or TASTy-MiMa filter found"), and the `mima` job fails on
      `main`. Change it to pass that case, and update the `no-filters-is-an-error` fixture in
      `.github/scripts/test-check-mima-filters.sh`. Keep a guard that the baseline line parses, so a broken parse still
      fails.
- [ ] **Check it works** — run `sbt +mimaReportBinaryIssues tastyMiMaReportIssues` and
      `.github/scripts/check-mima-filters.sh` once. Confirm `mimaFailOnNoPrevious := false` is still wanted: it makes a
      module with no published baseline skip silently instead of failing.
- [ ] **Snapshots** — they are now `2.0.1-SNAPSHOT`. Update the example in `docs/getting-started.md`.

## Tags

Only `v*` tags are releases: `release.yml` and the `version` override in `build.sbt` both match `v*` and nothing else.
The repository carries two other kinds of tag:

- **`tooling/*`** — prereleases that mirror a tool the CI downloads, such as the TLC 1.8 jar
  (`tooling/tla2tools-…`) and TLAPS (`tooling/tlapm-…`). The `Formal model` workflow names them in
  `TRACE_TLA_TOOLS_RELEASE` and `TLAPM_RELEASE` and downloads their assets from this repository's releases. Put such a
  tag on a parentless anchor commit, never on a commit of `main`, because the build derives its version from tags.
  Never overwrite a published asset; to move to a newer build, publish a new tag and change the pins in
  [`formal.yml`](.github/workflows/formal.yml). Both tags the workflow names exist on `origin`, each with its release;
  check that before deleting or renaming one.
- **`rescue/*`** — markers on old commits, not releases, and nothing publishes from them. At the time of writing they exist
  in local clones only, not on `origin`.

Before tagging, list the existing release tags with `git tag --list 'v*'` and check the new one is the version you mean.

## Cutting the release

1. Complete the checklist above and merge those doc changes to `main`.
2. Confirm the build matrix is green on the target commit.
3. Tag and push. The push publishes (see the warning at the top):
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
      [binary compatibility policy](#binary-compatibility-policy)). For 2.0.0 this is the follow-up commit under
      [2.0.0: additional steps](#200-additional-steps).
- [ ] If the next version already targets a newer OpenFeature spec or Java SDK, add a row marked "(unreleased)" to
      [`docs/spec-compliance.md`](docs/spec-compliance.md).

[sbt-ci-release]: https://github.com/sbt/sbt-ci-release
