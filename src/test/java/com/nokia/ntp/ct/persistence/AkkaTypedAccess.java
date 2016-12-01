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

package com.nokia.ntp.ct.persistence;

/**
 * For some tests, we need access to otherwise
 * unaccessible parts of Akka Typed.
 */
class AkkaTypedAccess {

	public static <A> akka.actor.ActorRef getUntypedActorRef(akka.typed.ActorRef<A> ref) {
		return akka.typed.adapter.package$.MODULE$.toUntyped(ref);
	}
}
