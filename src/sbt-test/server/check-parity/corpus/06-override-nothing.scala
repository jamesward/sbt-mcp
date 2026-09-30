package corpus

trait Base06:
  def foo: Int

class Impl06 extends Base06:
  def foo = 1
  override def bar = 2
