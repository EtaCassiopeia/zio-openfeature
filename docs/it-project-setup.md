# `optimizely-it` test-project setup

The `optimizely-it` module's tests run against committed Optimizely v4 datafiles under `optimizely-it/src/test/resources/datafiles/`. Those fixtures are currently **hand-crafted to match the v4 schema** — they're real bytes consumed by the real Optimizely Java SDK, but they were not produced by Optimizely's serializer.

This document is the source of truth for what those datafiles describe so you can:

1. Understand what each spec asserts.
2. Regenerate the fixtures from a real Optimizely free-tier project (recommended for the strongest "no fakes" coverage — see [Regenerating from a real project](#regenerating-from-a-real-project) below).
3. Add new flags / audiences / variables and keep the constants in `RealOptimizelySupport.scala` aligned with them.

## Constants

All constants live in `optimizely-it/src/test/scala/zio/openfeature/optimizely/it/RealOptimizelySupport.scala`. Don't rename anything there without updating both the JSON fixtures and this doc.

## `it_basic.json` — five flags, all rolled out 100%

| Flag | Variation | `featureEnabled` | Variables |
|---|---|---|---|
| `it_bool_flag` | `on` | true | — |
| `it_string_flag` | `on` | true | `value: "rolled-out"` (string) |
| `it_int_flag` | `on` | true | `value: 42` (integer) |
| `it_double_flag` | `on` | true | `value: 3.14` (double) |
| `it_object_flag` | `on` | true | `name: "alice"` (string), `level: 7` (integer) |

Each flag has a rollout with one experiment containing one variation that captures 100% of traffic. No audience targeting; every user lands on the rolled-out variation.

## `it_targeting.json` — audience-targeted decisions

Audience: `Country US`, condition `country == "US"` (custom-attribute exact match).

| Flag | Rule 1 (audience = US) | Rule 2 (default) |
|---|---|---|
| `it_audience_flag` | variation `us`, `featureEnabled=true`, `value: "us"` | variation `off`, `featureEnabled=false`, `value: "other"` |

A user with attribute `country=US` matches rule 1 and gets `us`. Any other user falls through to rule 2 and gets `off`. A request with no targeting key short-circuits before reaching Optimizely with `ErrorCode.TARGETING_KEY_MISSING`.

## `it_variations.json` — variation-key fallback

| Flag | Variation | `featureEnabled` | Variables |
|---|---|---|---|
| `it_variation_flag` | `treatment_a` | true | — |

Used to verify that when a flag has no `value` variable, `getStringEvaluation` falls back to `decision.getVariationKey` (so the test asserts `"treatment_a"`).

## Regenerating from a real project

If you'd rather have Optimizely's serializer produce the JSON (so the fixtures match exactly what a production deployment receives), set up a free-tier project in the Optimizely dashboard and recreate the flags above:

1. Create a new project under your Optimizely Feature Experimentation account (the free tier is fine).
2. Create the seven flags listed in the [Constants](#constants) section. Match the keys, variable names, types, and default values exactly.
3. For each rollout: set traffic to 100% and configure the variation values to match the tables above.
4. (When implementing the targeting spec in #149) create the `country == "US"` audience and the `it_audience_flag` flag.
5. Dump the datafile for each "SDK key" used by the tests. The SDK keys in the fixtures (`it_basic`, `it_targeting`, `it_variations`, …) are also the filenames nginx serves — you'll need a real Optimizely SDK key per file. The simplest path is to create one Optimizely project per fixture and use that project's SDK key.

   ```bash
   curl https://cdn.optimizely.com/datafiles/<your-sdk-key-for-it_basic>.json > optimizely-it/src/test/resources/datafiles/it_basic.json
   ```

6. Run `sbt optimizelyIt/test` to confirm the regenerated fixtures still produce the expected decisions.

If you do this, please remove this note and replace it with a pointer to the Optimizely project(s) used.
