/*
 * Copyright 2016-2017 Nokia Solutions and Networks Oy
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

package com.example

import akka.typed.ActorRef

import com.nokia.ntp.ct.persistence.{ PersistentActor, Update }

object PingActor {

  final case class MyMsg(s: String, replyTo: ActorRef[String])

  final case class Increment(amount: Int)

  object Increment {
    implicit val updater: Update[Int, Increment] =
      Update.instance(_ + _.amount)
  }

  val myBehavior = PersistentActor.immutable[MyMsg, Increment, Int](
    0,
    _.self.path.name
  ) { state => ctx => msg =>
      msg match {
        case MyMsg("ping", r) =>
          for {
            state <- ctx.apply(Increment(1))
          } yield {
            r ! s"${state} pings so far"
            state
          }
        case MyMsg("stop", r) =>
          r ! "OK"
          ctx.stop
        case MyMsg(_, _) =>
          ctx.same
      }
    }
}
