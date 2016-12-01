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

/**
 * Type class for abstracting over the
 * operation of updating the managed state
 * of a persistent actor with an event.
 *
 * And instance of this type class is
 * required to use PersistenceApi#apply.
 * For a few common types, instances are
 * provided in the `Update` companion object;
 * for all other types, the user can define
 * their own (e.g., in the companion object
 * of their `S` state type).
 */
trait Update[S, E] {

  /**
   * Computes the next state
   * from the current state and
   * an event.
   */
  def update(state: S, event: E): S
}

object Update {

  def apply[S, E](implicit inst: Update[S, E]): Update[S, E] =
    inst

  def instance[S, E](f: (S, E) => S): Update[S, E] = new Update[S, E] {
    override def update(state: S, event: E): S =
      f(state, event)
  }

  implicit def managedUpdaterForMap[K, V]: Update[Map[K, V], (K, V)] =
    instance { (s, e) => s.updated(e._1, e._2) }

  implicit def managedUpdaterForList[A]: Update[List[A], A] =
    instance { (s, e) => e :: s }

  implicit def managedUpdaterForVector[A]: Update[Vector[A], A] =
    instance { (s, e) => s :+ e }
}
