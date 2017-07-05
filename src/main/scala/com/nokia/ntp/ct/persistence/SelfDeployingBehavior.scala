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

import shapeless.tag.@@

import akka.{ actor => au, typed => at }

/**
 * Behavior, which must be started in a specific way.
 * To make this easier, it provides a pre-configured
 * `Props` instance, as well as a `deployInto` method.
 */
trait SelfDeployingBehavior[A] {

  private[this] val tagger = shapeless.tag[A]

  private[this] lazy val cachedProps =
    tagger(untypedProps)

  def props: au.Props @@ A =
    cachedProps

  def deployInto[C: ActorDeployer](ctx: C): at.ActorRef[A] =
    ActorDeployer[C].deployAnonymous(ctx)(props)

  def deployInto[C: ActorDeployer](ctx: C, name: String): at.ActorRef[A] =
    ActorDeployer[C].deploy(ctx)(props, name)

  protected[ct] def untypedProps: au.Props
}
