package corpus

object Given12:
  trait Show[A]:
    def show(a: A): String
  def render[A](a: A)(using s: Show[A]): String = s.show(a)
  def use: String = render(42)
