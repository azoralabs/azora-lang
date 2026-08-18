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

package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.ir.IrTopLevel
import org.azora.lang.ir.symbolDenotes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The IR ABI for a scoped declaration: **one** leading `__`, then the scope path
 * and the name joined by single `_`.
 *
 * ```
 * scope a { func b() }        ->  __a_b
 * scope x { scope y { z } }   ->  __x_y_z
 * ```
 *
 * The frontend spells the same thing `a__b` while it is still parsing, and
 * `IrSymbolCanonicalizer` converts it once on the way into IR. Keeping the two
 * apart is what stops separators accumulating through specialization
 * (`__a__b__c`), and gives every backend one spelling to match on.
 *
 * These assertions are on the IR, not on the parse, because the IR spelling is
 * the one the backends and the linker see. [org.azora.lang.frontend.ScopeNamespaceTest]
 * covers the frontend spelling.
 */
class ScopeSymbolManglingTest {

    private fun ir(source: String): List<IrTopLevel> {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return result.ir.items
    }

    private fun funcNames(source: String): List<String> =
        ir(source).filterIsInstance<IrTopLevel.Func>().map { it.function.name }

    private fun globalNames(source: String): List<String> =
        ir(source).filterIsInstance<IrTopLevel.Global>().mapNotNull {
            Regex("""name=([A-Za-z_$][\w$]*)""").find(it.stmt.toString())?.groupValues?.get(1)
        }

    @Test fun aScopedFunctionIsUnderscoreScopeUnderscoreName() {
        assertTrue(
            "__a_b" in funcNames("scope a {\n    func b(): Int { return 1 }\n}\nfunc main() {}"),
            "expected '__a_b'",
        )
    }

    @Test fun aScopedBindingUsesTheSameShape() {
        assertTrue(
            "__a_C" in globalNames("scope a {\n    fin C: Int = 2\n}\nfunc main() {}"),
            "expected '__a_C'",
        )
    }

    @Test fun aNestedScopeJoinsWithSingleUnderscores() {
        assertTrue(
            "__x_y_z" in funcNames(
                "scope x {\n    scope y {\n        func z(): Int { return 3 }\n    }\n}\nfunc main() {}",
            ),
            "expected '__x_y_z'",
        )
    }

    @Test fun theSeparatorNeverDoubles() {
        // `a__b` is the frontend's spelling and must not survive into IR, or a
        // later pass joining onto it would produce `__a__b__c`.
        val names = funcNames(
            "scope x {\n    scope y {\n        func z(): Int { return 3 }\n    }\n}\nfunc main() {}",
        )
        assertTrue(
            names.none { it.removePrefix("__").contains("__") },
            "no IR symbol carries a doubled separator: ${names.filter { it.removePrefix("__").contains("__") }}",
        )
    }

    @Test fun anUnscopedDeclarationIsLeftAlone() {
        // Nothing qualified it, so there is nothing to prefix.
        val names = funcNames("func plain(): Int { return 1 }\nfunc main() {}")
        assertTrue("plain" in names, "expected 'plain' unchanged: $names")
    }

    @Test fun aBackendRecognisesEitherSpelling() {
        // How a backend intercepts a compiler-provided declaration: by local
        // name, not by a spelling that depends on where the library declares it.
        assertTrue(symbolDenotes("println", "println"), "unqualified")
        assertTrue(symbolDenotes("__a_println", "println"), "IR-qualified")
        assertTrue(symbolDenotes("a__println", "println"), "frontend-qualified")
        // A method symbol is `Type_member` and must not be mistaken for one.
        assertTrue(!symbolDenotes("Compare_isLess", "isLess"), "a method is not an intrinsic")
    }

    @Test fun aScopedCallReachesItsScopedDeclaration() {
        // The call site and the declaration have to meet at the same symbol.
        val names = funcNames(
            """
            scope a {
                func b(): Int { return 1 }
            }
            func main() {
                fin x = a::b()
            }
            """.trimIndent(),
        )
        assertEquals(1, names.count { it == "__a_b" }, "declared once: $names")
    }
}
