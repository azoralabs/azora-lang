/*
 * Copyright 2026 AzoraLabs
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

package org.azora.lang

/**
 * Portable stand-in for `java.util.Map.putIfAbsent`, which Kotlin's common `MutableMap` does not
 * expose (it is JVM-only). First-writer-wins: if [key] is absent, stores [value] and returns null;
 * otherwise leaves the existing entry untouched and returns it.
 *
 * Callers rely on the "do not overwrite an existing entry" semantics (e.g. stdlib symbol indexing
 * where the first definition wins, and singleton caching under concurrency).
 */
internal inline fun <K, V> MutableMap<K, V>.putIfAbsentCompat(key: K, value: V): V? {
    val existing = get(key)
    if (existing == null && !containsKey(key)) put(key, value)
    return existing
}
