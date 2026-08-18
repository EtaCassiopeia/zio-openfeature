Feature: Served-default fallback logging

  #350/#378: the total tier (`*OrDefault` / `resolveOrDefault`) logs every served-default fallback at
  warn, rate-limited per flag key by the `FallbackLogging` policy. Before this, a provider-reported
  problem that made the library serve the default said nothing at all, and an absorbed defect was
  logged on every occurrence.

  The throttled scenarios use a one-hour window, so "one line per key" is a property of the limiter
  rather than of how fast the suite runs.

  Scenario: A throttled policy logs one line per key per window
    Given a flagless provider with fallback logging "throttled"
    When the boolean flag "alpha" is served its default 3 times
    Then the served-default warning count is 1
    And a served-default warning names the flag "alpha"

  Scenario: Each flag key gets its own throttling bucket
    Given a flagless provider with fallback logging "throttled"
    When the boolean flag "alpha" is served its default 2 times
    And the boolean flag "beta" is served its default 2 times
    Then the served-default warning count is 2
    And a served-default warning names the flag "beta"

  Scenario: The always policy logs every served default
    Given a flagless provider with fallback logging "always"
    When the boolean flag "alpha" is served its default 3 times
    Then the served-default warning count is 3

  Scenario: A zero window behaves like the always policy
    Given a flagless provider with fallback logging "unthrottled"
    When the boolean flag "alpha" is served its default 3 times
    Then the served-default warning count is 3

  Scenario: The off policy emits no served-default line at all
    Given a flagless provider with fallback logging "off"
    When the boolean flag "alpha" is served its default 3 times
    Then the served-default warning count is 0
