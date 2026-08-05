package zio.openfeature.internal

import zio._
import zio.openfeature._
import dev.openfeature.sdk.{Client => OFClient, FlagEvaluationDetails}

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

  /** A standard-type evaluation produced by [[evaluateStandard]], with the typed extractor pre-applied to the caller's
    * `A`. The `asInstanceOf[A]` cast lives once here, not at each call site.
    */
  final case class Erased[A](
    task: Task[FlagEvaluationDetails[_]],
    extract: FlagEvaluationDetails[_] => A
  )

  /** Look up the evaluator for a standard type by name and produce the type-erased evaluation. Returns None for
    * non-standard types (Object, custom) which need special handling.
    */
  def evaluateStandard[A](
    typeName: String,
    client: OFClient,
    key: String,
    default: A,
    context: dev.openfeature.sdk.EvaluationContext
  ): Option[Erased[A]] = {
    def erased[T](ev: ClientEvaluator[T]): Erased[A] =
      Erased[A](
        ev.evaluate(client, key, default.asInstanceOf[T], context),
        details => ev.extractValue(details).asInstanceOf[A]
      )
    typeName match {
      case "Boolean" => Some(erased(booleanEvaluator))
      case "String"  => Some(erased(stringEvaluator))
      case "Int"     => Some(erased(intEvaluator))
      case "Long"    => Some(erased(longEvaluator))
      case "Float"   => Some(erased(floatEvaluator))
      case "Double"  => Some(erased(doubleEvaluator))
      case _         => None
    }
  }

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
  // `getLongEvaluation`, which every provider this library ships now does) or a loud TYPE_MISMATCH from the SDK's
  // double-backed default. It also makes SDK-level `LongHook`s and `FlagValueType.LONG` observable, which the
  // hand-rolled routing hid. A third-party provider that does NOT override `getLongEvaluation` can be wrapped in
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
