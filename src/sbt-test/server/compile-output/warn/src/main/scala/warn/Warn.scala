package warn

object Warn:
  // Non-exhaustive match => Scala 3 emits a "match may not be exhaustive" warning,
  // but still compiles successfully.
  def f(o: Option[Int]): Int = o match
    case Some(x) => x
