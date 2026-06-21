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
