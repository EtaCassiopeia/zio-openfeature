Feature: Fallback-first construction reports whether the real provider is live

  `fromAcquireAsync` serves a fallback from time zero, so `providerStatus` reads READY before the real
  provider exists and a readiness probe cannot tell fallback values from real ones. #352/#377 added
  `AcquireStatus` (Constructing -> Live | Failed) plus an `onSwapped` callback, and #349/#376 added a
  `verify` step that rejects a candidate which constructed successfully but cannot actually serve —
  the "successful wrong values" case a first-successful chain would otherwise accept silently.

  Every scenario here uses `Verify.flagExists[Boolean]("kill.switch")` as the sentinel check and no
  retry schedule, so the terminal outcome is reached immediately.

  Scenario: A verified candidate is swapped in and reported Live
    Given a fallback-first instance whose real provider "is acquired and verified"
    When the construction outcome settles
    Then the acquire state is "Live"
    And the swap callback fired

  Scenario: The real provider serves once it is live
    Given a fallback-first instance whose real provider "is acquired and verified"
    And the construction outcome settles
    When the boolean flag "kill.switch" is evaluated with default "false"
    Then the flag value is "true"

  Scenario: A candidate that fails verification never serves
    Given a fallback-first instance whose real provider "fails verification"
    When the construction outcome settles
    Then the acquire state is "Failed"
    And the swap callback did not fire
    And the construction error mentions "kill.switch"

  Scenario: The fallback keeps serving after a failed verification
    Given a fallback-first instance whose real provider "fails verification"
    And the construction outcome settles
    When the boolean flag "kill.switch" is evaluated with default "false"
    Then the flag value is "false"

  Scenario: An acquire failure is terminal and reaches the construction-error callback
    Given a fallback-first instance whose real provider "cannot be acquired"
    When the construction outcome settles
    Then the acquire state is "Failed"
    And the swap callback did not fire
    And the construction error mentions "acquire exploded"
