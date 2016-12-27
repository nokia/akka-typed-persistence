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

import akka.typed.adapter.ActorContextAdapter;

/**
 * The whole reason for the existence of this Java
 * class is that `akka.typed.adapter.ActorContextAdapter`
 * is package private, but we must access its underlying
 * untyped ActorContext.
 */
final class ActorContextAccess {

	public static akka.actor.ActorContext getUntypedActorContext(akka.typed.ActorContext<?> typedContext) {
		if (typedContext instanceof ActorContextAdapter<?>) {
			ActorContextAdapter<?> adapter = (ActorContextAdapter<?>) typedContext;
			akka.actor.ActorContext result = adapter.akka$typed$adapter$ActorContextAdapter$$ctx;
			if (result == null) {
				throw new IllegalArgumentException("underlying ActorContext is null");
			} else {
				return result;
			}
		} else {
			throw new UnsupportedOperationException(
				"only adapted untyped ActorContexts permissible (got " +
				typedContext +
				" of class " +
				typedContext.getClass() +
				")"
			);
		}
	}
}
