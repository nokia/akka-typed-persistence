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

import akka.event.LoggingAdapter
import akka.{ typed => at }
import akka.typed.ScalaDSL.MessageOrSignal

import cats.data.Xor
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

  /**
   * Extension methods on `Proc`
   */
  implicit class ProcOps[A](p: Proc[A]) {

    /** Error handling combinator (catch and reify) */
    def attempt: Proc[Xor[ProcException, A]] =
      Free.liftF[ProcA, Xor[ProcException, A]](ProcA.Attempt[A](p))

    /** Error handling combinator (catch) */
    def recover(f: PartialFunction[ProcException, A]): Proc[A] =
      recoverWith(f andThen ProcA.pure)

    /** Error handling combinator (catch with possible rethrow) */
    def recoverWith(f: PartialFunction[ProcException, Proc[A]]): Proc[A] = {
      attempt.flatMap {
        case Xor.Left(ex) => f.lift(ex) getOrElse ProcA.fail(ex)
        case Xor.Right(res) => ProcA.pure(res)
      }
    }
  }

  /** Factory for a `PersistentBehavior` */
  def Persistent[A, D, S]( // scalastyle:ignore
    initialState: S,
    pid: at.ActorContext[A] => PersistenceId
  )(
    f: S => PersistenceApi[A, D, S] => MessageOrSignal[A] => Proc[S]
  )(implicit m: Update[S, D]): PersistentBehavior[A, D, S] = {
    PersistentFull[A, D, S](initialState, pid, Recovery(m))(f)
  }

  /** Factory for a `PersistentBehavior` */
  def PersistentFull[A, D, S]( // scalastyle:ignore
    initialState: S,
    pid: at.ActorContext[A] => PersistenceId,
    recovery: Recovery[A, D, S]
  )(
    f: S => PersistenceApi[A, D, S] => MessageOrSignal[A] => Proc[S]
  ): PersistentBehavior[A, D, S] = {
    new PersistentBehaviorImpl[A, D, S](
      _ => initialState,
      pid,
      recovery,
      (s, api, ctx, msg) => f(s)(api)(msg)
    )
  }

  // Internal utilities:

  // TODO: move to tests
  private[persistence] implicit class ContextOps[A](ctx: at.ActorContext[A]) {
    def log: LoggingAdapter =
      ctx.system.log
  }

  private[persistence] final val unsafeCast =
    "org.wartremover.warts.AsInstanceOf"

  private[persistence] final val unsafeVar =
    "org.wartremover.warts.Var"

  private[persistence] def impossible(msg: => String): Nothing =
    throw new IllegalStateException(msg)

  /** fs2.Task is trampolined */
  private[persistence] implicit val taskIsStackSafe: cats.RecursiveTailRecM[fs2.Task] =
    cats.RecursiveTailRecM.create

  /** ActorRefs have a correct equals */
  private[persistence] implicit def actorRefEq[A]: cats.Eq[akka.typed.ActorRef[A]] =
    cats.Eq.fromUniversalEquals

  /** SnapshotMetadata has a correct equals */
  private[persistence] implicit val snapshotMetadataEq: cats.Eq[akka.persistence.SnapshotMetadata] =
    cats.Eq.fromUniversalEquals

  /** For creating typed ActorRef adapters */
  private[persistence] def actorRef[A](untyped: akka.actor.ActorRef): akka.typed.ActorRef[A] =
    akka.typed.adapter.actorRefAdapter(untyped)
}
