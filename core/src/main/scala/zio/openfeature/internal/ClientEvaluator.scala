package zio.openfeature.internal

import zio._
import zio.openfeature._
import dev.openfeature.sdk.{Client => OFClient, EvaluationContext => OFEvaluationContext, FlagEvaluationDetails}

/** Type-safe dispatch for OpenFeature client flag evaluation.
  *
  * Each instance knows how to call the correct Java SDK method for a specific flag type and extract the result value
  * with proper type conversions, eliminating stringly-typed dispatch and unsafe casts.
  */
private[openfeature] trait ClientEvaluator[A] {

  /** Evaluate a flag using the appropriate typed method on the Java SDK client. */
  def evaluate(
    client: OFClient,
    key: String,
    default: A,
    context: OFEvaluationContext
  ): Task[FlagEvaluationDetails[_]]

  /** Extract the typed value from raw evaluation details. */
  def extractValue(details: FlagEvaluationDetails[_]): A
}

private[openfeature] object ClientEvaluator {

  val boolean: ClientEvaluator[Boolean] = new ClientEvaluator[Boolean] {
    def evaluate(client: OFClient, key: String, default: Boolean, ctx: OFEvaluationContext) =
      ZIO.attemptBlocking(client.getBooleanDetails(key, default, ctx))

    def extractValue(d: FlagEvaluationDetails[_]) =
      d.getValue.asInstanceOf[java.lang.Boolean].booleanValue()
  }

  val string: ClientEvaluator[String] = new ClientEvaluator[String] {
    def evaluate(client: OFClient, key: String, default: String, ctx: OFEvaluationContext) =
      ZIO.attemptBlocking(client.getStringDetails(key, default, ctx))

    def extractValue(d: FlagEvaluationDetails[_]) =
      d.getValue.asInstanceOf[String]
  }

  val int: ClientEvaluator[Int] = new ClientEvaluator[Int] {
    def evaluate(client: OFClient, key: String, default: Int, ctx: OFEvaluationContext) =
      ZIO.attemptBlocking(client.getIntegerDetails(key, Integer.valueOf(default), ctx))

    def extractValue(d: FlagEvaluationDetails[_]) =
      d.getValue.asInstanceOf[java.lang.Integer].intValue()
  }

  val long: ClientEvaluator[Long] = new ClientEvaluator[Long] {
    def evaluate(client: OFClient, key: String, default: Long, ctx: OFEvaluationContext) =
      ZIO.attemptBlocking(client.getIntegerDetails(key, Integer.valueOf(default.toInt), ctx))

    def extractValue(d: FlagEvaluationDetails[_]) =
      d.getValue.asInstanceOf[java.lang.Integer].longValue()
  }

  val float: ClientEvaluator[Float] = new ClientEvaluator[Float] {
    def evaluate(client: OFClient, key: String, default: Float, ctx: OFEvaluationContext) =
      ZIO.attemptBlocking(
        client.getDoubleDetails(key, java.lang.Double.valueOf(default.toDouble), ctx)
      )

    def extractValue(d: FlagEvaluationDetails[_]) =
      d.getValue.asInstanceOf[java.lang.Double].floatValue()
  }

  val double: ClientEvaluator[Double] = new ClientEvaluator[Double] {
    def evaluate(client: OFClient, key: String, default: Double, ctx: OFEvaluationContext) =
      ZIO.attemptBlocking(client.getDoubleDetails(key, java.lang.Double.valueOf(default), ctx))

    def extractValue(d: FlagEvaluationDetails[_]) =
      d.getValue.asInstanceOf[java.lang.Double].doubleValue()
  }

  /** Look up the appropriate evaluator for a given FlagType. Returns None for Object and custom types which require
    * special handling.
    */
  def forType[A](flagType: FlagType[A]): Option[ClientEvaluator[A]] = {
    val evaluator: Option[ClientEvaluator[_]] = flagType.typeName match {
      case "Boolean" => Some(boolean)
      case "String"  => Some(string)
      case "Int"     => Some(int)
      case "Long"    => Some(long)
      case "Float"   => Some(float)
      case "Double"  => Some(double)
      case _         => None
    }
    evaluator.map(_.asInstanceOf[ClientEvaluator[A]])
  }
}
