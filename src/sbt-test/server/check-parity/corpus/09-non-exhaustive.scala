package corpus

object Exhaustive09:
  enum Color:
    case Red, Green, Blue

  def name(c: Color): String = c match
    case Color.Red   => "r"
    case Color.Green => "g"
