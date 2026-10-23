# Examples

Code that uses **zio-openfeature** the way its documentation says to. None of it is published, but CI compiles all of
it against the current source, so the documented usage cannot rot.

## `docs/`

Every code sample on the [documentation site](https://etacassiopeia.github.io/zio-openfeature/). The pages include
the sections between `// --8<-- [start:name]` and `// --8<-- [end:name]` markers in
`src/main/scala-3/snippets/`, and the specs in `src/test/scala-3/snippets/` run the samples that can run on their own
and check what the pages say about them. To change a sample, change it here; the page picks it up.

```sh
sbt examplesDocs/test
```

## `ofrep-init-timeout/`

An OFREP-backed app wired for production:

- `OFREPProvider.make(baseUrl)` validates the URL, so a bad one fails at start-up, not at the first evaluation;
- `FeatureFlags.fromProviderAsync` with the default 30 s `initTimeout`, so a misconfigured endpoint cannot hang
  start-up;
- `CircuitBreakerProvider` from `zio-openfeature-extras`, so a degraded OFREP endpoint does not take the app down.

```sh
OFREP_BASE_URL=https://flags.example.com sbt 'examplesOfrepInitTimeout/run'
```

## `testkit-app/`

A `UserService` gated by a flag, and the spec that tests it against `TestFeatureProvider`:

- `TestFeatureProvider.layer` wires the test `FeatureFlags`;
- `provider.setFlag("…", value)` drives evaluations;
- `provider.setStatus(ProviderStatus.Error)` exercises the failure path.

```sh
sbt 'examplesTestkitApp/test'
```

## Adding an example

An example must compile against the published modules, run or test from sbt with nothing but environment variables,
and say which production pattern it shows. Add it to `build.sbt`, to the CI workflow's `examples` job and to this
file in the same change.
