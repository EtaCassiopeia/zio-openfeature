---
layout: default
title: Evaluation Context
nav_order: 3
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

ZIO OpenFeature supports a hierarchical context system with four levels:

1. **Global context** - Shared across all fibers
2. **Fiber-local context** - Specific to the current fiber
3. **Transaction context** - Active during a transaction
4. **Invocation context** - Passed directly to evaluation methods

Contexts are merged in order, with later contexts taking precedence.

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
  .withTargetingKey("user-456")
  .withAttribute("role", "admin")
  .withAttribute("beta", true)
  .withAttribute("score", 95.5)
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

### Fiber-Local Context

Fiber-local context is specific to the current fiber and its children.

```scala
val program = for
  flags <- ZIO.service[FeatureFlags]
  _     <- flags.setFiberContext(
             EvaluationContext("user-123")
               .withAttribute("session", "abc-xyz")
           )
  // This fiber's evaluations include the context
yield ()
```

### Invocation Context

Invocation context is passed directly to evaluation methods.

```scala
val ctx = EvaluationContext("user-789")
  .withAttribute("feature-group", "beta")

val result = flags.getBooleanValue("new-feature", false, ctx)
```

### Scoped Context

Use `withContext` to temporarily set context for a block of code.

```scala
val ctx = EvaluationContext("test-user")

val result = flags.withContext(ctx) {
  for
    a <- flags.getBooleanValue("feature-a", false)
    b <- flags.getStringValue("feature-b", "default")
  yield (a, b)
}
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
  .withAttribute("env", "prod")
  .withAttribute("version", "1.0")

val fiber = EvaluationContext("user-123")
  .withAttribute("version", "2.0")  // Overrides global

val invocation = EvaluationContext.empty
  .withAttribute("feature-group", "beta")

// Effective context:
// - targetingKey: "user-123" (from fiber)
// - env: "prod" (from global)
// - version: "2.0" (from fiber, overrides global)
// - feature-group: "beta" (from invocation)
```
