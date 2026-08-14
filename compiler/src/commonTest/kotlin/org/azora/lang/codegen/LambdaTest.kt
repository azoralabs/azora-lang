package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LambdaTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun lambdaAssignedAndCalled() {
        assertEquals("10", run("""
            import std.io
            func main() {
                var double = { x: std::Int -> x * 2 }
                std::println(double(5))
            }
        """.trimIndent()))
    }

    @Test fun higherOrderFunction() {
        assertEquals("16", run("""
            import std.io
            func apply(f: (std::Int) -> std::Int, x: std::Int): std::Int {
                return f(x)
            }
            func main() {
                std::println(apply({ x: std::Int -> x * x }, 4))
            }
        """.trimIndent()))
    }

    @Test fun closureCapturesParameter() {
        assertEquals("15", run("""
            import std.io
            func makeAdder(n: std::Int): (std::Int) -> std::Int {
                // Returned, so it escapes: it must own `n`, and std::Int is Copy.
                return [; n] { x: std::Int -> x + n }
            }
            func main() {
                var add5 = makeAdder(5)
                std::println(add5(10))
            }
        """.trimIndent()))
    }

    @Test fun closureCapturesLocalVar() {
        assertEquals("7", run("""
            import std.io
            func main() {
                var offset = 3
                var add = [; offset.&] { x: std::Int -> x + offset }
                std::println(add(4))
            }
        """.trimIndent()))
    }

    @Test fun lambdaInInterpolation() {
        assertEquals("25", run("""
            import std.io
            func apply(f: (std::Int) -> std::Int, x: std::Int): std::Int {
                return f(x)
            }
            func main() {
                std::println("${'$'}{apply({ x: Int -> x * x }, 5)}")
            }
        """.trimIndent()))
    }

    @Test fun noParamLambda() {
        assertEquals("hi", run("""
            import std.io
            func run(g: () -> std::String): std::String {
                return g()
            }
            func main() {
                std::println(run({ "hi" }))
            }
        """.trimIndent()))
    }

    @Test fun noParamLambdaUsesPackFieldContextWithoutAnArrow() {
        assertEquals("cancelled", run("""
            import std.io
            pack Subscription { fin cancel: () -> std::Unit }
            func main() {
                fin subscription = Subscription({ std::println("cancelled") })
                subscription.cancel()
            }
        """.trimIndent()))
    }

    @Test fun constructorAcceptsTrailingLambdaAfterArguments() {
        assertEquals("cancelled", run("""
            import std.io
            pack Subscription {
                fin active: std::Bool
                fin cancel: () -> std::Unit
            }
            func main() {
                fin subscription = Subscription(true) {
                    std::println("cancelled")
                }
                subscription.cancel()
            }
        """.trimIndent()))
    }

    @Test fun functionAcceptsTrailingLambdaWithoutParentheses() {
        assertEquals("hi", run("""
            import std.io
            func run(action: () -> std::String): std::String {
                return action()
            }
            func main() {
                std::println(run { "hi" })
            }
        """.trimIndent()))
    }

    @Test fun methodAcceptsContextuallyTypedTrailingLambda() {
        assertEquals("8", run("""
            import std.io
            pack Runner
            impl Runner {
                func run[self: std::Self&](action: (std::Int) -> std::Int): std::Int {
                    return action(4)
                }
            }
            func main() {
                fin runner = Runner()
                std::println(runner.run { value -> value * 2 })
            }
        """.trimIndent()))
    }

    @Test fun trailingLambdaFollowsNamedAndOrdinaryArguments() {
        assertEquals("[Azora]", run("""
            import std.io
            func render(prefix: std::String, action: () -> std::String): std::String {
                return prefix + action()
            }
            func main() {
                std::println(render(prefix: "[") { "Azora]" })
            }
        """.trimIndent()))
    }

    @Test fun genericAndRealmQualifiedCallsAcceptTrailingLambdas() {
        assertEquals("qualified", run("""
            import std.io
            import std.reactive
            func main() {
                std::println(std::untracked<std::String> { "qualified" })
            }
        """.trimIndent()))
    }

    @Test fun contextualReceiverLambdaCanTrailGenericQualifiedCall() {
        assertEquals("receiver", run("""
            import std.io
            pack Context { fin value: std::String }
            func run<T>(fallback: T, action: [Context&]() -> T): T {
                fin context = Context("receiver")
                var result = fallback
                with context { result = action() }
                return result
            }
            func main() {
                std::println(run<std::String>("") [context: Context&] { context.value })
            }
        """.trimIndent()))
    }

    @Test fun contextualReceiverLambdaCanTrailParenthesizedArguments() {
        assertEquals("prefixed", run("""
            import std.io
            pack Context { fin value: std::String }
            func render(prefix: std::String, action: [Context&]() -> std::String): std::String {
                fin context = Context("fixed")
                var result = prefix
                with context { result += action() }
                return result
            }
            func main() {
                std::println(render("pre") [context: Context&] { context.value })
            }
        """.trimIndent()))
    }

    @Test fun callableVariableAcceptsContextuallyTypedTrailingLambda() {
        assertEquals("variable", run("""
            import std.io
            func main() {
                fin invoke: (() -> std::String) -> std::String =
                    { action: () -> std::String -> return action() }
                std::println(invoke { "variable" })
            }
        """.trimIndent()))
    }

    @Test fun callablePackFieldAcceptsTrailingLambda() {
        assertEquals("field", run("""
            import std.io
            pack Runner {
                fin invoke: (() -> std::String) -> std::String =
                    { action: () -> std::String -> return action() }
            }
            func main() {
                std::println(Runner().invoke { "field" })
            }
        """.trimIndent()))
    }

    @Test fun groupedCallableExpressionAcceptsTrailingLambda() {
        assertEquals("grouped", run("""
            import std.io
            func main() {
                fin invoke: (() -> std::String) -> std::String =
                    { action: () -> std::String -> return action() }
                std::println((invoke) { "grouped" })
            }
        """.trimIndent()))
    }

    @Test fun primitiveExtensionMethodAcceptsTrailingLambda() {
        assertEquals("12", run("""
            import std.io
            impl std::Int {
                func map[self: std::Self&](action: (std::Int) -> std::Int): std::Int {
                    return action(self)
                }
            }
            func main() {
                std::println(4.map { value -> value * 3 })
            }
        """.trimIndent()))
    }

    @Test fun variantPayloadAcceptsContextuallyTypedTrailingLambda() {
        assertEquals("variant", run("""
            import std.io
            variant enum Work {
                Run(() -> std::String)
            }
            func main() {
                fin work = Work.Run { "variant" }
                when work {
                    Work.Run(action) -> { std::println(action()) }
                }
            }
        """.trimIndent()))
    }

    @Test fun specMethodAcceptsContextuallyTypedTrailingLambda() {
        assertEquals("spec", run("""
            import std.io
            spec Executor {
                func execute[self: std::Self&](action: () -> std::String): std::String
            }
            pack Direct
            impl Executor for Direct {
                func execute[self: std::Self&](action: () -> std::String): std::String {
                    return action()
                }
            }
            func executeWith(executor: Executor): std::String {
                return executor.execute { "spec" }
            }
            func main() {
                std::println(executeWith(Direct()))
            }
        """.trimIndent()))
    }

    @Test fun callableKindsAreStorablePackFields() {
        assertEquals("5\n2", run("""
            import std.io

            pack Calculator {
                fin add: [std::Int, std::Int]() -> std::Int =
                    [x: std::Int, y: std::Int] { x + y }
                fin sub: (std::Int, std::Int) -> std::Int =
                    { x: std::Int, y: std::Int -> return x - y }
            }

            func main() {
                fin calculator = Calculator()
                with [2, 3] { std::println(calculator.add()) }
                std::println(calculator.sub(9, 7))
            }
        """.trimIndent()))
    }

    /**
     * `it` is the name a lambda gives its parameter when it has exactly one and
     * did not name it - not a property of the braces. A body that reads no `it`
     * takes no parameters (LAMBDA_CONTEXT_CAPTURE_DIP.MD §5.1).
     */
    @Test fun aBareLambdaTakesNoParameterUnlessItsBodyReadsOne() {
        assertEquals("1", run("""
            import std.io
            func main() {
                var n = 0
                fin inc = [; n.!] { n = n + 1 }
                inc()
                std::println(n)
            }
        """.trimIndent()))
    }

    @Test fun aBareLambdaStillTakesItWhenTheBodyReadsIt() {
        assertEquals("6", run("""
            import std.io
            func main() {
                fin double: (std::Int) -> std::Int = { it * 2 }
                std::println(double(3))
            }
        """.trimIndent()))
    }

    /** A nested lambda owns its own `it`, so it does not give one to the lambda around it. */
    @Test fun aNestedItBelongsToTheNestedLambda() {
        assertEquals("2", run("""
            import std.io
            func main() {
                var seen = 0
                fin outer = [; seen.!] {
                    fin inner: (std::Int) -> std::Int = { it * 2 }
                    seen = inner(1)
                }
                outer()
                std::println(seen)
            }
        """.trimIndent()))
    }

    /** An empty parameter list is written by writing none, so `->` has no job left. */
    @Test fun aParameterlessLambdaMayNotWriteAnArrow() {
        val result = Compiler().compile("""
            func main() {
                var n = 0
                fin inc = { -> n = n + 1 }
                inc()
            }
        """.trimIndent())
        val errors = assertIs<CompilationResult.Failure>(result).errors
        assertTrue(
            errors.any { "a lambda with no parameters writes no '->'" in it },
            errors.toString(),
        )
    }

    /** A callable type is spellable as a generic argument (§5.2). */
    @Test fun aCallableTypeMayBeAGenericArgument() {
        assertEquals("ok", run("""
            import std.io
            func main() {
                var fs = std::listOf<() -> std::Int>()
                var handlers = std::listOf<(std::Int) -> std::Int>()
                std::println("ok")
            }
        """.trimIndent()))
    }

    @Test fun withSuppliesContextualReceivers() {
        assertEquals("5", run("""
            import std.io

            fin add: [std::Int, std::Int]() -> std::Int =
                [x: std::Int, y: std::Int] { x + y }

            func main() {
                with [2, 3] {
                    std::println(add())
                }
            }
        """.trimIndent()))
    }

    @Test fun aLambdaArgumentKeepsItsStatementsSeparate() {
        // A `{` reopens statement context, so newlines inside a lambda written
        // inline as a call argument still separate statements rather than being
        // swallowed by the call's parentheses.
        assertEquals("1\n2", run("""
            import std.io

            func twice(action: (std::Int) -> std::Unit) {
                action(1)
                action(2)
            }

            func main() {
                twice({ n ->
                    std::println(n)
                })
            }
        """.trimIndent()))
    }

    @Test fun aReceiverLambdaBindsItsContextByName() {
        assertEquals("5", run("""
            import std.io

            pack Vec2 { fin x = 0 fin y = 0 }

            func apply(v: Vec2&, f: [Vec2&]() -> std::Int): std::Int {
                var acc = 0
                with v { acc = f() }
                return acc
            }

            func main() {
                fin add = [self: Vec2&]{ self.x + self.y }
                std::println(apply(Vec2(2, 3), add))
            }
        """.trimIndent()))
    }

    @Test fun aReceiverLambdaTakesOrdinaryParametersToo() {
        assertEquals("22", run("""
            import std.io

            pack Vec2 { fin x = 0 fin y = 0 }

            func apply(v: Vec2&, o: Vec2&, f: [Vec2&](Vec2&) -> std::Int): std::Int {
                var acc = 0
                with v { acc = f(o) }
                return acc
            }

            func main() {
                fin add = [self: Vec2&]{ other: Vec2& -> self.x + other.y }
                std::println(apply(Vec2(2, 3), Vec2(10, 20), add))
            }
        """.trimIndent()))
    }

    @Test fun ordinaryAndContextualParametersCanBeCombined() {
        assertEquals("10\n14", run("""
            import std.io

            fin scale: [std::Int](std::Int) -> std::Int =
                [value: std::Int] { factor: std::Int -> value * factor }

            func main() {
                with 5 {
                    std::println(scale(2))
                }
                std::println(2.scale(7))
            }
        """.trimIndent()))
    }

    /**
     * A contextual receiver is not an argument. There are two ways to supply one:
     * a `with` block, or the receiver call - `2.scale(7)` for one, `[2, 3].add()`
     * for several (LAMBDA_CONTEXT_CAPTURE_DIP.MD §2).
     */
    @Test fun aReceiverIsSuppliedByWithOrByAReceiverCall() {
        assertEquals("10\n14\n5\n5", run("""
            import std.io
            fin scale: [std::Int](std::Int) -> std::Int = [value] { factor -> value * factor }
            fin add: [std::Int, std::Int]() -> std::Int = { x, y -> x + y }

            func main() {
                with 5 { std::println(scale(2)) }
                std::println(2.scale(7))
                with [2, 3] { std::println(add()) }
                std::println([2, 3].add())
            }
        """.trimIndent()))
    }

    @Test fun aReceiverMayNotBePassedAsAnArgument() {
        val errors = assertIs<CompilationResult.Failure>(Compiler().compile("""
            import std.io
            fin scale: [std::Int](std::Int) -> std::Int = [value] { factor -> value * factor }
            func main() { std::println(scale(2, 7)) }
        """.trimIndent())).errors
        assertTrue(
            errors.any { "expects 1 argument(s), got 2" in it && "not as arguments" in it },
            errors.toString(),
        )
    }

    /** Types are written only where the declared type does not already supply them. */
    @Test fun aLambdaMayOmitTypesTheDeclaredTypeSupplies() {
        assertEquals("6", run("""
            import std.io
            fin add: [std::Int, std::Int]() -> std::Int = { x, y -> x + y }
            fin twice: (std::Int) -> std::Int = { n -> n * 2 }
            func main() {
                with [1, 2] { std::println(add() + twice(1) + 1) }
            }
        """.trimIndent()))
    }

    @Test fun nonGenericVariadicFunctionsValidateEachElement() {
        assertEquals("3", run("""
            import std.io
            func sum(...values: std::Int): std::Int {
                var result = 0
                for value in values {
                    result += value
                }
                return result
            }
            func main() {
                std::println(sum(1, 1, 1))
            }
        """.trimIndent()))
    }
}
