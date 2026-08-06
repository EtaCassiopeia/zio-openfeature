# Vendored OpenFeature gherkin conformance assets

The `.feature` files in this directory are **verbatim copies** of the OpenFeature specification's
conformance suite, executed against the ZIO `FeatureFlags` API by Cucumber (see `RunConformance`).

- Source: https://github.com/open-feature/spec — `specification/assets/gherkin/`
- Pinned tag: **v0.9.0** (commit `d5b0a734d8cb9b42bf89be2a97c627f58208e811`)

That "Pinned tag" line is **authoritative and machine-read**: `.github/scripts/check-gherkin-drift.sh`
parses it for the ref to compare against when run without an explicit one, so this file is the single
source of truth for the pin (#336 — it used to be duplicated in the script, which could rot silently).
Preserve the exact shape ``- Pinned tag: **vX.Y.Z** (commit `<sha>`)`` when re-syncing; the script
exits 2 ("could not check") rather than guessing if the line is missing, malformed, or duplicated.

Keep these byte-identical to upstream so a re-sync is a clean diff. To update: re-copy the files
from the pinned path at a newer tag, update the pin line above, and run `sbt conformance/test`; new
or changed scenarios surface as failing/undefined steps rather than silently going unrun.

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
| `print-pin` | the "Pinned tag" line above still parses | the `format` job in `ci.yml` — every PR |

Upstream drift is not a PR author's doing, so it is scheduled and files a deduped issue rather than
blocking PRs. Breaking the in-repo mirror **is** a PR author's doing, so that one blocks.

`print-pin` is a parse-only mode (no network, no `gh`) that exists so the pin line's format is
CI-checked per PR: the scheduled `upstream` job always passes an explicit ref, so nothing else in CI
ever exercises the parse. Reformatting the pin line therefore fails the PR that makes it, rather
than surfacing later as a confusing exit 2 on somebody's machine (#338).

Run any of them locally:

```sh
.github/scripts/check-gherkin-drift.sh mirror
.github/scripts/check-gherkin-drift.sh print-pin           # what ref does the README pin to?
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
mandated is no longer required. Neither runner filters on `@spec-*` tags, so the rename is inert here.

**The short-circuit stays anyway, as deliberate library policy** (#332). v0.9.0 permits it rather than
requiring it, and removing it would change the published 1.0.0 error contract — `FeatureFlags`
evaluations fail with a typed `ProviderNotReady`/`ProviderFatal`, while transaction overrides and cached
evaluations deliberately bypass the gate — for no spec gain, since the gherkin asserts the same
observable outcomes either way. The step-definition bridge above therefore stays too. The behavioural
half of v0.9.0 (providers emitting their own lifecycle events) is upstream-blocked and tracked in #340.

Note the two `@spec-1.7.6`/`@spec-1.7.7` references in the paragraph above are deliberate: they record
what the scenarios *used to be* numbered so a future re-sync can trace the rename. Nothing in the code
or docs claims those requirements as current — the only other surviving mentions are past `CHANGELOG.md`
entries, which are release history and stay as written.
