Feature: Optimizely flag matrix — behaviour under different datafile configurations

  Each scenario is annotated with @flags(datafile=X).  zio-bdd expands every @flags(...) tag
  into an independent scenario run and calls flagLayer with Map("datafile" -> "X"), which builds
  a fresh WireMock server + Optimizely provider scoped to that scenario.  No stub-swap, no
  polling wait — each run has its own isolated provider that is torn down when the scenario ends.

  # ---------------------------------------------------------------------------
  # Basic kill-switch / variant matrix — one @flags tag per expected outcome.
  # zio-bdd names each run "Scenario name [datafile=X]" in the report.
  # ---------------------------------------------------------------------------

  @flags(datafile=empty)
  Scenario: No flags configured — service returns the safe default
    Then the recommendation service returns kind "default"

  @flags(datafile=kill-switch-off)
  Scenario: Kill-switch is off and variant is alpha — full recommendation
    Then the recommendation service returns kind "alpha"

  @flags(datafile=kill-switch-on)
  Scenario: Kill-switch is on — service returns degraded response
    Then the recommendation service returns kind "degraded"

  @flags(datafile=variant-beta)
  Scenario: Kill-switch is off and variant is beta — beta recommendation
    Then the recommendation service returns kind "beta"

  # ---------------------------------------------------------------------------
  # Audience-gated integer variable — premium vs standard plan
  #
  # The "audience-premium" datafile has three flags:
  #   • recommendation_kill_switch  — on for all users
  #   • recommendation_variant      — "alpha" for all users
  #   • recommendation_rate_limit   — integer variable:
  #       100  when user attribute plan = "premium" (audience rule fires)
  #        10  for all other users (audience miss → FlagNotFound → default)
  # ---------------------------------------------------------------------------

  @flags(datafile=audience-premium)
  Scenario: Premium user receives elevated rate limit from audience-gated variable
    When user "user-alice" with plan "premium" requests a recommendation
    Then the recommendation kind is "alpha"
    And the rate limit is 100

  @flags(datafile=audience-premium)
  Scenario: Standard user falls through audience rule and receives conservative rate limit
    When user "user-bob" with plan "standard" requests a recommendation
    Then the recommendation kind is "alpha"
    And the rate limit is 10

  @flags(datafile=kill-switch-on)
  Scenario: Kill-switch overrides all flags — degraded result regardless of plan attribute
    When user "user-alice" with plan "premium" requests a recommendation
    Then the recommendation kind is "degraded"
    And the rate limit is 0

  # ---------------------------------------------------------------------------
  # Combining multiple keys in a single @flags(...) tag
  #
  # A single tag with comma-separated key=value pairs expands to ONE flagLayer
  # call carrying both keys in one Map — one run, both overrides applied
  # together. Here "plan" is seeded as global context (MatrixHarness ->
  # setGlobalContext) instead of being passed as step text, so there is no
  # "with plan" wording in this scenario at all — it's entirely tag-driven.
  # ---------------------------------------------------------------------------

  @flags(datafile=audience-premium, plan=premium)
  Scenario: Datafile and plan overrides combined in a single @flags tag
    When a recommendation is requested
    Then the recommendation kind is "alpha"
    And the rate limit is 100

  # ---------------------------------------------------------------------------
  # Stacking multiple separate @flags(...) tags on one scenario
  #
  # Two distinct tag occurrences — not two keys in one tag — expand into two
  # independent runs, each calling flagLayer once with only that tag's own
  # Map. Each run gets its own isolated WireMock server + Optimizely provider
  # (per MatrixHarness), so this is two full scenario executions, not one
  # execution choosing between two configs. kill-switch-off and
  # audience-premium happen to agree on "alpha" under the default (no-plan)
  # context, so the same assertion holds for both runs even though the
  # underlying datafile — and provider instance — differs each time.
  # ---------------------------------------------------------------------------

  @flags(datafile=kill-switch-off) @flags(datafile=audience-premium)
  Scenario: Stacking two separate @flags tags runs the scenario twice, once per tag
    Then the recommendation service returns kind "alpha"
