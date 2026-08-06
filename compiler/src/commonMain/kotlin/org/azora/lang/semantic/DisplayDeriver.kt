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

package org.azora.lang.semantic

import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.MemberCallStyle
import org.azora.lang.frontend.ParamModifier
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef

/**
 * The `String` an interpolated value renders to, for a type that implements
 * `Display`.
 *
 * `Display`'s member writes into a `Formatter` rather than returning a string -
 * which is what lets a composite render its parts into one buffer - but
 * `"${value}"` needs a `String`. Something has to do the three steps between,
 * and doing them as *generated Azora* rather than as a backend intrinsic is
 * what keeps the three backends from each needing their own copy: the result is
 * an ordinary member, lowered and called like any other.
 *
 * ```azora
 * impl Vec2 {
 *     prop __displayString[self: Self&]: String {
 *         var formatter = std::Formatter()
 *         self.display(formatter)
 *         return formatter.rendered
 *     }
 * }
 * ```
 */
object DisplayDeriver {
    /** The generated member's name; [org.azora.lang.ir.IrGenerator] calls it. */
    // Not `__display`: that mangles to the same symbol as the spec's own
    // `display` member, and the generated body - which calls `display` - would
    // call itself.
    const val MEMBER = "__displayString"

    fun derive(program: Program): Program {
        // A type that implements `Display`, whatever else it implements.
        val implementors = program.items.filterIsInstance<TopLevel.Impl>()
            .filter { it.traitName == "Display" }
            .map { it.typeName }
            .distinct()
        if (implementors.isEmpty()) return program
        // Never twice, and never over one the author wrote.
        val existing = program.items.filterIsInstance<TopLevel.Impl>()
            .filter { impl -> impl.methods.any { it.name == MEMBER } }
            .map { it.typeName }
            .toSet()
        val packs = program.items.filterIsInstance<TopLevel.Pack>().associateBy { it.name }

        val generated = implementors.filterNot { it in existing }.mapNotNull { typeName ->
            val pack = packs[typeName] ?: return@mapNotNull null
            renderImpl(typeName, pack)
        }
        if (generated.isEmpty()) return program
        return program.copy(items = program.items + generated)
    }

    private fun renderImpl(typeName: String, pack: TopLevel.Pack): TopLevel.Impl {
        val line = pack.line
        val formatter = Expr.Identifier("__formatter", line, 0, 11)
        val body = listOf(
            // `var __formatter = std::Formatter()`
            Stmt.VarDecl(
                "__formatter",
                TypeAnnotation.Explicit(TypeRef.Named("Formatter")),
                Expr.Call("std__Formatter", emptyList(), line, 0),
                line,
                0,
            ),
            // `self.display(__formatter)`
            Stmt.ExprStmt(
                Expr.MethodCall(
                    Expr.Identifier("self", line, 0, 4),
                    "display",
                    listOf(formatter),
                    line,
                    0,
                ),
                line,
                0,
            ),
            // `return __formatter.rendered`
            Stmt.Return(Expr.Member(formatter, "rendered", line, 0), line, 0),
        )
        return TopLevel.Impl(
            typeName,
            listOf(
                FuncDecl(
                    MEMBER,
                    emptyList(),
                    TypeAnnotation.Explicit(TypeRef.Named("String")),
                    body,
                    false,
                    emptyList(),
                    line,
                    0,
                    receiverModifier = ParamModifier.SHARED,
                    receiverName = "self",
                    memberCallStyle = MemberCallStyle.PROPERTY,
                ),
            ),
            null,
            line,
            0,
            typeParams = pack.typeParams,
        )
    }
}
