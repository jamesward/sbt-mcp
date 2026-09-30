package macros

import scala.quoted.*

object Macros:
  /** Compile-time validated literal: an empty string is a compile error at the call site. */
  inline def nonEmpty(inline s: String): String = ${ nonEmptyImpl('s) }

  private def nonEmptyImpl(s: Expr[String])(using Quotes): Expr[String] =
    import quotes.reflect.*
    s.value match
      case Some("") => report.errorAndAbort("string literal must not be empty", s)
      case _        => s
