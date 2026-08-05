# Vendored OpenFeature gherkin conformance assets

The `.feature` files in this directory are **verbatim copies** of the OpenFeature specification's
conformance suite, executed against the ZIO `FeatureFlags` API by Cucumber (see `RunConformance`).

- Source: https://github.com/open-feature/spec — `specification/assets/gherkin/`
- Pinned tag: **v0.9.0** (commit `d5b0a734d8cb9b42bf89be2a97c627f58208e811`)

Keep these byte-identical to upstream so a re-sync is a clean diff. To update: re-copy the files
from the pinned path at a newer tag and run `sbt conformance/test`; new or changed scenarios
surface as failing/undefined steps rather than silently going unrun.

The same four files are vendored a second time under
`conformance-zio-bdd/src/test/resources/features/openfeature/`, where the zio-bdd runner executes
them. The two copies must stay byte-identical to each other.

## Drift detection

Both invariants are checked by `.github/scripts/check-gherkin-drift.sh`, split because they have
different owners:

| Mode | Invariant | Runs |
|------|-----------|------|
| `upstream` | vendored files match `open-feature/spec` at a ref | `.github/workflows/gherkin-drift.yml` — weekly + on dispatch |
| `mirror` | the two in-repo copies are identical | the `format` job in `ci.yml` — every PR |

Upstream drift is not a PR author's doing, so it is scheduled and files a deduped issue rather than
blocking PRs. Breaking the in-repo mirror **is** a PR author's doing, so that one blocks.

Run either locally:

```sh
.github/scripts/check-gherkin-drift.sh mirror
.github/scripts/check-gherkin-drift.sh upstream            # against the pinned tag
.github/scripts/check-gherkin-drift.sh upstream main       # has upstream moved since the pin?
```

## What is and isn't vendored

The vendoring contract is the gherkin **suites** — `*.feature`, nothing else. Upstream's directory
also ships `README.md` (documentation of their assets; this file happens to share its name and
location but is ours) and `test-flags.json` (fixture data — our step definitions build the
equivalent fixtures in `Fixtures.scala`). Neither is vendored, and the drift checker compares only
`*.feature`, so neither is reported. A brand-new upstream *suite* is still caught.

## Excluded tags

`RunConformance` filters out exactly `@deprecated`, `@async`, and `@immutability`, matching the
zio-bdd runner's `excludeTags`. The canonical rationale for each exclusion lives in `ConformanceSpec`
in the `conformance-zio-bdd` module. The `evaluation.feature` file is not vendored (it is entirely
`@deprecated`) and is listed in the drift checker's `EXCLUDED_FILES`.

`@reason-codes-cached` (spec 1.4.7) and `@evaluation-options` (spec 1.5.1) are executed: the CACHED
reason is exercised by wrapping the in-memory provider with the testkit `CachingReasonProvider`, and
the evaluation-options step definitions live in `ConformanceSteps`.

## Provider-status scenarios

`@provider-status` evaluation scenarios assert a default value + error reason when the provider is
NOT_READY/FATAL. This wrapper surfaces those states as a typed failure rather than a returned
resolution; the step definitions bridge that failure back into the resolution shape the gherkin
asserts (same value/reason/error-code, different channel).

Spec v0.9.0 renumbered these scenarios from `@spec-1.7.6`/`@spec-1.7.7` to `@spec-2.2.7`: provider
status is now derived from provider-emitted events, and the client short-circuit those requirements
mandated is no longer required. Neither runner filters on `@spec-*` tags, so the rename is inert
here. Adapting the library's own behaviour to that change is tracked separately in #332.
