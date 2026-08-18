Feature: Native 64-bit flag evaluation

  #333/#339 moved `Long` evaluation onto the SDK 1.22.0 native long surface: the provider's own
  `getLongEvaluation` decides the result, so the full 64-bit range is exact instead of being routed
  through an integer or double resolver and silently losing precision beyond 2^53. The change is
  observable three ways — exactness, the `FlagValueType.Long` hooks now see, and what happens to a
  third-party provider that never overrode `getLongEvaluation`.

  Scenario: A value beyond double precision is resolved exactly
    Given a test provider
    And the provider holds the long flag "budget.cents" with value 9007199254740993
    When the long flag "budget.cents" is evaluated with default 0
    Then the long value equals 9007199254740993

  Scenario: A long flag definition resolves the full 64-bit range through its typed surface
    Given a test provider
    And the provider holds the long flag "budget.cents" with value 4611686018427387904
    When the flag "Budget" is evaluated
    Then the long value equals 4611686018427387904

  Scenario: Long evaluations report the Long flag value type to hooks
    Given a test provider
    And the provider holds the long flag "budget.cents" with value 12
    And a hook scoped to the "Long" flag value type
    When the long flag "budget.cents" is evaluated with default 0
    Then the hook saw the flag value type "Long"

  Scenario: An Int-scoped hook no longer sees long evaluations
    Given a test provider
    And the provider holds the long flag "budget.cents" with value 12
    And a hook scoped to the "Int" flag value type
    When the long flag "budget.cents" is evaluated with default 0
    Then the hook did not run

  Scenario: A provider without a native long surface is served by its double resolver
    Given a legacy provider without native long support
    When the long flag "legacy.count" is evaluated with default 0
    Then the long value equals 99

  Scenario: The integer-widening wrapper restores int-range routing for such a provider
    Given a legacy provider wrapped for integer widening
    When the long flag "legacy.count" is evaluated with default 0
    Then the long value equals 7
