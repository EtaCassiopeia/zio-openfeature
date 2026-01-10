package zio.openfeature

import zio.test.*
import zio.test.Assertion.*

object EvaluationContextSpec extends ZIOSpecDefault:

  def spec = suite("EvaluationContextSpec")(
    suite("Construction")(
      test("empty creates empty context") {
        val ctx = EvaluationContext.empty
        assertTrue(ctx.isEmpty) &&
        assertTrue(ctx.targetingKey.isEmpty) &&
        assertTrue(ctx.attributes.isEmpty)
      },
      test("apply with targeting key") {
        val ctx = EvaluationContext("user-123")
        assertTrue(ctx.targetingKey.contains("user-123")) &&
        assertTrue(ctx.attributes.isEmpty)
      },
      test("forEntity creates context with targeting key and attributes") {
        val ctx = EvaluationContext.forEntity("entity-456", "account")
        assertTrue(ctx.targetingKey.contains("entity-456")) &&
        assertTrue(ctx.getString("entityId").contains("entity-456")) &&
        assertTrue(ctx.getString("entityType").contains("account"))
      },
      test("withAttributes creates context with attributes") {
        val ctx = EvaluationContext.withAttributes(
          "key1" -> AttributeValue.string("value1"),
          "key2" -> AttributeValue.int(42)
        )
        assertTrue(ctx.targetingKey.isEmpty) &&
        assertTrue(ctx.getString("key1").contains("value1")) &&
        assertTrue(ctx.getInt("key2").contains(42))
      }
    ),
    suite("Builder")(
      test("builds context with fluent API") {
        val ctx = EvaluationContext.builder
          .targetingKey("user-789")
          .attribute("name", "Alice")
          .attribute("premium", true)
          .attribute("score", 95)
          .build

        assertTrue(ctx.targetingKey.contains("user-789")) &&
        assertTrue(ctx.getString("name").contains("Alice")) &&
        assertTrue(ctx.getBoolean("premium").contains(true)) &&
        assertTrue(ctx.getInt("score").contains(95))
      }
    ),
    suite("Merging")(
      test("merge combines contexts with later taking precedence") {
        val ctx1 = EvaluationContext("user-1")
          .withAttribute("a", AttributeValue.string("from-ctx1"))
          .withAttribute("b", AttributeValue.string("only-in-ctx1"))

        val ctx2 = EvaluationContext("user-2")
          .withAttribute("a", AttributeValue.string("from-ctx2"))
          .withAttribute("c", AttributeValue.string("only-in-ctx2"))

        val merged = ctx1.merge(ctx2)

        assertTrue(merged.targetingKey.contains("user-2")) &&
        assertTrue(merged.getString("a").contains("from-ctx2")) &&
        assertTrue(merged.getString("b").contains("only-in-ctx1")) &&
        assertTrue(merged.getString("c").contains("only-in-ctx2"))
      },
      test("merge preserves first targeting key when second is empty") {
        val ctx1 = EvaluationContext("user-1")
        val ctx2 = EvaluationContext.empty.withAttribute("x", AttributeValue.string("y"))

        val merged = ctx1.merge(ctx2)
        assertTrue(merged.targetingKey.contains("user-1"))
      }
    ),
    suite("Attribute Operations")(
      test("withAttribute adds attribute") {
        val ctx = EvaluationContext.empty
          .withAttribute("key", AttributeValue.string("value"))
        assertTrue(ctx.getString("key").contains("value"))
      },
      test("withAttributes adds multiple attributes") {
        val ctx = EvaluationContext.empty.withAttributes(
          "a" -> AttributeValue.int(1),
          "b" -> AttributeValue.int(2)
        )
        assertTrue(ctx.getInt("a").contains(1)) &&
        assertTrue(ctx.getInt("b").contains(2))
      },
      test("withoutAttribute removes attribute") {
        val ctx = EvaluationContext.empty
          .withAttribute("toRemove", AttributeValue.string("value"))
          .withoutAttribute("toRemove")
        assertTrue(ctx.get("toRemove").isEmpty)
      },
      test("withTargetingKey sets targeting key") {
        val ctx = EvaluationContext.empty.withTargetingKey("new-key")
        assertTrue(ctx.targetingKey.contains("new-key"))
      }
    ),
    suite("Attribute Access")(
      test("getBoolean returns boolean value") {
        val ctx = EvaluationContext.empty
          .withAttribute("flag", AttributeValue.bool(true))
        assertTrue(ctx.getBoolean("flag").contains(true))
      },
      test("getString returns string value") {
        val ctx = EvaluationContext.empty
          .withAttribute("name", AttributeValue.string("test"))
        assertTrue(ctx.getString("name").contains("test"))
      },
      test("getInt returns int value") {
        val ctx = EvaluationContext.empty
          .withAttribute("count", AttributeValue.int(42))
        assertTrue(ctx.getInt("count").contains(42))
      },
      test("getLong returns long value") {
        val ctx = EvaluationContext.empty
          .withAttribute("bigNum", AttributeValue.long(1234567890123L))
        assertTrue(ctx.getLong("bigNum").contains(1234567890123L))
      },
      test("getLong converts int to long") {
        val ctx = EvaluationContext.empty
          .withAttribute("num", AttributeValue.int(42))
        assertTrue(ctx.getLong("num").contains(42L))
      },
      test("getDouble returns double value") {
        val ctx = EvaluationContext.empty
          .withAttribute("rate", AttributeValue.double(3.14))
        assertTrue(ctx.getDouble("rate").contains(3.14))
      },
      test("get returns None for missing key") {
        val ctx = EvaluationContext.empty
        assertTrue(ctx.get("missing").isEmpty) &&
        assertTrue(ctx.getString("missing").isEmpty) &&
        assertTrue(ctx.getInt("missing").isEmpty)
      },
      test("typed getters return None for wrong type") {
        val ctx = EvaluationContext.empty
          .withAttribute("str", AttributeValue.string("hello"))
        assertTrue(ctx.getInt("str").isEmpty) &&
        assertTrue(ctx.getBoolean("str").isEmpty)
      }
    ),
    suite("isEmpty/nonEmpty")(
      test("empty context is empty") {
        assertTrue(EvaluationContext.empty.isEmpty) &&
        assertTrue(!EvaluationContext.empty.nonEmpty)
      },
      test("context with targeting key is not empty") {
        val ctx = EvaluationContext("user")
        assertTrue(!ctx.isEmpty) &&
        assertTrue(ctx.nonEmpty)
      },
      test("context with attributes is not empty") {
        val ctx = EvaluationContext.empty
          .withAttribute("key", AttributeValue.string("value"))
        assertTrue(!ctx.isEmpty) &&
        assertTrue(ctx.nonEmpty)
      }
    )
  )
