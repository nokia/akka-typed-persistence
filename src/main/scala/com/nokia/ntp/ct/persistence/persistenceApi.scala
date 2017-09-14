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

import akka.{ persistence => ap, typed => at }
import cats.free.Free

import scala.concurrent.Future

/**
 * `Proc` primitives and combinators.
 * (See also `ProcOps`.)
 */
sealed trait PersistenceApi[A, D, S] {

  /**
   * Persists the event `e`, and updates the managed
   * state after the persistence succeeds. Returns
   * the updated state.
   *
   * @param e The event to persist and use to change the state.
   * @param sync If true, it is guaranteed
   *             that no other messages are handled
   *             before the event is persisted.
   * @param m The type class instance to update the state with.
   */
  def apply(e: D, sync: Boolean = false)(implicit m: Update[S, D]): Proc[S] =
    persist(e, sync = sync).flatMap(s => change(m.update(s, e)))

  /**
   * A `Proc` which persists the specified event,
   * and returns the managed state AFTER the
   * persistence successfully completed.
   *
   * @param e The event to persist
   * @param sync If true, it is guaranteed
   *             that no other messages are handled
   *             before the event is persisted.
   *
   * @note This is rarely needed, see `apply`
   *       for the common case.
   */
  def persist(e: D, sync: Boolean = false): Proc[S] =
    Free.liftF[ProcA, S](ProcA.Persist[D, S](e, async = !sync))

  /**
   * A `Proc` which takes a snapshot,
   * and returns the managed state AFTER the
   * snapshot successfully completed.
   */
  def snapshot: Proc[S] =
    Free.liftF[ProcA, S](ProcA.Snapshot[S]())

  /**
   * A `Proc` which changes the managed state to
   * the specified state immediately.
   *
   * @note This is rarely needed, see `apply`
   *       for the common case.
   */
  def change(state: S): Proc[S] =
    Free.liftF[ProcA, S](ProcA.Change[S](state))

  /**
   * Returns the sequence number
   * of the last written (or replayed)
   * event.
   */
  def lastSequenceNr: Proc[Long] =
    Free.liftF[ProcA, Long](ProcA.SeqNr)

  /**
   * A `Proc` which changes the managed state to
   * the stopped state.
   */
  def stop: Proc[S] =
    Free.liftF[ProcA, S](ProcA.Stop[S]())

  /**
   * A `Proc` which returns the current
   * managed state.
   */
  def same: Proc[S] =
    Free.liftF[ProcA, S](ProcA.Same[S]())

  /**
   * A `Proc` which fails with the specified exception.
   */
  def fail[X](ex: ProcException): Proc[X] =
    ProcA.fail(ex)

  /**
   * A `Proc` which returns the specified value.
   */
  def pure[X](x: X): Proc[X] =
    ProcA.pure(x)

  /**
   * A `Proc` which accepts a `Future[X]` and returns `X`
   */
  def future[X](fut: Future[X]): Proc[X] =
    Free.liftF[ProcA, X](ProcA.FromFuture[X](fut))

  /**
   * The context of the persistent actor.
   */
  def ctx: at.scaladsl.ActorContext[A]
}

private final class PersistenceApiImpl[A, D, S](override val ctx: at.scaladsl.ActorContext[A])
  extends PersistenceApi[A, D, S]

/** Possible exceptions during a persistence process */
sealed abstract class ProcException(cause: Throwable)
    extends RuntimeException(cause) {
  def cause: Throwable
}

/** Possible exceptions during persisting an event/snapshot */
sealed abstract class PersistenceException(cause: Throwable, seqNr: Long)
    extends ProcException(cause) {
  final def sequenceNr: Long = seqNr
}

/** Failed to persist an event; the actor will be automatically stopped. */
final case class PersistFailure(cause: Throwable, seqNr: Long)
  extends PersistenceException(cause, seqNr)

/** The persistence plugin rejected to persist an event. */
final case class PersistRejected(cause: Throwable, seqNr: Long)
  extends PersistenceException(cause, seqNr)

/** Failed to save a snapshot. */
final case class SnapshotFailure(cause: Throwable, metadata: ap.SnapshotMetadata)
  extends PersistenceException(cause, metadata.sequenceNr)

/** Other wrapped exception */
final case class UnexpectedException(cause: Throwable)
  extends ProcException(cause)

// TODO: add a user definable ProcException subclass

/** INTERNAL API: don't use directly, see `Proc` instead */
sealed trait ProcA[A]

/** INTERNAL API: don't use directly, see `Proc` instead */
object ProcA {

  /**
   * Extension methods on `Proc`.
   */
  implicit class ProcOps[A](p: Proc[A]) {

    /** Error handling combinator (catch and reify) */
    def attempt: Proc[Either[ProcException, A]] =
      Free.liftF[ProcA, Either[ProcException, A]](ProcA.Attempt[A](p))

    /** Error handling combinator (catch) */
    def recover(f: PartialFunction[ProcException, A]): Proc[A] =
      recoverWith(f andThen ProcA.pure)

    /** Error handling combinator (catch with possible rethrow) */
    def recoverWith(f: PartialFunction[ProcException, Proc[A]]): Proc[A] = {
      attempt.flatMap {
        case Left(ex) => f.lift(ex) getOrElse ProcA.fail(ex)
        case Right(res) => ProcA.pure(res)
      }
    }
  }

  private[persistence] final case class Persist[D, S](data: D, async: Boolean) extends ProcA[S]
  private[persistence] final case class Snapshot[S]() extends ProcA[S]
  private[persistence] final case class Change[S](state: S) extends ProcA[S]
  private[persistence] final case object SeqNr extends ProcA[Long]
  private[persistence] final case class Same[S]() extends ProcA[S]
  private[persistence] final case class Stop[S]() extends ProcA[S]
  private[persistence] final case class Attempt[A](proc: Proc[A]) extends ProcA[Either[ProcException, A]]
  private[persistence] final case class Fail[A](ex: ProcException) extends ProcA[A]
  private[persistence] final case class FromFuture[A](fut: Future[A]) extends ProcA[A]

  private[persistence] def same[X]: Proc[X] =
    Free.liftF[ProcA, X](Same[X]())

  private[persistence] def pure[X](x: X): Proc[X] =
    Free.pure(x)

  private[persistence] def fail[X](ex: ProcException): Proc[X] =
    Free.liftF[ProcA, X](ProcA.Fail[X](ex))
}
