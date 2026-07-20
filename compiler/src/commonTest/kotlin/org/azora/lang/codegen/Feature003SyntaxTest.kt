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
                shield var value: Int
            }
            func Counter.peek(): Int { ref self ->
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
                shield var value: Int
            }
            func Counter.bump() { mut ref self ->
                self.value = self.value + 1
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
                shield var value: Int
            }
            impl pack Counter {
                func bump() {
                    self.value = self.value + 1
                }
                func peek(): Int {
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
                var value: Int
            }
            func Counter.bump() { ref self ->
                self.value = self.value + 1
            }
            func main() {
                var c = Counter(0)
                c.bump()
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "ref self" in it }, "errors: ${result.errors}")
    }

    @Test fun loopIteratorContinueSkipsReset() {
        assertEquals("2\n0", run("""
            import std.io
            pack Iter {
                var i: Int
                var resets: Int
            }
            impl pack Iter {
                func reset() {
                    self.resets = self.resets + 1
                    self.i = 0
                }
                func hasNext(): Bool {
                    return self.i < 2
                }
                func next(): Int {
                    self.i = self.i + 1
                    return self.i
                }
            }
            func main() {
                var it = Iter(1, 0)
                loop it continue {
                    std::println(it.next())
                }
                std::println(it.resets)
            }
        """.trimIndent()))
    }

    @Test fun memRemRetAreReactiveBindings() {
        assertEquals("15", run("""
            import std.io
            func main() {
                mem a: Int = 1
                rem b: Int = 2
                ret c: Int = 3
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
                var value: String
            }
            spec Into<T>: T ( ref self ) use as "to${'$'}{T.typeName}"
            spec From<T>: T ( ref self ) use as "from${'$'}{T.typeName}"
            impl Into<String> for Label { ref self ->
                return "Label(" + self.value + ")"
            }
            impl From<String> for Label { ref self ->
                return "Label(" + self.value + ")"
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
                var value: String
            }
            impl Show<String> for Label { ref self ->
                return "Label(" + self.value + ")"
            }
            spec Show<T>: T ( ref self ) use as "show${'$'}{T.typeName}"
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
                var value: String
            }
            spec Render<T>: T ( ref self ) use as "render"
            impl Render<String> for Label { ref self ->
                return "Label(" + self.value + ")"
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
                var value: String
            }
            spec Into<T>: T ( ref self ) use as "to${'$'}{T.typeName}"
            impl Into<String> for Label { ref self ->
                return self.value
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
                var value: Int
            }
            spec Extract<T>(): T ( ref self ) use as "extract${'$'}{T.typeName}"
            impl Extract<Int> for Box { ref self ->
                return self.value
            }
            func main() {
                var box = Box(7)
                std::println(box.extractInt())
            }
        """.trimIndent()))

        val result = Compiler().compile("""
            import std.io
            pack Box {
                var value: Int
            }
            spec Extract<T>(): T ( ref self ) use as "extract${'$'}{T.typeName}"
            impl Extract<Int> for Box { ref self ->
                return self.value
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
            pack Label {
                var value: String
            }
            impl as String for Label { ref self ->
                return "cast:" + self.value
            }
            func main() {
                var label = Label("x")
                std::println(label as String)
            }
        """.trimIndent()))
    }

    @Test fun implAsStringDoesNotCreateToString() {
        val result = compile("""
            import std.io
            pack Label {
                var value: String
            }
            impl as String for Label { ref self ->
                return "cast:" + self.value
            }
            func main() {
                var label = Label("x")
                std::println(label.toString)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "toString" in it }, "errors: ${result.errors}")
    }

    @Test fun friendZoneAcceptsDoubleColonPath() {
        assertEquals("3", run("""
            import std.io
            friend zone std::math {
                func abs(x: Int): Int {
                    if x < 0 { return -x }
                    return x
                }
            }
            func main() {
                std::println(std::math::abs(-3))
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
                var get: Int
                var set: Int
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
                var value: Int
            }
            impl Box {
                oper[](i: Int): Int {
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
            shield pack Counter {
                var value: Int
            }
            impl pack Counter {
                func bump() {
                    self.value = self.value + 1
                }
            }
            func Counter.peek(): Int { ref self ->
                return self.value
            }
            func main() {
                mem label: String = "value="
                var c = Counter(40)
                c.bump()
                std::println(label + c.peek())
            }
        """.trimIndent())
        val success = assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        assertTrue(success.javascript.isNotBlank())
        assertTrue(success.wasm.isNotBlank())
        assertTrue(success.llvm.isNotBlank())
    }
}
