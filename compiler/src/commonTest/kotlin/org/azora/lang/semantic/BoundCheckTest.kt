package org.azora.lang.semantic

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A bound is one requirement however it is spelled - `<T: Spec>` or
 * `where T: Spec` - and it holds wherever the declaration is used. Before
 * this, a bound written in a type-parameter list was kept only on a function,
 * and a `where` clause only checked a type written with its arguments in a
 * body: `pack Box<T: Shape>` accepted `Box<NoShape>`, and a member reached
 * through it failed at run time with "no method 'area'".
 */
class BoundCheckTest {
    private val shapes = """
        import std.io
        spec Shape { func &.area(): Int }
        pack Sq { var s: Int }
        impl Shape for Sq { func &.area(): Int = self.s * self.s }
        pack NoShape { var s: Int }
        pack Box<T: Shape> { var item: T }
        pack Free<T> { var item: T }
    """.trimIndent()

    /** The lines [body] adds after the shared declarations, numbered from 1. */
    private fun rejected(body: String): List<String> {
        val offset = shapes.lines().size
        val result = Compiler().compile(shapes + "\n" + body.trimIndent())
        val errors = assertIs<CompilationResult.Failure>(result, "the program compiled").errors
        return errors.map { error ->
            Regex("""^line (\d+):""").find(error)?.let { match ->
                "line ${match.groupValues[1].toInt() - offset}:" + error.substring(match.value.length)
            } ?: error
        }
    }

    private fun output(body: String): String {
        val result = Compiler().compile(shapes + "\n" + body.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun assertRejected(errors: List<String>, expected: String) =
        assertTrue(errors.any { expected in it }, "expected \"$expected\" in $errors")

    private val notShape = "'T is Shape': NoShape does not implement Shape"

    @Test fun anInlineBoundOnAPackIsCheckedWhereTheTypeIsWritten() {
        assertRejected(
            rejected("""
                func main() {
                    fin b = Box<NoShape>(NoShape(2))
                }
            """),
            "line 2: 'Box<NoShape>' does not satisfy its 'where' clause: $notShape",
        )
    }

    @Test fun aConstructionThatInfersItsArgumentsAnswersToTheBound() {
        assertRejected(
            rejected("""
                func main() {
                    fin b = Box(NoShape(2))
                }
            """),
            "line 2: 'Box<NoShape>' does not satisfy its 'where' clause: $notShape",
        )
    }

    @Test fun aBoundHoldsInEveryPositionATypeIsWritten() {
        val positions = mapOf(
            "func grab(b: Box<NoShape>&): Int = 1" to 1,
            "func make(): Box<NoShape>? = null" to 1,
            "pack Outer { var inner: Box<NoShape>? }" to 1,
            "func main() {\n    fin b: Box<NoShape>? = null\n}" to 2,
            "func main() {\n    fin f = { b: Box<NoShape>& -> 1 }\n}" to 2,
            "variant enum Holder {\n    Full(Box<NoShape>)\n    Empty\n}" to 2,
            "typealias Bad = Box<NoShape>" to 1,
            "func nested(f: Free<Box<NoShape>>&): Int = 1" to 1,
            "func arrayed(f: Array<Box<NoShape>>&): Int = 1" to 1,
        )
        for ((source, line) in positions) {
            val errors = rejected(source + if ("func main" in source) "" else "\nfunc main() {}")
            assertRejected(errors, "'Box<NoShape>' does not satisfy its 'where' clause: $notShape")
            assertTrue(errors.any { it.startsWith("line $line:") }, "$source\n$errors")
        }
    }

    @Test fun genericCodeMustDeclareTheBoundItPassesOn() {
        val written = rejected("""
            func<U> wrap(x: U): Int {
                fin b = Box<U>(x)
                return 1
            }
            func main() {}
        """)
        assertRejected(written, "line 2: 'T' of 'Box' must be Shape, and 'U' is not declared to be; add 'where U: Shape'")
        val inferred = rejected("""
            func<U> wrap(x: U): Int {
                fin b = Box(x)
                return 1
            }
            func main() {}
        """)
        assertRejected(inferred, "line 2: 'T' of 'Box' must be Shape, and 'U' is not declared to be; add 'where U: Shape'")
        val call = rejected("""
            func<T: Shape> twice(x: T&): Int = x.area() * 2
            func<U> forward(x: U&): Int = twice(x)
            func main() {}
        """)
        assertRejected(call, "line 2: 'T' of 'twice' must be Shape, and 'U' is not declared to be; add 'where U: Shape'")
        val explicit = rejected("""
            func<T: Shape> twice(x: T&): Int = x.area() * 2
            func<U> forward(x: U&): Int = twice<U>(x)
            func main() {}
        """)
        assertRejected(explicit, "line 2: 'T' of 'twice' must be Shape, and 'U' is not declared to be; add 'where U: Shape'")
    }

    @Test fun aDotConstructionBuildsTheTypeItsPositionDeclares() {
        val field = rejected("""
            pack Keeper<U> { var b: Box<U>? = null }
            pack Tally<T: Shape> { var count: Int = 0 }
            pack Counter<U> { var t: Tally<U> = .() }
            func main() {}
        """)
        assertRejected(field, "line 3: 'T' of 'Tally' must be Shape, and 'U' is not declared to be; add 'where U: Shape'")
        assertTrue(field.none { it.startsWith("line 1:") }, field.toString())
        val local = rejected("""
            pack Tally<T: Shape> { var count: Int = 0 }
            func<U> fresh(): Int {
                var t: Tally<U> = .()
                return t.count
            }
            func main() {}
        """)
        assertRejected(local, "line 3: 'T' of 'Tally' must be Shape, and 'U' is not declared to be; add 'where U: Shape'")
        assertEquals(
            "0\n0",
            output("""
                pack Tally<T: Shape> { var count: Int = 0 }
                pack Counter<U: Shape> { var t: Tally<U> = .() }
                func<U: Shape> fresh(): Int {
                    var t: Tally<U> = .()
                    return t.count
                }
                func main() {
                    fin c: Counter<Sq> = .()
                    println(c.t.count)
                    println(fresh<Sq>())
                }
            """),
        )
    }

    @Test fun namingABoundedTypeOverATypeParameterAsksNothing() {
        // Every `Box` this can be handed was built where its argument answered.
        assertEquals(
            "9",
            output("""
                pack Shelf<U> { var slot: Box<U>? }
                func<U> held(b: Box<U>&): Box<U>& = b
                func main() {
                    fin shelf = Shelf<Sq>(null)
                    println(held(Box(Sq(3))).item.area())
                }
            """),
        )
    }

    @Test fun aSpecRequiredByTheDeclaredBoundIsDeclaredToo() {
        // `Order` requires `Equal`, so a parameter declared `Order` may stand
        // where `Equal` is asked for.
        val result = Compiler().compile("""
            import std.io
            import std.traits
            pack Same<T: Equal> { var item: T }
            func<U: Order> keep(x: U): Int {
                fin s = Same<U>(x)
                return 1
            }
            func main() {
                println(keep(3))
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("1", IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun aMembersOwnBoundIsCheckedAtTheCall() {
        for (member in listOf(
            "func<U: Shape> &.measure(x: U&): Int = x.area()",
            "func<U> &.measure(x: U&): Int where U: Shape = x.area()",
        )) {
            val errors = rejected("""
                impl Free<T> { $member }
                func main() {
                    fin f = Free<Int>(1)
                    fin n = f.measure(NoShape(2))
                }
            """)
            assertRejected(errors, "line 4: 'measure' does not satisfy its 'where' clause here: 'U is Shape': NoShape does not implement Shape")
        }
    }

    @Test fun anOperatorsOwnBoundIsCheckedForItsOperand() {
        for (member in listOf(
            "oper+ <U: Shape> &.(x: U&): Int = x.area()",
            "oper+ <U> &.(x: U&): Int where U: Shape = x.area()",
        )) {
            val errors = rejected("""
                impl Free<T> {
                    $member
                }
                func main() {
                    fin f = Free<Int>(1)
                    fin n = f + NoShape(2)
                }
            """)
            assertRejected(errors, "line 6: 'oper+' does not satisfy its 'where' clause here: 'U is Shape': NoShape does not implement Shape")
        }
        val inPlace = rejected("""
            pack Acc { var n: Int }
            impl Acc {
                oper+= <U: Shape> !.(x: U&) { self.n = self.n + x.area() }
            }
            func main() {
                var a = Acc(0)
                a += NoShape(2)
            }
        """)
        assertRejected(inPlace, "line 7: 'oper+=' does not satisfy its 'where' clause here: 'U is Shape': NoShape does not implement Shape")
        val subscript = rejected("""
            impl Free<T> {
                oper[] <U: Shape> &.(key: U&): Int = key.area()
            }
            func main() {
                fin f = Free<Int>(1)
                fin n = f[NoShape(2)]
            }
        """)
        assertRejected(subscript, "line 6: 'oper[]' does not satisfy its 'where' clause here: 'U is Shape': NoShape does not implement Shape")
        assertEquals(
            "9\n4\n16",
            output("""
                pack Acc { var n: Int }
                impl Acc {
                    oper+= <U: Shape> !.(x: U&) { self.n = self.n + x.area() }
                }
                impl Free<T> {
                    oper+ <U: Shape> &.(x: U&): Int = x.area()
                    oper[] <U: Shape> &.(key: U&): Int = key.area()
                }
                func main() {
                    println(Free<Int>(1) + Sq(3))
                    var a = Acc(0)
                    a += Sq(2)
                    println(a.n)
                    println(Free<Int>(1)[Sq(4)])
                }
            """),
        )
    }

    @Test fun aSpecMembersBoundIsCheckedThroughASpecTypedValue() {
        val errors = rejected("""
            spec Fold { func<U: Shape> &.fold(x: U&): Int }
            impl Fold for Sq { func<U> &.fold(x: U&): Int = 1 }
            func through(f: Fold&): Int = f.fold(NoShape(2))
            func main() {}
        """)
        assertRejected(errors, "line 3: 'fold' does not satisfy its 'where' clause here: 'U is Shape': NoShape does not implement Shape")
    }

    @Test fun everyGenericDeclarationKeepsItsInlineBound() {
        val declarations = mapOf(
            "variant enum Opt<T: Shape> {\n    Some(T)\n    None\n}" to "Opt<NoShape>",
            "typealias Al<T: Shape> = Free<T>" to "Al<NoShape>",
            "spec Holder<T: Shape> { func &.get(): Int }" to "Holder<NoShape>",
        )
        for ((declaration, use) in declarations) {
            val errors = rejected("$declaration\nfunc hold(x: $use&): Int = 1\nfunc main() {}")
            assertRejected(errors, "'$use' does not satisfy its 'where' clause: $notShape")
        }
        val implemented = rejected("""
            spec Holder<T: Shape> { func &.get(): Int }
            impl Holder<NoShape> for Sq { func &.get(): Int = 1 }
            func main() {}
        """)
        assertRejected(implemented, "line 2: 'Holder<NoShape>' does not satisfy its 'where' clause: $notShape")
    }

    @Test fun aBoundNothingCouldCheckIsRefusedWhereItIsWritten() {
        val lambda = rejected("""
            func main() {
                fin f = <U: Shape>{ x: U -> 1 }
            }
        """)
        assertRejected(lambda, "a lambda cannot bound a type parameter")
        val bridge = rejected("""
            bridge func<T: Shape> foreign(x: T): Int
            func main() {}
        """)
        assertRejected(bridge, "a bridge function cannot bound a type parameter")
    }

    @Test fun aCompoundTypeArgumentHasTheCapabilitiesTheLanguageGivesIt() {
        // No `impl` can name a tuple or a nullable, so neither implements a spec of the program's.
        for ((type, shown) in listOf("Sq?" to "Sq?", "(Sq, Sq)" to "(Sq, Sq)")) {
            val errors = rejected("func hold(b: Box<$type>&): Int = 1\nfunc main() {}")
            assertRejected(errors, "'Box<$shown>' does not satisfy its 'where' clause: 'T is Shape': $shown does not implement Shape")
        }
        // Equality and hashing go part by part; ordering does not apply to a tuple.
        val keys = rejected("""
            import std.container.set
            pack P { var v: Int }
            func main() {
                var a = HashSet<(P, Int)>()
                var b = TreeSet<(Int, Int)>()
            }
        """)
        assertRejected(keys, "line 4: 'HashSet<(P, Int)>' does not satisfy its 'where' clause: 'T is Hash': (P, Int) does not implement Hash: P does not implement Hash")
        assertRejected(keys, "line 5: 'TreeSet<(Int, Int)>' does not satisfy its 'where' clause: 'T is Order': (Int, Int) does not implement Order")
        // A type parameter inside one answers with its declared bounds.
        val generic = rejected("""
            import std.container.set
            func<U> pairs(): Int {
                var s = HashSet<(U, Int)>()
                return s.size
            }
            func main() {}
        """)
        assertRejected(generic, "line 3: 'HashSet<(U, Int)>' does not satisfy its 'where' clause: 'T is Hash': (U, Int) does not implement Hash: 'U' is not declared to be Hash; add 'where U: Hash'")
        assertEquals(
            "2\n2",
            output("""
                import std.container.set
                func main() {
                    var s = HashSet<(Int, Int)>()
                    s.add((1, 2))
                    s.add((1, 2))
                    s.add((2, 1))
                    println(s.size)
                    var n = HashSet<Int?>()
                    n.add(1)
                    n.add(null)
                    n.add(null)
                    println(n.size)
                }
            """),
        )
    }

    @Test fun anImplementationMayNotDemandMoreThanItsSpecMember() {
        val errors = rejected("""
            spec Fold { func<U> &.fold(x: U&): Int }
            impl Fold for Sq { func<U: Shape> &.fold(x: U&): Int = x.area() }
            func main() {}
        """)
        assertRejected(
            errors,
            "line 2: 'Sq.fold' requires 'U: Shape', which 'Fold.fold' does not promise for 'U'; a caller through 'Fold' may pass any type there",
        )
        assertEquals(
            "4",
            output("""
                spec Fold { func<U: Shape> &.fold(x: U&): Int }
                impl Fold for Sq { func<V: Shape> &.fold(x: V&): Int = x.area() }
                func through(f: Fold&): Int = f.fold(Sq(2))
                func main() {
                    println(through(Sq(1)))
                }
            """),
        )
    }

    @Test fun whatGenericCodePassesOnIsTracedThroughEveryDeclaredValue() {
        val passOn = "must be Shape, and 'U' is not declared to be; add 'where U: Shape'"
        val sources = listOf(
            "func<U> f(x: U): Int {\n    fin y = take x\n    fin b = Box(y)\n    return 1\n}",
            "func<U> f(x: U&): Int {\n    fin y = x\n    return twice(y)\n}",
            "func<U> f(xs: Array<U>&): Int = twice(xs[0])",
            "func<U> f(p: (U, Int)&): Int = twice(p.0)",
            "func<U> f(x: U&, g: (U&) -> U): Int = twice(g(x))",
            "func<U> f(x: U&, y: U&, c: Bool): Int = twice(if c then x else y)",
            "func<V> ident(v: V&): V& = v\nfunc<U> f(x: U&): Int = twice(ident(x))",
            "func<U> f(xs: ArrayList<U>&): Int = twice(xs.first)",
        )
        for (source in sources) {
            val errors = rejected("import std.container.list\nfunc<T: Shape> twice(x: T&): Int = x.area() * 2\n$source\nfunc main() {}")
            assertRejected(errors, passOn)
        }
        assertEquals(
            "18\n8",
            output("""
                import std.container.list
                func<T: Shape> twice(x: T&): Int = x.area() * 2
                impl Box<T> { func &.again(): Int = twice(self.item) }
                func<U: Shape> viaLocal(x: U): Int {
                    fin y = take x
                    fin b = Box(y)
                    return b.again()
                }
                func<U: Shape> head(xs: ArrayList<U>&): Int = twice(xs.first)
                func main() {
                    println(viaLocal(Sq(3)))
                    var xs: ArrayList<Sq> = .()
                    xs.add(Sq(2))
                    println(head(xs))
                }
            """),
        )
    }

    @Test fun aBoundNoDeclaredTypeTellsIsRefusedWithTheWayToWriteIt() {
        val errors = rejected("""
            func<T: Shape> twice(x: T&): Int = x.area() * 2
            func<U: Shape> untold(x: U&): Int {
                fin make = [x.&] { x }
                return twice(make())
            }
            func main() {}
        """)
        assertRejected(errors, "line 4: cannot tell which type 'T' of 'twice' is here, so its bound 'T: Shape' cannot be checked; write it, as in 'twice<…>(…)'")
        assertEquals(
            "8",
            output("""
                func<T: Shape> twice(x: T&): Int = x.area() * 2
                func<U: Shape> told(x: U&): Int {
                    fin make = [x.&] { x }
                    return twice<U>(make())
                }
                func main() {
                    println(told(Sq(2)))
                }
            """),
        )
    }

    @Test fun aPropertyCannotBoundItsOwnTypeParameter() {
        for (member in listOf(
            "prop<U: Shape> &.pick: Int = 1",
            "prop<U> &.pick: Int where U: Shape = 1",
        )) {
            val errors = rejected("impl Free<T> {\n    $member\n}\nfunc main() {}")
            assertTrue(errors.any { "a property cannot bound" in it && "no use writes a property's type argument" in it }, errors.toString())
        }
        val inSpec = rejected("spec Picks { prop<U: Shape> &.pick: Int }\nfunc main() {}")
        assertRejected(inSpec, "a property cannot bound a type parameter")
    }

    @Test fun boundsThatHoldCompileAndRun() {
        assertEquals(
            "9\n16\n25\n4\n1",
            output("""
                impl Box<T> { func &.a(): Int = self.item.area() }
                impl Free<T> { func<U: Shape> &.measure(x: U&): Int = x.area() }
                variant enum Opt<T: Shape> {
                    Some(T)
                    None
                }
                typealias Al<T: Shape> = Box<T>
                func<T: Shape> wrap(x: T): Box<T> = Box<T>(x)
                func<T> wrapWhere(x: T): Box<T> where T: Shape = Box(x)
                func main() {
                    fin b = Box(Sq(3))
                    println(b.a())
                    fin c: Al<Sq> = wrap(Sq(4))
                    println(c.item.area())
                    println(wrapWhere(Sq(5)).a())
                    println(Free<Int>(1).measure(Sq(2)))
                    fin o: Opt<Sq> = Opt<Sq>.None
                    println(1)
                }
            """),
        )
    }
}
