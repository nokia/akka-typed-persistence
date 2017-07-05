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

package com.nokia.ntp.ct
package persistence

import akka.{ typed => at }
import at.scaladsl.AskPattern._

class DeploymentSpec extends AbstractPersistenceSpec {

  import DeploymentSpec._

  "SelfDeployingBehavior" should "be able to start in an (untyped) ActorSystem" in {
    val name = "test1"
    val ref = child(name).deployInto(this.system, name)
    ref.?[Int](Num(42, _)).futureValue should === (43)
    ref.?[Int](Num(42, _)).futureValue should === (44)
    ref ! Stop
    awaitTermination(ref).futureValue

    val ref2 = child(name).deployInto(this.system, name)
    ref2.?[Int](Num(5, _)).futureValue should === (8)
    ref2.?[Int](Num(5, _)).futureValue should === (9)
    ref2 ! Stop
    awaitTermination(ref2).futureValue
  }

  it should "be able to start in an ActorContext (i.e., as a child actor)" in {
    val name = "test2"
    val ref = parent(name).deployInto(this.system, name)
    ref.?[Int](Num(42, _)).futureValue should === (43)
    ref.?[Int](Num(42, _)).futureValue should === (44)
    ref ! Stop
    awaitTermination(ref).futureValue

    val ref2 = parent(name).deployInto(this.system, name)
    ref2.?[Int](Num(9, _)).futureValue should === (12)
    ref2.?[Int](Num(9, _)).futureValue should === (13)
    ref2 ! Stop
    awaitTermination(ref2).futureValue
  }
}

object DeploymentSpec {

  sealed trait Mesg
  final case class Num(i: Int, replyTo: at.ActorRef[Int]) extends Mesg
  final case object Stop extends Mesg

  sealed trait ParentState
  final case object Uninitialized extends ParentState
  final case class Initialized(child: at.ActorRef[Mesg]) extends ParentState

  def parent(pid: PersistenceId) = PersistentActor.withRecovery[Mesg, at.ActorRef[Mesg], ParentState](
    initialState = Uninitialized,
    pid = _ => s"${pid}-parent",
    recovery = Recovery(
      (_, msg, ctx) => {
        val childRef = child(pid).deployInto(ctx)
        Initialized(childRef)
      }
    )
  ) { state => ctx => msg =>
      msg match {
        case Stop =>
          ctx.stop
        case n @ Num(_, _) =>
          state match {
            case Uninitialized =>
              val childRef = child(pid).deployInto(ctx.ctx)
              childRef ! n
              ctx.apply(childRef, sync = true)
            case Initialized(child) =>
              child ! n
              ctx.same
          }
      }
    }

  def child(pid: PersistenceId) = PersistentActor.immutable[Mesg, Unit, Int](
    initialState = 0,
    pid = _ => s"${pid}-child"
  ) { state => ctx =>
    {
      case Stop =>
        ctx.stop
      case Num(i, r) =>
        ctx.apply(()).map { state =>
          r ! i + state
          state
        }
    }
  }

  implicit val childUpdater: Update[Int, Unit] =
    Update.instance((s, _) => s + 1)

  implicit val parentUpdater: Update[ParentState, at.ActorRef[Mesg]] =
    Update.instance((_, e) => Initialized(e))
}
