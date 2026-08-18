Feature: Typed flag definitions and wire-type dispatch

  This file pins `FlagDef` as a first-class key/type/default (#357), evaluation
  dispatching on `FlagType.wireType` rather than `typeName` (#361), the diagnostic for a codec that
  declares a wire type its `encode` does not produce (#362), Mirror-based derivation for enums and
  case classes (#366), and `decode` running on the value extracted from the provider (#356).

  Scenario: A Mirror-derived enum flag is resolved through the provider's string resolver
    Given a test provider
    And the provider holds the string flag "user.plan" with value "premium"
    When the flag "Plan" is evaluated
    Then the evaluation succeeds
    And the flag value is "Premium"

  Scenario: A flag definition serves its own default when the provider does not know the key
    Given a test provider
    When the flag "Plan" is resolved with its own default
    Then the flag value is "Free"
    And the resolved error code is "FLAG_NOT_FOUND"
    And the resolved reason is "ERROR"

  Scenario: A value the derived codec cannot read is a type mismatch, not a silent default
    Given a test provider
    And the provider holds the string flag "user.plan" with value "platinum"
    When the flag "Plan" is evaluated
    Then the evaluation fails with error code "TYPE_MISMATCH"
    And the failure message mentions "Unknown Tier"

  Scenario: A Mirror-derived product flag is resolved on the object path
    Given a test provider
    And the provider holds the object flag "rollout" with fields
      | key  | value | value_type |
      | tier | beta  | string     |
      | pct  | 25    | integer    |
    When the flag "Rollout" is evaluated
    Then the evaluation succeeds
    And the flag value is "Release(beta,25,None)"

  Scenario: An absent product field falls back to its declared Scala default
    Given a test provider
    And the provider holds the object flag "rollout" with fields
      | key  | value  | value_type |
      | tier | canary | string     |
    When the flag "Rollout" is evaluated
    Then the flag value is "Release(canary,10,None)"

  Scenario: A mapped type over a numeric wire type is resolved through the integer resolver
    Given a test provider
    And the provider holds the integer flag "max.items" with value 250
    When the flag "MaxItems" is evaluated
    Then the flag value is "Level(250)"

  Scenario: A codec whose encode contradicts its declared wire type fails with a diagnostic
    Given a test provider
    When the flag "Contradictory" is evaluated
    Then the evaluation fails with error code "TYPE_MISMATCH"
    And the failure message mentions "declares wireType 'Int'"

  Scenario: A provider returning a null String is a type mismatch, not a null flag value
    Given a provider that returns a null string
    When the string flag "banner" is evaluated with default "none"
    Then the evaluation fails with error code "TYPE_MISMATCH"

  Scenario: A string-backed custom type is visible to a hook scoped to String
    Given a test provider
    And the provider holds the string flag "user.plan" with value "enterprise"
    And a hook scoped to the "String" flag value type
    When the flag "Plan" is evaluated
    Then the flag value is "Enterprise"
    And the hook saw the flag value type "String"

  Scenario: A string-backed custom type is filtered out of an Object-scoped hook
    Given a test provider
    And the provider holds the string flag "user.plan" with value "enterprise"
    And a hook scoped to the "Object" flag value type
    When the flag "Plan" is evaluated
    Then the hook did not run
