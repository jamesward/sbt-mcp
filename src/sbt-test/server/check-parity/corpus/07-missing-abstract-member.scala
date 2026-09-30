package corpus

trait Base07:
  def foo: Int
  def bar(x: String): String

class Impl07 extends Base07:
  def foo = 1
