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
package testkit

import scala.util.{ Failure, Success, Try }
import scala.util.Random

import akka.{ typed => at }

import cats.{ ~>, Eq, Monad, RecursiveTailRecM }
import cats.data.{ StateT, Xor }
import cats.implicits._

/**
 * WIP testing framework for our persistence API.
 * The end goal is to be able to test persistent
 * actors without asynchrony or mocking persistence
 * plugins, ...
 *
 * @note The current implementation is incomplete.
 */
@SuppressWarnings(Array(unsafeCast)) // NB: a ton of false positives
abstract class TestInterpreter[M, D, S](
    name: String,
    initialBehavior: PersistentBehavior[M, D, S],
    sys: at.ActorSystem[Nothing]
) {

  sealed case class InterpState(name: String, store: Store, behavior: PersistentBehavior[M, D, S]) {
    val ctx = new at.EffectfulActorContext(name, behavior, _mailboxCapacity = 1000, _system = sys)
    def actorState: S = behavior.state(ctx)
    def changeState(newState: S): InterpState =
      this.copy(behavior = this.behavior.withState(ctx, newState))
  }

  sealed trait SpecState
  case object Stopped extends SpecState
  case class Error(ex: Throwable, st: InterpState) extends SpecState

  sealed trait Store {
    def update(event: D): Store
    def snap(snapshot: S): Store
  }

  object Store {
    val empty: Store = Journal(Nil)
  }

  case class Snapshot(snapshot: S) extends Store {
    def update(event: D): Store = JournalAndSnapshot(event :: Nil, snapshot)
    def snap(snapshot: S): Store = Snapshot(snapshot)
  }

  case class Journal(reversedEvents: List[D]) extends Store {
    def update(event: D): Store = Journal(event :: reversedEvents)
    def snap(snapshot: S): Store = Snapshot(snapshot)
  }

  case class JournalAndSnapshot(reversedEvents: List[D], snapshot: S) extends Store {
    def update(event: D): Store = JournalAndSnapshot(event :: reversedEvents, snapshot)
    def snap(snapshot: S): Store = Snapshot(snapshot)
  }

  type Xss[X] = Xor[SpecState, X]
  type TestProc[A] = StateT[Xss, InterpState, A]

  // aargh ... SI-2712
  implicit val xssMonad: Monad[Xss] with RecursiveTailRecM[Xss] =
    Xor.catsDataInstancesForXor[SpecState]
  implicit val tpMonad: Monad[TestProc] =
    StateT.catsDataMonadForStateT[Xss, InterpState]
  implicit val tpTrm: RecursiveTailRecM[TestProc] =
    StateT.catsDataRecursiveTailRecMForStateT[Xss, InterpState]

  val getInterpSt: TestProc[InterpState] =
    StateT.inspect[Xss, InterpState, InterpState](identity)

  val getActorSt: TestProc[S] =
    getInterpSt.map(_.actorState)

  val interpreter: ProcA ~> TestProc = new (ProcA ~> TestProc) {
    override def apply[X](proc: ProcA[X]): TestProc[X] = proc match {
      case p: ProcA.Persist[D, S] =>
        eventHook(p.data) match {
          case Success(ev) =>
            for {
              _ <- StateT.modify[Xss, InterpState](s => s.copy(store = s.store.update(p.data)))
              st <- getActorSt
            } yield st
          case Failure(ex) =>
            for {
              st <- getInterpSt
              ex <- StateT.lift[Xss, InterpState, S](Xor.left(Error(ex, st)))
            } yield ex
        }
      case _: ProcA.Snapshot[S] =>
        for {
          st <- getActorSt
          _ <- StateT.modify[Xss, InterpState](s => s.copy(store = s.store.snap(st)))
        } yield st
      case p: ProcA.Change[S] =>
        for {
          _ <- StateT.modify[Xss, InterpState](_.changeState(p.state))
        } yield p.state
      case _: ProcA.Same[S] =>
        getActorSt
      case _: ProcA.Stop[S] =>
        StateT.lift[Xss, InterpState, S](Xor.left(Stopped))
      case att: ProcA.Attempt[a] =>
        att.proc.foldMap(interpreter).transformF[Xss, Xor[ProcException, a]] {
          case x @ Xor.Left(Error(ex, st)) => ex match {
            case ex: TypedPersistentActor.ActorStop =>
              x
            case ex: ProcException =>
              Xor.right((st, Xor.left(ex)))
            case ex: Any =>
              Xor.right((st, Xor.left(UnexpectedException(ex))))
          }
          case x @ Xor.Left(Stopped) =>
            x
          case Xor.Right((st, x)) =>
            Xor.right((st, Xor.right(x)))
        }
      case f: ProcA.Fail[X] =>
        getInterpSt.flatMap { st =>
          StateT.lift[Xss, InterpState, X](Xor.left(Error(f.ex, st)))
        }
    }
  }

  protected[this] def eventHook(ev: D): Try[D] =
    Success(ev)

  protected[this] def snapshotHook(s: S): Try[S] =
    Success(s)

  protected[this] def assert(b: Boolean, msg: String = ""): Try[Unit]

  protected[this] def fail(msg: String): Nothing

  val initialState =
    InterpState(name, Store.empty, initialBehavior)

  def message(msg: M): TestProc[S] = for {
    st <- getInterpSt
    r <- Try(st.behavior.messageProc(st.ctx, msg)) match {
      case Success(p) => p.foldMap(interpreter)
      case Failure(ex) => StateT.lift[Xss, InterpState, S](Xor.left(Error(ex, st)))
    }
  } yield r

  def signal(sig: at.Signal): TestProc[S] = for {
    st <- getInterpSt
    r <- Try(st.behavior.managementProc(st.ctx, sig)) match {
      case Success(p) => p.foldMap(interpreter)
      case Failure(ex) => StateT.lift[Xss, InterpState, S](Xor.left(Error(ex, st)))
    }
  } yield r

  def ask[A](msg: at.ActorRef[A] => M): TestProc[A] = {
    val tmpName = Random.alphanumeric.take(10).mkString("")
    val inb = at.Inbox[A](tmpName)
    val mesg = msg(inb.ref)
    for {
      st <- message(mesg)
    } yield inb.receiveMsg()
  }

  def expect[A](msg: at.ActorRef[A] => M, expectedAnswer: A)(implicit A: Eq[A]): TestProc[Unit] = for {
    a <- ask(msg)
    _ <- assertEq(a, expectedAnswer)
  } yield ()

  def assertEq[A](x: A, y: A)(implicit A: Eq[A]): TestProc[Unit] = for {
    st <- getInterpSt
    _ <- assert(x === y, s"${x} was not equal to ${y}") match {
      case Success(()) => StateT.pure[Xss, InterpState, Unit](())
      case Failure(ex) => StateT.lift[Xss, InterpState, S](Xor.left(Error(ex, st)))
    }
  } yield ()

  private def assertFlag(b: Boolean, msg: String): TestProc[Unit] = for {
    st <- getInterpSt
    _ <- if (b) {
      StateT.pure[Xss, InterpState, Unit](())
    } else {
      StateT.lift[Xss, InterpState, S](Xor.left(Error(new AssertionError, st)))
    }
  } yield ()

  def expectSt[A](extract: S => A, expected: A)(implicit A: Eq[A]): TestProc[Unit] = for {
    st <- getActorSt
    _ <- assertEq(extract(st), expected)
  } yield ()

  def expectStore(p: PartialFunction[Store, Unit]): TestProc[Unit] = for {
    st <- getInterpSt
    _ <- assertFlag(p.isDefinedAt(st.store), "no match")
  } yield ()

  def expectStop: TestProc[Unit] = {
    getInterpSt.transformF[Xss, Unit] { x: Xss[(InterpState, InterpState)] =>
      x match {
        case Xor.Left(Stopped) =>
          Xor.left[SpecState, (InterpState, Unit)](Stopped)
        case Xor.Left(Error(ex, st)) =>
          Xor.left[SpecState, (InterpState, Unit)](Error(new AssertionError(s"expected stop, got exception: ${ex}"), st))
        case Xor.Right((st, _)) =>
          Xor.left[SpecState, (InterpState, Unit)](Error(new AssertionError("expected stop"), st))
      }
    }
  }

  def run[A](p: TestProc[A]): Xss[A] =
    p.runA(initialState)

  def check[A](p: TestProc[A]): Unit = {
    run(p) match {
      case Xor.Left(Stopped) =>
      case Xor.Left(Error(ex, _)) => fail(ex.getMessage)
      case Xor.Right(st) =>
    }
  }
}
