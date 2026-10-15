package app

object Main:
  def main(args: Array[String]): Unit =
    println(core.Greeter.greet("app") + " args=" + args.mkString("|"))
