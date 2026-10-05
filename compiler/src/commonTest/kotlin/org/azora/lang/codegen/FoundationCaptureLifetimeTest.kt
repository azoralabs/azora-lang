package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FoundationCaptureLifetimeTest {
    private fun rejects(source: String, message: String = "while borrowing 'value'") {
        val result = assertIs<CompilationResult.Failure>(Compiler().compile(source.trimIndent()))
        assertTrue(result.errors.any { message in it }, result.errors.toString())
    }

    @Test fun returningClosureAliasesRetainsBorrowProvenance() = rejects("""
        func make(): () -> Int {
            var value = 42
            fin first = [value.&] { value }
            fin second = first
            return second
        }
    """)

    @Test fun returningAClosureInAPackRetainsBorrowProvenance() = rejects("""
        pack Handler { fin run: () -> Int }
        func make(): Handler {
            var value = 42
            fin handler = Handler([value.&] { value })
            return handler
        }
    """)

    @Test fun returningAClosureInAnArrayRetainsBorrowProvenance() = rejects("""
        func make(): Array<() -> Int> {
            var value = 42
            fin handlers = [[value.&] { value }]
            return handlers
        }
    """)

    @Test fun aliasCannotEnterAnEscapingParameter() = rejects("""
        func keepCallback(callback: escaping () -> Int) { }
        func main() {
            var value = 42
            fin callback = [value.&] { value }
            keepCallback(callback)
        }
    """)

    @Test fun assigningAClosureToAReturnedPackRetainsBorrowProvenance() = rejects("""
        pack Handler { var run: () -> Int }
        func make(): Handler {
            var value = 42
            var handler = Handler({ 0 })
            handler.run = [value.&] { value }
            return handler
        }
    """)

    @Test fun copyingABorrowingClosureIntoACaptureCannotHideItsOrigin() = rejects("""
        func make(): () -> Int {
            var value = 42
            fin first = [value.&] { value }
            return [take first] { first() }
        }
    """)

    @Test fun nonEscapingParametersCannotBeReturned() = rejects("""
        func make(callback: () -> Int): () -> Int { return callback }
    """, "non-escaping callable 'callback'")

    @Test fun anOuterBindingCannotKeepAnInnerScopeBorrow() = rejects("""
        func main() {
            var callback: () -> Int = { 0 }
            scope {
                var value = 42
                callback = [value.&] { value }
            }
            callback()
        }
    """)

    @Test fun genericIdentityCannotHideTheBorrowOrigin() = rejects("""
        func<T> identity(input: T): T { return input }
        func make(): () -> Int {
            var value = 42
            fin first = [value.&] { value }
            return identity(first)
        }
    """)

    @Test fun ownedAndEscapingAliasesAreValid() {
        for (source in listOf(
            "func make(): () -> Int { var value = 42\n fin callback = [value] { value }\n return callback }",
            "func make(callback: escaping () -> Int): () -> Int { return callback }",
            "func call(callback: () -> Int): Int { return callback() }",
        )) assertIs<CompilationResult.Success>(Compiler().compile(source))
    }
}
