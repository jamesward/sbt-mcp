package fail

object Fail:
  // Type mismatch => compile error with a "Found: String / Required: Int" message.
  val x: Int = "not an int"
