package corpus

object Unreachable17:
  def f(x: Int): String = x match
    case _: Int => "any"
    case 1      => "one"
