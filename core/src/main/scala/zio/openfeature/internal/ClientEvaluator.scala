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

  // Long uses Double SDK method (exact integers up to 2^53)
  implicit val longEvaluator: ClientEvaluator[Long] = new ClientEvaluator[Long] {
    def evaluate(
      client: OFClient,
      key: String,
      default: Long,
      context: dev.openfeature.sdk.EvaluationContext
    ): Task[FlagEvaluationDetails[_]] =
      ZIO.attemptBlocking(
        client.getDoubleDetails(key, java.lang.Double.valueOf(default.toDouble), context)
      )

    def extractValue(details: FlagEvaluationDetails[_]): Long =
      details.getValue.asInstanceOf[java.lang.Double].longValue()
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
