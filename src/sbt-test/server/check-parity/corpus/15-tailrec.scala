package corpus

object TailRec15:
  @annotation.tailrec
  def f(n: Int): Int = if n == 0 then 0 else 1 + f(n - 1)
