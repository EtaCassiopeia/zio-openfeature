---
layout: default
title: Evaluation Context
nav_order: 4
---

# Evaluation Context
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Evaluation context provides information about the current evaluation environment. This includes user information, application state, and any other data that might influence flag evaluation.

ZIO OpenFeature supports a hierarchical context system with three levels:

1. **Global context** - Shared across all evaluations, set via `setGlobalContext`
2. **Scoped context** - Applied to a block of code via `withContext`
3. **Invocation context** - Passed directly to evaluation methods

Contexts are merged in order, with later contexts taking precedence. Transaction context can also override values within a transaction block.

## Creating Context

### Basic Context

```scala
import zio.openfeature.*

// Empty context
val empty = EvaluationContext.empty

// Context with targeting key
val userCtx = EvaluationContext("user-123")

// Context with attributes
val richCtx = EvaluationContext("user-123")
  .withAttribute("plan", "premium")
  .withAttribute("country", "US")
  .withAttribute("age", 25)
```

### Using the Builder

```scala
val ctx = EvaluationContext.builder
  .targetingKey("user-456")
  .attribute("role", "admin")
  .attribute("beta", true)
  .attribute("score", 95.5)
  .build
```

## Context Hierarchy

### Global Context

Global context is shared across all fibers and persists for the lifetime of the service.

```scala
val program = for
  flags <- ZIO.service[FeatureFlags]
  _     <- flags.setGlobalContext(
             EvaluationContext("app")
               .withAttribute("environment", "production")
               .withAttribute("version", "1.0.0")
           )
  // All evaluations will include this context
yield ()
```

### Scoped Context

Use `withContext` to set context for a specific scope of code.

```scala
val ctx = EvaluationContext("user-123")
  .withAttribute("session", AttributeValue.string("abc-xyz"))

val program = FeatureFlags.withContext(ctx) {
  // All evaluations in this block use the scoped context
  FeatureFlags.boolean("feature", false)
}
```

### Invocation Context

Invocation context is passed directly to evaluation methods.

```scala
val ctx = EvaluationContext("user-789")
  .withAttribute("feature-group", "beta")

val result = FeatureFlags.boolean("new-feature", false, ctx)
```

## Attribute Types

Context attributes support various types through `AttributeValue`:

```scala
import java.time.Instant

val ctx = EvaluationContext("user")
  .withAttribute("active", true)                    // Boolean
  .withAttribute("name", "John")                    // String
  .withAttribute("age", 30)                         // Int
  .withAttribute("balance", 1000L)                  // Long
  .withAttribute("score", 95.5)                     // Double
  .withAttribute("created", Instant.now())          // Instant
```

### Complex Attributes

```scala
// List attribute
val tags = AttributeValue.list(
  AttributeValue.string("premium"),
  AttributeValue.string("beta")
)

// Struct attribute
val address = AttributeValue.struct(
  "city" -> AttributeValue.string("NYC"),
  "zip" -> AttributeValue.string("10001")
)

val ctx = EvaluationContext("user")
  .withAttribute("tags", tags)
  .withAttribute("address", address)
```

## Context Merging

When multiple contexts are present, they are merged with later contexts taking precedence:

```scala
val global = EvaluationContext.empty
  .withAttribute("env", AttributeValue.string("prod"))
  .withAttribute("version", AttributeValue.string("1.0"))

val scoped = EvaluationContext("user-123")
  .withAttribute("version", AttributeValue.string("2.0"))  // Overrides global

val invocation = EvaluationContext.empty
  .withAttribute("feature-group", AttributeValue.string("beta"))

// Effective context when using withContext(scoped) and passing invocation:
// - targetingKey: "user-123" (from scoped)
// - env: "prod" (from global)
// - version: "2.0" (from scoped, overrides global)
// - feature-group: "beta" (from invocation)
```
