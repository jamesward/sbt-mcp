package corpus

object OldSyntax22 {
  def f(xs: List[Int]) = xs.map { x => x * 2 }
  def g = for { x <- List(1, 2) } yield x
  def h(s: String) = s match { case _ => () }
  def wildcard: List[_] = Nil
  implicit val ord: Ordering[Int] = Ordering.Int
}
