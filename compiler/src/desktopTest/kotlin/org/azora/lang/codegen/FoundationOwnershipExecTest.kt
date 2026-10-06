/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FoundationOwnershipExecTest {
    @Test fun genericCloneCallsProduceIndependentConcreteValues() = agrees("""
        import std.io
        import std.traits::Clone
        pack Value derives Clone { var number: Int }
        pack Holder<T> { var value: T }
        func<T: Clone> cloned(value: T&): T { return value.clone() }
        func<T: Clone> snapshot(holder: Holder<T>&, ignored: Int): T { return cloned(holder.value) }
        func main() {
            var original = Value(7)
            var explicit = cloned<Value>(original)
            explicit.number = 9
            var inferred = cloned(original)
            inferred.number = 11
            println(original.number)
            println(explicit.number)
            println(inferred.number)
            var holder = Holder<Value>(Value(13))
            var copy = snapshot(holder, 0)
            copy.number = 17
            println(holder.value.number)
            println(copy.number)
        }
    """, "7\n9\n11\n13\n17")
    @Test fun genericBoolAndNestedFieldArgumentsKeepTheirRuntimeType() = agrees("""
        import std.io
        import std.memory.shared
        pack Box<T> { var value: T }
        pack Holder<T> { var nested: Box<T> }
        func main() {
            var active = sharedOf(true)
            if active.get { println(1) }
            active.set(false)
            if !active.get { println(2) }
            var holder = Holder<Box<Bool>>(Box<Box<Bool>>(Box<Bool>(true)))
            if holder.nested.value.value { println(3) }
        }
    """, "1\n2\n3")
    private fun agrees(source: String, expected: String) {
        for (release in listOf(false, true)) {
            val compiled = Compiler().compile(source.trimIndent(), release = release)
            val result = assertIs<CompilationResult.Success>(compiled, (compiled as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter")
            if (LlvmExec.available) assertEquals(expected, LlvmExec.run(source.trimIndent(), release), "LLVM release=$release")
        }
    }

    @Test fun copiedClosureHandlesReleaseMovedCapturesOnlyAfterTheLastOwner() = agrees("""
        import std.io
        pack Value { var id: Int }
        impl Value { dtor .() { println(99) } }
        func make(): () -> Int {
            var value = Value(17)
            return [take value] { value.id }
        }
        func main() {
            var first = make()
            var second = first
            println(second())
            purge first
            println(second())
            purge second
        }
    """, "17\n17\n99")

    @Test fun ownedClosureCaptureMutationPersistsAcrossCalls() = agrees("""
        import std.io
        func main() {
            var counter = 0
            fin next = [counter] { counter += 1
                counter }
            println(next())
            println(next())
        }
    """, "1\n2")

    @Test fun subscriptionDisposalCanOutliveItsObservedState() = agrees("""
        import std.io
        import std.reactive
        func make(): Subscription {
            var source = state(1)
            return observe(source) { value: Int -> println(value) }
        }
        func main() {
            var subscription = make()
            subscription.dispose()
            subscription.dispose()
            println(2)
        }
    """, "1\n2")

    @Test fun earlyReturnBeforeConstructionDoesNotReadAnUninitializedOwner() = agrees("""
        import std.io
        pack Owner { var id: Int }
        impl Owner { dtor .() { println(self.id) } }
        func work(early: Bool): Int {
            if early { return 1 }
            let owner = Owner(9)
            return 2
        }
        func main() {
            println(work(true))
            println(work(false))
        }
    """, "1\n9\n2")

    @Test fun copyConstructorFieldsHaveIndependentStorage() = agrees("""
        import std.io
        import std.traits::Copy
        pack Point derives Copy { var x: Int }
        pack Box { var point: Point }
        func main() {
            var original = Point(4)
            var box = Box(original)
            box.point.x = 9
            println(original.x)
            println(box.point.x)
        }
    """, "4\n9")

    @Test fun arrayGrowthReplacementRemovalAndDiscardedTransfersDestroyExactlyOnce() = agrees("""
        import std.io
        pack Value { var id: Int }
        impl Value { dtor .() { println(self.id) } }
        func main() {
            var values: Array<Value> = [Value(1), Value(2)]
            values.add(Value(3))
            let removed = values.removeAt(0)
            values[0] = Value(4)
            values.remove(0)
            values.pop()
            values.add(Value(5))
            values.add(Value(6))
            values.clear()
        }
    """, "2\n4\n3\n6\n5\n1")

    @Test fun erasedArrayStorageCarriesConcreteDestructionThroughCalls() = agrees("""
        import std.io
        pack Value { var id: Int }
        impl Value { dtor .() { println(self.id) } }
        pack Holder<T> { var values: Array<T> }
        func<T> make(): Holder<T> = Holder<T>([])
        func<T> insert(holder: Holder<T>!, value: T) { holder.values.add(value) }
        func<T> remove(holder: Holder<T>!) { holder.values.remove(0) }
        func main() {
            var holder = make<Value>()
            insert(holder, Value(1))
            insert(holder, Value(2))
            remove(holder)
        }
    """, "1\n2")

    @Test fun variantPayloadsOwnTransferredPacks() = agrees("""
        import std.io
        pack Value { var id: Int }
        impl Value { dtor .() { println(self.id) } }
        variant enum Item {
            Empty
            Held(value: Value)
        }
        func make(): Item {
            var value = Value(7)
            return Item.Held(take value)
        }
        func main() { let item = make() }
    """, "7")

    @Test fun scopeReturnAndLoopEdgesDestroyInReverseOrder() = agrees("""
        import std.io
        pack Owner { var id: Int }
        impl Owner { dtor .() { println(self.id) } }
        func work(): Int {
            let root = Owner(9)
            if true {
                let inner = Owner(1)
                println(10)
            }
            for i in 0..<3 {
                let item = Owner(i + 2)
                if i == 0 { continue }
                break
            }
            if true {
                let exit = Owner(5)
                return 6
            }
        }
        func main() { println(work()) }
    """, "10\n1\n2\n3\n5\n9\n6")

    @Test fun moveReturnReplacementAndExplicitPurgeConsumeExactlyOnce() = agrees("""
        import std.io
        pack Owner { var id: Int }
        impl Owner { dtor .() { println(self.id) } }
        func make(): Owner {
            let value = Owner(4)
            return value
        }
        func main() {
            var source = Owner(1)
            if true {
                let destination = take source
                println(10)
            }
            source = Owner(2)
            source = Owner(3)
            purge source
            let returned = make()
        }
    """, "10\n1\n2\n3\n4")

    @Test fun recursiveFieldsAndAllocatedPointeesAreDestroyed() = agrees("""
        import std.io
        pack Child { var id: Int }
        impl Child { dtor .() { println(self.id) } }
        pack Parent {
            var first: Child
            var second: Child
        }
        impl Parent { dtor .() {
            println(10)
            purge self.first
        } }
        func main() {
            if true { let parent = Parent(Child(1), Child(2)) }
            if true { let pointer = alloc^ Child(3) }
            let explicit = alloc^ Child(4)
            purge explicit
        }
    """, "10\n1\n2\n3\n4")

    @Test fun lastSharedAndSyncSharedOwnerDestroyTheConcretePointee() {
        for ((module, factory) in listOf("shared" to "sharedOf", "syncshared" to "syncSharedOf")) {
            agrees("""
                import std.io
                import std.memory.$module
                pack Value { var id: Int }
                impl Value { dtor .() { println(self.id) } }
                func main() {
                    var first = $factory(Value(7))
                    if true {
                        let second = first.clone()
                        println(first.refCount)
                    }
                    println(first.refCount)
                }
            """, "2\n1\n7")
        }
    }

    @Test fun movedTaskCaptureReleasesAnOwnerWithoutExplicitPurge() = agrees("""
        import std.io
        import std.memory.syncshared
        pack Value { var id: Int }
        impl Value { dtor .() { println(self.id) } }
        async func main() {
            var owner = syncSharedOf(Value(7))
            var copy = owner.clone()
            fin task = async [take copy] { println(copy.refCount) }
            await task
            println(owner.refCount)
        }
    """, "2\n1\n7")

    @Test fun deferredBodyReadsItsOwnerBeforeDestruction() = agrees("""
        import std.io
        pack Owner { var id: Int }
        impl Owner { dtor .() { println(self.id) } }
        func main() {
            if true {
                let owner = Owner(1)
                defer { println(owner.id + 10) }
            }
            println(20)
        }
    """, "20\n11\n1")

    @Test fun uniqueDisposeAndAutomaticDestructorReleasePointeeOnce() = agrees("""
        import std.io
        import std.memory.unique
        pack Value { var id: Int }
        impl Value { dtor .() { println(self.id) } }
        func main() {
            var first = uniqueOf(Value(8))
            first.dispose()
            if true { let second = uniqueOf(Value(9)) }
        }
    """, "8\n9")

    @Test fun failedFieldEvaluationDestroysCompletedOwnedFields() = agrees("""
        import std.io
        error Failure { Bad }
        pack Child { var id: Int }
        impl Child { dtor .() { println(self.id) } }
        pack Parent {
            var first: Child
            var second: Child
        }
        impl Parent { dtor .() { println(99) } }
        func fail(): Child ?! Failure { return .Bad }
        func construct(): Int ?! Failure {
            let before = Child(8)
            let parent = Parent(Child(1), fail())
            return 0
        }
        func main() { println(construct() catch 7) }
    """, "1\n8\n7")
}
