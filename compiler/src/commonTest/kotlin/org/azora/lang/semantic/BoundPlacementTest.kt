package org.azora.lang.semantic

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * §1.1's audit, the parser side: a bound was read and thrown away in three
 * more places, and each let a program compile that then failed or printed
 * the wrong thing.
 *
 *  - `where T: Conv<Int>` kept `Conv` and dropped `<Int>`, so a type that
 *    implements only `Conv<String>` satisfied it and `x.conv() + x.conv()`
 *    concatenated two strings where the signature promised an `Int`.
 *  - A member's `where` clause about its type's parameters (`func &.measure()
 *    where T: Shape` in `impl Box<T>`) was never decided for the receiver, so
 *    `Box(NoShape(2)).measure()` failed at run time with "no method 'area'".
 *  - `impl Box<T: Shape> { … }` parsed the bound and dropped it, with the
 *    same result; a bound in any other type-argument list was dropped too.
 */
class BoundPlacementTest {
    private val decls = """
        import std.io
        spec Shape { func &.area(): Int }
        pack Sq { var s: Int }
        impl Shape for Sq { func &.area(): Int = self.s * self.s }
        pack NoShape { var s: Int }
        spec Conv<To> { func &.conv(): To }
        pack Text { var v: Int }
        impl Conv<String> for Text { func &.conv(): String = "t${'$'}{self.v}" }
        pack Num { var v: Int }
        impl Conv<Int> for Num { func &.conv(): Int = self.v * 10 }
    """.trimIndent()

    /** The diagnostics for [body] after the shared declarations, lines numbered from 1. */
    private fun rejected(body: String): List<String> {
        val offset = decls.lines().size
        val result = Compiler().compile(decls + "\n" + body.trimIndent())
        val errors = assertIs<CompilationResult.Failure>(result, "the program compiled").errors
        return errors.map { error ->
            Regex("""^line (\d+):""").find(error)?.let { match ->
                "line ${match.groupValues[1].toInt() - offset}:" + error.substring(match.value.length)
            } ?: error
        }
    }

    /** A parse refusal: the parser stops at the first one, so there is only the message. */
    private fun refusedByParser(body: String): String {
        val result = Compiler().compile(decls + "\n" + body.trimIndent())
        return assertIs<CompilationResult.Failure>(result, "the program compiled").errors.joinToString("\n")
    }

    private fun output(body: String): String {
        val result = Compiler().compile(decls + "\n" + body.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun assertRejected(errors: List<String>, expected: String) =
        assertTrue(errors.any { expected in it }, "expected \"$expected\" in $errors")

    // ── a spec's arguments are part of the bound ─────────────────

    @Test fun aBoundKeepsItsSpecsArguments() {
        val reason = "'T is Conv<Int>': Text implements Conv<String>, not Conv<Int>"
        assertRejected(
            rejected("""
                func<T> twice(x: T&): Int where T: Conv<Int> { return x.conv() + x.conv() }
                func main() { println(twice(Text(1))) }
            """),
            "line 2: 'twice' does not satisfy its 'where' clause here: $reason",
        )
        assertRejected(
            rejected("""
                func<T: Conv<Int>> twice(x: T&): Int = x.conv() + x.conv()
                func main() { println(twice(Text(1))) }
            """),
            "line 2: 'twice' does not satisfy its 'where' clause here: $reason",
        )
        assertRejected(
            rejected("""
                pack Holder<T: Conv<Int>> { var item: T }
                func main() { fin h = Holder(Text(1)) }
            """),
            "line 2: 'Holder<Text>' does not satisfy its 'where' clause: $reason",
        )
        assertRejected(
            rejected("""
                func<T> twice(x: T&): Int where T: [Shape, Conv<Int>] { return 1 }
                func main() { println(twice(Text(1))) }
            """),
            "'T is Shape': Text does not implement Shape",
        )
    }

    @Test fun genericCodePassesOnABoundWithItsArguments() {
        val wrong = rejected("""
            func<T> twice(x: T&): Int where T: Conv<Int> { return x.conv() + x.conv() }
            func<U> other(x: U&): Int where U: Conv<String> { return twice(x) }
            func<U> bare(x: U&): Int where U: Conv { return twice(x) }
            func main() {}
        """)
        assertRejected(wrong, "line 2: 'T' of 'twice' must be Conv<Int>, and 'U' is not declared to be; add 'where U: Conv<Int>'")
        assertRejected(wrong, "line 3: 'T' of 'twice' must be Conv<Int>, and 'U' is not declared to be; add 'where U: Conv<Int>'")
        val written = rejected("""
            pack Holder<T: Conv<Int>> { var item: T }
            func<U> wrap(x: U): Int where U: Conv<String> {
                fin h = Holder<U>(x)
                return 1
            }
            func main() {}
        """)
        assertRejected(written, "line 3: 'T' of 'Holder' must be Conv<Int>, and 'U' is not declared to be; add 'where U: Conv<Int>'")
    }

    @Test fun anUnwrittenArgumentIsItsDefault() {
        // `PartialEqual` alone is `PartialEqual<Self>` (OPERATOR_OVERLOADING_DIP §7.4).
        val errors = rejected("""
            import std.traits::{PartialEqual}
            pack Meters { var v: Int }
            impl PartialEqual<Int> for Meters { oper== &.(rhs: Int&): Bool = self.v == rhs }
            func<T> same(a: T&, b: T&): Bool where T: PartialEqual { return a == b }
            func main() { println(same(Meters(1), Meters(1))) }
        """)
        assertRejected(errors, "'T is PartialEqual': Meters implements PartialEqual<Int>, not PartialEqual<Meters>")
        assertEquals(
            "true false",
            output("""
                import std.traits::{Equal, PartialEqual}
                pack P { var v: Int }
                derive Equal for P
                func<T> same(a: T&, b: T&): Bool where T: PartialEqual<T> { return a == b }
                func<T> sameBare(a: T&, b: T&): Bool where T: PartialEqual { return a == b }
                func main() {
                    print(same(P(1), P(1)))
                    print(" ")
                    print(sameBare(P(1), P(2)))
                }
            """),
        )
    }

    @Test fun anImplementationMayNotDemandOtherArgumentsThanItsSpecMember() {
        assertRejected(
            rejected("""
                spec Fold { func<U: Conv<String>> &.fold(x: U&): Int }
                impl Fold for Sq { func<U: Conv<Int>> &.fold(x: U&): Int = x.conv() }
                func main() {}
            """),
            "requires 'U: Conv<Int>', which 'Fold.fold' does not promise for 'U'",
        )
    }

    @Test fun aTypeNamedInABoundsArgumentsIsAUseOfIt() {
        val result = Compiler().compile(
            decls + "\n" + """
                @Deprecated(since: "0.1", replacement: "Fresh")
                pack Stale { var v: Int }
                func<T> pick(x: T&): Int where T: Conv<Stale> { return 1 }
                func main() {}
            """.trimIndent(),
            release = false,
        )
        val warnings = assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString()).warnings
        assertTrue(warnings.any { "'Stale' is deprecated since 0.1 - use 'Fresh' instead" in it }, warnings.toString())
    }

    // ── a member's bound on its type's parameters ─────────────────

    @Test fun aMembersBoundOnItsTypesParameterIsDecidedForTheReceiver() {
        val notShape = "'T is Shape': NoShape does not implement Shape"
        val forms = listOf(
            "impl Box<T> { func &.measure(): Int where T: Shape = self.item.area() }" to
                "fin n = Box(NoShape(2)).measure()",
            "impl Box<T> { prop &.measure: Int where T: Shape = self.item.area() }" to
                "fin n = Box(NoShape(2)).measure",
            "oper+ Box<T>&.(rhs: Int): Int where T: Shape { return self.item.area() + rhs }" to
                "fin n = Box(NoShape(2)) + 1",
            "oper[] Box<T>&.(index: Int): Int where T: Shape { return self.item.area() }" to
                "fin n = Box(NoShape(2))[0]",
        )
        for ((member, use) in forms) {
            val errors = rejected("""
                pack Box<T> { var item: T }
                $member
                func main() {
                    $use
                }
            """)
            assertRejected(errors, "line 4: ")
            assertRejected(errors, notShape)
        }
    }

    @Test fun anImplementationMayNotBoundItsTypesParameterBeyondTheSpec() {
        // Through `Bag`, a `Pile<NoShape>` would reach `firstArea` unchecked.
        val required = rejected("""
            spec Bag<E> { func &.firstArea(): Int }
            pack Pile<T> { var item: T }
            impl Bag<T> for Pile<T> {
                func &.firstArea(): Int where T: Shape = self.item.area()
            }
            func main() {}
        """)
        assertRejected(
            required,
            "line 4: 'Pile.firstArea' requires 'T: Shape', which 'Bag.firstArea' does not promise; a caller through " +
                "'Bag' may hold a 'Pile' of any 'T' - bound the spec member (a 'where' clause on 'Bag.firstArea'), " +
                "bound the type ('pack Pile<T: Shape>'), or drop the bound here",
        )
        val provided = rejected("""
            spec Bag<E> { func &.firstArea(): Int { return 0 } }
            pack Pile<T> { var item: T }
            impl Bag<T> for Pile<T> {
                func &.firstArea(): Int where T: Shape = self.item.area()
            }
            func main() {}
        """)
        assertRejected(provided, "line 4: 'Pile.firstArea' requires 'T: Shape', which 'Bag.firstArea' does not promise")
        // What the type or the spec already requires of the parameter is promised.
        assertEquals(
            "9 4",
            output("""
                spec Bag<E> { func &.firstArea(): Int }
                pack Pile<T: Shape> { var item: T }
                impl Bag<T> for Pile<T> {
                    func &.firstArea(): Int where T: Shape = self.item.area()
                }
                spec Sack<E: Shape> { func &.firstArea(): Int }
                pack Heap<T> { var item: T }
                impl Sack<T> for Heap<T> {
                    func &.firstArea(): Int where T: Shape = self.item.area()
                }
                func main() {
                    print(Pile(Sq(3)).firstArea())
                    print(" ")
                    print(Heap(Sq(2)).firstArea())
                }
            """),
        )
    }

    /**
     * A spec member states what it needs of its spec's parameters (R-10): an
     * implementation may restate the bound and rely on it, a call through a
     * spec-typed value is decided for the receiver's arguments, and generic
     * code holding one declares the bound itself.
     */
    @Test fun aSpecMemberMayBoundItsSpecsParameters() {
        val spec = """
            spec Bag<E> {
                func &.firstArea(): Int where E: Shape
                func &.count(): Int
            }
            pack Pile<T> { var item: T }
            impl Bag<T> for Pile<T> {
                func &.firstArea(): Int where T: Shape = self.item.area()
                func &.count(): Int = 1
            }
        """
        assertEquals(
            "9\n9\n1",
            output(spec + """
                func through(b: Bag<Sq>&): Int = b.firstArea()
                func main() {
                    fin p = Pile<Sq>(Sq(3))
                    println(p.firstArea())
                    println(through(p))
                    println(Pile<NoShape>(NoShape(1)).count())
                }
            """),
        )
        assertRejected(
            rejected(spec + "func through(b: Bag<NoShape>&): Int = b.firstArea()\nfunc main() {}"),
            "'firstArea' does not satisfy its 'where' clause here: 'E is Shape': NoShape does not implement Shape",
        )
        assertRejected(
            rejected(spec + "func<U> through(b: Bag<U>&): Int = b.firstArea()\nfunc main() {}"),
            "add 'where U: Shape'",
        )
        assertRejected(
            rejected(spec + "func main() { println(Pile<NoShape>(NoShape(1)).firstArea()) }"),
            "'firstArea' does not satisfy its 'where' clause here: 'T is Shape': NoShape does not implement Shape",
        )
    }

    @Test fun genericCodeCallingABoundedMemberDeclaresTheBound() {
        assertRejected(
            rejected("""
                pack Box<T> { var item: T }
                impl Box<T> { func &.measure(): Int where T: Shape = self.item.area() }
                func<U> via(b: Box<U>&): Int { return b.measure() }
                impl Box<T> { func &.again(): Int = self.measure() }
                func main() {}
            """),
            "line 3: 'T' of 'measure' must be Shape, and 'U' is not declared to be; add 'where U: Shape'",
        )
    }

    // ── an impl head declares its parameters, and may bound them ───

    @Test fun anImplHeadsBoundIsEveryMembersBound() {
        val errors = rejected("""
            pack Box<T> { var item: T }
            impl Box<T: Shape> {
                func &.measure(): Int = self.item.area()
                prop &.doubled: Int = self.item.area() * 2
            }
            func main() {
                fin a = Box(NoShape(2)).measure()
                fin b = Box(NoShape(2)).doubled
            }
        """)
        assertRejected(errors, "line 7: 'measure' does not satisfy its 'where' clause here: 'T is Shape': NoShape does not implement Shape")
        assertRejected(errors, "line 8: 'doubled' does not satisfy its 'where' clause here: 'T is Shape': NoShape does not implement Shape")
        assertRejected(
            rejected("""
                pack Box<T> { var item: T }
                impl Box<T: Conv<Int>> { func &.twice(): Int = self.item.conv() * 2 }
                func main() { fin n = Box(Text(1)).twice() }
            """),
            "'T is Conv<Int>': Text implements Conv<String>, not Conv<Int>",
        )
    }

    @Test fun aBoundWhereNothingIsDeclaredIsRefused() {
        val specTarget = refusedByParser("""
            spec Show { func &.show(): String }
            pack Box<T> { var item: T }
            impl Show for Box<T: Shape> { func &.show(): String = "box" }
            func main() {}
        """)
        assertTrue("a bound on an implementation's target would make the spec hold for some 'T'" in specTarget, specTarget)
        val specArgs = refusedByParser("""
            pack Box<T> { var item: T }
            impl Conv<T: Shape> for Box<T> { func &.conv(): T = self.item }
            func main() {}
        """)
        assertTrue("bounds a type parameter in the spec's arguments" in specArgs, specArgs)
        val derived = refusedByParser("""
            import std.traits::{Equal, PartialEqual}
            pack Box<T> { var item: T }
            derive PartialEqual<T: Shape> for Box
            func main() {}
        """)
        assertTrue("'T: Shape' bounds a type parameter, and nothing is declared here" in derived, derived)
        // `N: Int` in an impl head states a const parameter's kind, not a bound.
        assertEquals(
            "7",
            output("""
                pack Row<T, N: Int> { var item: T }
                impl Row<T, N: Int> { func &.first(): T = self.item }
                func main() { print(Row<Int, 3>(7).first()) }
            """),
        )
    }

    // ── an impl target declares parameters, not types ──────────────

    @Test fun aTypeWrittenAsAnImplTargetsArgumentIsRefused() {
        // `impl Show for Box<Int>` declared a parameter called `Int`: the impl
        // held for every `Box`, and `self.item + 1` on a `Box<String>`'s text
        // printed "text1" where an `Int` was promised.
        val spec = rejected("""
            spec Show { func &.show(): String }
            pack Box<T> { var item: T }
            impl Show for Box<Int> { func &.show(): String = "int box ${'$'}{self.item + 1}" }
            func main() { println(Box("text").show()) }
        """)
        assertRejected(
            spec,
            "line 3: 'impl Show for Box<Int>': 'Int' is a type, but an implementation's target declares " +
                "parameters, so this declares one called 'Int' that hides it and implements every 'Box'; " +
                "write a parameter ('T') - an implementation for one instantiation is not supported",
        )
        val inherent = rejected("""
            pack Box<T> { var item: T }
            impl Box<Sq> { func &.area(): Int = 1 }
            func main() {}
        """)
        assertRejected(inherent, "line 2: 'impl Box<Sq>': 'Sq' is a type, but an implementation's target declares parameters")
        for (target in listOf("Box<Array<T>>", "Box<Int?>", "Box<(T, Int)>")) {
            val message = refusedByParser("""
                spec Show { func &.show(): String }
                pack Box<T> { var item: T }
                impl Show for $target { func &.show(): String = "box" }
                func main() {}
            """)
            assertTrue("an implementation's target declares its parameters, one name each" in message, "$target: $message")
        }
        // A target's own parameter, named as its declaration names it, is one
        // even where the program declares a type of that name - std's
        // `impl Integer for Int<N>` beside a program's `pack N`.
        assertEquals(
            "5",
            output("""
                pack N { var v: Int }
                pack Holder<N> { var item: N }
                impl Holder<N> { func &.get(): N = self.item }
                func main() { print(Holder(5).get()) }
            """),
        )
        assertRejected(
            rejected("""
                pack N { var v: Int }
                pack Box<T> { var item: T }
                impl Box<N> { func &.get(): Int = 1 }
                func main() {}
            """),
            "line 3: 'impl Box<N>': 'N' is a type",
        )
        // A parameter named as the implementation likes stays what it was.
        assertEquals(
            "box 7 | 8",
            output("""
                spec Show { func &.show(): String }
                pack Box<T> { var item: T }
                impl Show for Box<T> { func &.show(): String = "box " }
                impl Box<U> { func &.first(): U = self.item }
                func main() {
                    print(Box(7).show())
                    print(Box(7).first())
                    print(" | ")
                    print(Box(8).first())
                }
            """),
        )
    }

    // ── what holds still runs ─────────────────────────────────────

    @Test fun boundsThatHoldCompileAndRun() {
        assertEquals(
            "40 t7 60 10 | 9 8 16 | 90",
            output("""
                func<T> twice(x: T&): Int where T: Conv<Int> { return x.conv() + x.conv() }
                func<T: Conv<String>> label(x: T&): String = x.conv()
                func<U> relay(x: U&): Int where U: Conv<Int> { return twice(x) }
                pack Holder<T: Conv<Int>> { var item: T }
                pack Box<T> { var item: T }
                impl Box<T: Shape> {
                    func &.measure(): Int = self.item.area()
                    func &.twiceMeasure(): Int = self.measure() * 2
                }
                impl Box<T> { func &.converted(): Int where T: Conv<Int> = self.item.conv() * 3 }
                func<U> viaGeneric(b: Box<U>&): Int where U: Shape { return b.measure() }
                func main() {
                    print(twice(Num(2)))
                    print(" ")
                    print(label(Text(7)))
                    print(" ")
                    print(relay(Num(3)))
                    print(" ")
                    print(Holder(Num(1)).item.conv())
                    print(" | ")
                    print(Box(Sq(3)).measure())
                    print(" ")
                    print(Box(Sq(2)).twiceMeasure())
                    print(" ")
                    print(viaGeneric(Box(Sq(4))))
                    print(" | ")
                    print(Box(Num(3)).converted())
                }
            """),
        )
    }
}
