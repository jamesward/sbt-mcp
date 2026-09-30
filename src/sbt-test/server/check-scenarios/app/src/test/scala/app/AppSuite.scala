package app

class AppSuite extends munit.FunSuite:
  test("run") {
    assertEquals(App.run, "hello world")
  }
