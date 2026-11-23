package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
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
                var value: Int
            }
            func Counter&.peek(): Int {
                return self.value
            }
            func main() {
                var c = Counter(7)
                println(c.peek())
            }
        """.trimIndent()))
    }

    @Test fun packWithoutExposedFieldsRejectsMutableExtensionReceiver() {
        val result = compile("""
            import std.io
            pack Counter {
                var _value: Int
            }
            func Counter!.bump() {
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
                var value: Int
            }
            impl pack Counter {
                func !.bump() {
                    self.value = self.value + 1
                }
                func &.peek(): Int {
                    return self.value
                }
            }
            func main() {
                var c = Counter(1)
                c.bump()
                println(c.peek())
            }
        """.trimIndent()))
    }

    @Test fun implPackDeclaresAStatelessPackWithItsAnnotations() {
        assertEquals("3\n6\nstatus", run("""
            import std.io
            import std.reflection
            annot @Tag for .Pack { fin name: String = "" }
            @Tag(name: "status")
            impl pack StatusBar {
                func hello(): Int = 3
                func &.twice(): Int = 6
            }
            func main() {
                println(StatusBar::hello())
                println(StatusBar().twice())
                inline for C in reflect<*>.withAnnot<Tag> {
                    println(reflect<C>.annotMeta<Tag>.name)
                }
            }
        """.trimIndent()))
    }

    @Test fun aStaticOfTheExpectedTypeIsCalledWithoutItsName() {
        assertEquals("11.0\n10.0\n10.0\n8", run("""
            import std.io
            pack Style { var width: Double = 0.0 }
            impl Style {
                func wide(): Style = Style(10.0)
                func &.taller(): Style = Style(self.width + 1.0)
            }
            func show(style: Style) { println(style.width) }
            func pick(): Style = ::wide()
            func main() {
                show(::wide().taller())
                fin chosen: Style = ::wide()
                println(chosen.width)
                println(pick().width)
                fin twice = { x: Int -> x * 2 }
                scope {
                    fin twice = 0
                    println(::twice(4))
                }
            }
        """.trimIndent()))
    }

    @Test fun leadingDotsAtTheSamePositionInTwoFilesKeepTheirOwnTypes() {
        // Every file numbers its lines from 1: `.(…)` in the library and `.High`
        // in the program are both written at 3:20, and each means its own type.
        val library = org.azora.lang.LibrarySource("shapes/marks.az", """
            module shapes.marks
            pack Mark { var size: Int = 0 }
            func mark(): Mark { fin m: Mark = .(7)
                return m }
        """.trimIndent())
        val program = """
            import std.io
            import shapes.marks
            fin levelxxxxxxxxxxxxxxx: Level = .High
            enum Level { Low High }
            func main() {
                println(mark().size)
                println(levelxxxxxxxxxxxxxxx == Level.High)
            }
        """.trimIndent()
        val result = Compiler(listOf(library)).compile(program, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("7\ntrue", IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun aStringBuilderTakesAnyNumberOfParts() {
        assertEquals("100", run("""
            import std.io
            import std.string
            func main() {
                var text = StringBuilder()
                var i = 0
                while i < 50 {
                    text.append("ab")
                    i = i + 1
                }
                println(stringLength(text.build()))
            }
        """.trimIndent()))
    }

    @Test fun aSiblingModuleStoresACopyOfACopyPlace() {
        // The store into a field copies the `fin` it reads: the binding is freed
        // at the end of each iteration, and the field must not share it.
        val library = org.azora.lang.LibrarySource("demo/aiming.az", """
            module demo.aiming
            import std.traits
            pack Point derives Clone { var y: Double = 0.0 }
            pack Hit derives Clone { var point: Point = .() }
            pack Aim derives Clone { var hit: Hit = .() }
            func cast(at: Double): Hit {
                var hit: Hit = .()
                hit.point = .(at * 2.0)
                return hit
            }
            func aim(): Aim {
                var best: Aim = .()
                var index = 0
                while index < 3 {
                    fin hit = cast(index as Double + 1.0)
                    best.hit = hit
                    index = index + 1
                }
                return best
            }
        """.trimIndent())
        val result = Compiler(listOf(library)).compile("import std.io\nimport demo.aiming\nfunc main() { println(aim().hit.point.y) }", release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        // The copy is made first, the field's old value dropped, then the copy stored.
        val ir = result.ir.prettyPrint()
        assertTrue(Regex("fin (__member_replace_\\d+): Hit = __isolated\\(hit\\)\\s+__purge\\(best\\.hit\\)\\s+best\\.hit = \\1").containsMatchIn(ir), ir)
        assertEquals("6.0", IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun aSiblingModulesMethodNamedLikeAStringIntrinsicIsItself() {
        val library = org.azora.lang.LibrarySource("demo/region.az", """
            module demo.region
            pack Region { var size: Double = 1.0 }
            impl Region {
                func &.contains(x: Double, y: Double): Bool = x >= 0.0 && y >= 0.0 && x <= self.size && y <= self.size
            }
        """.trimIndent())
        val result = Compiler(listOf(library)).compile(
            "import std.io\nimport demo.region\nfunc main() { println(Region(2.0).contains(1.0, 1.5)) }",
        )
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        val definitions = Regex("define [^@]*@(\\S*Region_contains)\\(").findAll(result.llvm).map { it.groupValues[1] }.toList()
        assertEquals(1, definitions.size, "one definition, not the method and a string intrinsic: $definitions")
    }

    @Test fun aThreadLocalPackTakesAWholeValue() {
        assertEquals("0.0\n6.0\n10.0", run("""
            import std.io
            import std.traits
            pack Point derives Clone {
                var x: Double = 0.0
                var y: Double = 0.0
            }
            threadlocal var plane: Point = .()
            func make(v: Double): Point = .(v, v * 2.0)
            func main() {
                println(plane.y)
                fin p = make(3.0)
                plane = p
                println(plane.y)
                plane = make(5.0)
                println(plane.y)
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
            func Counter&.bump() {
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
                remember var a: Int = 1
                retain var b: Int = 2
                preserve var c: Int = 3
                a = 4
                b = 5
                c = 6
                println(a + b + c)
            }
        """.trimIndent()))
    }

    @Test fun compactConversionSpecsGeneratePropertyStyleMethods() {
        assertEquals("Label(ok)\nLabel(ok)", run("""
            import std.io
            pack Label {
                var value: String
            }
            spec Into<T> {
                prop<T> &.into: T
                inline prop to${'$'}{T.typeName}: T = into<T>
            }
            spec From<T> {
                prop<T> &.from: T
                inline prop from${'$'}{T.typeName}: T = from<T>
            }
            impl Into<String> for Label {
                prop &.into: String {
                    return "Label(" + self.value + ")"
                }
            }
            impl From<String> for Label {
                prop &.from: String {
                    return "Label(" + self.value + ")"
                }
            }
            func main() {
                var label = Label("ok")
                println(label.toString)
                println(label.fromString)
            }
        """.trimIndent()))
    }

    @Test fun compactConversionSpecUseAsWorksBeforeSpecDeclaration() {
        assertEquals("Label(ok)", run("""
            import std.io
            pack Label {
                var value: String
            }
            impl Show<String> for Label {
                prop &.show: String {
                    return "Label(" + self.value + ")"
                }
            }
            spec Show<T> {
                prop<T> &.show: T
                inline prop show${'$'}{T.typeName}: T = show<T>
            }
            func main() {
                var label = Label("ok")
                println(label.showString)
            }
        """.trimIndent()))
    }

    @Test fun compactConversionSpecUseAsAcceptsLiteralMemberName() {
        assertEquals("Label(ok)", run("""
            import std.io
            pack Label {
                var value: String
            }
            spec Render<T> {
                prop<T> &.render: T
                inline prop render: T = render<T>
            }
            impl Render<String> for Label {
                prop &.render: String {
                    return "Label(" + self.value + ")"
                }
            }
            func main() {
                var label = Label("ok")
                println(label.render)
            }
        """.trimIndent()))
    }

    @Test fun compactConversionSpecsRejectParenthesesWhenSpecHasNoParens() {
        val result = Compiler().compile("""
            import std.io
            pack Label {
                var value: String
            }
            spec Into<T> {
                prop<T> &.into: T
                inline prop to${'$'}{T.typeName}: T = into<T>
            }
            impl Into<String> for Label {
                prop &.into: String {
                    return self.value
                }
            }
            func main() {
                var label = Label("ok")
                println(label.toString())
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
            spec Extract<T> {
                func<T> &.extract(): T
                inline prop extract${'$'}{T.typeName}: T = extract<T>
            }
            impl Extract<Int> for Box {
                func &.extract(): Int {
                    return self.value
                }
            }
            func main() {
                var box = Box(7)
                println(box.extractInt())
            }
        """.trimIndent()))

        val result = Compiler().compile("""
            import std.io
            pack Box {
                var value: Int
            }
            spec Extract<T> {
                func<T> &.extract(): T
                inline prop extract${'$'}{T.typeName}: T = extract<T>
            }
            impl Extract<Int> for Box {
                func &.extract(): Int {
                    return self.value
                }
            }
            func main() {
                var box = Box(7)
                println(box.extractInt)
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
            impl Cast<String> for Label {
                prop &.castValue: String {
                    return "cast:" + self.value
                }
            }
            func main() {
                var label = Label("x")
                println(label as String)
            }
        """.trimIndent()))
    }

    @Test fun implAsStringDoesNotCreateToString() {
        val result = compile("""
            import std.io
            pack Label {
                var value: String
            }
            impl Cast<String> for Label {
                prop &.castValue: String {
                    return "cast:" + self.value
                }
            }
            func main() {
                var label = Label("x")
                println(label.toString)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "toString" in it }, "errors: ${result.errors}")
    }

    @Test fun friendScopeAcceptsDoubleColonPath() {
        assertEquals("3", run("""
            import std.io
            scope std {
                func abs(x: Int): Int {
                    if x < 0 { return -x }
                    return x
                }
            }
            func main() {
                println(std::abs(-3))
            }
        """.trimIndent()))
    }

    @Test fun emptyPackCanOmitBody() {
        assertEquals("ok", run("""
            import std.io
            pack Marker
            func main() {
                var marker = Marker()
                println("ok")
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
                println(accessors.get + accessors.set)
            }
        """.trimIndent()))
    }

    @Test fun operatorInsideRegularImplUsesReceiverShorthand() {
        assertEquals("1", run("""
            import std.io
            pack Box {
                var value: Int
            }
            impl Box {
                oper[] &.(i: Int): Int {
                    return self.value
                }
            }
            func main() {
                var box = Box(1)
                println(box[0])
            }
            """.trimIndent()))
    }

    @Test fun activeCodegenTargetsAreProducedForNewSyntax() {
        val result = compile("""
            import std.io
            import std.reactive
            pack Counter {
                var value: Int
            }
            impl pack Counter {
                func !.bump() {
                    self.value = self.value + 1
                }
            }
            func Counter&.peek(): Int {
                return self.value
            }
                        react func main() {
                preserve fin label: String = "value="
                var c = Counter(40)
                c.bump()
                println(label + c.peek())
            }
        """.trimIndent())
        val success = assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        assertTrue(success.wasm.isNotBlank())
        assertTrue(success.llvm.isNotBlank())
    }
}
