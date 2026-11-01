package zio.openfeature.ofrep

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock._
import zio.Chunk

/** An HTTP error response from an OFREP endpoint, as three independent axes: the status, the body and the headers.
  *
  * The axes are independent on purpose: a proxy or a crashing server can pair any status with any body, and only the
  * combination shows whether the client classifies by what it was told or by what it happened to parse.
  */
final case class FaultShape(status: Int, body: FaultShape.Body, headers: FaultShape.Headers) {

  /** A flag key naming this shape, so a failure report says which shape it was. */
  def key: String = s"shape-$status-${body.label}-${headers.label}"
}

object FaultShape {

  sealed abstract class Body(val label: String) extends Product with Serializable
  object Body {

    /** An OFREP error body: `FLAG_NOT_FOUND` for a 404, `INVALID_CONTEXT` for a 400, otherwise error details only. */
    case object OfrepJson extends Body("ofrep")

    /** JSON, but not an OFREP error. */
    case object OtherJson extends Body("json")

    /** A proxy's HTML error page. */
    case object Html extends Body("html")

    case object Empty extends Body("empty")

    /** OFREP JSON cut off mid-token. */
    case object Truncated extends Body("truncated")

    val values: Chunk[Body] = Chunk(OfrepJson, OtherJson, Html, Empty, Truncated)
  }

  /** The only headers sent: `Content-Type` comes from here alone, so a JSON body can arrive as `text/html` too. */
  sealed abstract class Headers(val label: String) extends Product with Serializable
  object Headers {
    case object NoHeaders       extends Headers("none")
    case object RetryAfter      extends Headers("retry")
    case object ContentTypeJson extends Headers("ctjson")
    case object ContentTypeHtml extends Headers("cthtml")
    case object Both            extends Headers("both")

    val values: Chunk[Headers] = Chunk(NoHeaders, RetryAfter, ContentTypeJson, ContentTypeHtml, Both)
  }

  val statuses: Chunk[Int] = Chunk(400, 401, 403, 404, 429, 500, 502, 503)

  /** Every shape: 8 statuses x 5 bodies x 5 header sets. */
  val all: Chunk[FaultShape] =
    for {
      s <- statuses
      b <- Body.values
      h <- Headers.values
    } yield FaultShape(s, b, h)

  private def bodyText(shape: FaultShape): Option[String] = shape.body match {
    case Body.OfrepJson =>
      Some(shape.status match {
        case 404 => s"""{"key":"${shape.key}","errorCode":"FLAG_NOT_FOUND","errorDetails":"no such flag"}"""
        case 400 => s"""{"key":"${shape.key}","errorCode":"INVALID_CONTEXT","errorDetails":"bad context"}"""
        case _   => """{"errorDetails":"something went wrong"}"""
      })
    case Body.OtherJson => Some("""{"message":"nope","status":"error"}""")
    case Body.Html      => Some("<html><body><h1>Error</h1></body></html>")
    case Body.Empty     => None
    case Body.Truncated => Some(s"""{"key":"${shape.key}","errorCo""")
  }

  def response(shape: FaultShape): ResponseDefinitionBuilder = {
    val withStatus = aResponse().withStatus(shape.status)
    val withBody   = bodyText(shape).fold(withStatus)(withStatus.withBody)
    val retry      = shape.headers == Headers.RetryAfter || shape.headers == Headers.Both
    val withRetry  = if (retry) withBody.withHeader("Retry-After", "1") else withBody
    shape.headers match {
      case Headers.ContentTypeJson | Headers.Both => withRetry.withHeader("Content-Type", "application/json")
      case Headers.ContentTypeHtml                => withRetry.withHeader("Content-Type", "text/html")
      case _                                      => withRetry
    }
  }

  /** Stub `shape` on `server`, on the OFREP single-flag endpoint for its key. */
  def install(server: WireMockServer, shape: FaultShape): Unit = {
    server.stubFor(post(urlEqualTo(s"/ofrep/v1/evaluate/flags/${shape.key}")).willReturn(response(shape)))
    ()
  }
}
