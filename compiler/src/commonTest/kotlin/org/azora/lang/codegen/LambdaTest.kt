package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
                var double = { x: Int -> x * 2 }
                std::println(double(5))
            }
        """.trimIndent()))
    }

    @Test fun higherOrderFunction() {
        assertEquals("16", run("""
            import std.io
            func apply(f: (Int) -> Int, x: Int): Int {
                return f(x)
            }
            func main() {
                std::println(apply({ x: Int -> x * x }, 4))
            }
        """.trimIndent()))
    }

    @Test fun closureCapturesParameter() {
        assertEquals("15", run("""
            import std.io
            func makeAdder(n: Int): (Int) -> Int {
                return { x: Int -> x + n }
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
                var add = { x: Int -> x + offset }
                std::println(add(4))
            }
        """.trimIndent()))
    }

    @Test fun lambdaInInterpolation() {
        assertEquals("25", run("""
            import std.io
            func apply(f: (Int) -> Int, x: Int): Int {
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
            func run(g: () -> String): String {
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
            pack Subscription { fin cancel: () -> Unit }
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
                fin active: Bool
                fin cancel: () -> Unit
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
            func run(action: () -> String): String {
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
                func run[self: Self&](action: (Int) -> Int): Int {
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
            func render(prefix: String, action: () -> String): String {
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
                std::println(std::untracked<String> { "qualified" })
            }
        """.trimIndent()))
    }

    @Test fun contextualReceiverLambdaCanTrailGenericQualifiedCall() {
        assertEquals("receiver", run("""
            import std.io
            pack Context { fin value: String }
            func run<T>(fallback: T, action: [Context&] -> T): T {
                fin context = Context("receiver")
                var result = fallback
                with context { result = action() }
                return result
            }
            func main() {
                std::println(run<String>("") [context: Context&] { context.value })
            }
        """.trimIndent()))
    }

    @Test fun contextualReceiverLambdaCanTrailParenthesizedArguments() {
        assertEquals("prefixed", run("""
            import std.io
            pack Context { fin value: String }
            func render(prefix: String, action: [Context&] -> String): String {
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
                fin invoke: (() -> String) -> String =
                    func(action: () -> String) { return action() }
                std::println(invoke { "variable" })
            }
        """.trimIndent()))
    }

    @Test fun callablePackFieldAcceptsTrailingLambda() {
        assertEquals("field", run("""
            import std.io
            pack Runner {
                fin invoke: (() -> String) -> String =
                    func(action: () -> String) { return action() }
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
                fin invoke: (() -> String) -> String =
                    func(action: () -> String) { return action() }
                std::println((invoke) { "grouped" })
            }
        """.trimIndent()))
    }

    @Test fun primitiveExtensionMethodAcceptsTrailingLambda() {
        assertEquals("12", run("""
            import std.io
            impl Int {
                func map[self: Self&](action: (Int) -> Int): Int {
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
                Run(() -> String)
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
                func execute[self: Self&](action: () -> String): String
            }
            pack Direct
            impl Executor for Direct {
                func execute[self: Self&](action: () -> String): String {
                    return action()
                }
            }
            func executeWith(executor: Executor): String {
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
                fin add: [Int, Int] -> Int =
                    func { x: Int, y: Int -> x + y }
                fin sub: (Int, Int) -> Int =
                    func(x: Int, y: Int) { return x - y }
            }

            func main() {
                fin calculator = Calculator()
                std::println(calculator.add(2, 3))
                std::println(calculator.sub(9, 7))
            }
        """.trimIndent()))
    }

    @Test fun withSuppliesContextualReceivers() {
        assertEquals("5", run("""
            import std.io

            fin add: [Int, Int] -> Int =
                func { x: Int, y: Int -> x + y }

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

            func twice(action: (Int) -> Unit) {
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

            func apply(v: Vec2&, f: [Vec2&] -> Int): Int {
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

            func apply(v: Vec2&, o: Vec2&, f: [Vec2&](Vec2&) -> Int): Int {
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

            fin scale: [Int](Int) -> Int =
                func(value: Int) { factor: Int -> value * factor }

            func main() {
                with 5 {
                    std::println(scale(2))
                }
                std::println(scale(2, 7))
            }
        """.trimIndent()))
    }

    @Test fun nonGenericVariadicFunctionsValidateEachElement() {
        assertEquals("3", run("""
            import std.io
            func sum(...values: Int): Int {
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
