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

package org.azora.azls

import kotlinx.serialization.Serializable

/**
 * A colorized region of source text.
 *
 * @property start inclusive character offset
 * @property end exclusive character offset
 * @property type one of `keyword`, `string`, `interpolation`, `number`,
 *   `comment`, `function`, `type`, `annotation`, `char`
 */
@Serializable
data class HighlightSpan(val start: Int, val end: Int, val type: String)

/**
 * A compiler diagnostic mapped to the edited document.
 *
 * @property line 1-based line in the document
 * @property message human-readable description
 * @property severity `error` or `warning`
 */
@Serializable
data class Diagnostic(val line: Int, val message: String, val severity: String)

/**
 * A completion proposal.
 *
 * @property label text shown in the popup
 * @property kind `keyword`, `function`, `pack`, `enum`, `enumMember`,
 *   `variable`, `field`, `method` or `param`
 * @property detail signature / type information shown next to the label
 * @property insert text inserted into the document when accepted
 */
@Serializable
data class Completion(val label: String, val kind: String, val detail: String = "", val insert: String = "")

/** Hover information for the symbol under the caret. */
@Serializable
data class Hover(val signature: String, val detail: String = "")

/** An outline entry (top-level declaration). */
@Serializable
data class DocumentSymbol(val name: String, val kind: String, val line: Int, val detail: String = "")

/** One variable visible at a debug pause. */
@Serializable
data class DebugLocal(val name: String, val value: String)

/**
 * Debug-session snapshot polled by Studio.
 *
 * @property status `none`, `starting`, `running`, `paused`, `terminated` or `failed`
 * @property line 1-based document line while paused
 * @property output program output produced since the previous poll
 */
@Serializable
data class DebugStatus(
    val status: String,
    val line: Int = 0,
    val pauseId: Int = 0,
    val locals: List<DebugLocal> = emptyList(),
    val output: String = "",
    val error: String? = null,
)
