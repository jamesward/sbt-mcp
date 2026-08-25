package example

object Main:
  def main(args: Array[String]): Unit = println(greeting("world"))
  def greeting(name: String): String = s"Hello, $name!"

final case class Widget(id: Int, name: String):
  def label: String = s"#$id $name"
