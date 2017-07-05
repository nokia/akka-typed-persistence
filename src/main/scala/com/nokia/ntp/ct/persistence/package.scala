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

import akka.{ typed => at }

import cats.free.Free

/**
 * Persistence API for akka-typed actors
 */
package object persistence {

  /** An actor ID, which should be stable between different incarnations */
  type PersistenceId = String

  /**
   * The description of a "persistence process".
   * For the possible commands, see `ProcOps`. Note, that
   * this is a monad, so commands can be chained with flatMap.
   */
  type Proc[A] = Free[ProcA, A]

  // Internal utilities:

  private[persistence] final val unsafeCast =
    "org.wartremover.warts.AsInstanceOf"

  private[persistence] final val unsafeVar =
    "org.wartremover.warts.Var"

  private[persistence] def impossible(msg: => String): Nothing =
    throw new IllegalStateException(msg)

  /** ActorRefs have a correct equals */
  private[persistence] implicit def actorRefEq[A]: cats.Eq[akka.typed.ActorRef[A]] =
    cats.Eq.fromUniversalEquals

  /** SnapshotMetadata has a correct equals */
  private[persistence] implicit val snapshotMetadataEq: cats.Eq[akka.persistence.SnapshotMetadata] =
    cats.Eq.fromUniversalEquals

  /** For creating typed ActorRef adapters */
  private[persistence] def actorRef[A](untyped: akka.actor.ActorRef): at.ActorRef[A] =
    at.scaladsl.adapter.actorRefAdapter(untyped)
}
