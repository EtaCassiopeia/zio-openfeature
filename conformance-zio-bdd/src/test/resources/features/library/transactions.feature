Feature: Transaction caching and overrides for custom-typed flags

  #365: a transaction caches the *wire* value of each evaluation and serves a same-key re-read by
  running it back through `decode`, so a custom-typed flag re-read inside a transaction is decoded
  exactly as a provider answer would be. The same change lets an override be given as either the
  wire value or the domain value, with `decode` the arbiter of what counts as the domain type.

  Scenario: A repeated read inside a transaction is served from the cached wire value
    Given a test provider
    And the provider holds the string flag "user.plan" with value "premium"
    When a transaction reads the flag "Plan" twice
    Then both transaction reads are "Premium"
    And the provider evaluation count for the flag "user.plan" is 1

  Scenario: A transaction override may be given as the domain value
    Given a test provider
    And the provider holds the string flag "user.plan" with value "premium"
    When a transaction overriding the flag "Plan" with the domain value "Enterprise" reads it twice
    Then both transaction reads are "Enterprise"
    And the flag "Plan" was overridden in the transaction
    And the provider evaluation count for the flag "user.plan" is 0

  Scenario: A transaction override may be given as the wire value
    Given a test provider
    And the provider holds the string flag "user.plan" with value "premium"
    When a transaction overriding the flag "Plan" with the wire value "enterprise" reads it twice
    Then both transaction reads are "Enterprise"
    And the flag "Plan" was overridden in the transaction
