package corpus

object Deprecated11:
  @deprecated("use newer", "1.0") def old: Int = 1
  def use: Int = old + 1
