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

package org.azora.azls

import kotlinx.serialization.Serializable

/**
 * A colorized region of source text.
 *
 * @property start inclusive character offset
 * @property end exclusive character offset
 * @property type one of `keyword`, `string`, `interpolation-punctuation`,
 *   `number`, `comment`, `function`, `variable`, `parameter`, `type`,
 *   `generic`, `label`, `scope`, `annotation`, `macro`, or `char`
 */
@Serializable
data class HighlightSpan(val start: Int, val end: Int, val type: String)

/**
 * Compatibility JSON view of a structured compiler diagnostic.
 * New editor integrations consume the richer LSP diagnostic directly.
 */
@Serializable
data class Diagnostic(
    val line: Int,
    val message: String,
    val severity: String,
    val column: Int = 1,
    val endLine: Int = line,
    val endColumn: Int = column,
    val code: String = "AZ-CMP-9000",
    val stage: String = "compiler",
)

/**
 * A completion proposal.
 *
 * @property label text shown in the popup
 * @property kind `keyword`, `function`, `pack`, `enum`, `enumMember`,
 *   `variable`, `field`, `method` or `param`
 * @property detail signature / type information shown next to the label
 * @property insert text inserted into the document when accepted
 * @property importModule module AZLS must import when this otherwise-invisible
 *   workspace symbol is accepted
 */
@Serializable
data class Completion(
    val label: String,
    val kind: String,
    val detail: String = "",
    val insert: String = "",
    val importModule: String? = null,
)

/**
 * Hover information for the symbol under the caret.
 *
 * @property signature the declaration signature (monospace)
 * @property detail extra type information
 * @property doc the doc-comment block preceding the declaration, if any
 */
@Serializable
data class Hover(val signature: String, val detail: String = "", val doc: String = "")

/**
 * The declaration site of the symbol under the caret.
 *
 * @property line 1-based declaration line; when [inCurrentFile] is false this is
 *   0 and [name] carries the symbol so the client can search other files
 * @property column 1-based column, when known
 * @property name the symbol name (used to locate cross-file declarations)
 * @property module source module for a cross-file declaration; clients use it
 *   to avoid jumping to an unrelated same-named symbol
 * @property inCurrentFile whether the declaration is in the edited document
 */
@Serializable
data class Definition(
    val line: Int,
    val column: Int = 0,
    val name: String = "",
    val module: String? = null,
    val inCurrentFile: Boolean = true,
)

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
