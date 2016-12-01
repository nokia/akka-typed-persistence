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

import scala.collection.mutable
import scala.concurrent.Future

import akka.persistence.SelectedSnapshot
import akka.persistence.SnapshotMetadata
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.snapshot.SnapshotStore

import cats.implicits._

/** In-memory snapshot store, only for testing. */
final class MockSnapshotStore extends SnapshotStore {

  // we have to store the snapshots somewhere ...
  @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
  private[this] val state: mutable.Map[String, List[(SnapshotMetadata, Any)]] =
    mutable.Map()

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
    val lst = state.getOrElse(persistenceId, Nil)
    state.put(persistenceId, lst.filterNot { case (md, _) => matches(md, criteria) })
    Future.successful(())
  }

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
    val lst = state.getOrElse(metadata.persistenceId, Nil)
    state.put(metadata.persistenceId, lst.filterNot { case (md, _) => md === metadata })
    Future.successful(())
  }

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    val lst = state.getOrElse(persistenceId, Nil)
    val hit = lst.find {
      case (metadata, _) =>
        matches(metadata, criteria)
    }

    Future.successful(hit.map { case (md, ss) => SelectedSnapshot(md, ss) })
  }

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    val lst = state.getOrElse(metadata.persistenceId, Nil)
    state.put(metadata.persistenceId, (metadata, snapshot) :: lst)
    Future.successful(())
  }

  private[this] def matches(metadata: SnapshotMetadata, criteria: SnapshotSelectionCriteria): Boolean = {
    (metadata.sequenceNr <= criteria.maxSequenceNr) && (metadata.timestamp <= criteria.maxTimestamp) &&
      (metadata.sequenceNr >= criteria.minSequenceNr) && (metadata.timestamp >= criteria.minTimestamp)
  }
}
