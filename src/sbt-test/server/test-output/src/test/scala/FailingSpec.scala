import zio.test.*

object FailingSpec extends ZIOSpecDefault:
  def spec = suite("FailingSpec")(
    test("passes")(assertTrue(1 == 1)),
    test("compares words") {
      val actual = "beta"
      assertTrue(actual == "alpha")
    },
  )
