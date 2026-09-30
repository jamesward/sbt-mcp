package corpus

import java.util.concurrent.ConcurrentHashMap

object Java23:
  val map = new ConcurrentHashMap[String, Int]()
  def put(): Int = map.put("a", "not an int")
