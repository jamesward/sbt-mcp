package example

/** A trivial app so the sbt-mcp TASTy tools have real symbols to find. */
object Main:
  def main(args: Array[String]): Unit =
    println(greeting("world"))

  def greeting(name: String): String = s"Hello, $name!"

final case class Widget(id: Int, name: String):
  def label: String = s"#$id $name"

trait Greeter:
  def greet(who: String): String
