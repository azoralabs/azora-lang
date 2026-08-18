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
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The ownership model: capabilities, `take`, `.clone()`, and move checking
 * (`DIPs/azora-ownership-model.md`).
 *
 * `Clone` and `Copy` are nominal capabilities. `Copy` *requires* `Clone`
 * rather than extending it, so a type states every capability it has and none
 * is inferred from a sibling. There is no `Movable`: every value can be given
 * away with `take`.
 */
class OwnershipTest {
    private fun compile(source: String): CompilationResult = Compiler().compile(source.trimIndent())

    private fun accepts(source: String) {
        val result = compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
    }

    private fun rejects(source: String, needle: String) {
        val failure = assertIs<CompilationResult.Failure>(compile(source))
        assertTrue(failure.errors.any { needle in it }, failure.errors.toString())
    }

    private fun run(source: String): String {
        val result = compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    // -- capabilities --------------------------------------------------------

    @Test fun requiresIsCheckedAtTheImpl() = rejects("""
        spec Base
        spec Derived requires Base
        pack P { var x: Int }
        impl Derived for P {}
    """, "'P' cannot implement 'Derived' until it also implements 'Base'")

    @Test fun severalRequirementsTakeAList() = accepts("""
        spec A
        spec B
        spec C requires [A, B]
        pack P { var x: Int }
        derive [A, B] for P
        impl C for P {}
    """)

    @Test fun aSingleRequirementIsWrittenWithoutBrackets() = rejects("""
        spec Base
        spec Derived requires [Base]
    """, "written 'requires Base', without brackets")

    @Test fun aManualSpecImplementationRequiresABody() = rejects("""
        spec Marker
        pack P
        impl Marker for P
    """, "manual implementation 'impl Marker for P' requires a body")

    @Test fun aBridgeImplementationStillRequiresABody() = rejects("""
        bridge spec Marker
        pack P
        bridge impl Marker for P
    """, "manual implementation 'impl Marker for P' requires a body")

    @Test fun requirementsSatisfiedExplicitly() = accepts("""
        spec Base
        spec Derived requires Base
        pack P { var x: Int }
        impl Base for P {}
        impl Derived for P {}
    """)

    @Test fun requiringDoesNotImply() = rejects("""
        spec Base
        spec Derived requires Base
        spec Third requires Derived
        pack P { var x: Int }
        impl Base for P {}
        impl Derived for P {}
        pack Q { var y: Int }
        impl Third for Q {}
    """, "'Q' cannot implement 'Third' until it also implements 'Derived'")

    @Test fun aBridgeSpecCanBeDerivedFromItsCompilerLowering() = accepts("""
        bridge spec Clone { func clone[self: Self&](): Self }
        pack P { var x: Int }
        derive Clone for P
    """)

    @Test fun anImplInheritsItsReturnTypeFromTheSpec() = accepts("""
        bridge spec Clone { func clone[self: Self&](): Self }
        pack P { var x: Int }
        impl Clone for P {
            func clone[self: Self&]() { return P(self.x) }
        }
    """)

    // -- derivation (§21) ----------------------------------------------------

    @Test fun aPackOfCopyableFieldsIsDerivedCopyable() = accepts("""
        import std.traits
        pack Vec2 { var x: Double
            var y: Double }
        func requiresCopy<T>(v: T): Int where T is Copy { return 1 }
        func main() { fin n = requiresCopy(Vec2(1.0, 2.0)) }
    """)

    @Test fun derivationReachesThroughNestedPacks() = accepts("""
        import std.traits
        pack Inner { var x: Int }
        pack Outer { var i: Inner }
        func main() {
            var o = Outer(Inner(1))
            fin taken = take o
        }
    """)

    // -- take (§10) ----------------------------------------------------------

    @Test fun takeMovesTheValue() = assertEquals("7", run("""
        import std.io
        import std.traits
        pack Counter { var n: Int }
        func main() {
            var a = Counter(7)
            fin owned = take a
            println(owned.n)
        }
    """))

    @Test fun usingATakenBindingIsRejected() = rejects("""
        import std.traits
        pack Counter { var n: Int }
        func main() {
            var a = Counter(1)
            fin owned = take a
            fin again = a.n
        }
    """, "use of taken value 'a'")

    @Test fun theDiagnosticNamesWhereOwnershipWent() = rejects("""
        import std.traits
        pack Counter { var n: Int }
        func main() {
            var a = Counter(1)
            fin owned = take a
            fin again = a.n
        }
    """, "ownership transferred at line 5")

    @Test fun theDiagnosticSuggestsClone() = rejects("""
        import std.traits
        pack Counter { var n: Int }
        func main() {
            var a = Counter(1)
            fin owned = take a
            fin again = a.n
        }
    """, "use 'a.clone()' instead when both owners need a value")

    @Test fun takingIntoAFunctionArgumentAlsoMoves() = rejects("""
        import std.traits
        pack Counter { var n: Int }
        func consume(c: Counter): Int { return c.n }
        func main() {
            var a = Counter(1)
            fin used = consume(take a)
            fin again = consume(a)
        }
    """, "use of taken value 'a'")

    // -- rebinding after a move (§7) -----------------------------------------

    @Test fun aVarMayBeReboundAfterAMove() = assertEquals("2", run("""
        import std.io
        import std.traits
        pack Counter { var n: Int }
        func main() {
            var a = Counter(1)
            fin owned = take a
            a = Counter(2)
            println(a.n)
        }
    """))

    @Test fun aValMayBeRebeoundAfterAMove() = assertEquals("2", run("""
        import std.io
        import std.traits
        pack Counter { var n: Int }
        func main() {
            val a = Counter(1)
            fin owned = take a
            a = Counter(2)
            println(a.n)
        }
    """))

    @Test fun aLetStaysUnusableAfterAMove() = rejects("""
        import std.traits
        pack Counter { var n: Int }
        func main() {
            let a = Counter(1)
            fin owned = take a
            a = Counter(2)
        }
    """, "cannot reassign immutable binding 'a'")

    // -- clone (§9) ----------------------------------------------------------

    @Test fun cloneLeavesTheSourceUsable() = assertEquals("99\n10", run("""
        import std.io
        import std.traits
        pack Counter { var n: Int }
        func main() {
            var original = Counter(10)
            var duplicate = original.clone()
            original.n = 99
            println(original.n)
            println(duplicate.n)
        }
    """))

    @Test fun aPrimitiveIsCloneable() = assertEquals("5", run("""
        import std.io
        import std.traits
        func main() {
            fin a = 5
            fin b = a.clone()
            println(b)
        }
    """))

    @Test fun aWrittenCloneWinsOverTheDefault() = assertEquals("ana", run("""
        import std.io
        import std.traits
        pack UserProfile { var name: String }
        impl Clone for UserProfile {
            func clone[self: Self&]() { return UserProfile(self.name.clone()) }
        }
        func main() {
            fin u = UserProfile("ana")
            fin v = u.clone()
            println(v.name)
        }
    """))

    // A union reinterprets its storage, so nothing about it can be derived
    // field-wise - which makes it the case where a capability really is absent.

    @Test fun cloningANonCloneableTypeIsRejected() = rejects("""
        import std.traits
        unsafe union Raw { i: Int }
        func main() {
            unsafe {
                var r = Raw(i: 1)
                fin d = r.clone()
            }
        }
    """, "no method 'clone'")

    // Every value can be given away, so `take` asks nothing of its operand -
    // even a union, which derives no capability at all.
    @Test fun takeAsksNothingOfItsOperand() = accepts("""
        import std.traits
        unsafe union Raw { i: Int }
        func main() {
            unsafe {
                var r = Raw(i: 1)
                fin q = take r
            }
        }
    """)

    // -- flow sensitivity ----------------------------------------------------

    @Test fun aMoveInOneBranchDoesNotLeakPastIt() = accepts("""
        import std.traits
        pack Counter { var n: Int }
        func consume(c: Counter): Int { return c.n }
        func main() {
            var a = Counter(1)
            if a.n > 0 {
                fin used = consume(take a)
            }
            fin later = a.n
        }
    """)

    @Test fun aMoveIsStillSeenLaterInTheSameBranch() = rejects("""
        import std.traits
        pack Counter { var n: Int }
        func consume(c: Counter): Int { return c.n }
        func main() {
            var a = Counter(1)
            if a.n > 0 {
                fin used = consume(take a)
                fin again = a.n
            }
        }
    """, "use of taken value 'a'")

    @Test fun eachFunctionIsCheckedOnItsOwn() = accepts("""
        import std.traits
        pack Counter { var n: Int }
        func first() {
            var a = Counter(1)
            fin owned = take a
        }
        func second() {
            var a = Counter(2)
            fin n = a.n
        }
    """)

    // -- the implicit-copy rule (§8, §10) ------------------------------------

    private val nonCopyable = """
        import std.traits
        import std.container.list
        pack Handle { var tags: List<String> }
    """.trimIndent()

    @Test fun aCopyableValueIsPassedImplicitly() = accepts("""
        import std.traits
        pack Vec2 { var x: Double
            var y: Double }
        func widthOf(v: Vec2): Double { return v.x }
        func main() {
            var v = Vec2(1.0, 2.0)
            fin a = widthOf(v)
            fin b = widthOf(v)
        }
    """)

    @Test fun aPrimitiveIsPassedImplicitly() = accepts("""
        import std.traits
        func twice(n: Int): Int { return n * 2 }
        func main() {
            var n = 21
            fin a = twice(n)
            fin b = twice(n)
        }
    """)

    @Test fun aNonCopyableNamedValueNeedsTakeOrClone() = rejects("""
        $nonCopyable
        func consume(h: Handle): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            fin n = consume(h)
        }
    """, "cannot pass 'h' by ownership - 'Handle' is not Copy")

    @Test fun theTransferDiagnosticNamesBothFixes() = rejects("""
        $nonCopyable
        func consume(h: Handle): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            fin n = consume(h)
        }
    """, "transfer ownership with 'take h', or create an independent value with 'h.clone()'")

    @Test fun takeSatisfiesTheTransfer() = accepts("""
        $nonCopyable
        func consume(h: Handle): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            fin n = consume(take h)
        }
    """)

    @Test fun aBorrowedParameterTransfersNothing() = accepts("""
        $nonCopyable
        func inspect(h: Handle&): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            fin a = inspect(h.&)
            fin b = inspect(h.&)
        }
    """)

    @Test fun aTemporaryHasNoOtherOwner() = accepts("""
        $nonCopyable
        func consume(h: Handle): Int { return 1 }
        func main() {
            fin n = consume(Handle(listOf<String>()))
        }
    """)

    // -- borrow origins (§13) ------------------------------------------------

    // `String&[a]` says the result is borrowed from `a` - part of the signature,
    // so it binds the body as much as the type does.

    @Test fun aReturnedBorrowMayNameItsOrigin() = accepts("""
        func first(a: String&, b: String&): String&[a] { return a }
        func main() {}
    """)

    @Test fun returningTheOtherBorrowBreaksTheSignature() = rejects("""
        func first(a: String&, b: String&): String&[a] { return b }
        func main() {}
    """, "returns a borrow of 'b', but the signature says the result is borrowed from 'a'")

    @Test fun anOriginMustNameAParameter() = rejects("""
        func first(a: String&, b: String&): String&[c] { return a }
        func main() {}
    """, "names borrow origin 'c', which is not one of its borrowed parameters (a, b)")

    @Test fun aByValueParameterCannotBeAnOrigin() = rejects("""
        func first(a: String, b: String&): String&[a] { return b }
        func main() {}
    """, "cannot borrow from 'a' - it is passed by value, so it does not outlive the call")

    @Test fun aBorrowMayComeFromEitherOfSeveralOrigins() = accepts("""
        func choose(a: String&, b: String&, pick: Bool): String&[a, b] {
            return if pick { a } else { b }
        }
        func main() {}
    """)

    @Test fun theReceiverIsAnOrigin() = accepts("""
        pack Box { var v: Int }
        impl Box {
            func value[self: Self&](): Int&[self] { return self.v.& }
        }
        func main() {}
    """)

    @Test fun aMethodMayNotReturnABorrowOfSomethingElse() = rejects("""
        pack Box { var v: Int }
        impl Box {
            func value[self: Self&](other: Int&): Int&[self] { return other }
        }
        func main() {}
    """, "returns a borrow of 'other', but the signature says the result is borrowed from 'self'")

    // Naming an origin is optional; a signature that names none promises nothing
    // and constrains nothing.
    @Test fun aBorrowWithoutOriginsIsUnconstrained() = accepts("""
        func first(a: String&, b: String&): String& { return b }
        func main() {}
    """)

    // -- ownership reaches a borrow parameter (§12) --------------------------

    // A parameter that borrows never takes ownership, so handing it some costs
    // the caller the value and buys the callee nothing.

    @Test fun ownershipCannotBeGivenToASharedBorrow() = rejects("""
        $nonCopyable
        func look(v: Handle&): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            fin a = look(take h)
        }
    """, "cannot take 'h' to parameter 'v' of 'look' - the parameter borrows")

    @Test fun theBorrowDiagnosticNamesTheSigilToWrite() = rejects("""
        $nonCopyable
        func poke(v: Handle!): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            fin a = poke(take h)
        }
    """, "write 'h.!' to borrow it for the call")

    @Test fun lendingToABorrowIsRejectedToo() = rejects("""
        $nonCopyable
        func look(v: Handle&): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            fin a = look(lend h)
        }
    """, "cannot lend 'h' to parameter 'v' of 'look'")

    // -- moves and control flow (§10) ----------------------------------------

    // One iteration looks exactly like one conditional path, so the branch rule
    // alone would let a loop move the same value on every pass.

    @Test fun aMoveInsideALoopIsRejected() = rejects("""
        $nonCopyable
        func consume(h: Handle): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            for i in 0..3 {
                fin n = consume(take h)
            }
        }
    """, "'h' is moved inside a loop, so the next iteration would use a value that is gone")

    @Test fun aWhileLoopIsCheckedTheSameWay() = rejects("""
        $nonCopyable
        func consume(h: Handle): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            var go = true
            while go {
                fin n = consume(take h)
                go = false
            }
        }
    """, "is moved inside a loop")

    // A binding the loop itself declares is fresh on each pass.
    @Test fun movingALoopLocalIsFine() = accepts("""
        $nonCopyable
        func consume(h: Handle): Int { return 1 }
        func main() {
            for i in 0..3 {
                var t = Handle(listOf<String>())
                fin n = consume(take t)
            }
        }
    """)

    // A value every path gives away is gone whichever path ran.
    @Test fun aMoveOnEveryBranchOutlivesTheBranch() = rejects("""
        $nonCopyable
        func consume(h: Handle): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            if true { fin a = consume(take h) } else { fin b = consume(take h) }
            fin c = consume(take h)
        }
    """, "use of taken value 'h'")

    // -- moving a field out (§11) --------------------------------------------

    private val app = """
        import std.traits
        import std.container.list
        pack Handle { var tags: List<String> }
        pack App { var db: Handle
            var n: Int }
        func consume(h: Handle): Int { return 1 }
    """.trimIndent()

    @Test fun aFieldIsSpentOnItsOwn() = rejects("""
        $app
        func main() {
            var a = App(Handle(listOf<String>()), 1)
            fin x = consume(take a.db)
            fin y = consume(take a.db)
        }
    """, "use of taken value 'a.db'")

    @Test fun aMovedFieldCannotBeRead() = rejects("""
        $app
        func look(v: Handle&): Int { return 1 }
        func main() {
            var a = App(Handle(listOf<String>()), 1)
            fin x = consume(take a.db)
            fin y = look(a.db)
        }
    """, "use of taken value 'a.db'")

    // Only that field is gone; the rest of the value is untouched.
    @Test fun theOtherFieldsSurvive() = accepts("""
        $app
        import std.io
        func main() {
            var a = App(Handle(listOf<String>()), 1)
            fin x = consume(take a.db)
            println(a.n)
        }
    """)

    // Taking a field away changes the value it belonged to, so it asks the same
    // access a write does.
    @Test fun aFieldCannotBeMovedThroughASharedReceiver() = rejects("""
        $app
        impl App {
            func detach[self: Self&](): Int { return consume(take self.db) }
        }
        func main() {}
    """, "cannot take 'self.db' - 'self' is a shared borrow, which may not be changed")

    @Test fun anExclusiveReceiverMayMoveAFieldOut() = accepts("""
        $app
        impl App {
            func detach[self: Self!](): Int { return consume(take self.db) }
        }
        func main() {}
    """)

    @Test fun aSharedParameterCannotHaveAFieldMovedOut() = rejects("""
        $app
        func detach(a: App&): Int { return consume(take a.db) }
        func main() {}
    """, "cannot take 'a.db' - 'a' is a shared borrow")

    @Test fun anExclusiveParameterMay() = accepts("""
        $app
        func detach(a: App!): Int { return consume(take a.db) }
        func main() {}
    """)

    // -- a borrow has nothing to give away (§12) -----------------------------

    // A borrow owns nothing, so it cannot hand the value to anyone. Without
    // this, a function taking `h: H&` could give the caller's value away
    // permanently and the owner would never be told.

    @Test fun aSharedBorrowCannotBeGivenAway() = rejects("""
        $nonCopyable
        func sink(h: Handle): Int { return 1 }
        func relay(h: Handle&): Int { return sink(take h) }
        func main() {}
    """, "cannot take 'h' - 'h' is borrowed, so this function does not own it")

    @Test fun anExclusiveBorrowCannotBeGivenAwayEither() = rejects("""
        $nonCopyable
        func sink(h: Handle): Int { return 1 }
        func relay(h: Handle!): Int { return sink(take h) }
        func main() {}
    """, "cannot take 'h' - 'h' is borrowed, so this function does not own it")

    @Test fun aBoundBorrowNamesTheOwnerItCannotGiveAway() = rejects("""
        $nonCopyable
        func sink(h: Handle): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            fin b: Handle& = h.&
            fin n = sink(take b)
        }
    """, "cannot take 'b' - 'b' is a borrow of 'h', which owns nothing")

    @Test fun aBorrowedReceiverCannotBeGivenAway() = rejects("""
        $nonCopyable
        func sink(h: Handle): Int { return 1 }
        impl Handle {
            func give[self: Self&](): Int { return sink(take self) }
        }
        func main() {}
    """, "cannot take 'self' - 'self' is borrowed, so this function does not own it")

    // Nor lent, for the same reason: lending is ownership too.
    @Test fun aBorrowCannotBeLentOnward() = rejects("""
        $nonCopyable
        func sink(h: return Handle): Int { return 1 }
        func relay(h: Handle&): Int { return sink(lend h) }
        func main() {}
    """, "cannot lend 'h' - 'h' is borrowed, so this function does not own it")

    @Test fun anOwnedParameterMayStillBeGivenAway() = accepts("""
        $nonCopyable
        func sink(h: Handle): Int { return 1 }
        func relay(h: Handle): Int { return sink(take h) }
        func main() {}
    """)

    // A lent parameter really is owned while the call runs, so it may be passed
    // on - the loan is what the callee gives back, not what it holds.
    @Test fun aLentParameterMayBeGivenAway() = accepts("""
        $nonCopyable
        func sink(h: Handle): Int { return 1 }
        func relay(h: return Handle): Int { return sink(take h) }
        func main() {}
    """)

    // -- lending (§13) -------------------------------------------------------

    // `x: return T` says the callee owns the value while it runs and the caller
    // owns it again afterwards. The argument is written `lend x`, so the two
    // ends of that contract are both visible.

    @Test fun aLentValueComesBack() = assertEquals("12\n4", run("""
        import std.io
        func add(x: return Int, y: return Int): Int { return x + y }
        func main() {
            var x = 4
            var y = 8
            var sum = add(lend x, lend y)
            println(sum)
            println(x)
        }
    """))

    // The point of lending: a non-`Copy` value may go in and come back, twice,
    // where `take` would have spent it the first time.
    @Test fun aNonCopyValueMayBeLentRepeatedly() = assertEquals("2", run("""
        $nonCopyable
        import std.io
        func inspect(h: return Handle): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            fin a = inspect(lend h)
            fin b = inspect(lend h)
            println(a + b)
        }
    """))

    @Test fun takeStillSpendsTheValue() = rejects("""
        $nonCopyable
        func consume(h: Handle): Int { return 1 }
        func main() {
            var h = Handle(listOf<String>())
            fin a = consume(take h)
            fin b = consume(take h)
        }
    """, "use of taken value 'h'")

    @Test fun lendingNeedsAParameterThatGivesBack() = rejects("""
        func f(a: Int): Int { return a }
        func main() {
            var x = 1
            fin n = f(lend x)
        }
    """, "cannot lend to parameter 'a' of 'f' - it does not give ownership back")

    @Test fun aReturningParameterIsLentTo() = rejects("""
        func f(a: return Int): Int { return a }
        func main() {
            var x = 1
            fin n = f(x)
        }
    """, "gives ownership back, so its argument is lent; write 'lend' before it")

    // A borrow leaves the caller owning the value throughout, so there is
    // nothing for the callee to give back.
    @Test fun aBorrowHasNoOwnershipToReturn() = rejects("""
        func f(a: return Int&): Int { return a }
        func main() {}
    """, "is a borrow, so 'f' never takes ownership of it and has none to give back")

    @Test fun everyParameterMayGiveOwnershipBack() = assertEquals("6\n1\n2\n3", run("""
        import std.io
        func total(a: return Int, b: return Int, c: return Int): Int { return a + b + c }
        func main() {
            var x = 1
            var y = 2
            var z = 3
            println(total(lend x, lend y, lend z))
            println(x)
            println(y)
            println(z)
        }
    """))

    // `lend` only means the operator when a name follows it, so it stays an
    // ordinary identifier everywhere else.
    @Test fun lendIsStillAUsableName() = assertEquals("5", run("""
        import std.io
        func main() {
            var lend = 5
            println(lend)
        }
    """))

    // -- optionals (§17) -----------------------------------------------------

    // Moving out of an optional leaves it empty rather than pointing at a
    // moved-from value, so an optional is always either null or valid.

    private val optionalFile = """
        import std.io
        pack File { var name: String }
    """.trimIndent()

    @Test fun takingOutOfAnOptionalYieldsTheValue() = assertEquals("data.txt", run("""
        $optionalFile
        func main() {
            var file: File? = File("data.txt")
            fin owned: File = take file.require()
            println(owned.name)
        }
    """))

    @Test fun theOptionalIsEmptyAfterTheTake() = assertEquals("true", run("""
        $optionalFile
        func main() {
            var file: File? = File("data.txt")
            fin owned: File = take file.require()
            println(file == null)
        }
    """))

    @Test fun theShorthandBehavesLikeTheKeywordForm() = assertEquals("data.txt\ntrue", run("""
        $optionalFile
        func main() {
            var file: File? = File("data.txt")
            fin owned: File = file.take()
            println(owned.name)
            println(file == null)
        }
    """))

    // Reading is not moving: `require()` on its own only drops the `?`.
    @Test fun requireOnItsOwnLeavesTheOptionalAlone() = assertEquals("false", run("""
        $optionalFile
        func main() {
            var file: File? = File("data.txt")
            fin seen: File = file.require()
            println(file == null)
        }
    """))

    @Test fun takingOutOfAnEmptyOptionalIsCaught() {
        val thrown = assertFailsWith<IllegalStateException> {
            run("""
                $optionalFile
                func main() {
                    var file: File? = null
                    fin owned: File = take file.require()
                    println(owned.name)
                }
            """)
        }
        assertTrue("took a value out of a null optional" in thrown.message.orEmpty(), thrown.message.orEmpty())
    }

    @Test fun theEmptyingStaysInTheBranchThatDidIt() = assertEquals("data.txt\ntrue", run("""
        $optionalFile
        func main() {
            var file: File? = File("data.txt")
            if file != null {
                fin owned: File = take file.require()
                println(owned.name)
            }
            println(file == null)
        }
    """))

    @Test fun requireAsksForAnOptional() = rejects("""
        pack File { var name: String }
        func main() {
            var file = File("data.txt")
            fin owned = file.require()
        }
    """, "'require()' needs an optional, but file is File")

    // `take` is an ordinary method name, so a type that declares one keeps it.
    @Test fun aDeclaredTakeMethodIsNotTheOptionalOne() = assertEquals("1", run("""
        import std.io
        pack Lexer { var at: Int }
        impl Lexer { func take[self: Self!](): Int { return 1 } }
        func main() {
            var lexer = Lexer(0)
            println(lexer.take())
        }
    """))

    // -- suspension (§15) ----------------------------------------------------

    // A borrow lives for the call. A task that suspends outlives the call, so a
    // borrow it still holds afterwards has nothing keeping its owner alive.

    @Test fun aBorrowThatEndsBeforeSuspendingIsFine() = accepts("""
        import std.io
        pack Data { var n: Int }
        async func inspect(data: Data&) {
            println(data.n)
            delay 100
        }
        func main() {}
    """)

    @Test fun aBorrowHeldAcrossADelayIsRejected() = rejects("""
        import std.io
        pack Data { var n: Int }
        async func inspect(data: Data&) {
            delay 100
            println(data.n)
        }
        func main() {}
    """, "'data' is borrowed across a suspension point")

    @Test fun theSuspensionDiagnosticOffersAllThreeFixes() = rejects("""
        import std.io
        pack Data { var n: Int }
        async func inspect(data: Data&) {
            delay 100
            println(data.n)
        }
        func main() {}
    """, "transfer ownership with 'take data', create an independent value with 'data.clone()', " +
        "or end the borrow before the suspension")

    @Test fun anOwnedParameterCrossesASuspensionFreely() = accepts("""
        import std.io
        pack Data { var n: Int }
        async func inspect(data: Data) {
            delay 100
            println(data.n)
        }
        func main() {}
    """)

    // Only a task suspends, so an ordinary function's borrows are untouched.
    @Test fun anOrdinaryFunctionIsUnaffected() = accepts("""
        import std.io
        pack Data { var n: Int }
        func inspect(data: Data&) {
            println(data.n)
            println(data.n)
        }
        func main() {}
    """)

    // -- closure captures (§16) ----------------------------------------------

    @Test fun aClosureMayCaptureByBorrow() = accepts("""
        import std.io
        pack User { var name: String }
        func main() {
            var user = User("ana")
            fin printName = [; user.&] { println(user.name) }
            println(user.name)
        }
    """)

    @Test fun aClosureMayCaptureByClone() = accepts("""
        import std.io
        import std.traits
        func main() {
            var message = "Hello"
            fin callback = [; message.&] {
                fin owned: String = message.clone()
                println(owned)
            }
            println(message)
        }
    """)

    // Taking inside a closure is still a move of the outer binding: the closure
    // owns the value afterwards, so the binding that had it does not.
    @Test fun takingInAClosureMovesTheOuterBinding() = rejects("""
        import std.traits
        pack Socket { var port: Int }
        func main() {
            var socket = Socket(8080)
            fin worker = { fin owned: Socket = take socket }
            fin again = socket.port
        }
    """, "use of taken value 'socket'")

    @Test fun anAsyncClosureMovesTheOuterBindingToo() = rejects("""
        import std.traits
        pack Socket { var port: Int }
        async func main() {
            var socket = Socket(8080)
            fin worker = async { fin owned: Socket = take socket }
            fin again = socket.port
        }
    """, "use of taken value 'socket'")

    // -- generics (§20) ------------------------------------------------------

    @Test fun aGenericBodyMayTakeItsParameter() = accepts("""
        import std.traits
        func transfer<T>(value: T): T where T is Clone {
            return take value
        }
        func main() { fin n = transfer(5) }
    """)
}
