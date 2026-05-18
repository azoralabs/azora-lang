/*
 * Copyright 2026 AzoraTech
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

package dev.azora.convention

import org.gradle.api.Project
import java.util.Locale

fun Project.pathToPackageName(): String {
    val relativePackageName = path
        .replace(":", ".")
        .replace("-", "")
        .lowercase()
    return "dev.azora$relativePackageName"
}

fun Project.pathToResourcePrefix() = path
    .replace(":", "_")
    .replace("-", "_")
    .lowercase()
    .drop(1) + "_"

fun Project.pathToFrameworkName(): String {
    val parts = this.path.split(":", "-", "_", " ")
    return parts.joinToString("") { part ->
        part.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}
