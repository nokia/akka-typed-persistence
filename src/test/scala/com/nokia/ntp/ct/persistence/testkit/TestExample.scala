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

package com.nokia.ntp.ct
package persistence
package testkit

import scala.util.Try

import org.scalatest.FlatSpecLike

import akka.testkit.TestKit
import akka.typed._
import akka.typed.ScalaDSL._

import cats.implicits._

class TestExample extends TestKit(akka.actor.ActorSystem()) with FlatSpecLike { spec =>

  sealed trait MyMsg
  case class Add(n: Int, replyTo: ActorRef[Long]) extends MyMsg
  case object Snap extends MyMsg
  case object Stop extends MyMsg

  sealed trait MyEv
  case class Incr(amount: Int) extends MyEv

  sealed case class MyState(ctr: Long) {
    def update(ev: MyEv): MyState = ev match {
      case Incr(n) => this.copy(ctr = ctr + n)
    }
  }

  object MyState {
    implicit val mngd: Update[MyState, MyEv] =
      Update.instance(_ update _)
  }

  val name = "TestExample"

  val b = Persistent[MyMsg, MyEv, MyState](
    MyState(ctr = 0),
    _ => name
  ) { state => p => {
      case Msg(_, Add(n, r)) =>
        for {
          st <- p.apply(Incr(n))
        } yield {
          r ! st.ctr
          st
        }
      case Msg(_, Snap) =>
        p.snapshot
      case Msg(_, Stop) =>
        p.stop
      case Sig(_, _) =>
        p.same
    }
    }

  val ti = new TestInterpreter(name, b, ActorSystem.wrap(this.system)) {
    override def assert(b: Boolean, msg: String = ""): Try[Unit] =
      Try(spec.assert(b, msg))
    override def fail(msg: String): Nothing =
      spec.fail(msg)
  }

  "It" should "work" in {
    ti.check(for {
      _ <- ti.expect[Long](Add(3, _), 3)
      _ <- ti.expect[Long](Add(2, _), 5)
      _ <- ti.expectSt(_.ctr, 5L)
      _ <- ti.message(Stop)
      _ <- ti.expectStop
    } yield ())
  }
}
