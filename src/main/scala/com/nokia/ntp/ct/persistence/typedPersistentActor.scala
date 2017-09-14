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

import scala.util.control.ControlThrowable

import akka.{ actor => au, persistence => ap, typed => at }
import akka.typed.scaladsl.Actor

import cats.~>

import fs2.{ Strategy, Task }
import fs2.interop.cats._

import scala.concurrent.ExecutionContext

/**
 * Combined `PersistentActor` and `ActorAdapter`.
 */
private abstract class PersistentActorAdapter[A](initialBehavior: at.Behavior[A])
    extends PublicActorAdapter[A](initialBehavior)
    with akka.persistence.PersistentActor
    with au.ActorLogging {

  protected[this] val (debug, autoDelete) = {
    val settings = PersistenceSettings(this.context.system)
    (settings.debug, settings.autoDelete)
  }

  override def receive: PartialFunction[Any, Unit] =
    super[PersistentActor].receive

  override def receiveCommand: PartialFunction[Any, Unit] =
    super[PublicActorAdapter].receive

  override def receiveRecover: PartialFunction[Any, Unit]

  protected[this] def debug(msg: => String): Unit = {
    if (this.debug) {
      log.debug(msg)
    }
  }
}

/** Special actor adapter able to run a `PersistentBehavior`. */
private final class TypedPersistentActor[A, D, S](
    b: PersistentActor[A, D, S]
) extends PersistentActorAdapter[A](Actor.deferred { ctx => b.undefer(ctx) }) { actor =>

  import TypedPersistentActor._

  private[this] lazy val cachedPid =
    this.currentBehavior.persistenceId(this.ctx)

  private[this] lazy val recoveryHandler: Recovery[A, D, S] =
    this.currentBehavior.recovery

  override def persistenceId: String =
    cachedPid

  override def receiveCommand: PartialFunction[Any, Unit] = {
    case cmd: Any if this.handleCommand.isDefinedAt(cmd) =>
      this.handleCommand(cmd)
      fixupBehavior()
    case sm @ (au.Terminated(_) | au.ReceiveTimeout) =>
      super.receiveCommand(sm)
      fixupBehavior()
    case x: Any =>
      super.receiveCommand(x)
      fixupBehavior()
  }

  override def receiveRecover: PartialFunction[Any, Unit] = {
    case ap.RecoveryCompleted =>
      log.debug("Recovery completed")
      handleRecovery(RecoveryCompleted)
    case ap.SnapshotOffer(m, s) =>
      debug(s"Snapshot offer: $m")
      handleRecovery(SnapshotOffer(s.asInstanceOf[S], m))
    case d: Any =>
      debug(s"Recovery offer: $d")
      handleRecovery(RecoveryOffer(d.asInstanceOf[D]))
  }

  private[this] def handleCommand: PartialFunction[Any, Unit] = {
    case ap.SaveSnapshotSuccess(m) =>
      handleSnapshot(m, err = None)
    case ap.SaveSnapshotFailure(m, ex) =>
      handleSnapshot(m, err = Some(ex))
    case ap.DeleteSnapshotsSuccess(crit) =>
      debug(s"Successfully deleted snapshots corresponding to ${crit}")
    case ap.DeleteSnapshotsFailure(crit, ex) =>
      log.warning(s"Failed to delete snapshots (${crit}, due to ${ex.getClass}: ${ex.getMessage}), ignoring")
    case ap.DeleteMessagesSuccess(seqNr) =>
      debug(s"Successfully deleted events up to (and including) seqNr ${seqNr}")
    case ap.DeleteMessagesFailure(ex, seqNr) =>
      log.warning(s"Failed to delete events up to (and including) seqNr ${seqNr} (due to ${ex.getClass}: ${ex.getMessage}), ignoring")
  }

  private[this] def handleRecovery(rev: RecoveryEvent[D, S]): Unit = rev match {
    case RecoveryOffer(d) =>
      this.changeState(recoveryHandler.recovery(currentState, d, this.ctx))
    case SnapshotOffer(s, _) =>
      this.changeState(s)
    case RecoveryCompleted =>
      this.changeState(recoveryHandler.completed(currentState, this.ctx))
    case RecoveryFailure(ex) =>
      recoveryHandler.failure(currentState, ex, this.ctx)
  }

  private[this] def handleSnapshot(m: ap.SnapshotMetadata, err: Option[Throwable]): Unit = {
    debug("Taking snapshot ended, retrieving callback ...")
    val callback = this.snapshotTaskCallbacks.removeFirst()
    debug(s"Retrieved snapshot callback: ${callback}")
    err.fold {
      log.debug(s"Snapshot success (${m})")
      if (autoDelete) {
        // We have a correct snapshot at `m.sequenceNr`,
        // so we can delete every older snapshot, and every
        // event which is not newer than that (we wouldn't
        // encounter these during recovery anyway, since
        // replaying starts from the newest snapshot).
        debug(
          s"Successful snapshot at sequenceNr ${m.sequenceNr}, " +
            "deleting older snapshots and not newer events (auto-delete is true)"
        )
        deleteSnapshots(ap.SnapshotSelectionCriteria(maxSequenceNr = m.sequenceNr - 1))
        deleteMessages(m.sequenceNr)
      }

      debug("Calling callback after successful snapshot")
      callback(Right(()))
    } { ex =>
      log.warning(s"Snapshot failure of ${m} with ${ex.getClass}: ${ex.getMessage}")
      callback(Left(SnapshotFailure(ex, m)))
    }
  }

  private[this] def changeBehavior(next: at.Behavior[A]): Unit = {
    this.behavior = next
    this.fixupBehavior()
  }

  // TODO: clean this up (we're mutating left and right)
  private[this] def fixupBehavior(): Unit = {
    if (at.Behavior.isAlive(this.behavior)) {
      this.behavior = this.canonicalize(this.behavior)
    }
    if (!at.Behavior.isAlive(this.behavior)) {
      this.context.stop(this.self)
    }
  }

  override def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit = {
    super.onRecoveryFailure(cause, event)
    handleRecovery(RecoveryFailure(cause))
  }

  // FIXME: supervisorStrategy???

  override def preStart(): Unit = {
    super.preStart()
    fixupBehavior()

    // Force initializing recovery; make sure behavior is undeferred correctly:
    val _ = this.currentBehavior
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    fixupBehavior()
    super.preRestart(reason, message)
    fixupBehavior()
  }

  override def postRestart(reason: Throwable): Unit = {
    fixupBehavior()
    super.postRestart(reason)
    fixupBehavior()
  }

  override def postStop(): Unit = {
    fixupBehavior()
    super.postStop()
    fixupBehavior()
  }

  private[this] def canonicalize(next: at.Behavior[A]): at.Behavior[A] = {
    at.Behavior.canonicalize(next, this.behavior, this.ctx) match {
      case w: PersistentActor.Wrap[A, D, S] =>
        // It's a wrapped Proc, we'll have to execute it.
        // We'll handle both the intermediate (if any),
        // and the final result with this:
        def handleResult(r: Either[Throwable, S], previous: at.Behavior[A]): Unit = r match {
          case Left(_: ActorStop) =>
            log.debug(s"Stop requested, stopping the actor")
            this.behavior = Actor.stopped(previous)
            // if called asynchronously, the above
            // is not enough, we must call stop:
            this.context.stop(this.self)
          case Left(_: PersistFailure) =>
            // ignore, the actor will stop anyway
            log.error(s"Persist failure, the actor will be stopped")
          case Left(ex) =>
            log.error(ex, "Interpretation completed with an error")
            throw ex
          case Right(nextState) =>
            debug(s"Changing state to $nextState")
            this.changeState(nextState)
        }

        debug(s"Starting to interpret ${w.proc}")
        val task = interpret(w.proc)
        task.unsafeRunAsync { r =>
          handleResult(r, w.current)
        }

        // If it's not really async, handleResult
        // was already called, and already set the
        // correct behavior. Otherwise, we don't yet
        // have the next behavior, so we remain at
        // the current one.
        if (at.Behavior.isAlive(this.behavior)) {
          this.currentBehavior
        } else {
          // we already stopped
          this.behavior
        }
      case x: Any =>
        x
    }
  }

  private[this] def changeState(newState: S): Unit = {
    val next = this.currentBehavior.withState(this.ctx, newState)
    this.changeBehavior(next)
  }

  private[this] def currentState: S =
    this.currentBehavior.state(this.ctx)

  private[this] def currentBehavior: PersistentActor.PersistentActorImpl[A, D, S] = {
    this.behavior = at.Behavior.undefer(this.behavior, this.ctx)
    this.behavior match {
      case w: PersistentActor.Wrap[A, D, S] =>
        w.current
      case pb: PersistentActor[A, D, S] =>
        pb match {
          case pbi: PersistentActor.PersistentActorImpl[A, D, S] =>
            pbi
          // NB: it cannot be a DeferredPersistentBehavior, since it's an at.Behavior
        }
      case b: Any if !at.Behavior.isAlive(b) =>
        impossible("the actor already stopped")
      case x: Any =>
        impossible(s"PersistentBehavior transitioned into ${x.getClass.getName}")
    }
  }

  private[this] def interpret[X](proc: Proc[X]): Task[X] =
    proc.foldMap(interpreter)

  private[this] val persistTaskCallbacks: java.util.Deque[Either[ProcException, D] => Unit] =
    new java.util.LinkedList

  private[this] val snapshotTaskCallbacks: java.util.Deque[Either[SnapshotFailure, Unit] => Unit] =
    new java.util.LinkedList

  private[this] val interpreter: (ProcA ~> Task) = new (ProcA ~> Task) {
    override def apply[X](proc: ProcA[X]): Task[X] = proc match {
      case p: ProcA.Persist[D, S] =>
        val persist = Task.unforkedAsync[D] { cb =>
          if (actor.recoveryFinished) {
            actor.persistTaskCallbacks.addLast(cb)
            debug(s"Calling .persist${if (!p.async) "Sync" else ""} of data: ${p.data.getClass.getName}")
            val callback: (D => Unit) = { d =>
              debug(s"Persisting data: ${d.getClass.getName} completed")
              val popped = actor.persistTaskCallbacks.removeFirst()
              assert(popped eq cb)
              cb(Right(d))
            }
            if (p.async) {
              actor.persistAsync(p.data)(callback)
            } else {
              actor.persist(p.data)(callback)
            }
          } else {
            cb(Left(new IllegalStateException("Impossible: persist attempt during recovery")))
          }
        }
        persist.map { _ => actor.currentState }
      case s: ProcA.Snapshot[S] =>
        val snap = Task.unforkedAsync[Unit] { (cb: Either[Throwable, Unit] => Unit) =>
          debug(s"Taking snapshot ...")
          actor.saveSnapshot(actor.currentState)
          actor.snapshotTaskCallbacks.addLast(cb)
        }
        snap.map { _ => actor.currentState }
      case ch: ProcA.Change[S] =>
        Task.delay {
          debug(s"Executing requested state change to ${ch.state}")
          actor.changeState(ch.state)
          ch.state
        }
      case ProcA.SeqNr =>
        Task.delay(actor.lastSequenceNr)
      case _: ProcA.Same[S] =>
        Task.delay(actor.currentBehavior.state(actor.ctx))
      case ProcA.Stop() =>
        Task.fail(new ActorStop)
      case ProcA.Attempt(p) =>
        val inner = interpret(p)
        inner.attempt.flatMap {
          case Left(ex: ActorStop) => Task.fail(ex)
          case Left(ex: ProcException) => Task.now(Left(ex))
          case Left(ex) => Task.now(Left(UnexpectedException(ex)))
          case Right(res) => Task.now(Right(res))
        }
      case ProcA.Fail(ex) =>
        Task.fail(ex)
      case f: ProcA.FromFuture[X] =>
        implicit val S: Strategy = Strategy.fromExecutor(ctx.executionContext)
        implicit val ec: ExecutionContext = ctx.executionContext
        Task.fromFuture(f.fut)
    }
  }

  override def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit = {
    super.onPersistFailure(cause, event, seqNr)
    persistError(PersistFailure(cause, seqNr))
  }

  override def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
    super.onPersistRejected(cause, event, seqNr)
    persistError(PersistRejected(cause, seqNr))
    // FIXME: what should happen, if a PersistRejectedException
    // is NOT handled? Stop the actor? Ignore? Log a warning?
  }

  private[this] def persistError(ex: ProcException): Unit = {
    val cb = this.persistTaskCallbacks.removeFirst()
    debug(s"PersistError, calling callback")
    cb(Left(ex))
  }
}

private object TypedPersistentActor {

  /** Internal exception for signaling a requested stop */
  final class ActorStop extends ControlThrowable

  /** Internal data type for representing events related to recovery */
  private sealed trait RecoveryEvent[+D, +S]

  /** A previously persisted event is offered for recovery */
  private final case class RecoveryOffer[D](data: D) extends RecoveryEvent[D, Nothing]

  /** A previously persisted snapshot is offered for restoration */
  private final case class SnapshotOffer[S](snap: S, meta: ap.SnapshotMetadata) extends RecoveryEvent[Nothing, S]

  /** Recovery is done, no more `RecoveryEvent`s will happen */
  private final case object RecoveryCompleted extends RecoveryEvent[Nothing, Nothing]

  /**
   * An error was encountered while trying to recover the persisted
   * state of the actor. The actor will be automatically stopped after this.
   */
  private final case class RecoveryFailure(cause: Throwable) extends RecoveryEvent[Nothing, Nothing]
}
