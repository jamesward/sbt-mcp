package app

import core.CoreApi
import macros.Macros

object App:
  def run: String = CoreApi.greet(Macros.nonEmpty("world"))
