package zio.openfeature.contrib.optimizely

import zio.*
import zio.openfeature.*

/** Extensions for Optimizely's "flat flag" pattern.
  *
  * Optimizely feature flags are boolean by default. To support non-boolean values, Optimizely uses "variables" attached
  * to features. A common pattern is to use a single variable named "_" (underscore) to represent the primary value of a
  * flag.
  *
  * This module provides extension methods that make it easy to work with this pattern:
  *
  * {{{
  * import zio.openfeature.contrib.optimizely.*
  *
  * // Get a string value from a flag's "_" variable
  * val config = FeatureFlags.flatString("config-flag", "default")
  *
  * // Get an int value from a flag's "_" variable
  * val limit = FeatureFlags.flatInt("limit-flag", 100)
  * }}}
  */
object FlatFlags:

  /** Get a string value from a flag's "_" (underscore) variable.
    *
    * This is useful for Optimizely flags where the primary value is stored in a single variable named "_".
    *
    * @param key
    *   The flag key
    * @param default
    *   The default value if the flag is not found or has no "_" variable
    * @return
    *   The string value from the "_" variable, or the default
    */
  def flatString(key: String, default: String): ZIO[FeatureFlags, FeatureFlagError, String] =
    FeatureFlags.obj(key, Map("_" -> default)).map { map =>
      map.get("_").map(_.toString).getOrElse(default)
    }

  /** Get an int value from a flag's "_" (underscore) variable. */
  def flatInt(key: String, default: Int): ZIO[FeatureFlags, FeatureFlagError, Int] =
    FeatureFlags.obj(key, Map("_" -> default)).map { map =>
      map.get("_") match
        case Some(n: Number) => n.intValue()
        case Some(s: String) => scala.util.Try(s.toInt).getOrElse(default)
        case _               => default
    }

  /** Get a long value from a flag's "_" (underscore) variable. */
  def flatLong(key: String, default: Long): ZIO[FeatureFlags, FeatureFlagError, Long] =
    FeatureFlags.obj(key, Map("_" -> default)).map { map =>
      map.get("_") match
        case Some(n: Number) => n.longValue()
        case Some(s: String) => scala.util.Try(s.toLong).getOrElse(default)
        case _               => default
    }

  /** Get a double value from a flag's "_" (underscore) variable. */
  def flatDouble(key: String, default: Double): ZIO[FeatureFlags, FeatureFlagError, Double] =
    FeatureFlags.obj(key, Map("_" -> default)).map { map =>
      map.get("_") match
        case Some(n: Number) => n.doubleValue()
        case Some(s: String) => scala.util.Try(s.toDouble).getOrElse(default)
        case _               => default
    }

  /** Get a boolean value from a flag's "_" (underscore) variable. */
  def flatBoolean(key: String, default: Boolean): ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    FeatureFlags.obj(key, Map("_" -> default)).map { map =>
      map.get("_") match
        case Some(b: Boolean)  => b
        case Some(s: String)   => s.toLowerCase == "true"
        case Some(n: Number)   => n.intValue() != 0
        case _                 => default
    }

  /** Extension methods for FeatureFlags service to access flat flags. */
  extension (ff: FeatureFlags)
    /** Get a string value from a flag's "_" variable. */
    def flatString(key: String, default: String): IO[FeatureFlagError, String] =
      ff.obj(key, Map("_" -> default)).map { map =>
        map.get("_").map(_.toString).getOrElse(default)
      }

    /** Get an int value from a flag's "_" variable. */
    def flatInt(key: String, default: Int): IO[FeatureFlagError, Int] =
      ff.obj(key, Map("_" -> default)).map { map =>
        map.get("_") match
          case Some(n: Number) => n.intValue()
          case Some(s: String) => scala.util.Try(s.toInt).getOrElse(default)
          case _               => default
      }

    /** Get a long value from a flag's "_" variable. */
    def flatLong(key: String, default: Long): IO[FeatureFlagError, Long] =
      ff.obj(key, Map("_" -> default)).map { map =>
        map.get("_") match
          case Some(n: Number) => n.longValue()
          case Some(s: String) => scala.util.Try(s.toLong).getOrElse(default)
          case _               => default
      }

    /** Get a double value from a flag's "_" variable. */
    def flatDouble(key: String, default: Double): IO[FeatureFlagError, Double] =
      ff.obj(key, Map("_" -> default)).map { map =>
        map.get("_") match
          case Some(n: Number) => n.doubleValue()
          case Some(s: String) => scala.util.Try(s.toDouble).getOrElse(default)
          case _               => default
      }

    /** Get a boolean value from a flag's "_" variable. */
    def flatBoolean(key: String, default: Boolean): IO[FeatureFlagError, Boolean] =
      ff.obj(key, Map("_" -> default)).map { map =>
        map.get("_") match
          case Some(b: Boolean) => b
          case Some(s: String)  => s.toLowerCase == "true"
          case Some(n: Number)  => n.intValue() != 0
          case _                => default
      }
