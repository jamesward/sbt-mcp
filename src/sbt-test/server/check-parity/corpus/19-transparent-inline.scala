package corpus

object Transparent19:
  transparent inline def pick(inline b: Boolean) = inline if b then 1 else "s"
  val n: Int    = pick(false)
  val m: String = pick(true)
