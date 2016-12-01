/*
 * Copyright 2016 Nokia Solutions and Networks Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import sbt._
import sbt.Keys._
import org.scalastyle.sbt.ScalastylePlugin.scalastyle

object Utils {

  def compileWithScalastyle: Def.Setting[_] =
    compileWithScalastyle(Compile)

  def compileWithScalastyle(cfg: Configuration): Def.Setting[_] = {
    (compile in cfg) := seq(
      (compile in cfg),
      (scalastyle in cfg).toTask("")
    ).value
  }

  private def seq[A, B](first: Def.Initialize[Task[A]], second: Def.Initialize[Task[B]]): Def.Initialize[Task[A]] = {
    Def.taskDyn {
      val r = first.value
      sec[A, B](r, second)
    }
  }

  private def sec[A, B](res: A, second: Def.Initialize[Task[B]]): Def.Initialize[Task[A]] = {
    Def.task {
      val _ = second.value
      res
    }
  }
}
