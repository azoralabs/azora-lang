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
import org.azora.lang.frontend.Param
import org.azora.lang.frontend.ParamModifier
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef

/**
 * Field-wise `Equal`, `Order` and `Hash` for a pack that asked for them.
 *
 * `DIPs/OPERATOR_OVERLOADING_DIP.MD` §7: `T derives [Equal, Order]` or the
 * standalone `derive [Equal, Order] for T` is the derive request, and an `impl`
 * always contains the author's own implementation. The
 * generated members are ordinary Azora AST, so the resolver, the lowerer and
 * all three backends see nothing new - the same approach `SerializationDeriver`
 * takes.
 *
 * **Nothing is derived silently.** Unlike `Clone`, which is derived for any
 * pack that did not say, comparison is derived only where it is written.
 * Equality has semantics - a cache field, an interned id, a tolerance all
 * change what "the same" means - and a compiler that guesses will be wrong for
 * exactly the types where it matters most.
 */
object ComparisonDeriver {
    data class Result(val program: Program, val errors: List<String>)

    private const val PARTIAL_EQUAL = "PartialEqual"
    private const val EQUAL = "Equal"
    private const val ORDER = "Order"
    private const val HASH = "Hash"

    fun derive(program: Program): Result {
        val packs = program.items.filterIsInstance<TopLevel.Pack>()
            .filterNot { it.isBridge || it.isUnion }
            .associateBy { it.name }
        if (packs.isEmpty()) return Result(program, emptyList())

        // Only nodes explicitly produced by `derive` / `derives` are requests.
        // An empty manual impl is never interpreted as compiler generation.
        val requested = mutableMapOf<String, MutableSet<String>>()
        val written = mutableMapOf<String, MutableSet<String>>()
        for (item in program.items.filterIsInstance<TopLevel.Impl>()) {
            val capability = item.traitName ?: continue
            if (capability !in setOf(EQUAL, ORDER, HASH)) continue
            if (item.typeName !in packs) continue
            if (item.isDerived) {
                requested.getOrPut(item.typeName) { mutableSetOf() }.add(capability)
            } else {
                written.getOrPut(item.typeName) { mutableSetOf() }.add(capability)
            }
        }
        // A type that states `Equal` also gets `Hash`, over the same fields in
        // the same order (§7.3). The two are one contract: a `HashMap` looks in
        // the bucket the hash names, so a hash that disagrees with equality
        // loses the entry rather than finding it.
        for ((type, caps) in requested) {
            if (EQUAL in caps) caps.add(HASH)
            // A total order and its equality have to agree, so `Order` implies
            // both - this is the `requires` chain, made real.
            if (ORDER in caps) { caps.add(EQUAL); caps.add(HASH) }
        }

        // Every conformance already stated, so the chain below adds only what is
        // missing rather than declaring a duplicate.
        val stated = mutableMapOf<String, MutableSet<String>>()
        for (item in program.items.filterIsInstance<TopLevel.Impl>()) {
            item.traitName?.let { stated.getOrPut(item.typeName) { mutableSetOf() }.add(it) }
        }

        val errors = mutableListOf<String>()
        val generated = mutableListOf<TopLevel>()
        for ((typeName, caps) in requested) {
            val pack = packs.getValue(typeName)
            if (pack.fields.isEmpty()) continue
            val alreadyWritten = written[typeName].orEmpty()
            for (capability in caps.sorted()) {
                if (capability in alreadyWritten) continue
                when (capability) {
                    EQUAL -> generated += equalsImpl(pack)
                    ORDER -> generated += orderImpl(pack)
                    HASH -> generated += hashImpl(pack)
                }
            }
            // The generated `oper==` *is* `PartialEqual`, so the request
            // satisfies the `requires` chain it sits on rather than asking the
            // author to restate a capability the compiler just supplied.
            if (PARTIAL_EQUAL !in stated[typeName].orEmpty()) {
                generated += TopLevel.Impl(
                    typeName,
                    emptyList(),
                    PARTIAL_EQUAL,
                    pack.line,
                    0,
                    typeParams = pack.typeParams,
                    isDerived = true,
                    hasBody = false,
                )
            }
            for (implied in listOf(EQUAL, HASH)) {
                if (implied in caps && implied !in stated[typeName].orEmpty()) {
                    generated += TopLevel.Impl(
                        typeName,
                        emptyList(),
                        implied,
                        pack.line,
                        0,
                        typeParams = pack.typeParams,
                        isDerived = true,
                        hasBody = false,
                    )
                }
            }
        }
        if (generated.isEmpty()) return Result(program, errors)
        return Result(program.copy(items = program.items + generated), errors)
    }

    private fun self(line: Int) = Expr.Identifier("self", line, 0, 4)
    private fun rhs(line: Int) = Expr.Identifier("rhs", line, 0, 3)

    private fun field(target: Expr, name: String, line: Int) =
        Expr.Member(target, name, line, 0)

    /** `oper== [self: P&](rhs: P&): Bool { return self.a == rhs.a && … }` */
    private fun equalsImpl(pack: TopLevel.Pack): TopLevel.Impl {
        val line = pack.line
        val comparison = pack.fields
            .map { f ->
                Expr.Binary(
                    field(self(line), f.name, line),
                    TokenType.EQUAL_EQUAL,
                    field(rhs(line), f.name, line),
                    line,
                ) as Expr
            }
            .reduce { acc, next -> Expr.Binary(acc, TokenType.AND_AND, next, line) }
        return implOf(
            pack,
            FuncDecl(
                "oper==",
                listOf(Param("rhs", selfRef(pack), modifier = ParamModifier.SHARED)),
                TypeAnnotation.Explicit(TypeRef.Named("Bool")),
                listOf(Stmt.Return(comparison, line, 0)),
                false,
                emptyList(),
                line,
                0,
                receiverModifier = ParamModifier.SHARED,
                receiverName = "self",
            ),
        )
    }

    /**
     * Lexicographic `<=>`, in declaration order, returning at the first field
     * that is not equal.
     *
     * Written with `<` rather than a nested `<=>` so it holds for any field
     * whose type is ordered, and so each field's comparison is the same two
     * tests a hand-written one makes.
     */
    private fun orderImpl(pack: TopLevel.Pack): TopLevel.Impl {
        val line = pack.line
        val body = mutableListOf<Stmt>()
        for (f in pack.fields) {
            val mine = field(self(line), f.name, line)
            val theirs = field(rhs(line), f.name, line)
            body += ifReturn(
                Expr.Binary(mine, TokenType.LESS, theirs, line),
                compareVariant("Less", line),
                line,
            )
            body += ifReturn(
                Expr.Binary(theirs, TokenType.LESS, mine, line),
                compareVariant("Greater", line),
                line,
            )
        }
        body += Stmt.Return(compareVariant("Equal", line), line, 0)
        return implOf(
            pack,
            FuncDecl(
                "oper<=>",
                listOf(Param("rhs", selfRef(pack), modifier = ParamModifier.SHARED)),
                TypeAnnotation.Explicit(TypeRef.Named("Compare")),
                body,
                false,
                emptyList(),
                line,
                0,
                receiverModifier = ParamModifier.SHARED,
                receiverName = "self",
            ),
        )
    }

    /**
     * `prop hash[self: P&]: ULong` - the field-wise hash, mixed so that field
     * order matters and two packs holding the same values in different slots do
     * not collide.
     */
    private fun hashImpl(pack: TopLevel.Pack): TopLevel.Impl {
        val line = pack.line
        var acc: Expr = Expr.IntLiteral(17, line, 0)
        for (f in pack.fields) {
            val scaled = Expr.Binary(acc, TokenType.STAR, Expr.IntLiteral(31, line, 0), line)
            acc = Expr.Binary(
                scaled,
                TokenType.PLUS,
                field(field(self(line), f.name, line), "hash", line),
                line,
            )
        }
        return implOf(
            pack,
            FuncDecl(
                "hash",
                emptyList(),
                TypeAnnotation.Explicit(TypeRef.Named("ULong")),
                listOf(Stmt.Return(acc, line, 0)),
                false,
                emptyList(),
                line,
                0,
                receiverModifier = ParamModifier.SHARED,
                receiverName = "self",
                memberCallStyle = MemberCallStyle.PROPERTY,
            ),
        )
    }

    private fun ifReturn(condition: Expr, value: Expr, line: Int): Stmt =
        Stmt.If(condition, listOf(Stmt.Return(value, line, 0)), null, line, 0)

    private fun compareVariant(name: String, line: Int): Expr =
        Expr.Member(Expr.Identifier("Compare", line, 0, 7), name, line, 0)

    private fun selfRef(pack: TopLevel.Pack): TypeRef =
        TypeRef.Named(pack.name, pack.typeParams.map { TypeRef.Named(it) })

    private fun implOf(pack: TopLevel.Pack, method: FuncDecl): TopLevel.Impl =
        TopLevel.Impl(
            pack.name,
            listOf(method),
            null,
            pack.line,
            0,
                        typeParams = pack.typeParams,
                        isDerived = true,
                        hasBody = false,
        )
}
