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

/**
 * A `Behavior`, which must be deployed as a `TypedPersistentActor`.
 * Use the provided `props` or `deployInto` methods for this. Instances of
 * this trait can be created with the `Persistent` factory method.
 *
 * @tparam A the message type
 * @tparam D the event type
 * @tparam S the state (and snapshot) type
 */
sealed trait PersistentActor[A, D, S] extends SelfDeployingBehavior[A] {

  def onSignal(s: (S, PersistenceApi[A, D, S]) => PartialFunction[at.Signal, Proc[S]]): PersistentActor[A, D, S]

  private[persistence] def undefer(ctx: at.scaladsl.ActorContext[A]): PersistentActor.PersistentActorImpl[A, D, S]

  // it's safe, because we're contravariant
  @SuppressWarnings(Array(unsafeCast))
  def narrowTo[B <: A]: PersistentActor[B, D, S] =
    this.asInstanceOf[PersistentActor[B, D, S]]
}

object PersistentActor {

  def immutable[A, D, S](
    initialState: S,
    pid: at.scaladsl.ActorContext[A] => PersistenceId
  )(f: S => PersistenceApi[A, D, S] => A => Proc[S])(
    implicit
    m: Update[S, D]
  ): PersistentActor[A, D, S] = {
    withRecovery[A, D, S](initialState, pid, Recovery.fromUpdate(m))(f)
  }

  def withRecovery[A, D, S](
    initialState: S,
    pid: at.scaladsl.ActorContext[A] => PersistenceId,
    recovery: Recovery[A, D, S]
  )(f: S => PersistenceApi[A, D, S] => A => Proc[S]): PersistentActor[A, D, S] = {
    new PersistentActorImpl[A, D, S](
      _ => initialState,
      pid,
      recovery,
      (s, api, msg) => f(s)(api)(msg),
      (s, api) => PartialFunction.empty
    )
  }

  def deferred[A, D, S](
    factory: at.scaladsl.ActorContext[A] => PersistentActor[A, D, S]
  ): PersistentActor[A, D, S] = {
    new DeferredPersistentActorImpl[A, D, S](factory, None)
  }

  private class DeferredPersistentActorImpl[A, D, S](
      factory: at.scaladsl.ActorContext[A] => PersistentActor[A, D, S],
      signalHandler: Option[(S, PersistenceApi[A, D, S]) => PartialFunction[at.Signal, Proc[S]]]
  ) extends PersistentActor[A, D, S] {

    override def onSignal(s: (S, PersistenceApi[A, D, S]) => PartialFunction[at.Signal, Proc[S]]): PersistentActor[A, D, S] =
      new DeferredPersistentActorImpl(factory, Some(s))

    private[ct] override def undefer(ctx: at.scaladsl.ActorContext[A]): PersistentActorImpl[A, D, S] = {
      val b = factory(ctx).undefer(ctx)
      signalHandler match {
        case Some(sh) => b.onSignal(sh)
        case None => b
      }
    }

    protected[ct] def untypedProps: akka.actor.Props =
      au.Props(new TypedPersistentActor(this))
  }

  private[persistence] class PersistentActorImpl[A, D, S](
      initialState: at.scaladsl.ActorContext[A] => S,
      pid: at.scaladsl.ActorContext[A] => PersistenceId,
      rec: Recovery[A, D, S],
      message: (S, PersistenceApi[A, D, S], A) => Proc[S],
      signal: (S, PersistenceApi[A, D, S]) => PartialFunction[at.Signal, Proc[S]]
  ) extends at.ExtensibleBehavior[A] with PersistentActor[A, D, S] {

    private[persistence] override def undefer(ctx: at.scaladsl.ActorContext[A]): PersistentActorImpl[A, D, S] =
      this

    private[this] def api(ctx: at.scaladsl.ActorContext[A]) =
      new PersistenceApiImpl[A, D, S](ctx)

    def state(ctx: at.scaladsl.ActorContext[A]): S =
      initialState(ctx)

    val recovery: Recovery[A, D, S] =
      rec

    def persistenceId(ctx: at.scaladsl.ActorContext[A]): PersistenceId =
      pid(ctx)

    def withState(ctx: at.scaladsl.ActorContext[A], newState: S): PersistentActorImpl[A, D, S] =
      new PersistentActorImpl(_ => newState, pid, rec, message, signal)

    override def onSignal(s: (S, PersistenceApi[A, D, S]) => PartialFunction[at.Signal, Proc[S]]): PersistentActorImpl[A, D, S] =
      new PersistentActorImpl[A, D, S](initialState, pid, rec, message, s)

    def managementProc(ctx: at.scaladsl.ActorContext[A], sig: at.Signal): Proc[S] =
      signal(state(ctx), api(ctx)).applyOrElse[at.Signal, Proc[S]](sig, _ => ProcA.same[S])

    def messageProc(ctx: at.scaladsl.ActorContext[A], msg: A): Proc[S] =
      message(state(ctx), api(ctx), msg)

    override def receiveSignal(ctx: at.ActorContext[A], sig: at.Signal): at.Behavior[A] =
      PersistentActor.Wrap[A, D, S](managementProc(ctx.asScala, sig), this)

    override def receiveMessage(ctx: at.ActorContext[A], msg: A): at.Behavior[A] =
      PersistentActor.Wrap[A, D, S](messageProc(ctx.asScala, msg), this)

    protected[ct] override val untypedProps = {
      // TODO: bounded mailbox (?)
      au.Props(new TypedPersistentActor(this))
    }
  }

  private[this] final val warning = "maybe the persistent typed behavior was deployed as a regular actor?"

  private[persistence] final case class Wrap[A, D, S](proc: Proc[S], current: PersistentActorImpl[A, D, S])
      extends at.ExtensibleBehavior[A] {

    override def receiveSignal(ctx: at.ActorContext[A], msg: at.Signal): at.Behavior[A] =
      impossible(s"${this.getClass.getSimpleName}.receiveSignal called with $msg; $warning")

    override def receiveMessage(ctx: at.ActorContext[A], msg: A): at.Behavior[A] =
      impossible(s"${this.getClass.getSimpleName}.receiveMessage called with $msg; $warning")
  }
}
