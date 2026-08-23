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
import org.azora.lang.ir.ctorSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A type may declare several `ctor`s, and they must not collide on one symbol.
 *
 * What tells two ctors apart is how they are *called*: by how many arguments,
 * and by whether the call repeats (`.(fill) * count`). A repeated ctor takes its
 * repetition as a trailing `count` parameter, so arity alone would make it
 * collide with an ordinary ctor one argument wider - which is exactly what
 * `std/container/list.az` did, three ctors deep.
 *
 * A type declaring one `ctor` keeps the plain `Type_ctor`, so the common case is
 * unchanged and no existing symbol moved.
 */
class CtorOverloadTest {

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

    private val threeCtors =
        """
        pack Buffer {
            var size: Int = 0
            var fill: Int = 0
        }

        impl Buffer {
            ctor .() * count {
                self.size = count
            }

            ctor .(initial: Int) {
                self.size = initial
            }

            ctor .(fill: Int) * count {
                self.size = count
                self.fill = fill
            }
        }

        func main() {}
        """.trimIndent()

    @Test fun threeCtorsGetThreeSymbols() {
        val ctors = funcNames(threeCtors).filter { it.startsWith("Buffer_ctor") }
        assertEquals(3, ctors.size, "each overload keeps its own symbol: $ctors")
        assertEquals(ctors.size, ctors.distinct().size, "and they are distinct: $ctors")
    }

    @Test fun eachOverloadGetsItsOwnFactory() {
        val factories = funcNames(threeCtors).filter { it.startsWith("__ctor_Buffer") }
        assertEquals(3, factories.size, "one factory per overload: $factories")
        assertEquals(factories.size, factories.distinct().size, "distinct: $factories")
    }

    @Test fun repetitionIsPartOfTheIdentity() {
        // `() * count` and `(initial: Int)` are both one written argument. Without
        // the repetition in the key they would be the same symbol.
        assertTrue(
            ctorSymbol("Buffer", 1, repeated = true, overloaded = true) !=
                ctorSymbol("Buffer", 1, repeated = false, overloaded = true),
            "a repeated ctor and a plain one of the same arity differ",
        )
    }

    @Test fun aritiesAreToldApart() {
        assertTrue(
            ctorSymbol("Buffer", 1, repeated = true, overloaded = true) !=
                ctorSymbol("Buffer", 2, repeated = true, overloaded = true),
        )
    }

    @Test fun theSingleCtorCaseIsUnchanged() {
        // Nothing moved for a type with one ctor, so no existing program's
        // symbols changed when overloads became possible.
        assertEquals("Buffer_ctor", ctorSymbol("Buffer", 1, repeated = false, overloaded = false))
        assertEquals("Buffer_ctor", ctorSymbol("Buffer", 2, repeated = true, overloaded = false))
        val names = funcNames(
            """
            pack Point {
                var x: Int = 0
            }

            impl Point {
                ctor .(x: Int) {
                    self.x = x
                }
            }

            func main() {}
            """.trimIndent(),
        )
        assertTrue("Point_ctor" in names, "the plain symbol is kept: ${names.filter { "Point" in it }}")
    }

    @Test fun anOverloadedCtorStillRunsFromItsFactory() {
        // The factory calls the ctor, so both sides have to agree on the symbol.
        val items = ir(threeCtors)
        val declared = items.filterIsInstance<IrTopLevel.Func>().map { it.function.name }.toSet()
        val called = items.filterIsInstance<IrTopLevel.Func>()
            .filter { it.function.name.startsWith("__ctor_Buffer") }
            .mapNotNull { fn ->
                Regex("""Call\(name=(Buffer_ctor[\w]*)""").find(fn.function.body.toString())?.groupValues?.get(1)
            }
        assertEquals(3, called.size, "each factory calls a ctor: $called")
        assertTrue(called.all { it in declared }, "every call names a declared ctor: $called vs $declared")
    }

    // -- a variadic ctor takes any number of arguments ----------------------

    private val variadic =
        """
        pack Bag<T> {
            var _items: T* = null
            var _n: Int = 0
        }

        impl Bag<T> {
            prop &.count: Int = self._n

            ctor .(...args: T) {
                self._items = alloc args
                self._n = args.length
            }
        }
        """.trimIndent()

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return org.azora.lang.backend.IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun aVariadicCtorTakesMoreArgumentsThanItHasSlots() {
        // `.(1, 2, 3)` reaches a ctor with one parameter: the last one takes
        // however many arguments are left. Selection used to search only upwards
        // from the argument count, so a three-argument call never found it.
        assertEquals(
            "3",
            run("import std.io\n$variadic\nfunc main() {\n    var b: Bag<Int> = .(1, 2, 3)\n    println(b.count)\n}"),
        )
    }

    @Test fun aVariadicCtorTakesNoneAtAll() {
        assertEquals(
            "0",
            run("import std.io\n$variadic\nfunc main() {\n    var b: Bag<Int> = .()\n    println(b.count)\n}"),
        )
    }

    @Test fun anInlineForOverARuntimeVariadicIsRefused() {
        // `args` is a packed array that exists at run time, so there is nothing
        // for a compile-time loop to expand. The buffer is `alloc args`.
        val result = Compiler().compile(
            """
            pack Bag<T> {
                var _items: T* = null
            }
            impl Bag<T> {
                ctor .(...args: T) {
                    self._items = alloc .(
                        inline for e in args { e }
                    )
                }
            }
            func main() {}
            """.trimIndent(),
        )
        val failure = assertIs<CompilationResult.Failure>(result)
        assertTrue(
            failure.errors.any { "'inline for' argument was not expanded" in it },
            "expected a clear refusal, got: ${failure.errors}",
        )
    }
}
