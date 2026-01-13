---
layout: default
title: Evaluation Context
nav_order: 5
---

# Evaluation Context
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Evaluation context provides information about the current evaluation environment. This includes user information, application state, and any other data that might influence flag evaluation. The context is converted to OpenFeature SDK format and passed to the underlying provider.

ZIO OpenFeature supports a hierarchical context system with five levels (per OpenFeature spec):

1. **Global context** - API-level, shared across all evaluations, set via `setGlobalContext`
2. **Client context** - Client-level, persisted on the FeatureFlags instance, set via `setClientContext`
3. **Scoped context** - Applied to a block of code via `withContext` (fiber-local)
4. **Transaction context** - Applied within a transaction block
5. **Invocation context** - Passed directly to evaluation methods

Contexts are merged in order, with later contexts taking precedence.

---

## Creating Context

### Basic Context

```scala
import zio.openfeature.*

// Empty context
val empty = EvaluationContext.empty

// Context with targeting key (user ID)
val userCtx = EvaluationContext("user-123")

// Context with attributes
val richCtx = EvaluationContext("user-123")
  .withAttribute("plan", "premium")
  .withAttribute("country", "US")
  .withAttribute("age", 25)
  .withAttribute("beta", true)
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

---

## Context Hierarchy

### Global Context

Global context is shared across all fibers and persists for the lifetime of the service. Use it for attributes that apply to all evaluations, such as application version or environment.

```scala
import zio.*
import zio.openfeature.*

val program = for
  _ <- FeatureFlags.setGlobalContext(
         EvaluationContext("app")
           .withAttribute("environment", "production")
           .withAttribute("version", "1.0.0")
           .withAttribute("datacenter", "us-east")
       )
  // All evaluations will include these attributes
  enabled <- FeatureFlags.boolean("feature", false)
yield enabled
```

### Client Context

Client context is persisted on the `FeatureFlags` instance and applies to all evaluations made through that client. Per the OpenFeature spec, client context is merged after global context but before scoped/transaction context.

```scala
// Set client-level context
FeatureFlags.setClientContext(
  EvaluationContext.empty
    .withAttribute("service", "checkout-api")
    .withAttribute("region", "us-east-1")
)

// Get client context
val clientCtx = FeatureFlags.clientContext

// Client context is separate from global context
for
  _ <- FeatureFlags.setGlobalContext(EvaluationContext("app"))
  _ <- FeatureFlags.setClientContext(EvaluationContext("client"))
  global <- FeatureFlags.globalContext  // "app"
  client <- FeatureFlags.clientContext  // "client"
yield (global, client)
```

### Scoped Context

Use `withContext` to set context for a specific scope of code. This is useful for request handling where you want all evaluations in a request to share the same user context.

```scala
val userCtx = EvaluationContext("user-123")
  .withAttribute("session_id", "abc-xyz")
  .withAttribute("user_agent", request.headers.userAgent)

val handleRequest = FeatureFlags.withContext(userCtx) {
  for
    // All evaluations in this block use userCtx
    showNewUI    <- FeatureFlags.boolean("new-ui", false)
    maxItems     <- FeatureFlags.int("max-items", 10)
    buttonColor  <- FeatureFlags.string("button-color", "blue")
  yield Response(showNewUI, maxItems, buttonColor)
}
```

### Invocation Context

Pass context directly to individual evaluation methods. This is useful for one-off attributes or when you need to override context for a specific evaluation.

```scala
val baseCtx = EvaluationContext("user-789")
  .withAttribute("feature-group", "beta")

// Evaluate with invocation context
val result = FeatureFlags.boolean("new-feature", false, baseCtx)

// Override for a specific evaluation
val specialCtx = baseCtx.withAttribute("override", true)
val special = FeatureFlags.boolean("special-feature", false, specialCtx)
```

---

## Attribute Types

Context attributes support various types:

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

// Struct attribute (nested object)
val address = AttributeValue.struct(
  "city" -> AttributeValue.string("NYC"),
  "zip" -> AttributeValue.string("10001"),
  "country" -> AttributeValue.string("US")
)

val ctx = EvaluationContext("user")
  .withAttribute("tags", tags)
  .withAttribute("address", address)
```

---

## Context Merging

When multiple contexts are present, they are merged with later contexts taking precedence:

```scala
// Global context (lowest precedence)
FeatureFlags.setGlobalContext(
  EvaluationContext.empty
    .withAttribute("env", "prod")
    .withAttribute("version", "1.0")
)

// Scoped context (overrides global)
val scopedCtx = EvaluationContext("user-123")
  .withAttribute("version", "2.0")  // Overrides global version

FeatureFlags.withContext(scopedCtx) {
  // Invocation context (highest precedence)
  val invCtx = EvaluationContext.empty
    .withAttribute("experiment", "A")

  // Final merged context for this evaluation:
  // - targetingKey: "user-123" (from scoped)
  // - env: "prod" (from global)
  // - version: "2.0" (from scoped, overrides global)
  // - experiment: "A" (from invocation)
  FeatureFlags.boolean("feature", false, invCtx)
}
```

### Merge Order

Per the OpenFeature specification, contexts are merged in this order (lowest to highest precedence):

```
┌──────────────────────────────────────────────────────────────┐
│                     Final Merged Context                      │
│  (Invocation > Transaction > Scoped > Client > Global)       │
└──────────────────────────────────────────────────────────────┘
                            ▲
                            │
    ┌───────────────────────┼───────────────────────┐
    │                       │                       │
┌───┴───┐             ┌─────┴─────┐           ┌─────┴─────┐
│Invoc. │             │Transaction│           │  Scoped   │
│(high) │             │  Context  │           │  Context  │
└───────┘             └───────────┘           └───────────┘
                            │
                     ┌──────┴──────┐
                     │   Client    │
                     │  Context    │
                     └─────────────┘
                            │
                     ┌──────┴──────┐
                     │   Global    │
                     │  (lowest)   │
                     └─────────────┘
```

---

## Targeting Key

The targeting key is a unique identifier for the evaluation subject (typically a user ID). It's used by providers to ensure consistent flag values for the same user.

```scala
// Create context with targeting key
val ctx = EvaluationContext("user-12345")

// Or set via builder
val ctx2 = EvaluationContext.builder
  .targetingKey("session-abc123")
  .attribute("role", "admin")
  .build
```

### Best Practices for Targeting Key

1. **Use stable identifiers**: User IDs, account IDs, or device IDs work well
2. **Be consistent**: Use the same ID format across your application
3. **Consider privacy**: Avoid PII in targeting keys; use hashed values if needed
4. **Anonymous users**: Generate a consistent session-based ID for anonymous users

---

## Practical Examples

### HTTP Request Handler

```scala
def handleRequest(req: Request) = {
  val ctx = EvaluationContext(req.userId.getOrElse("anonymous"))
    .withAttribute("path", req.path)
    .withAttribute("method", req.method)
    .withAttribute("ip_country", geolocate(req.ip))

  FeatureFlags.withContext(ctx) {
    for
      enabled <- FeatureFlags.boolean("new-api", false)
      response <- if enabled then newApiHandler(req) else legacyHandler(req)
    yield response
  }
}
```

### Multi-Tenant Application

```scala
val tenantCtx = EvaluationContext(tenantId)
  .withAttribute("plan", tenant.plan)
  .withAttribute("seats", tenant.seatCount)
  .withAttribute("region", tenant.region)

FeatureFlags.withContext(tenantCtx) {
  // All evaluations scoped to this tenant
  for
    maxUsers <- FeatureFlags.int("max-users", 10)
    features <- FeatureFlags.obj("features", Map.empty)
  yield TenantConfig(maxUsers, features)
}
```

### A/B Testing

```scala
val experimentCtx = EvaluationContext(userId)
  .withAttribute("experiment_group", determineGroup(userId))
  .withAttribute("cohort", userCohort)

val variant = FeatureFlags.string("checkout-flow", "control", experimentCtx)
```

---

## OpenFeature SDK Conversion

ZIO OpenFeature contexts are automatically converted to OpenFeature SDK `EvaluationContext` format when passed to the underlying provider. The conversion:

1. Maps `targetingKey` to OpenFeature's targeting key field
2. Converts all attributes to OpenFeature `Value` types
3. Preserves nested structures and lists

This conversion happens internally - you work only with ZIO OpenFeature's `EvaluationContext` type.

