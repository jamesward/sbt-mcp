package corpus

object Inline08:
  inline def fail(): Unit = scala.compiletime.error("inline failure reported at the call site")
  def use(): Unit = fail()
