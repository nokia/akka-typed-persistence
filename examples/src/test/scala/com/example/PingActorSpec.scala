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

package com.example

import scala.concurrent.duration._

import org.scalatest.{ FlatSpecLike, Matchers }
import org.scalatest.concurrent.ScalaFutures

import akka.actor.ActorSystem
import akka.actor.Scheduler
import akka.testkit.TestKit
import akka.typed.ActorRef
import akka.typed.AskPattern._
import akka.typed.adapter.actorRefAdapter
import akka.util.Timeout

class PingActorSpec extends TestKit(ActorSystem("pingSystem")) with FlatSpecLike with Matchers with ScalaFutures {

  import PingActor.MyMsg

  implicit val timeout: Timeout =
    Timeout(1.second)

  implicit val scheduler: Scheduler =
    this.system.scheduler

  val dummyRef: ActorRef[Any] =
    actorRefAdapter(this.testActor)

  "PingActor" should "reply with the number of pings it received so far" in {
    val ref: ActorRef[MyMsg] = PingActor.myBehavior.deployInto(this.system, "pingActor")
    ref.?[String](MyMsg("ping", _)).futureValue should be ("1 pings so far")
    ref ! MyMsg("foo", dummyRef)
    this.expectNoMsg()
    ref ! MyMsg("bar", dummyRef)
    this.expectNoMsg()
    ref.?[String](MyMsg("ping", _)).futureValue should be ("2 pings so far")
    ref.?[String](MyMsg("stop", _)).futureValue should be ("OK")
    val ref2: ActorRef[MyMsg] = PingActor.myBehavior.deployInto(this.system, "pingActor")
    ref2.?[String](MyMsg("ping", _)).futureValue should be ("3 pings so far")
  }
}
