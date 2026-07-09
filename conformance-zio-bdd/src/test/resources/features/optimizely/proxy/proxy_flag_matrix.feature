Feature: Optimizely flag matrix over a simulated CDN proxy

  # Every scenario below fetches the SAME "audience-segments" datafile from the SAME
  # simulated CDN endpoint (one shared in-process Rift mock space the OptimizelyProvider
  # fetches its datafile from directly — see ProxyMatrixHarness). scenarioParallelism=8 on
  # this suite means these scenarios really do run concurrently against that one shared
  # space, each with its own provider instance and OpenFeature domain so they can't see
  # each other's evaluation context.
  #
  # recommendation_rate_limit in that datafile is gated on an audience requiring BOTH
  # plan = "premium" AND region = "eu" together — so a single @flags(...) tag carrying both
  # keys is what actually changes the result, not a provider/datafile swap.

  @flags(plan=premium, region=eu)
  Scenario: Both audience attributes match — elevated rate limit
    When a recommendation is requested
    Then the recommendation kind is "alpha"
    And the rate limit is 250

  @flags(plan=premium, region=us)
  Scenario: Plan matches but region doesn't — falls through to conservative default
    When a recommendation is requested
    Then the recommendation kind is "alpha"
    And the rate limit is 10

  @flags(plan=standard, region=eu)
  Scenario: Region matches but plan doesn't — falls through to conservative default
    When a recommendation is requested
    Then the recommendation kind is "alpha"
    And the rate limit is 10

  @flags(plan=standard, region=us)
  Scenario: Neither attribute matches — conservative default
    When a recommendation is requested
    Then the recommendation kind is "alpha"
    And the rate limit is 10

  # Stacking two @flags tags on one scenario: two independent runs, each with its own provider
  # and context, against the same shared CDN mock space — one run lands in the audience, the
  # other doesn't.
  @flags(plan=premium, region=eu)
  @flags(plan=premium, region=us)
  Scenario: Stacked tags run this scenario twice, once per tag, against the same shared CDN
    When a recommendation is requested
    Then the recommendation kind is "alpha"
