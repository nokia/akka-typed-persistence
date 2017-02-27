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

import scala.collection.immutable
import scala.concurrent.Promise
import scala.util.{ Failure, Success, Try }

import akka.{ typed => at }
import akka.typed.AskPattern._
import akka.typed.adapter._

import shapeless._

class PersistenceSpec extends AbstractPersistenceSpec {

  import AbstractPersistenceSpec._
  import PersistenceSpec._

  "Persistent dictionary with ack/nack" should "work as expected" in {
    val name = "hl-dictNoSnapshotWithAck-ok"
    val ref: at.ActorRef[DictMsg] = dictWithAck(DState(), name).deployInto(system, name)
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (None)
    ref.?[DictAck](Set("foo", 9, _)).futureValue should be (Ack("foo", 9))
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (Some(9))
    ref ! StopDict
    awaitTermination(ref).futureValue

    val ref2: at.ActorRef[DictMsg] = dictWithAck(DState(), name).deployInto(system, name)
    ref2.?[Option[Int]](Get("foo", _)).futureValue should be (Some(9))
    ref2.?[DictAck](Set("bar", 3, _)).futureValue should be (Ack("bar", 3))
    ref2.?[Option[Int]](Get("bar", _)).futureValue should be (Some(3))
    ref2 ! StopDict
    awaitTermination(ref2).futureValue
  }

  it should "send a NAck if the persist is rejected" in {
    val name = "hl-dictNoSnapshotWithAck-reject"
    val sys = startWithDummyJournal(persistAction = Reject, recoverAction = No)
    val ref: at.ActorRef[DictMsg] = dictWithAck(DState(), name).deployInto(sys, name)
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (None)
    ref.?[DictAck](Set("foo", 9, _)).futureValue should be (NAck("foo", 9))
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (None)
    ref ! StopDict
    awaitTermination(ref).futureValue
    shutdown(sys, verifySystemShutdown = true)
  }

  "Managed persistent state" should "work without snapshots (dictionary)" in {
    val name = "hl-dictNoSnapshot"
    val ref: at.ActorRef[DictMsg] = dictionary2(DState(), name).deployInto(system, name)
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (None)
    ref.?[DictAck](Set("foo", 9, _)).futureValue should be (Ack("foo", 9))
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (Some(9))
    ref ! StopDict
    awaitTermination(ref).futureValue

    val ref2: at.ActorRef[DictMsg] = dictionary2(DState(), name).deployInto(system, name)
    ref2.?[Option[Int]](Get("foo", _)).futureValue should be (Some(9))
    ref2.?[DictAck](Set("bar", 3, _)).futureValue should be (Ack("bar", 3))
    ref2.?[Option[Int]](Get("bar", _)).futureValue should be (Some(3))
    ref2 ! StopDict
    awaitTermination(ref2).futureValue
  }

  it should "work without snapshots (simple register)" in {
    val name = "hl-regNoSnapshot"
    val ref: at.ActorRef[Mes] = register(9, name).deployInto(system, name)
    ask(ref) should be (9)
    ref.?[Int](SetInt(10, _)).futureValue should be (10)
    ask(ref) should be (10)
    ref ! Stop
    awaitTermination(ref).futureValue

    val ref2: at.ActorRef[Mes] = register(999, name).deployInto(system, name)
    ask(ref2) should be (10)
    ref2.?[Int](SetInt(11, _)).futureValue should be (11)
    ask(ref2) should be (11)
    ref2 ! Stop
    awaitTermination(ref2).futureValue
  }

  it should "not work in a regular ActorAdapter" in {
    val name = "hl-regInNotPersistent"
    val ref: at.ActorRef[Mes] =
      system.spawn(register(10, name), name)
    // this will fail, the actor will die:
    ref.?[Int](r => GetInt(r))
    awaitTermination(ref).futureValue
  }

  "Error handling" should "be possible" in {
    val name = "hl-error-reject-ok"
    val sys = startWithDummyJournal(persistAction = Reject, recoverAction = No)
    val err = Promise[ProcException]()
    val ref: at.ActorRef[DictMsg] = dictionaryError(DState(), name, err).deployInto(sys, name)
    ref.?[DictAck](Set("foo", 9, _)) // we won't get a reply
    err.future.futureValue match {
      case PersistRejected(_, 1) =>
      case x: Any => fail(s"unexpected value: $x")
    }
    shutdown(sys, verifySystemShutdown = true)
  }

  "Persistence failure" should "stop the actor, even if handled" in {
    val name = "hl-error-fail_handled-ok"
    val sys = startWithDummyJournal(persistAction = Fail, recoverAction = Ok)
    val p = Promise[ProcException]()
    val ref: at.ActorRef[PP] = promiser(p, name).deployInto(sys, name)
    ref ! N(5)
    p.future.futureValue match {
      case PersistFailure(_: MyException, _) =>
      case x: Any => fail(s"unexpected: $x")
    }
    awaitTermination(ref).futureValue
    shutdown(sys, verifySystemShutdown = true)
  }

  it should "stop the actor (unhandled)" in {
    val name = "hl-error-fail_unhandled-ok"
    val sys = startWithDummyJournal(persistAction = Fail, recoverAction = Ok)
    val ref: at.ActorRef[PP] = unhandled(name).deployInto(sys, name)
    ref ! N(5)
    awaitTermination(ref).futureValue
    shutdown(sys, verifySystemShutdown = true)
  }

  "Persistence rejection" should "not stop the actor if handled" in {
    val name = "hl-error-reject_handled-ok"
    val sys = startWithDummyJournal(persistAction = Reject, recoverAction = Ok)
    val p = Promise[ProcException]()
    val ref: at.ActorRef[PP] = promiser(p, name).deployInto(sys, name)
    ref ! N(5)
    p.future.futureValue match {
      case PersistRejected(_: MyException, _) =>
      case x: Any => fail(s"unexpected: $x")
    }
    ref.?[Boolean](AreYouAlive(_)).futureValue should be (true)
    shutdown(sys, verifySystemShutdown = true)
  }

  it should "stop the actor if unhandled" in {
    val name = "hl-error-reject_unhandled-ok"
    val sys = startWithDummyJournal(persistAction = Reject, recoverAction = Ok)
    val ref: at.ActorRef[PP] = unhandled(name).deployInto(sys, name)
    ref ! N(5)
    awaitTermination(ref).futureValue
    shutdown(sys, verifySystemShutdown = true)
  }

  it should "stop, if the actor chooses to stop" in {
    val name = "hl-regStopOnReject"
    val sys = startWithDummyJournal(persistAction = Reject, recoverAction = No)
    val ref: at.ActorRef[Mes] = register(9, name).deployInto(sys, name)
    ask(ref) should be (9)
    ref.?[Int](SetInt(10, _)).futureValue should be (-2) // the actor calls p.stop()
    awaitTermination(ref).futureValue
    shutdown(sys, verifySystemShutdown = true)
  }

  "Recovery failure" should "be sent as a message, then the actor stopped" in {
    val name = "hl-error-ok-fail"
    val sys = startWithDummyJournal(persistAction = Ok, recoverAction = Fail)
    val p = Promise[RecoveryEvent]()
    val ref: at.ActorRef[PP] = recoveryPromiser(p, name).deployInto(sys, name)
    p.future.futureValue.select[Throwable] match {
      case Some(_: MyException) =>
      case x: Any => fail(s"unexpected: $x")
    }
    awaitTermination(ref).futureValue
    shutdown(sys, verifySystemShutdown = true)
  }

  "Exception in the callback after persist" should "stop the actor" in {
    val name = "hl-exception-in-callback"
    val ref: at.ActorRef[PP] = exception(name).deployInto(system, name)
    ref ! N(5)
    awaitTermination(ref).futureValue
  }

  "Exception in the callback after pure" should "stop the actor" in {
    val name = "hl-exception-in-callback-pure"
    val ref: at.ActorRef[PP] = exception(name).deployInto(system, name)
    ref ! N(0)
    awaitTermination(ref).futureValue
  }

  "The user" should "not be able to catch ActorStop" in {
    val name = "try-catch-actor-stop"
    val ref: at.ActorRef[PP] = tryCatchStop(name).deployInto(system, name)
    ref ! N(0)
    awaitTermination(ref).futureValue
  }

  "Requested state change" should "take effect immediately" in {
    val act = PersistentFull[Set, Int, Long](0L, _ => "chchch", Recovery { (s, _, _) => s }) { state => p =>
      PersistentBehavior.Total {
        case Set("", v, replyTo) =>
          replyTo ! Ack("", v)
          p.stop
        case Set(k, _, replyTo) =>
          for {
            state <- p.persist(1)
            _ <- p.change(99L)
            state <- p.persist(2)
          } yield {
            replyTo ! Ack(k, state.toInt)
            state
          }
      }
    }

    val ref = act.deployInto(system, "chchch")
    ref.?[DictAck](Set("foo", 9, _)).futureValue should be (Ack("foo", 99))

    // this will stop the actor:
    ref.?[DictAck](Set("", 0, _)).futureValue should be (Ack("", 0))
    awaitTermination(ref).futureValue
  }

  "Persistent dictionary with ack/nack and persistSync" should "always reflect the last Set in its reply" in {
    val name = "hl-dictNoSnapshotWithAckSync-ok"
    val ref: at.ActorRef[DictMsg] = dictWithAck(DState(), name, async = false).deployInto(system, name)
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (None)

    // starting Set:
    val futAckSet = ref.?[DictAck](Set("foo", 9, _))

    // sending a Get, which should be answered only
    // after the Set is persisted and takes effect:
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (Some(9))

    // and meanwhile the Set should be acked too:
    futAckSet.futureValue should be (Ack("foo", 9))

    ref ! StopDict
    awaitTermination(ref).futureValue

    val ref2: at.ActorRef[DictMsg] = dictWithAck(DState(), name, async = false).deployInto(system, name)
    ref2.?[Option[Int]](Get("foo", _)).futureValue should be (Some(9))

    // the same again:
    val futAckSet2 = ref2.?[DictAck](Set("bar", 3, _))
    ref2.?[Option[Int]](Get("bar", _)).futureValue should be (Some(3))
    futAckSet2.futureValue should be (Ack("bar", 3))

    ref2 ! StopDict
    awaitTermination(ref2).futureValue
  }

  "Snapshots" should "be used when recovering" in {
    import system.dispatcher

    val name = "hl-dict-withSnapshot-withAck-ok"
    val testRef = actorRef[Either[Throwable, DSS]](testActor)
    val testRefRec = actorRef[Set](testActor)

    def start() = {
      dictWithAckSnap(DState(), name, testRef, testRefRec, handleSnapError = false).deployInto(system, name)
    }

    val ref: at.ActorRef[DictMsg] = start()

    // sanity check:
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (None)

    // send a few Sets to force snapshot:
    for (i <- 0 to snapThreshold) {
      ref.?[DictAck](Set("foo", i, _)).futureValue should be (Ack("foo", i))
    }

    // expect a snapshot:
    this.expectMsgPF(remainingOrDefault, "first snapshot") {
      case Right(DSS(0, map)) =>
        map should be (Map("foo" -> snapThreshold))
    }

    // state should be correct:
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (Some(snapThreshold))

    // force 2 more snapshots:
    for (i <- 0 to snapThreshold) {
      ref.?[DictAck](Set("bar", i, _)).futureValue should be (Ack("bar", i))
      ref.?[DictAck](Set("baz", i, _)).futureValue should be (Ack("baz", i))
    }

    // expect them:
    this.expectMsgPF(remainingOrDefault, "2nd snapshot") {
      case Right(DSS(0, map)) =>
        map should contain key ("foo")
        map should contain key ("bar")
        map should contain key ("baz")
    }
    this.expectMsgPF(remainingOrDefault, "3rd snapshot") {
      case Right(DSS(0, map)) =>
        map should be (Map("foo" -> snapThreshold, "bar" -> snapThreshold, "baz" -> snapThreshold))
    }

    // now the first 2 snapshots should have been deleted:
    val f = getPreviousSnapshot[DSS](name).map[Try[DSS]](Success(_)).recover { case ex: Any => Failure(ex) }
    withDilatedTimeout(10.0) {
      f.futureValue match {
        case Failure(_) => // OK, we didn't find an earlier snapshot
        case Success(x) => fail(s"Unexpected snapshot: ${x}")
      }
    }

    // stop:
    ref ! StopDict
    awaitTermination(ref).futureValue

    // recover from snapshots:
    val ref2: at.ActorRef[DictMsg] = start()
    this.expectNoMsg() // no recovery from events

    ref2.?[Option[Int]](Get("foo", _)).futureValue should be (Some(snapThreshold))
    ref2.?[Option[Int]](Get("bar", _)).futureValue should be (Some(snapThreshold))
    ref2.?[Option[Int]](Get("baz", _)).futureValue should be (Some(snapThreshold))
    this.expectNoMsg() // no recovery from events

    // let's take another snapshot:
    for (i <- 0 to snapThreshold) {
      ref2.?[DictAck](Set("xxx", i, _)).futureValue should be (Ack("xxx", i))
    }

    // expect it:
    this.expectMsgPF(remainingOrDefault, "4th snapshot") {
      case Right(DSS(0, map)) =>
        map should contain key ("xxx")
    }

    ref2.?[Option[Int]](Get("xxx", _)).futureValue should be (Some(snapThreshold))
    ref2.?[DictAck](Set("xyz", 5, _)).futureValue should be (Ack("xyz", 5))

    ref2 ! StopDict
    awaitTermination(ref2).futureValue

    // restart, now it should recover from the last snapshot and 1 event:
    val ref3: at.ActorRef[DictMsg] = start()
    this.expectMsgPF(remainingOrDefault, "recovery event") {
      case Set("xyz", 5, _) =>
    }
    this.expectNoMsg() // no more events

    ref3.?[Option[Int]](Get("xyz", _)).futureValue should be (Some(5))

    ref3 ! StopDict
    awaitTermination(ref3).futureValue
  }

  "SnapshotFailure" should "stop the actor if not handled" in {
    val name = "hl-dict-withSnapshot-withAck-snapshotFailure-notHandled"
    val sys = startWithDummyJournal(persistAction = Ok, recoverAction = No, snapshotAction = Fail)
    val testRef = actorRef[Either[Throwable, DSS]](testActor)
    val testRefRec = actorRef[Set](testActor)

    def start() = {
      dictWithAckSnap(DState(), name, testRef, testRefRec, handleSnapError = false).deployInto(sys, name)
    }

    val ref: at.ActorRef[DictMsg] = start()

    // sanity check:
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (None)

    // send a few Sets to force snapshot:
    for (i <- 0 to snapThreshold) {
      ref.?[DictAck](Set("foo", i, _)).futureValue should be (Ack("foo", i))
    }

    // expect a snapshot failure:
    this.expectMsgPF(remainingOrDefault, "snapshot failure") {
      case Left(SnapshotFailure(_, _)) =>
    }

    // the actor should be stopped:
    awaitTermination(ref).futureValue should be (())
  }

  it should "be able to be handled with 'recoverWith'" in {
    val name = "hl-dict-withSnapshot-withAck-snapshotFailure-handled"
    val sys = startWithDummyJournal(persistAction = Ok, recoverAction = No, snapshotAction = Fail)
    val testRef = actorRef[Either[Throwable, DSS]](testActor)
    val testRefRec = actorRef[Set](testActor)

    def start() = {
      dictWithAckSnap(DState(), name, testRef, testRefRec, handleSnapError = true).deployInto(sys, name)
    }

    val ref: at.ActorRef[DictMsg] = start()

    // sanity check:
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (None)

    // send a few Sets to force snapshot:
    for (i <- 0 to snapThreshold) {
      ref.?[DictAck](Set("foo", i, _)).futureValue should be (Ack("foo", i))
    }

    // expect a snapshot failure:
    this.expectMsgPF(remainingOrDefault, "snapshot failure") {
      case Left(SnapshotFailure(_, _)) =>
    }

    // the actor should NOT be stopped:
    ref.?[Option[Int]](Get("foo", _)).futureValue should be (Some(snapThreshold))
  }

  "Last sequence number" should "be accessible in the actor" in {
    val name = "seqNr-normal"
    val ref = getSeqNr(name).deployInto(system, name)
    (ref ? GetSeqNr).futureValue should be (0L)
    (ref ? GetSeqNr).futureValue should be (0L)
    (ref ? Persist).futureValue
    (ref ? GetSeqNr).futureValue should be (1L)
    (ref ? GetSeqNr).futureValue should be (1L)
    (ref ? Persist).futureValue
    (ref ? Persist).futureValue
    (ref ? GetSeqNr).futureValue should be (3L)
    ref ! End
    awaitTermination(ref).futureValue

    val ref2 = getSeqNr(name).deployInto(system, name)
    (ref2 ? GetSeqNr).futureValue should be (3L)
    (ref2 ? Persist).futureValue
    (ref2 ? Persist).futureValue
    (ref2 ? GetSeqNr).futureValue should be (5L)
    ref2 ! End
    awaitTermination(ref2).futureValue
  }
}

object PersistenceSpec {

  import AbstractPersistenceSpec._

  sealed trait DictMsg
  final case class Get(key: String, replyTo: at.ActorRef[Option[Int]]) extends DictMsg

  final case class Set(key: String, value: Int, ackTo: at.ActorRef[DictAck]) extends DictMsg

  object Set {
    implicit val forMap: Update[DState, Set] =
      Update.instance { (s, e) => s.updated(e.key, e.value) }
  }

  case object StopDict extends DictMsg

  sealed trait DictAck
  final case class Ack(key: String, value: Int) extends DictAck
  final case class NAck(key: String, value: Int) extends DictAck

  type DState = immutable.Map[String, Int]
  val DState = immutable.Map

  private final val snapThreshold = 10

  final case class DSS(ctr: Long, map: immutable.Map[String, Int]) {
    def update(ev: Set): DSS = {
      if (ctr >= snapThreshold) {
        copy(ctr = 0, map = map.updated(ev.key, ev.value))
      } else {
        copy(ctr = ctr + 1, map = map.updated(ev.key, ev.value))
      }
    }
  }

  object DSS {
    implicit val updater: Update[DSS, Set] = Update.instance(_ update _)
  }

  def dictWithAck(initialState: DState, pid: PersistenceId, async: Boolean = true) = Persistent[DictMsg, Set, DState](
    initialState,
    _ => pid
  ) { state => p =>
      PersistentBehavior.Total {
        case StopDict =>
          p.stop
        case Get(k, r) =>
          r ! state.get(k)
          p.same
        case ev @ Set(k, v, ackTo) =>
          (for {
            st <- p.apply(ev, sync = !async)
            _ = ackTo ! Ack(k, v)
          } yield st).recoverWith {
            case PersistRejected(_, _) =>
              ackTo ! NAck(k, v)
              p.same
            case ex: Any =>
              ackTo ! NAck(k, v)
              p.fail(ex)
          }
      }
    }

  def dictWithAckSnap(
    initialMap: DState,
    pid: PersistenceId,
    snapshots: at.ActorRef[Either[Throwable, DSS]],
    recovery: at.ActorRef[Set],
    handleSnapError: Boolean
  ) = PersistentFull[DictMsg, Set, DSS](
    DSS(0, initialMap),
    _ => pid,
    Recovery(
      recovery = { (s, ev, ctx) =>
      ctx.log.debug(s"Recovery: ${ev}")
      recovery ! ev
      s.copy(map = s.map.updated(ev.key, ev.value))
    },
      completed = (s, _) => s.copy(ctr = 0)
    )
  ) { state => p =>
      PersistentBehavior.Total {
        case StopDict =>
          p.stop
        case Get(k, r) =>
          r ! state.map.get(k)
          p.same
        case ev @ Set(k, v, ackTo) =>
          (for {
            st <- p.apply(ev)
            st <- {
              ackTo ! Ack(k, v)
              st match {
                case DSS(0, _) => p.change(st).flatMap { _ =>
                  p.ctx.log.debug(s"SNAP: ${st} ...")
                  p.snapshot.map { asst =>
                    p.ctx.log.debug(s"SNAP ${st} DONE")
                    snapshots ! Right(st)
                    asst
                  }.recoverWith {
                    case ex: Throwable =>
                      p.ctx.log.debug(s"SNAP ${st} FAILED")
                      snapshots ! Left(ex)
                      if (handleSnapError) p.same else p.fail(ex)
                  }
                }
                case DSS(_, _) => p.pure(st)
              }
            }
          } yield st).recoverWith {
            case PersistRejected(_, _) =>
              ackTo ! NAck(k, v)
              p.same
            case ex: Any =>
              ackTo ! NAck(k, v)
              p.fail(ex)
          }
      }
    }

  def dictionary2(state: DState, pid: PersistenceId) = Persistent[DictMsg, Set, DState](
    state,
    _ => pid
  ) { state => p =>
      PersistentBehavior.Total {
        case StopDict =>
          p.stop
        case Get(k, r) =>
          r ! state.get(k)
          p.same
        case ev @ Set(_, _, ackTo) =>
          for {
            st <- p.apply(ev)
          } yield {
            ackTo ! Ack(ev.key, ev.value)
            st
          }
      }
    }

  def dictionaryError(state: DState, pid: PersistenceId, error: Promise[ProcException]) = Persistent[DictMsg, Set, DState](
    state,
    _ => pid
  ) { state => p =>
      PersistentBehavior.Total {
        case StopDict =>
          p.stop
        case Get(k, r) =>
          r ! state.get(k)
          p.same
        case ev @ Set(_, _, _) =>
          val proc = p.apply(ev)
          proc.recoverWith {
            case e @ PersistRejected(ex, _) =>
              error.success(e)
              p.same
          }
      }
    }

  /** Simple persistent `Int` register, doesn't use snapshots */
  def register(startAt: Int, pid: PersistenceId) = PersistentFull[Mes, Int, Int](
    startAt,
    _ => pid,
    Recovery { (_, ev, _) => ev }
  ) { state => p => PersistentBehavior.Total {
      case SetInt(n, ackTo) =>
        p.ctx.log.info(s"Starting to persist $n")
        val proc = for {
          _ <- p.persist(n)
          _ = p.ctx.log.info(s"Persisted $n, changing state")
          _ = ackTo ! n
        } yield n
        proc.recoverWith {
          case ex: PersistenceException =>
            p.ctx.log.error(s"Persistence failed: ${ex.cause.getMessage}")
            ackTo ! -2
            p.stop
        }
      case GetInt(ref) =>
        p.ctx.log.info(s"Returning $state")
        ref ! state
        p.same
      case Stop =>
        p.stop
    }
    }

  sealed trait SeqNrOp
  final case class GetSeqNr(replyTo: at.ActorRef[Long]) extends SeqNrOp
  final case class Persist(ackTo: at.ActorRef[Unit]) extends SeqNrOp
  final case object End extends SeqNrOp

  implicit val updateUnit: Update[Unit, Unit] =
    Update.instance { (s, e) => s }

  def getSeqNr(pid: PersistenceId) = PersistentFull[SeqNrOp, Unit, Unit](
    (),
    _ => pid,
    Recovery { (_, _, _) => () }
  ) { state => p => PersistentBehavior.Total {
      case GetSeqNr(ref) =>
        for {
          snr <- p.lastSequenceNr
          _ = ref ! snr
          state <- p.same
        } yield state
      case Persist(ref) =>
        for {
          state <- p.apply(())
          _ = ref ! (())
        } yield state
      case End =>
        p.stop
    }
    }

  private def promiser(promise: Promise[ProcException], pid: PersistenceId) = PersistentFull[PP, Unit, Int](
    0,
    _ => pid,
    Recovery { (s, e, _) => s }
  ) { state => p =>
      PersistentBehavior.Total {
        case N(n) =>
          p.persist(()).recover {
            case ex: ProcException =>
              promise.success(ex)
              0
          }
        case AreYouAlive(r) =>
          r ! true
          p.same
      }
    }

  private def unhandled(pid: PersistenceId) = PersistentFull[PP, Unit, Int](
    0,
    _ => pid,
    Recovery { (s, _, _) => s }
  ) { state => p =>
      PersistentBehavior.Total {
        case N(n) =>
          p.persist(())
        case AreYouAlive(r) =>
          r ! true
          p.same
      }
    }

  type RecoveryEvent = Int :+: Throwable :+: Unit :+: CNil

  private def recoveryPromiser(promise: Promise[RecoveryEvent], pid: PersistenceId) = PersistentFull[PP, Int, Int](
    0,
    _ => pid,
    Recovery(
      (s, e, _) => {
        promise.success(Coproduct[RecoveryEvent](e))
        s
      },
      failure = (_, ex, ctx) => promise.success(Coproduct[RecoveryEvent](ex)),
      completed = (s, _) => {
        promise.success(Coproduct[RecoveryEvent](()))
        s
      }
    )
  ) { state => p =>
      PersistentBehavior.Total {
        case x: Any =>
          p.fail(UnexpectedException(new Exception(s"unexpected msg: $x")))
      }
    }

  private def exception(pid: PersistenceId) = PersistentFull[PP, Int, Int](
    0,
    _ => pid,
    Recovery { (s, _, _) => s }
  ) { state => p =>
      PersistentBehavior.Total {
        case N(0) =>
          for {
            _ <- p.pure(99)
          } yield thrw(new MyException("test exception (pure)"))
        case N(n) =>
          for {
            _ <- p.persist(n)
          } yield thrw(new MyException("test exception (persist)"))
        case _ =>
          p.same
      }
    }

  private def thrw(ex: Throwable): Nothing =
    throw ex

  private def tryCatchStop(pid: PersistenceId) = PersistentFull[PP, Int, Int](
    0,
    _ => pid,
    Recovery { (s, _, _) => s }
  ) { state => p =>
      PersistentBehavior.Total {
        case _ =>
          p.stop.recover { // this shouldn't work
            case _ => 99
          }
      }
    }
}
