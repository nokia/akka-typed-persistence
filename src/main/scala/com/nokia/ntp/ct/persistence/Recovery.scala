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

import akka.typed.ActorContext

/**
 * Recovery behavior definition of a persistent actor
 *
 * @param folder Computes the next state from the
 *        current state and a persisted event.
 * @param failure Handler which will be invoked
 *        in case of recovery failure (the actor
 *        will be stopped after calling the callback).
 * @param completed Handler which will be invoked
 *        at the end of recovery (can change the state
 *        one last time before starting regular operation).
 */
final case class Recovery[A, D, S](
  recovery: (S, D, ActorContext[A]) => S,
  failure: (S, Throwable, ActorContext[A]) => Unit = { (s: S, ex: Throwable, ctx: ActorContext[A]) => () },
  completed: (S, ActorContext[A]) => S = { (s: S, ctx: ActorContext[A]) => s }
)

object Recovery {
  def apply[A, D, S](m: Update[S, D]): Recovery[A, D, S] =
    Recovery((s, e, _) => m.update(s, e))
}
