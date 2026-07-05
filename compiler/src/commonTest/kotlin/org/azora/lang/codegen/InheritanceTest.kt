package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for inheritance: `node` / `leaf` / `repl` / `virt` / `base` with dynamic dispatch.
 */
class InheritanceTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun nodeBasicMethodsAndFields() {
        assertEquals("Rex\n...", run("""
            node Animal(name: String) {
                func speak(): String {
                    return "..."
                }
                func describe(): String {
                    return self.name
                }
            }
            func main() {
                var a = Animal("Rex")
                println(a.describe())
                println(a.speak())
            }
        """.trimIndent()))
    }

    @Test fun nodeInheritanceInheritsFields() {
        assertEquals("Rex", run("""
            node Animal(name: String) {
                func speak(): String {
                    return "generic"
                }
            }
            leaf Dog(name: String) : Animal(name) {
                repl func speak(): String {
                    return "Woof"
                }
            }
            func main() {
                var d = Dog("Rex")
                println(d.name)
            }
        """.trimIndent()))
    }

    @Test fun nodeDynamicDispatch() {
        assertEquals("Woof", run("""
            node Animal(name: String) {
                func speak(): String {
                    return "generic"
                }
            }
            leaf Dog(name: String) : Animal(name) {
                repl func speak(): String {
                    return "Woof"
                }
            }
            func main() {
                var a: Animal = Dog("Rex")
                println(a.speak())
            }
        """.trimIndent()))
    }

    @Test fun nodeInheritedMethodCallsOverride() {
        assertEquals("Rex says Woof", run("""
            node Animal(name: String) {
                func speak(): String {
                    return "generic"
                }
                func describe(): String {
                    return self.name + " says " + self.speak()
                }
            }
            leaf Dog(name: String) : Animal(name) {
                repl func speak(): String {
                    return "Woof"
                }
            }
            func main() {
                var a: Animal = Dog("Rex")
                println(a.describe())
            }
        """.trimIndent()))
    }

    @Test fun nodeDispatchOnTwoInstances() {
        assertEquals("Woof\nMeow", run("""
            node Animal(name: String) {
                func speak(): String {
                    return "generic"
                }
            }
            leaf Dog(name: String) : Animal(name) {
                repl func speak(): String {
                    return "Woof"
                }
            }
            leaf Cat(name: String) : Animal(name) {
                repl func speak(): String {
                    return "Meow"
                }
            }
            func main() {
                var a: Animal = Dog("Rex")
                var b: Animal = Cat("Whiskers")
                println(a.speak())
                println(b.speak())
            }
        """.trimIndent()))
    }

    @Test fun baseCallsParentMethod() {
        assertEquals("animal (overridden)", run("""
            node Animal(name: String) {
                func speak(): String {
                    return "animal"
                }
            }
            leaf Dog(name: String) : Animal(name) {
                repl func speak(): String {
                    return base.speak() + " (overridden)"
                }
            }
            func main() {
                var a: Animal = Dog("Rex")
                println(a.speak())
            }
        """.trimIndent()))
    }

    @Test fun leafWithoutNodeKeyword() {
        assertEquals("42", run("""
            node Base(x: Int) {
                func get(): Int { return self.x }
            }
            leaf Derived(x: Int) : Base(x) {
                repl func get(): Int { return self.x + 1 }
            }
            func main() {
                var d: Base = Derived(41)
                println(d.get())
            }
        """.trimIndent()))
    }

    @Test fun virtFuncInNode() {
        assertEquals("hello\nHELLO", run("""
            node Greeter(msg: String) {
                virt func greet(): String {
                    return self.msg
                }
                func shout(): String {
                    return self.greet().toUpperCase()
                }
            }
            leaf LoudGreeter(msg: String) : Greeter(msg) {
                repl func greet(): String {
                    return self.msg.toUpperCase()
                }
            }
            func main() {
                var g = Greeter("hello")
                println(g.greet())
                var l: Greeter = LoudGreeter("hello")
                println(l.shout())
            }
        """.trimIndent()))
    }
}
