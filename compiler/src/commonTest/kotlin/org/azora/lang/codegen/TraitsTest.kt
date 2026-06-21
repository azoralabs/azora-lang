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
            pack Point {
                var x: Int
                var y: Int
            }
            spec Describable {
                func describe(): String
            }
            impl Describable for Point {
                func describe(): String {
                    return "Point(" + self.x + ", " + self.y + ")"
                }
            }
            func main() {
                var p = Point(3, 4)
                println(p.describe())
            }
        """.trimIndent()))
    }

    @Test fun specWithMultipleMethods() {
        assertEquals("green\n0", run("""
            pack Light {
                var color: String
                var brightness: Int
            }
            spec Device {
                func status(): String
                func level(): Int
            }
            impl Device for Light {
                func status(): String {
                    return self.color
                }
                func level(): Int {
                    return self.brightness
                }
            }
            func main() {
                var l = Light("green", 0)
                println(l.status())
                println(l.level())
            }
        """.trimIndent()))
    }

    @Test fun specMissingMethodFails() {
        val errors = expectFailure("""
            spec Describable {
                func describe(): String
                func detail(): String
            }
            pack P {
                var x: Int
            }
            impl Describable for P {
                func describe(): String {
                    return "P"
                }
            }
            func main() { println("hi") }
        """.trimIndent())
        assertTrue(errors.any { it.contains("detail") }, "Expected missing 'detail' error, got: $errors")
    }

    @Test fun unknownSpecFails() {
        val errors = expectFailure("""
            pack P {
                var x: Int
            }
            impl NonExistent for P {
                func foo(): Int {
                    return 42
                }
            }
            func main() { println("hi") }
        """.trimIndent())
        assertTrue(errors.any { it.contains("NonExistent") }, "Expected unknown spec error, got: $errors")
    }

    @Test fun implWithoutTraitWorksAsBefore() {
        assertEquals("42", run("""
            pack P {
                var x: Int
            }
            impl P {
                func getX(): Int {
                    return self.x
                }
            }
            func main() {
                var p = P(42)
                println(p.getX())
            }
        """.trimIndent()))
    }
}
