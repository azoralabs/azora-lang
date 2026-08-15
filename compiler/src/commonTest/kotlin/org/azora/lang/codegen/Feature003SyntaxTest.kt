package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Feature003SyntaxTest {
    private fun compile(source: String): CompilationResult =
        Compiler().compile(source, release = false)

    private fun run(source: String): String {
        val result = compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun packWithoutExposedFieldsAllowsReadOnlyExtension() {
        assertEquals("7", run("""
            import std.io
            pack Counter {
                var value: std::Int
            }
            func peek[self: Counter&](): std::Int {
                return self.value
            }
            func main() {
                var c = Counter(7)
                std::println(c.peek())
            }
        """.trimIndent()))
    }

    @Test fun packWithoutExposedFieldsRejectsMutableExtensionReceiver() {
        val result = compile("""
            import std.io
            pack Counter {
                var _value: std::Int
            }
            func bump[self: Counter!]() {
                self._value = self._value + 1
            }
            func main() {
                var c = Counter(0)
                c.bump()
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "pack 'Counter' has no exposed fields" in it }, "errors: ${result.errors}")
    }

    @Test fun implPackCanMutatePackInDeclaringFile() {
        assertEquals("2", run("""
            import std.io
            pack Counter {
                var value: std::Int
            }
            impl pack Counter {
                func bump() {
                    self.value = self.value + 1
                }
                func peek(): std::Int {
                    return self.value
                }
            }
            func main() {
                var c = Counter(1)
                c.bump()
                std::println(c.peek())
            }
        """.trimIndent()))
    }

    @Test fun implPackCannotTargetImportedStdlibPack() {
        val result = compile("""
            import std.io
            import std.string
            impl pack StringBuilder {
                func steal() {
                }
            }
            func main() {
                var builder = StringBuilder()
                builder.steal()
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "impl pack StringBuilder" in it }, "errors: ${result.errors}")
    }

    @Test fun refExtensionCannotMutateSelf() {
        val result = compile("""
            import std.io
            pack Counter {
                var value: std::Int
            }
            func bump[self: Counter&]() {
                self.value = self.value + 1
            }
            func main() {
                var c = Counter(0)
                c.bump()
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "self&" in it }, "errors: ${result.errors}")
    }

    @Test fun reactiveLifetimesAreBindingModifiers() {
        assertEquals("15", run("""
            import std.io
            import std.reactive
                        react func main() {
                remember var a: std::Int = 1
                retain var b: std::Int = 2
                preserve var c: std::Int = 3
                a = 4
                b = 5
                c = 6
                std::println(a + b + c)
            }
        """.trimIndent()))
    }

    @Test fun compactConversionSpecsGeneratePropertyStyleMethods() {
        assertEquals("Label(ok)\nLabel(ok)", run("""
            import std.io
            pack Label {
                var value: std::String
            }
            spec Into<T>[self: std::Self&]: T use as "to${'$'}{T.typeName}"
            spec From<T>[self: std::Self&]: T use as "from${'$'}{T.typeName}"
            impl Into<std::String> for Label {
                prop into[self: std::Self&]: std::String {
                    return "Label(" + self.value + ")"
                }
            }
            impl From<std::String> for Label {
                prop from[self: std::Self&]: std::String {
                    return "Label(" + self.value + ")"
                }
            }
            func main() {
                var label = Label("ok")
                std::println(label.toString)
                std::println(label.fromString)
            }
        """.trimIndent()))
    }

    @Test fun compactConversionSpecUseAsWorksBeforeSpecDeclaration() {
        assertEquals("Label(ok)", run("""
            import std.io
            pack Label {
                var value: std::String
            }
            impl Show<std::String> for Label {
                prop show[self: std::Self&]: std::String {
                    return "Label(" + self.value + ")"
                }
            }
            spec Show<T> {
                prop show<T>[self: std::Self&]: T
                use show<T> as "show${'$'}{T.typeName}"
            }
            func main() {
                var label = Label("ok")
                std::println(label.showString)
            }
        """.trimIndent()))
    }

    @Test fun compactConversionSpecUseAsAcceptsLiteralMemberName() {
        assertEquals("Label(ok)", run("""
            import std.io
            pack Label {
                var value: std::String
            }
            spec Render<T> {
                prop render<T>[self: std::Self&]: T
                use render<T> as "render"
            }
            impl Render<std::String> for Label {
                prop render[self: std::Self&]: std::String {
                    return "Label(" + self.value + ")"
                }
            }
            func main() {
                var label = Label("ok")
                std::println(label.render)
            }
        """.trimIndent()))
    }

    @Test fun compactConversionSpecsRejectParenthesesWhenSpecHasNoParens() {
        val result = Compiler().compile("""
            import std.io
            pack Label {
                var value: std::String
            }
            spec Into<T>[self: std::Self&]: T use as "to${'$'}{T.typeName}"
            impl Into<std::String> for Label {
                prop into[self: std::Self&]: std::String {
                    return self.value
                }
            }
            func main() {
                var label = Label("ok")
                std::println(label.toString())
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "without parentheses" in it }, "${'$'}{result.errors}")
    }

    @Test fun compactConversionSpecsWithParensRequireCallSyntax() {
        assertEquals("7", run("""
            import std.io
            pack Box {
                var value: std::Int
            }
            spec Extract<T> {
                func extract<T>[self: std::Self&](): T
                use extract<T> as "extract${'$'}{T.typeName}"
            }
            impl Extract<std::Int> for Box {
                func extract[self: std::Self&](): std::Int {
                    return self.value
                }
            }
            func main() {
                var box = Box(7)
                std::println(box.extractInt())
            }
        """.trimIndent()))

        val result = Compiler().compile("""
            import std.io
            pack Box {
                var value: std::Int
            }
            spec Extract<T> {
                func extract<T>[self: std::Self&](): T
                use extract<T> as "extract${'$'}{T.typeName}"
            }
            impl Extract<std::Int> for Box {
                func extract[self: std::Self&](): std::Int {
                    return self.value
                }
            }
            func main() {
                var box = Box(7)
                std::println(box.extractInt)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "requires a method call" in it }, "${'$'}{result.errors}")
    }

    @Test fun implAsStringIsCastOnly() {
        assertEquals("cast:x", run("""
            import std.io
            import std.convert
            pack Label {
                var value: std::String
            }
            impl std::Cast<std::String> for Label {
                prop castValue[self: std::Self&]: std::String {
                    return "cast:" + self.value
                }
            }
            func main() {
                var label = Label("x")
                std::println(label as std::String)
            }
        """.trimIndent()))
    }

    @Test fun implAsStringDoesNotCreateToString() {
        val result = compile("""
            import std.io
            import std.convert
            pack Label {
                var value: std::String
            }
            impl std::Cast<std::String> for Label {
                prop castValue[self: std::Self&]: std::String {
                    return "cast:" + self.value
                }
            }
            func main() {
                var label = Label("x")
                std::println(label.toString)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "toString" in it }, "errors: ${result.errors}")
    }

    @Test fun friendRealmAcceptsDoubleColonPath() {
        assertEquals("3", run("""
            import std.io
            realm std {
                func abs(x: std::Int): std::Int {
                    if x < 0 { return -x }
                    return x
                }
            }
            func main() {
                std::println(std::abs(-3))
            }
        """.trimIndent()))
    }

    @Test fun emptyPackCanOmitBody() {
        assertEquals("ok", run("""
            import std.io
            pack Marker
            func main() {
                var marker = Marker()
                std::println("ok")
            }
        """.trimIndent()))
    }

    @Test fun getAndSetKeywordsRemainSoftForMembers() {
        assertEquals("7", run("""
            import std.io
            pack Accessors {
                var get: std::Int
                var set: std::Int
            }
            func main() {
                var accessors = Accessors(3, 4)
                std::println(accessors.get + accessors.set)
            }
        """.trimIndent()))
    }

    @Test fun operInsideRegularImplIsRejected() {
        val error = assertFailsWith<IllegalStateException> {
            compile("""
            import std.io
            pack Box {
                var value: std::Int
            }
            impl Box {
                oper[](i: std::Int): std::Int {
                    return self.value
                }
            }
            func main() {
                var box = Box(1)
                std::println(box[0])
            }
            """.trimIndent())
        }
        assertTrue(error.message?.contains("impl oper[]") == true, "error: ${error.message}")
    }

    @Test fun activeCodegenTargetsAreProducedForNewSyntax() {
        val result = compile("""
            import std.io
            import std.reactive
            pack Counter {
                var value: std::Int
            }
            impl pack Counter {
                func bump() {
                    self.value = self.value + 1
                }
            }
            func peek[self: Counter&](): std::Int {
                return self.value
            }
                        react func main() {
                preserve fin label: std::String = "value="
                var c = Counter(40)
                c.bump()
                std::println(label + c.peek())
            }
        """.trimIndent())
        val success = assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        assertTrue(success.wasm.isNotBlank())
        assertTrue(success.llvm.isNotBlank())
    }
}
