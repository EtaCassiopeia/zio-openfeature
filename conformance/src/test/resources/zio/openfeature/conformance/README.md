# Vendored OpenFeature gherkin conformance assets

The `.feature` files in this directory are **verbatim copies** of the OpenFeature specification's
conformance suite, executed against the ZIO `FeatureFlags` API by Cucumber (see `RunConformance`).

- Source: https://github.com/open-feature/spec — `specification/assets/gherkin/`
- Pinned commit: **203c25f93495**

Keep these byte-identical to upstream so a re-sync is a clean diff. To update: re-copy the files
from the pinned path at a newer commit and run `sbt conformance/test`; new or changed scenarios
surface as failing/undefined steps rather than silently going unrun.

## Excluded tags (mirrors the OpenFeature Java SDK's own e2e runner)

`RunConformance` filters out: `@deprecated` (superseded by `evaluation_v2`), `@reason-codes-cached`,
`@async`, `@immutability`, `@evaluation-options`. The `evaluation.feature` file is not vendored (it
is entirely `@deprecated`).

## Provider-status scenarios

`@provider-status` evaluation scenarios assert a default value + error reason when the provider is
NOT_READY/FATAL. This wrapper surfaces those states as a typed failure rather than a returned
resolution; the step definitions bridge that failure back into the resolution shape the gherkin
asserts (same value/reason/error-code, different channel).
