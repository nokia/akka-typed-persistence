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

import akka.{ actor => au, typed => at }
import akka.typed.ScalaDSL.{ MessageOrSignal, Msg, Sig }

import shapeless.tag.@@

/**
 * Behavior, which must be started in a specific way.
 * To make this easier, it provides a pre-configured
 * `Props` instance, as well as a `deployInto` method.
 */
trait SelfDeployingBehavior[A] extends at.Behavior[A] {

  private[this] val tagger = shapeless.tag[A]

  private[this] lazy val cachedProps =
    tagger(untypedProps)

  def props: au.Props @@ A =
    cachedProps

  def deployInto(system: au.ActorSystem, name: String = ""): at.ActorRef[A] = {
    at.adapter.actorRefAdapter(
      if (name.nonEmpty) system.actorOf(props, name)
      else system.actorOf(props)
    )
  }

  protected def untypedProps: au.Props
}

/**
 * A `Behavior`, which must be deployed as a `TypedPersistentActor`.
 * Use the provided `props` or `deployInto` methods for this. Instances of
 * this trait can be created with the `Persistent` factory method.
 *
 * @tparam A the message type
 * @tparam D the event type
 * @tparam S the state (and snapshot) type
 */
sealed trait PersistentBehavior[A, D, S] extends SelfDeployingBehavior[A] {

  def state(ctx: at.ActorContext[A]): S

  def withState(ctx: at.ActorContext[A], newState: S): PersistentBehavior[A, D, S]

  def messageProc(ctx: at.ActorContext[A], msg: A): Proc[S]

  def managementProc(ctx: at.ActorContext[A], sig: at.Signal): Proc[S]

  // it's safe, because we're contravariant
  @SuppressWarnings(Array(unsafeCast))
  def narrowTo[B <: A]: PersistentBehavior[B, D, S] =
    this.asInstanceOf[PersistentBehavior[B, D, S]]

  protected def persistenceId(ctx: at.ActorContext[A]): PersistenceId
}

object PersistentBehavior {

  /**
   * Factory to be used together with `Persistent`, to define
   * a persistent actor in a way similar to `akka.typed.ScalaDSL.Total`.
   */
  def Total[A, D, S](f: A => Proc[S]): MessageOrSignal[A] => Proc[S] = { // scalastyle:ignore
    case Sig(ctx, sig) =>
      ProcA.same
    case Msg(ctx, msg) =>
      f(msg)
  }

  /**
   * Factory to be used together with `Persistent`, to define
   * a persistent actor in a way similar to `akka.typed.ScalaDSL.FullTotal`.
   */
  def FullTotal[A, D, S](f: MessageOrSignal[A] => Proc[S]) = // scalastyle:ignore
    f

  /**
   * Fake `Behavior`, because we must return a real
   * behavior, while we'd like to return a `Proc`.
   * So we wrap a `Proc` into this. It will be unwrapped
   * by `TypedPersistentActor`.
   */
  private[persistence] final case class Wrap[A, D, S](proc: Proc[S], current: PersistentBehavior[A, D, S])
      extends at.Behavior[A] {

    override def management(ctx: at.ActorContext[A], msg: at.Signal): at.Behavior[A] =
      impossible(s"${this.getClass.getSimpleName}.management called with $msg; $warning")

    override def message(ctx: at.ActorContext[A], msg: A): at.Behavior[A] =
      impossible(s"${this.getClass.getSimpleName}.message called with $msg; $warning")
  }

  private[this] val warning = "maybe the persistent typed behavior was deployed as a regular actor?"
}

private[ct] class PersistentBehaviorImpl[A, D, S](
    initialState: at.ActorContext[A] => S,
    pid: at.ActorContext[A] => PersistenceId,
    rec: Recovery[A, D, S],
    f: (S, PersistenceApi[A, D, S], at.ActorContext[A], MessageOrSignal[A]) => Proc[S]
) extends PersistentBehavior[A, D, S] {

  private[this] def api(ctx: at.ActorContext[A]) =
    new PersistenceApiImpl[A, D, S](ctx)

  override def state(ctx: at.ActorContext[A]): S =
    initialState(ctx)

  override def withState(ctx: at.ActorContext[A], newState: S): PersistentBehavior[A, D, S] =
    new PersistentBehaviorImpl(_ => newState, pid, rec, f)

  override def managementProc(ctx: at.ActorContext[A], sig: at.Signal): Proc[S] =
    f(state(ctx), api(ctx), ctx, Sig(ctx, sig))

  override def messageProc(ctx: at.ActorContext[A], msg: A): Proc[S] =
    f(state(ctx), api(ctx), ctx, Msg(ctx, msg))

  override def management(ctx: at.ActorContext[A], msg: at.Signal): at.Behavior[A] =
    PersistentBehavior.Wrap[A, D, S](managementProc(ctx, msg), this)

  override def message(ctx: at.ActorContext[A], msg: A): at.Behavior[A] =
    PersistentBehavior.Wrap[A, D, S](messageProc(ctx, msg), this)

  protected override def persistenceId(ctx: at.ActorContext[A]): PersistenceId =
    pid(ctx)

  protected override val untypedProps = {
    au.Props(new TypedPersistentActor(this.persistenceId, rec, this))
  }
}
