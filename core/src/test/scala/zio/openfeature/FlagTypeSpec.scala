package zio.openfeature

import zio.test.*
import zio.test.Assertion.*

object FlagTypeSpec extends ZIOSpecDefault:

  def spec = suite("FlagTypeSpec")(
    suite("Boolean FlagType")(
      test("decodes true boolean") {
        val result = FlagType[Boolean].decode(true)
        assertTrue(result == Right(true))
      },
      test("decodes false boolean") {
        val result = FlagType[Boolean].decode(false)
        assertTrue(result == Right(false))
      },
      test("decodes string 'true'") {
        val result = FlagType[Boolean].decode("true")
        assertTrue(result == Right(true))
      },
      test("decodes string 'false'") {
        val result = FlagType[Boolean].decode("false")
        assertTrue(result == Right(false))
      },
      test("decodes non-zero number as true") {
        val result = FlagType[Boolean].decode(java.lang.Integer.valueOf(1))
        assertTrue(result == Right(true))
      },
      test("decodes zero as false") {
        val result = FlagType[Boolean].decode(java.lang.Integer.valueOf(0))
        assertTrue(result == Right(false))
      },
      test("has correct typeName") {
        assertTrue(FlagType[Boolean].typeName == "Boolean")
      },
      test("has correct defaultValue") {
        assertTrue(FlagType[Boolean].defaultValue == false)
      }
    ),
    suite("String FlagType")(
      test("decodes string") {
        val result = FlagType[String].decode("hello")
        assertTrue(result == Right("hello"))
      },
      test("decodes null as empty string") {
        val result = FlagType[String].decode(null)
        assertTrue(result == Right(""))
      },
      test("decodes other types via toString") {
        val result = FlagType[String].decode(java.lang.Integer.valueOf(42))
        assertTrue(result == Right("42"))
      },
      test("has correct typeName") {
        assertTrue(FlagType[String].typeName == "String")
      }
    ),
    suite("Int FlagType")(
      test("decodes int") {
        val result = FlagType[Int].decode(42)
        assertTrue(result == Right(42))
      },
      test("decodes long to int") {
        val result = FlagType[Int].decode(42L)
        assertTrue(result == Right(42))
      },
      test("decodes double to int") {
        val result = FlagType[Int].decode(42.9)
        assertTrue(result == Right(42))
      },
      test("decodes Java Integer") {
        val result = FlagType[Int].decode(java.lang.Integer.valueOf(42))
        assertTrue(result == Right(42))
      },
      test("decodes string") {
        val result = FlagType[Int].decode("42")
        assertTrue(result == Right(42))
      },
      test("fails on invalid string") {
        val result = FlagType[Int].decode("not-a-number")
        assertTrue(result.isLeft)
      },
      test("has correct typeName") {
        assertTrue(FlagType[Int].typeName == "Int")
      }
    ),
    suite("Long FlagType")(
      test("decodes long") {
        val result = FlagType[Long].decode(1234567890123L)
        assertTrue(result == Right(1234567890123L))
      },
      test("decodes int to long") {
        val result = FlagType[Long].decode(42)
        assertTrue(result == Right(42L))
      },
      test("decodes string") {
        val result = FlagType[Long].decode("1234567890123")
        assertTrue(result == Right(1234567890123L))
      }
    ),
    suite("Double FlagType")(
      test("decodes double") {
        val result = FlagType[Double].decode(3.14)
        assertTrue(result == Right(3.14))
      },
      test("decodes float to double") {
        val result = FlagType[Double].decode(3.14f)
        assertTrue(result.isRight)
      },
      test("decodes int to double") {
        val result = FlagType[Double].decode(42)
        assertTrue(result == Right(42.0))
      },
      test("decodes string") {
        val result = FlagType[Double].decode("3.14")
        assertTrue(result == Right(3.14))
      }
    ),
    suite("Object FlagType")(
      test("decodes Scala Map") {
        val map: Map[String, Any] = Map("key" -> "value")
        val result                = FlagType[Map[String, Any]].decode(map)
        assertTrue(result == Right(map))
      },
      test("decodes Java Map") {
        val javaMap = new java.util.HashMap[String, Any]()
        javaMap.put("key", "value")
        val result = FlagType[Map[String, Any]].decode(javaMap)
        assertTrue(result.isRight && result.toOption.get("key") == "value")
      },
      test("fails on non-map") {
        val result = FlagType[Map[String, Any]].decode("not a map")
        assertTrue(result.isLeft)
      }
    ),
    suite("Option FlagType")(
      test("decodes null as None") {
        val result = FlagType[Option[Int]].decode(null)
        assertTrue(result == Right(None))
      },
      test("decodes None as None") {
        val result = FlagType[Option[Int]].decode(None)
        assertTrue(result == Right(None))
      },
      test("decodes Some value") {
        val result = FlagType[Option[Int]].decode(Some(42))
        assertTrue(result == Right(Some(42)))
      },
      test("decodes raw value as Some") {
        val result = FlagType[Option[Int]].decode(42)
        assertTrue(result == Right(Some(42)))
      },
      test("has correct typeName") {
        assertTrue(FlagType[Option[Int]].typeName == "Option[Int]")
      }
    ),
    suite("List FlagType")(
      test("decodes Scala List") {
        val result = FlagType[List[Int]].decode(List(1, 2, 3))
        assertTrue(result == Right(List(1, 2, 3)))
      },
      test("decodes Seq") {
        val result = FlagType[List[Int]].decode(Seq(1, 2, 3))
        assertTrue(result == Right(List(1, 2, 3)))
      },
      test("decodes Array") {
        val result = FlagType[List[Int]].decode(Array(1, 2, 3))
        assertTrue(result == Right(List(1, 2, 3)))
      },
      test("decodes Java List") {
        val javaList = java.util.Arrays.asList(1, 2, 3)
        val result   = FlagType[List[Int]].decode(javaList)
        assertTrue(result == Right(List(1, 2, 3)))
      },
      test("has correct typeName") {
        assertTrue(FlagType[List[Int]].typeName == "List[Int]")
      }
    ),
    suite("Float FlagType")(
      test("decodes float") {
        val result = FlagType[Float].decode(3.14f)
        assertTrue(result == Right(3.14f))
      },
      test("decodes double to float") {
        val result = FlagType[Float].decode(3.14)
        assertTrue(result.isRight)
      },
      test("decodes int to float") {
        val result = FlagType[Float].decode(42)
        assertTrue(result == Right(42.0f))
      },
      test("decodes long to float") {
        val result = FlagType[Float].decode(42L)
        assertTrue(result == Right(42.0f))
      },
      test("decodes Java Number") {
        val result = FlagType[Float].decode(java.lang.Float.valueOf(3.14f))
        assertTrue(result == Right(3.14f))
      },
      test("decodes string") {
        val result = FlagType[Float].decode("3.14")
        assertTrue(result.isRight)
      },
      test("fails on invalid string") {
        val result = FlagType[Float].decode("not-a-number")
        assertTrue(result.isLeft)
      },
      test("has correct typeName") {
        assertTrue(FlagType[Float].typeName == "Float")
      },
      test("has correct defaultValue") {
        assertTrue(FlagType[Float].defaultValue == 0.0f)
      }
    ),
    suite("Boolean decode edge cases")(
      test("fails on unsupported type") {
        val result = FlagType[Boolean].decode(List(1, 2, 3))
        assertTrue(result.isLeft)
      },
      test("fails on invalid boolean string") {
        val result = FlagType[Boolean].decode("invalid")
        assertTrue(result.isLeft)
      }
    ),
    suite("Long decode edge cases")(
      test("decodes double to long") {
        val result = FlagType[Long].decode(42.9)
        assertTrue(result == Right(42L))
      },
      test("decodes Java Number") {
        val result = FlagType[Long].decode(java.lang.Long.valueOf(123L))
        assertTrue(result == Right(123L))
      },
      test("fails on invalid string") {
        val result = FlagType[Long].decode("not-a-number")
        assertTrue(result.isLeft)
      },
      test("fails on unsupported type") {
        val result = FlagType[Long].decode(List(1, 2, 3))
        assertTrue(result.isLeft)
      }
    ),
    suite("Double decode edge cases")(
      test("decodes long to double") {
        val result = FlagType[Double].decode(100L)
        assertTrue(result == Right(100.0))
      },
      test("decodes Java Number") {
        val result = FlagType[Double].decode(java.lang.Double.valueOf(3.14))
        assertTrue(result == Right(3.14))
      },
      test("fails on invalid string") {
        val result = FlagType[Double].decode("not-a-number")
        assertTrue(result.isLeft)
      },
      test("fails on unsupported type") {
        val result = FlagType[Double].decode(List(1, 2, 3))
        assertTrue(result.isLeft)
      }
    ),
    suite("Int decode edge cases")(
      test("fails on unsupported type") {
        val result = FlagType[Int].decode(List(1, 2, 3))
        assertTrue(result.isLeft)
      }
    ),
    suite("List decode edge cases")(
      test("fails on non-list type") {
        val result = FlagType[List[Int]].decode("not a list")
        assertTrue(result.isLeft)
      },
      test("fails when element decode fails") {
        val result = FlagType[List[Int]].decode(List("not", "ints"))
        assertTrue(result.isLeft)
      }
    ),
    suite("FlagType helper methods")(
      test("FlagType.apply summons instance") {
        val ft = FlagType[Int]
        assertTrue(ft.typeName == "Int")
      },
      test("FlagType.typeName helper") {
        assertTrue(FlagType.typeName[String] == "String")
      }
    ),
    suite("Custom FlagType")(
      test("FlagType.from creates custom decoder") {
        case class MyType(value: String)

        given FlagType[MyType] = FlagType.from(
          name = "MyType",
          default = MyType(""),
          decoder = {
            case s: String => Right(MyType(s))
            case _         => Left("Expected string")
          }
        )

        val result = FlagType[MyType].decode("hello")
        assertTrue(result == Right(MyType("hello")))
      },
      test("FlagType.mapped creates derived decoder") {
        case class UserId(value: String)

        given FlagType[UserId] = FlagType.mapped[UserId, String](
          name = "UserId",
          default = UserId("")
        )(
          map = UserId(_),
          contramap = _.value
        )

        val result = FlagType[UserId].decode("user-123")
        assertTrue(result == Right(UserId("user-123")))
      }
    )
  )
