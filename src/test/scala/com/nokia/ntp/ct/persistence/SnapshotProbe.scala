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

import scala.concurrent.Promise

import akka.{ persistence => ap }

private final class SnapshotProbe[A](
    override val persistenceId: String,
    lessThanSeqNr: Long,
    promise: Promise[SnapshotProbe.SnapshotInfo[A]]
) extends ap.PersistentActor {

  import SnapshotProbe._

  private[this] val fail = new RuntimeException("no such snapshot")

  override def recovery = {
    super.recovery.copy(fromSnapshot = ap.SnapshotSelectionCriteria(
      maxSequenceNr = lessThanSeqNr - 1
    ))
  }

  def receiveCommand: PartialFunction[Any, Unit] = {
    case msg: Any =>
      promise.tryFailure(fail)
      this.context.stop(self)
  }

  def receiveRecover: PartialFunction[Any, Unit] = {
    case ap.RecoveryCompleted =>
      promise.tryFailure(fail)
    case ap.SnapshotOffer(m, s) =>
      promise.success(SnapshotInfo[A](m, s.asInstanceOf[A]))
    case rec: Any =>
    // recovery offer, ignore
  }
}

private object SnapshotProbe {
  final case class SnapshotInfo[A](m: ap.SnapshotMetadata, a: A)
}
