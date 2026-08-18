Feature: Pull-based ambient evaluation context

  #353/#373 added `ContextSource`: an effect consulted once per evaluation for ambient context, for
  applications that carry request identity outside the ZIO environment. Its precedence slot is fixed
  and is the reason this belongs in the library rather than in a `before` hook —
  global -> transaction -> client -> contextSource -> fiberLocal -> invocation.

  Scenario: The ambient source contributes context to every evaluation
    Given a test provider with an ambient context source carrying "user" = "alice"
    When the boolean flag "kill.switch" is evaluated with default "false"
    Then the provider received the context attribute "user" = "alice"

  Scenario: The ambient source is consulted again on the next evaluation
    Given a test provider with an ambient context source carrying "user" = "alice"
    When the boolean flag "kill.switch" is evaluated with default "false"
    And the ambient context source is updated to carry "user" = "bob"
    And the boolean flag "kill.switch" is evaluated with default "false"
    Then the provider received the context attribute "user" = "bob"

  Scenario: The ambient source overrides client and global context
    Given a test provider with an ambient context source carrying "user" = "ambient"
    And the global context carries "user" = "global"
    And the client context carries "user" = "client"
    When the boolean flag "kill.switch" is evaluated with default "false"
    Then the provider received the context attribute "user" = "ambient"

  Scenario: An invocation context still wins over the ambient source
    Given a test provider with an ambient context source carrying "user" = "ambient"
    When the boolean flag "kill.switch" is evaluated with invocation context "user" = "call"
    Then the provider received the context attribute "user" = "call"
