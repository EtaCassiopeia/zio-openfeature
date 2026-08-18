Feature: An absent flag key reports FLAG_NOT_FOUND

  Before this, a provider that did not hold a key answered with the caller's default and a DEFAULT
  reason, which ends a `MultiProvider` chain rather than letting it advance. #370 (config providers)
  and #374 (the testkit provider) make an absent key `FLAG_NOT_FOUND`; #364 additionally sends the
  caller's own default down the object path and keeps the provider's error code on the way back.

  How the two tiers surface it (#388): the TYPED tier (`value` / `*Details`) fails with a typed
  `FlagNotFound` — the tier a caller reaches for precisely because a default would be wrong must not
  hand one back silently — while the TOTAL tier (`*OrDefault` / `resolveOrDefault`) serves the
  caller's default with `errorCode = FLAG_NOT_FOUND` and `reason = ERROR`.

  Scenario: The testkit provider reports FLAG_NOT_FOUND for a key it does not hold — the typed tier fails
    Given a test provider
    When the boolean flag "unknown.flag" is evaluated with default "false"
    Then the evaluation fails with error code "FLAG_NOT_FOUND"

  Scenario: The testkit provider reports FLAG_NOT_FOUND for a key it does not hold — the total tier serves the default
    Given a test provider
    When the boolean flag "unknown.flag" is resolved with default "false"
    Then the resolved error code is "FLAG_NOT_FOUND"
    And the resolved reason is "ERROR"
    And the flag value is "false"

  Scenario: The HOCON provider reports FLAG_NOT_FOUND for an absent key
    Given a HOCON provider configured with "known.flag = true"
    When the boolean flag "unknown.flag" is evaluated with default "false"
    Then the evaluation fails with error code "FLAG_NOT_FOUND"

  Scenario: The HOCON provider still serves the keys it does hold
    Given a HOCON provider configured with "known.flag = true"
    When the boolean flag "known.flag" is evaluated with default "false"
    Then the flag value is "true"

  Scenario: The environment-variable provider reports FLAG_NOT_FOUND for an absent key
    Given an environment-variable provider holding "FF_KNOWN_FLAG" = "true"
    When the boolean flag "unknown.flag" is evaluated with default "false"
    Then the evaluation fails with error code "FLAG_NOT_FOUND"

  Scenario: The environment-variable provider still serves the keys it does hold
    Given an environment-variable provider holding "FF_KNOWN_FLAG" = "true"
    When the boolean flag "known.flag" is evaluated with default "false"
    Then the flag value is "true"

  Scenario: FLAG_NOT_FOUND is what lets a chain of distinctly-named providers advance
    Given a chain of a "primary" provider and a "secondary" provider, only the second holding the boolean flag "kill.switch"
    When the boolean flag "kill.switch" is evaluated with default "false"
    Then the flag value is "true"

  Scenario: The caller's default reaches the provider on the object path and the error code survives — typed tier
    Given a provider that records the defaults it is handed
    When the object flag "config.blob" is evaluated with default field "region" set to "eu"
    Then the evaluation fails with error code "FLAG_NOT_FOUND"
    And the provider was handed the object default field "region" with value "eu"

  Scenario: The caller's default reaches the provider on the object path and is served back — total tier
    Given a provider that records the defaults it is handed
    When the object flag "config.blob" is resolved with default field "region" set to "eu"
    Then the resolved error code is "FLAG_NOT_FOUND"
    And the object field "region" is "eu"
    And the provider was handed the object default field "region" with value "eu"
