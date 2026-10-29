package zio.openfeature.internal

import zio._
import zio.openfeature._
import dev.openfeature.sdk.{Client => OFClient, FlagEvaluationDetails, Reason}

/** Typeclass that encapsulates type-safe dispatch to the OpenFeature Java SDK client methods.
  *
  * Each instance knows how to call the correct SDK method (getBooleanDetails, getStringDetails, etc.) for a given Scala
  * type, handling Java boxing conversions where needed.
  */
private[openfeature] trait ClientEvaluator[A] {

  /** Evaluate a flag using the appropriate SDK method and return raw FlagEvaluationDetails.
    *
    * @param client
    *   the OpenFeature Java SDK client
    * @param key
    *   the flag key
    * @param default
    *   the default value
    * @param context
    *   the evaluation context
    * @return
    *   the evaluation details from the SDK, wrapped in a blocking ZIO
    */
  def evaluate(
    client: OFClient,
    key: String,
    default: A,
    context: dev.openfeature.sdk.EvaluationContext
  ): Task[FlagEvaluationDetails[_]]

  /** Extract the typed value from the raw SDK evaluation details.
    *
    * Some types (Long, Float) require converting the SDK result type (Integer, Double) back to the target type.
    */
  def extractValue(details: FlagEvaluationDetails[_]): A
}

private[openfeature] object ClientEvaluator {

  /** A standard-type evaluation produced by [[evaluateStandard]], with the wire extractor and the `FlagType`'s decoder
    * pre-applied to the caller's `A`.
    *
    * `extract` yields an `Either` rather than an `A` because the wire value is not always the domain value: for a
    * scalar-backed custom type the decode can legitimately reject what the provider returned (an unknown enum variant),
    * and that outcome has to be representable. Returning `A` forced an unchecked `asInstanceOf[A]` here, which threw
    * `ClassCastException` — a defect — instead of producing a typed `TypeMismatch`.
    */
  final case class Erased[A](
    task: Task[FlagEvaluationDetails[_]],
    extract: FlagEvaluationDetails[_] => Either[String, A]
  )

  /** Look up the evaluator for `flagType.wire` and produce the type-erased evaluation.
    *
    * Dispatch is on `wire`, not `typeName`, so a domain type carried over the wire as a scalar is resolved through that
    * scalar's SDK method. The default sent down is `flagType.encode(default)` — already the wire value — and the result
    * is run back through `flagType.decode`.
    *
    * `Option[A]` over a scalar `A` resolves through `A`'s resolver. A `None` default encodes to `null`, which the
    * scalar evaluators would unbox to `false`/`0`, so `A`'s own zero (`underlying.encode(underlying.defaultValue)`) is
    * sent as a sentinel instead. The result reads back as `None` when the resolution carries an error code, or when its
    * reason is `DEFAULT`, `DISABLED` or `ERROR` and the value is the sentinel: the provider served the default it was
    * handed. A provider that serves exactly the zero value with one of those reasons therefore reads as `None`.
    *
    *   - `None` — the wire type is `Object`; the caller uses the object path.
    *   - `Some(Right(erased))` — dispatch resolved.
    *   - `Some(Left(message))` — the instance is internally inconsistent: it declares a scalar wire type that its
    *     `encode` does not produce. The caller reports this as a typed `TypeMismatch`.
    */
  def evaluateStandard[A](
    flagType: FlagType[A],
    client: OFClient,
    key: String,
    default: A,
    context: dev.openfeature.sdk.EvaluationContext
  ): Option[Either[String, Erased[A]]] = {
    // `wireClass` is the box the chosen evaluator will unbox. The `asInstanceOf[T]` below is erased, so a mismatch
    // does NOT fail here — it fails later inside the evaluator's bridge method as a bare ClassCastException carrying
    // no flag key and no hint about the wire/encode mismatch. Checking the box up front is what turns that
    // programmer error at a documented extension point into an actionable typed failure.
    def erased[T](ev: ClientEvaluator[T], wireClass: Class[_]): Either[String, Erased[A]] = {
      val encoded         = flagType.encode(default)
      val (wire, extract) = flagType match {
        case optional: OptionalFlagType[_] if encoded == null =>
          // Produced by the element's own `encode`, so it passes the box check below by construction.
          val sentinel = optional.underlying.encode(optional.underlying.defaultValue)
          val read: FlagEvaluationDetails[_] => Either[String, A] = { details =>
            val raw = ev.extractValue(details)
            // `decode(null)` is the Option instance's own `None`, so no cast to `A` is needed.
            if (details.getErrorCode != null || (FellBackReasons.contains(details.getReason) && raw == sentinel))
              flagType.decode(null)
            else flagType.decode(raw)
          }
          (sentinel, read)
        case _ =>
          (encoded, (details: FlagEvaluationDetails[_]) => flagType.decode(ev.extractValue(details)))
      }
      // `null` is let through: it reached the SDK before this check existed, and narrowing that is not this change's
      // business.
      if (wire == null || wireClass.isInstance(wire))
        Right(Erased[A](ev.evaluate(client, key, wire.asInstanceOf[T], context), extract))
      else
        Left(
          s"FlagType '${flagType.typeName}' declares wire type '${flagType.wire.name}' but encode produced " +
            s"${wire.getClass.getName}; override encode to produce ${wireClass.getName}"
        )
    }

    flagType.wire match {
      case WireType.Boolean => Some(erased(booleanEvaluator, classOf[java.lang.Boolean]))
      case WireType.String  => Some(erased(stringEvaluator, classOf[String]))
      case WireType.Int     => Some(erased(intEvaluator, classOf[java.lang.Integer]))
      case WireType.Long    => Some(erased(longEvaluator, classOf[java.lang.Long]))
      case WireType.Float   => Some(erased(floatEvaluator, classOf[java.lang.Float]))
      case WireType.Double  => Some(erased(doubleEvaluator, classOf[java.lang.Double]))
      case WireType.Object  => None
    }
  }

  // The spec reasons that mean "the provider served the default it was handed" — what the `Option` sentinel rule in
  // `evaluateStandard` reads as `None`.
  private val FellBackReasons: Set[String] = Set(Reason.DEFAULT.name, Reason.DISABLED.name, Reason.ERROR.name)

  implicit val booleanEvaluator: ClientEvaluator[Boolean] = new ClientEvaluator[Boolean] {
    def evaluate(
      client: OFClient,
      key: String,
      default: Boolean,
      context: dev.openfeature.sdk.EvaluationContext
    ): Task[FlagEvaluationDetails[_]] =
      ZIO.attemptBlocking(client.getBooleanDetails(key, default, context))

    def extractValue(details: FlagEvaluationDetails[_]): Boolean =
      details.getValue.asInstanceOf[java.lang.Boolean].booleanValue()
  }

  implicit val stringEvaluator: ClientEvaluator[String] = new ClientEvaluator[String] {
    def evaluate(
      client: OFClient,
      key: String,
      default: String,
      context: dev.openfeature.sdk.EvaluationContext
    ): Task[FlagEvaluationDetails[_]] =
      ZIO.attemptBlocking(client.getStringDetails(key, default, context))

    def extractValue(details: FlagEvaluationDetails[_]): String =
      details.getValue.asInstanceOf[String]
  }

  implicit val intEvaluator: ClientEvaluator[Int] = new ClientEvaluator[Int] {
    def evaluate(
      client: OFClient,
      key: String,
      default: Int,
      context: dev.openfeature.sdk.EvaluationContext
    ): Task[FlagEvaluationDetails[_]] =
      ZIO.attemptBlocking(client.getIntegerDetails(key, Integer.valueOf(default), context))

    def extractValue(details: FlagEvaluationDetails[_]): Int =
      details.getValue.asInstanceOf[java.lang.Integer].intValue()
  }

  // SDK 1.22.0 added a native Long surface, so Long evaluations dispatch to the provider's own `getLongEvaluation`
  // rather than being routed here. That routing used to live at this call site: an int-range default went to
  // `getIntegerDetails` (so an integer-stored flag met the provider's integer resolver instead of its double one),
  // and anything larger went to `getDoubleDetails` — exact only up to 2^53, silently lossy beyond it.
  //
  // Going native trades that silent precision loss for either an exact 64-bit result (providers that override
  // `getLongEvaluation`) or a loud TYPE_MISMATCH from the SDK's double-backed default. Every provider this library
  // ships overrides it: the OFREP contrib provider does not, which is why the ofrep factories return
  // `OFREPFeatureProvider`, whose override resolves long reads through the contrib object resolver. It also makes
  // SDK-level `LongHook`s and `FlagValueType.LONG` observable, which the hand-rolled routing hid.
  // A third-party provider that does NOT override `getLongEvaluation` can be wrapped in
  // `zio.openfeature.extras.IntegerWideningLongProvider` to restore the old int-range behaviour.
  implicit val longEvaluator: ClientEvaluator[Long] = new ClientEvaluator[Long] {
    def evaluate(
      client: OFClient,
      key: String,
      default: Long,
      context: dev.openfeature.sdk.EvaluationContext
    ): Task[FlagEvaluationDetails[_]] =
      ZIO.attemptBlocking(client.getLongDetails(key, java.lang.Long.valueOf(default), context))

    // Still `Number`, not a `Long` cast: the SDK's double-backed default path can hand back a boxed Double.
    def extractValue(details: FlagEvaluationDetails[_]): Long =
      details.getValue.asInstanceOf[java.lang.Number].longValue()
  }

  // Float uses Double SDK method with conversion
  implicit val floatEvaluator: ClientEvaluator[Float] = new ClientEvaluator[Float] {
    def evaluate(
      client: OFClient,
      key: String,
      default: Float,
      context: dev.openfeature.sdk.EvaluationContext
    ): Task[FlagEvaluationDetails[_]] =
      ZIO.attemptBlocking(
        client.getDoubleDetails(key, java.lang.Double.valueOf(default.toDouble), context)
      )

    def extractValue(details: FlagEvaluationDetails[_]): Float =
      details.getValue.asInstanceOf[java.lang.Double].floatValue()
  }

  implicit val doubleEvaluator: ClientEvaluator[Double] = new ClientEvaluator[Double] {
    def evaluate(
      client: OFClient,
      key: String,
      default: Double,
      context: dev.openfeature.sdk.EvaluationContext
    ): Task[FlagEvaluationDetails[_]] =
      ZIO.attemptBlocking(client.getDoubleDetails(key, java.lang.Double.valueOf(default), context))

    def extractValue(details: FlagEvaluationDetails[_]): Double =
      details.getValue.asInstanceOf[java.lang.Double].doubleValue()
  }
}
