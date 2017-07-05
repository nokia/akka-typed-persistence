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

import akka.{ actor => au, typed => at }
import akka.typed.scaladsl.ActorContext
import akka.typed.scaladsl.adapter._

import shapeless.tag.@@

/**
 * Type class for abstracting over
 * things which can start actors
 * (e.g., ActorSystems or ActorContexts).
 */
trait ActorDeployer[D] {

  def deploy[A](d: D)(props: au.Props @@ A, name: String): at.ActorRef[A] =
    actorRef[A](deployUntyped(d)(props, name))

  def deployAnonymous[A](d: D)(props: au.Props @@ A): at.ActorRef[A] =
    actorRef[A](deployUntypedAnonymous(d)(props))

  protected def deployUntyped(d: D)(props: au.Props, name: String): au.ActorRef

  protected def deployUntypedAnonymous(d: D)(props: au.Props): au.ActorRef
}

object ActorDeployer {

  def apply[A](implicit ev: ActorDeployer[A]): ActorDeployer[A] = ev

  implicit val untypedActorSystemDeployerInstance: ActorDeployer[au.ActorSystem] = new ActorDeployer[au.ActorSystem] {
    override def deployUntyped(d: au.ActorSystem)(props: au.Props, name: String): au.ActorRef =
      d.actorOf(props, name)
    override def deployUntypedAnonymous(d: au.ActorSystem)(props: au.Props): au.ActorRef =
      d.actorOf(props)
  }

  implicit val untypedActorContextDeployer: ActorDeployer[au.ActorContext] = new ActorDeployer[au.ActorContext] {
    override def deployUntyped(d: au.ActorContext)(props: au.Props, name: String): au.ActorRef =
      d.actorOf(props, name)
    override def deployUntypedAnonymous(d: au.ActorContext)(props: au.Props): au.ActorRef =
      d.actorOf(props)
  }

  implicit def typedActorContextDeployerInstance[A]: ActorDeployer[ActorContext[A]] = new ActorDeployer[ActorContext[A]] {

    override def deployUntyped(d: ActorContext[A])(props: au.Props, name: String): au.ActorRef =
      d.actorOf(props, name)

    override def deployUntypedAnonymous(d: ActorContext[A])(props: au.Props): au.ActorRef =
      d.actorOf(props)
  }
}
