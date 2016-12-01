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

import com.typesafe.config.Config

import akka.{ actor => au }

final class PersistenceSettings private (private[this] val system: au.ActorSystem) extends au.Extension {

  val config: Config = system.settings.config

  val debug: Boolean = config.getBoolean("com.nokia.ntp.ct.persistence.debug")

  val autoDelete: Boolean = config.getBoolean("com.nokia.ntp.ct.persistence.auto-delete")
}

object PersistenceSettings extends au.ExtensionId[PersistenceSettings] with au.ExtensionIdProvider {

  override def createExtension(system: au.ExtendedActorSystem): PersistenceSettings =
    new PersistenceSettings(system)

  override def lookup(): akka.actor.ExtensionId[_ <: au.Extension] =
    PersistenceSettings
}
