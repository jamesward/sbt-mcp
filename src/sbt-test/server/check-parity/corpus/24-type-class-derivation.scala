package corpus

import scala.deriving.Mirror

object Derivation24:
  inline def fieldCount[A](using m: Mirror.ProductOf[A]): Int =
    scala.compiletime.constValue[Tuple.Size[m.MirroredElemTypes]]
  case class P(a: Int, b: String)
  class NotACaseClass
  val ok: Int  = fieldCount[P]
  val bad: Int = fieldCount[NotACaseClass]
