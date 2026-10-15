package core

import zio.test.*

object GreeterSpec extends ZIOSpecDefault:
  def spec = suite("GreeterSpec")(
    test("greets by name")(assertTrue(Greeter.greet("Ada") == "Hello, Ada")),
    test("keeps the comma")(assertTrue(Greeter.greet("x").contains(","))),
  )
