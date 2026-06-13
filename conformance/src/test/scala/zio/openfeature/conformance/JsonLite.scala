package zio.openfeature.conformance

/** Minimal JSON parser for the object literals that appear in the gherkin (`{}`, `{"a": 1}`, the flat template object).
  * Numbers are parsed to `Double` to match how the wrapper decodes provider object values, so parsed expectations and
  * resolved values compare by `==`. Not a general-purpose parser — just enough for the conformance object scenarios.
  */
object JsonLite {

  def parseObject(input: String): Map[String, Any] =
    parse(input) match {
      case m: Map[_, _] => m.asInstanceOf[Map[String, Any]]
      case other        => throw new IllegalArgumentException(s"Expected a JSON object, got: $other from '$input'")
    }

  def parse(input: String): Any = {
    val p = new Parser(input)
    val v = p.parseValue()
    p.skipWs()
    if (!p.atEnd) throw new IllegalArgumentException(s"Trailing content in JSON: '$input'")
    v
  }

  final private class Parser(s: String) {
    private var i = 0

    def atEnd: Boolean = i >= s.length
    def skipWs(): Unit = while (i < s.length && s(i).isWhitespace) i += 1

    def parseValue(): Any = {
      skipWs()
      s(i) match {
        case '{'                        => parseObj()
        case '['                        => parseArr()
        case '"'                        => parseStr()
        case 't' | 'f'                  => parseBool()
        case 'n'                        => parseNull()
        case c if c == '-' || c.isDigit => parseNum()
        case c                          => throw new IllegalArgumentException(s"Unexpected character '$c' in JSON")
      }
    }

    private def parseObj(): Map[String, Any] = {
      i += 1 // {
      skipWs()
      if (s(i) == '}') { i += 1; return Map.empty }
      val b        = Map.newBuilder[String, Any]
      var continue = true
      while (continue) {
        skipWs()
        val key = parseStr()
        skipWs()
        require(s(i) == ':', "expected ':' in JSON object"); i += 1
        b += (key -> parseValue())
        skipWs()
        s(i) match {
          case ',' => i += 1
          case '}' => i += 1; continue = false
          case c   => throw new IllegalArgumentException(s"Unexpected '$c' in JSON object")
        }
      }
      b.result()
    }

    private def parseArr(): List[Any] = {
      i += 1 // [
      skipWs()
      if (s(i) == ']') { i += 1; return Nil }
      val b        = List.newBuilder[Any]
      var continue = true
      while (continue) {
        b += parseValue()
        skipWs()
        s(i) match {
          case ',' => i += 1
          case ']' => i += 1; continue = false
          case c   => throw new IllegalArgumentException(s"Unexpected '$c' in JSON array")
        }
      }
      b.result()
    }

    private def parseStr(): String = {
      require(s(i) == '"', "expected '\"' starting a JSON string"); i += 1
      val sb = new StringBuilder
      while (s(i) != '"') {
        if (s(i) == '\\') {
          i += 1
          s(i) match {
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case '/'  => sb.append('/')
            case 'n'  => sb.append('\n')
            case 't'  => sb.append('\t')
            case 'r'  => sb.append('\r')
            case c    => sb.append(c)
          }
        } else sb.append(s(i))
        i += 1
      }
      i += 1 // closing "
      sb.toString
    }

    private def parseBool(): Boolean =
      if (s.startsWith("true", i)) { i += 4; true }
      else if (s.startsWith("false", i)) { i += 5; false }
      else throw new IllegalArgumentException("invalid JSON boolean")

    private def parseNull(): Null =
      if (s.startsWith("null", i)) { i += 4; null }
      else throw new IllegalArgumentException("invalid JSON null")

    private def parseNum(): Double = {
      val start = i
      while (i < s.length && (s(i).isDigit || "-+.eE".contains(s(i)))) i += 1
      s.substring(start, i).toDouble
    }
  }
}
