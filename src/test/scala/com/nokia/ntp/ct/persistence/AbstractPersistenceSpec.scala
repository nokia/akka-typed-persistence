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

import java.util.UUID

import scala.collection.{ immutable, mutable }
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }
import scala.util.DynamicVariable

import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.ScalaFutures

import com.typesafe.config.Config
import com.typesafe.config.ConfigValueFactory

import akka.{ actor => au, persistence => ap, typed => at }
import akka.Done
import akka.event.LoggingAdapter
import akka.testkit.TestKit
import akka.typed.AskPattern._
import akka.typed.ScalaDSL._
import akka.typed.adapter._
import akka.util.Timeout

import cats.implicits._

abstract class AbstractPersistenceSpec
    extends TestKit(au.ActorSystem(AbstractPersistenceSpec.randomSysName()))
    with FlatSpecLike
    with BeforeAndAfterAll
    with ScalaFutures
    with Matchers
    with NonImplicitAssertions {

  import AbstractPersistenceSpec._

  protected val dilationFactor: DynamicVariable[Double] = new DynamicVariable(5.0)

  def withDilatedTimeout[A](factor: Double)(block: => A): A =
    dilationFactor.withValue(factor)(block)

  implicit def timeout: Timeout =
    Timeout((5 * dilationFactor.value).seconds)

  implicit override def patienceConfig: PatienceConfig =
    PatienceConfig(timeout = super.patienceConfig.timeout.scaledBy(dilationFactor.value))

  implicit def defaultScheduler(implicit sys: akka.actor.ActorSystem): akka.actor.Scheduler =
    sys.scheduler

  private[this] val systemsToStop: mutable.ListBuffer[au.ActorSystem] =
    new mutable.ListBuffer

  protected def config: Config =
    this.system.settings.config

  def startWithDummyJournal(
    persistAction: PersistAction,
    recoverAction: RecoverAction,
    snapshotAction: SnapshotAction = No
  ): au.ActorSystem = {
    val cfg = config
      .withValue("akka.persistence.journal.plugin", ConfigValueFactory.fromAnyRef("com.nokia.ntp.ct.persistence.test.dummy-journal"))
      .withValue("com.nokia.ntp.ct.persistence.test.dummy-journal.persist-action", ConfigValueFactory.fromAnyRef(persistAction.toString))
      .withValue("com.nokia.ntp.ct.persistence.test.dummy-journal.recovery-action", ConfigValueFactory.fromAnyRef(recoverAction.toString))
    val finalConfig = if (snapshotAction eq No) {
      cfg
    } else {
      cfg
        .withValue("akka.persistence.snapshot-store.plugin", ConfigValueFactory.fromAnyRef("com.nokia.ntp.ct.persistence.test.dummy-snapshot"))
        .withValue("com.nokia.ntp.ct.persistence.test.dummy-snapshot.snapshot-action", ConfigValueFactory.fromAnyRef(snapshotAction.toString))
    }
    this.startAdditionalSystem(Some(finalConfig), true, wait = 100.millis)
  }

  def startAdditionalSystem(config: Option[Config] = None, newName: Boolean = false, wait: FiniteDuration = 1.second): au.ActorSystem = {
    val cfg = config.getOrElse(this.config)
    val sys = au.ActorSystem(
      if (newName) AbstractPersistenceSpec.randomSysName() else this.system.name,
      config = Some(cfg.withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(0)))
    )
    stopAfterAll(sys)
    Thread.sleep(wait.toMillis)
    sys
  }

  def stopAfterAll(sys: au.ActorSystem): Unit = {
    this.systemsToStop += sys
  }

  override def afterAll(): Unit = {
    super.afterAll()
    for (sys <- this.systemsToStop) {
      this.shutdown(sys, verifySystemShutdown = true)
    }
  }

  def ask(ref: at.ActorRef[Mes]): Int =
    askAsync(ref).futureValue

  def askAsync(ref: at.ActorRef[Mes]): Future[Int] =
    ref.?[Int](GetInt(_))

  def awaitTermination(ref: at.ActorRef[_])(implicit system: au.ActorSystem): Future[Unit] = {
    val p = Promise[Unit]()
    val wd = system.spawnAnonymous[Nothing](watchDog(ref, p))
    p.future
  }

  private def watchDog(ref: at.ActorRef[Nothing], p: Promise[Unit]): at.Behavior[Nothing] = {
    FullTotal[Done.type] {
      case Sig(ctx, at.PreStart) =>
        ctx.watch[Nothing](ref)
        Same
      case Sig(ctx, at.Terminated(r)) =>
        if (ref === r) {
          // OK, it died, but we add some more
          // delay, because if the caller wants
          // to immediately recreate an actor
          // with the same name, that could fail.
          ctx.schedule(500.millis, ctx.self, Done)
          Same
        } else {
          p.failure(new Exception(s"Unexpected termination message from ${r}"))
          Stopped
        }
      case Sig(_, _) =>
        Unhandled
      case Msg(ctx, Done) =>
        p.success(())
        Stopped
    }.narrow
  }

  /**
   * Tries to get the second youngest snapshot of a persistent actor.
   *
   * @param persistenceId The persistence ID for which to get the snapshot.
   */
  def getPreviousSnapshot[A](persistenceId: String)(implicit system: au.ActorSystem): Future[A] = {
    import system.dispatcher
    for {
      snr <- getLastSnapshotSeqNr(persistenceId)
      ss <- getOldSnapshot[A](persistenceId, lessThanSeqNr = snr)
    } yield ss.a
  }

  private def getOldSnapshot[A](
    persistenceId: String,
    lessThanSeqNr: Long
  )(implicit system: au.ActorSystem): Future[SnapshotProbe.SnapshotInfo[A]] = {
    import SnapshotProbe._
    import system.dispatcher
    val p = Promise[SnapshotInfo[A]]()
    val ref = actorRef[Unit](system.actorOf(au.Props(new SnapshotProbe[A](
      persistenceId,
      lessThanSeqNr,
      p
    ))))
    ref ! (()) // stops the actor

    for {
      _ <- awaitTermination(ref)
      si <- p.future
    } yield si
  }

  private def getLastSnapshotSeqNr(persistenceId: String)(implicit system: au.ActorSystem): Future[Long] = {
    import system.dispatcher
    getOldSnapshot[Any](persistenceId, lessThanSeqNr = Long.MaxValue).map(_.m.sequenceNr)
  }
}

object AbstractPersistenceSpec {

  private[persistence] implicit class ContextOps[A](ctx: at.ActorContext[A]) {
    def log: LoggingAdapter =
      ctx.system.log
  }

  sealed trait Mes
  final case class GetInt(replyTo: at.ActorRef[Int]) extends Mes
  final case class SetInt(i: Int, ackTo: at.ActorRef[Int]) extends Mes
  final case object Stop extends Mes

  class MyException(msg: String) extends Exception(msg)

  sealed trait PP
  final case class N(i: Int) extends PP
  final case class AreYouAlive(replyTo: at.ActorRef[Boolean]) extends PP

  sealed trait PersistAction
  sealed trait RecoverAction
  sealed trait SnapshotAction
  case object Ok extends RecoverAction with PersistAction with SnapshotAction
  case object Fail extends RecoverAction with PersistAction with SnapshotAction
  case object No extends RecoverAction with SnapshotAction
  case object Reject extends PersistAction

  class FailingJournalPlugin(config: Config) extends akka.persistence.journal.AsyncWriteJournal {

    val persistAct: PersistAction = config.getString("persist-action").toLowerCase match {
      case "ok" => Ok
      case "fail" => Fail
      case "reject" => Reject
      case x: Any => throw new IllegalArgumentException(x)
    }

    val recoverAct: RecoverAction = config.getString("recovery-action").toLowerCase match {
      case "ok" => Ok
      case "fail" => Fail
      case "no" => No
      case x: Any => throw new IllegalArgumentException(x)
    }

    def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
      Future.successful(())

    def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
      Future.successful(if (recoverAct eq No) 0L else 1L)
    }

    def asyncReplayMessages(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long,
      max: Long
    )(recoveryCallback: akka.persistence.PersistentRepr => Unit): Future[Unit] = {
      recoverAct match {
        case No =>
          throw new IllegalStateException
        case Ok =>
          recoveryCallback(akka.persistence.PersistentRepr((), 1))
          Future.successful(())
        case Fail =>
          Future.failed(new MyException("recovery"))
      }
    }

    def asyncWriteMessages(messages: immutable.Seq[akka.persistence.AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
      persistAct match {
        case Ok =>
          Future.successful(messages.map { _ => Success(()) })
        case Reject =>
          Future.successful(messages.map { _ => Failure(new MyException("persist")) })
        case Fail =>
          Future.failed(new MyException("persist"))
      }
    }
  }

  class FailingSnapshotPlugin(config: Config) extends akka.persistence.snapshot.SnapshotStore {

    val snapshotAct: SnapshotAction = config.getString("snapshot-action").toLowerCase match {
      case "ok" => Ok
      case "fail" => Fail
      case x: Any => throw new IllegalArgumentException(x)
    }

    override def loadAsync(persistenceId: String, criteria: ap.SnapshotSelectionCriteria): Future[Option[ap.SelectedSnapshot]] = {
      Future.successful(None)
    }

    override def saveAsync(metadata: ap.SnapshotMetadata, snapshot: Any): Future[Unit] = {
      snapshotAct match {
        case Ok | No => Future.successful(())
        case Fail => Future.failed(new MyException("snap"))
      }
    }

    override def deleteAsync(metadata: ap.SnapshotMetadata): Future[Unit] =
      Future.successful(())

    override def deleteAsync(persistenceId: String, criteria: ap.SnapshotSelectionCriteria): Future[Unit] =
      Future.successful(())
  }

  def randomSysName(): String =
    s"testSystem-${UUID.randomUUID()}"
}
