# Examples

Reference applications showing the recommended patterns for using **zio-openfeature** in production.
The code in this directory is not published as an artifact, but it compiles against the same source the library
publishes, so the README and docs snippets stay honest as the public API evolves.

## `ofrep-init-timeout/`

End-to-end wiring for an OFREP-backed app combining:

- `OFREPProvider.make(baseUrl)` for validated construction (bad URL fails at startup, not at first evaluation).
- `FeatureFlags.fromProviderAsync` with the default 30 s `initTimeout` so a misconfigured endpoint can't hang the app
  startup forever.
- `CircuitBreakerProvider` from `zio-openfeature-extras` so a degraded OFREP CDN doesn't take the app down.

Run it:

```sh
OFREP_BASE_URL=https://flags.example.com sbt 'examplesOfrepInitTimeout/run'
```

## `testkit-app/`

A trivial `UserService` gated by a feature flag, plus the spec that tests it against `TestFeatureProvider`. Shows the
intended pattern for unit-testing flag-driven application code without touching a real provider:

- `TestFeatureProvider.scopedLayer` wires the test FeatureFlags service.
- `provider.setFlag("…", value)` drives evaluations.
- `provider.setStatus(ProviderStatus.Error)` exercises the failure path.

Run the spec:

```sh
sbt 'examplesTestkitApp/test'
```

## Adding a new example

Examples must (a) compile against the published modules, (b) be runnable or testable from sbt with no manual setup
beyond environment variables, and (c) document the production pattern they illustrate. Add the new sub-project to
`build.sbt`, the CI workflow's `examples` job, and this README in the same PR.
