package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TraitsTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun expectFailure(source: String): List<String> {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Failure>(result, "Expected compilation failure but got success")
        return result.errors
    }

    @Test fun specAndImplFor() {
        assertEquals("Point(3, 4)", run("""
            import std.io
            pack Point {
                var x: std::Int
                var y: std::Int
            }
            spec Describable {
                func describe[self: std::Self&](): std::String
            }
            impl Describable for Point {
                func describe(): std::String {
                    return "Point(" + self.x + ", " + self.y + ")"
                }
            }
            func main() {
                var p = Point(3, 4)
                std::println(p.describe())
            }
        """.trimIndent()))
    }

    @Test fun specWithMultipleMethods() {
        assertEquals("green\n0", run("""
            import std.io
            pack Light {
                var color: std::String
                var brightness: std::Int
            }
            spec Device {
                func status[self: std::Self&](): std::String
                func level[self: std::Self&](): std::Int
            }
            impl Device for Light {
                func status(): std::String {
                    return self.color
                }
                func level(): std::Int {
                    return self.brightness
                }
            }
            func main() {
                var l = Light("green", 0)
                std::println(l.status())
                std::println(l.level())
            }
        """.trimIndent()))
    }

    @Test fun specMissingMethodFails() {
        val errors = expectFailure("""
            import std.io
            spec Describable {
                func describe[self: std::Self&](): std::String
                func detail[self: std::Self&](): std::String
            }
            pack P {
                var x: std::Int
            }
            impl Describable for P {
                func describe(): std::String {
                    return "P"
                }
            }
            func main() { std::println("hi") }
        """.trimIndent())
        assertTrue(errors.any { it.contains("detail") }, "Expected missing 'detail' error, got: $errors")
    }

    @Test fun unknownSpecFails() {
        val errors = expectFailure("""
            import std.io
            pack P {
                var x: std::Int
            }
            impl NonExistent for P {
                func foo(): std::Int {
                    return 42
                }
            }
            func main() { std::println("hi") }
        """.trimIndent())
        assertTrue(errors.any { it.contains("NonExistent") }, "Expected unknown spec error, got: $errors")
    }

    @Test fun implWithoutTraitWorksAsBefore() {
        assertEquals("42", run("""
            import std.io
            pack P {
                var x: std::Int
            }
            impl P {
                func getX(): std::Int {
                    return self.x
                }
            }
            func main() {
                var p = P(42)
                std::println(p.getX())
            }
        """.trimIndent()))
    }

    @Test fun decoratorCanBeImplementedAsMarkerTrait() {
        assertEquals("", run("""
            annot Serializable {}
            pack UserId {
                fin value: std::Long
            }
            impl Serializable for UserId {}
            func main() {}
        """.trimIndent()))
    }

    @Test fun decoratorImplementationRequiresAnExplicitBody() {
        val errors = expectFailure("""
            annot Serializable {}
            pack UserId { fin value: std::Long }
            impl Serializable for UserId
            func main() {}
        """.trimIndent())
        assertTrue(errors.any { it.contains("manual implementation") && it.contains("requires a body") }, errors.toString())
    }

    @Test fun decoratorMarkerImplementationCannotDeclareMethods() {
        val errors = expectFailure("""
            annot Serializable {}
            pack UserId {
                fin value: std::Long
            }
            impl Serializable for UserId {
                func generated[self: std::Self&](): std::Unit {
                    return
                }
            }
            func main() {}
        """.trimIndent())
        assertTrue(errors.any { it.contains("marker contract") && it.contains("must be empty") }, errors.toString())
    }

    @Test fun duplicateDecoratorImplementationFails() {
        val errors = expectFailure("""
            annot Serializable {}
            pack UserId {
                fin value: std::Long
            }
            impl Serializable for UserId {}
            impl Serializable for UserId {}
            func main() {}
        """.trimIndent())
        assertTrue(errors.any { it.contains("duplicate decorator") }, errors.toString())
    }
}
