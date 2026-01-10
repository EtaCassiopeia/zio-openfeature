package zio.openfeature

import zio.*
import zio.test.*
import zio.test.Assertion.*
import java.time.Instant

object AttributeValueSpec extends ZIOSpecDefault:

  def spec = suite("AttributeValueSpec")(
    suite("Construction helpers")(
      test("bool creates BoolValue") {
        assertTrue(AttributeValue.bool(true) == AttributeValue.BoolValue(true)) &&
        assertTrue(AttributeValue.bool(false) == AttributeValue.BoolValue(false))
      },
      test("string creates StringValue") {
        assertTrue(AttributeValue.string("test") == AttributeValue.StringValue("test"))
      },
      test("int creates IntValue") {
        assertTrue(AttributeValue.int(42) == AttributeValue.IntValue(42))
      },
      test("long creates LongValue") {
        assertTrue(AttributeValue.long(123456789L) == AttributeValue.LongValue(123456789L))
      },
      test("double creates DoubleValue") {
        assertTrue(AttributeValue.double(3.14) == AttributeValue.DoubleValue(3.14))
      },
      test("instant creates InstantValue") {
        val now = Instant.now()
        assertTrue(AttributeValue.instant(now) == AttributeValue.InstantValue(now))
      },
      test("list creates ListValue") {
        val result = AttributeValue.list(AttributeValue.int(1), AttributeValue.int(2))
        assertTrue(result == AttributeValue.ListValue(List(AttributeValue.IntValue(1), AttributeValue.IntValue(2))))
      },
      test("struct creates StructValue") {
        val result = AttributeValue.struct("a" -> AttributeValue.int(1))
        assertTrue(result == AttributeValue.StructValue(Map("a" -> AttributeValue.IntValue(1))))
      }
    ),
    suite("asBoolean extractor")(
      test("extracts from BoolValue") {
        assertTrue(AttributeValue.BoolValue(true).asBoolean == Some(true)) &&
        assertTrue(AttributeValue.BoolValue(false).asBoolean == Some(false))
      },
      test("returns None for non-boolean types") {
        assertTrue(AttributeValue.StringValue("test").asBoolean == None) &&
        assertTrue(AttributeValue.IntValue(1).asBoolean == None) &&
        assertTrue(AttributeValue.DoubleValue(1.0).asBoolean == None) &&
        assertTrue(AttributeValue.LongValue(1L).asBoolean == None) &&
        assertTrue(AttributeValue.InstantValue(Instant.now()).asBoolean == None) &&
        assertTrue(AttributeValue.ListValue(Nil).asBoolean == None) &&
        assertTrue(AttributeValue.StructValue(Map.empty).asBoolean == None)
      }
    ),
    suite("asString extractor")(
      test("extracts from StringValue") {
        assertTrue(AttributeValue.StringValue("hello").asString == Some("hello"))
      },
      test("returns None for non-string types") {
        assertTrue(AttributeValue.BoolValue(true).asString == None) &&
        assertTrue(AttributeValue.IntValue(42).asString == None)
      }
    ),
    suite("asInt extractor")(
      test("extracts from IntValue") {
        assertTrue(AttributeValue.IntValue(42).asInt == Some(42))
      },
      test("returns None for non-int types") {
        assertTrue(AttributeValue.StringValue("test").asInt == None) &&
        assertTrue(AttributeValue.LongValue(42L).asInt == None) &&
        assertTrue(AttributeValue.DoubleValue(42.0).asInt == None)
      }
    ),
    suite("asLong extractor")(
      test("extracts from LongValue") {
        assertTrue(AttributeValue.LongValue(123456789L).asLong == Some(123456789L))
      },
      test("extracts from IntValue and converts") {
        assertTrue(AttributeValue.IntValue(42).asLong == Some(42L))
      },
      test("returns None for non-numeric types") {
        assertTrue(AttributeValue.StringValue("test").asLong == None) &&
        assertTrue(AttributeValue.DoubleValue(42.0).asLong == None) &&
        assertTrue(AttributeValue.BoolValue(true).asLong == None)
      }
    ),
    suite("asDouble extractor")(
      test("extracts from DoubleValue") {
        assertTrue(AttributeValue.DoubleValue(3.14).asDouble == Some(3.14))
      },
      test("extracts from IntValue and converts") {
        assertTrue(AttributeValue.IntValue(42).asDouble == Some(42.0))
      },
      test("extracts from LongValue and converts") {
        assertTrue(AttributeValue.LongValue(100L).asDouble == Some(100.0))
      },
      test("returns None for non-numeric types") {
        assertTrue(AttributeValue.StringValue("test").asDouble == None) &&
        assertTrue(AttributeValue.BoolValue(true).asDouble == None)
      }
    ),
    suite("asInstant extractor")(
      test("extracts from InstantValue") {
        val now = Instant.now()
        assertTrue(AttributeValue.InstantValue(now).asInstant == Some(now))
      },
      test("returns None for non-instant types") {
        assertTrue(AttributeValue.StringValue("2024-01-01").asInstant == None) &&
        assertTrue(AttributeValue.IntValue(1704067200).asInstant == None)
      }
    ),
    suite("asList extractor")(
      test("extracts from ListValue") {
        val list = List(AttributeValue.IntValue(1), AttributeValue.IntValue(2))
        assertTrue(AttributeValue.ListValue(list).asList == Some(list))
      },
      test("returns None for non-list types") {
        assertTrue(AttributeValue.StringValue("test").asList == None) &&
        assertTrue(AttributeValue.StructValue(Map.empty).asList == None)
      }
    ),
    suite("asStruct extractor")(
      test("extracts from StructValue") {
        val map = Map("key" -> AttributeValue.StringValue("value"))
        assertTrue(AttributeValue.StructValue(map).asStruct == Some(map))
      },
      test("returns None for non-struct types") {
        assertTrue(AttributeValue.StringValue("test").asStruct == None) &&
        assertTrue(AttributeValue.ListValue(Nil).asStruct == None)
      }
    ),
    suite("isNull check")(
      test("empty string is null") {
        assertTrue(AttributeValue.StringValue("").isNull == true)
      },
      test("non-empty string is not null") {
        assertTrue(AttributeValue.StringValue("test").isNull == false)
      },
      test("empty list is null") {
        assertTrue(AttributeValue.ListValue(Nil).isNull == true)
      },
      test("non-empty list is not null") {
        assertTrue(AttributeValue.ListValue(List(AttributeValue.IntValue(1))).isNull == false)
      },
      test("empty struct is null") {
        assertTrue(AttributeValue.StructValue(Map.empty).isNull == true)
      },
      test("non-empty struct is not null") {
        assertTrue(AttributeValue.StructValue(Map("a" -> AttributeValue.IntValue(1))).isNull == false)
      },
      test("other types are not null") {
        assertTrue(AttributeValue.BoolValue(false).isNull == false) &&
        assertTrue(AttributeValue.IntValue(0).isNull == false) &&
        assertTrue(AttributeValue.LongValue(0L).isNull == false) &&
        assertTrue(AttributeValue.DoubleValue(0.0).isNull == false) &&
        assertTrue(AttributeValue.InstantValue(Instant.EPOCH).isNull == false)
      }
    ),
    suite("implicit conversions")(
      test("Boolean converts to BoolValue") {
        val av: AttributeValue = true
        assertTrue(av == AttributeValue.BoolValue(true))
      },
      test("String converts to StringValue") {
        val av: AttributeValue = "hello"
        assertTrue(av == AttributeValue.StringValue("hello"))
      },
      test("Int converts to IntValue") {
        val av: AttributeValue = 42
        assertTrue(av == AttributeValue.IntValue(42))
      },
      test("Long converts to LongValue") {
        val av: AttributeValue = 123456789L
        assertTrue(av == AttributeValue.LongValue(123456789L))
      },
      test("Double converts to DoubleValue") {
        val av: AttributeValue = 3.14
        assertTrue(av == AttributeValue.DoubleValue(3.14))
      },
      test("Instant converts to InstantValue") {
        val now                = Instant.now()
        val av: AttributeValue = now
        assertTrue(av == AttributeValue.InstantValue(now))
      }
    )
  )
