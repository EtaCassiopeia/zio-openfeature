Feature: Typed test fixtures and distinctly-named test providers

  #351/#372 added `FlagDef := value` fixtures: the value is type-checked against the flag's declared
  type and stored through `flagType.encode`, so a test reads it back through the same decode path
  production uses. #375 added `makeNamed`, because a `MultiProvider` chain and the `setProvider`
  event-identity guard both key providers by their metadata name — two default-named test providers
  are indistinguishable to either.

  Scenario: A typed override stores the flag's wire value, not its domain value
    Given a test provider seeded with the typed override MaxItems set to 250
    When the string flag "max.items" is evaluated with default "none"
    Then the flag value is "250"

  Scenario: A typed override of a derived enum stores its canonical label
    Given a test provider seeded with the typed override Plan set to "Premium"
    When the string flag "user.plan" is evaluated with default "none"
    Then the flag value is "Premium"

  Scenario: A typed override reads back through the production decode path
    Given a test provider seeded with the typed override Plan set to "Enterprise"
    When the flag "Plan" is evaluated
    Then the evaluation succeeds
    And the flag value is "Enterprise"

  Scenario: Two overrides for the same key are rejected rather than merged last-wins
    When two typed overrides for the same key are used to seed a provider
    Then seeding the provider was rejected

  Scenario: A named test provider reports its own metadata name
    Given a test provider named "primary"
    Then the provider metadata name is "primary"

  Scenario: An unnamed test provider still reports the default name
    Given a test provider
    Then the provider metadata name is "TestFeatureProvider"
