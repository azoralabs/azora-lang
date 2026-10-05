package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConstructorResultTest {
    companion object {
        val tree = """
            import std.io
            pack NodeScope { var next: Int = 0 }
            pack Caption { var text: String = "" }
            impl Caption {
                react ctor (context: NodeScope!).(text: String): Int {
                    self.text = text
                    context.next += 1
                    return context.next
                }
            }
            pack Group { var marker: Int = 0 }
            impl Group {
                react ctor (context: NodeScope!).(children: react NodeScope!.() -> Int): Int {
                    context.next += 1
                    fin entity = context.next
                    using context { children() }
                    return entity
                }
            }
            pack Zero { var value: Int = 1 }
            impl Zero { ctor .(): Int { return self.value + 41 } }
            react func main() {
                var nodeScope = NodeScope()
                using nodeScope {
                    fin parent: Int = Group {
                        Caption("First")
                        Caption("Second")
                    }
                    println(parent)
                }
                println(nodeScope.next)
                fin plain = Caption("plain value")
                println(plain.text)
                fin zero: Int = Zero()
                println(zero)
            }
        """.trimIndent()
    }

    @Test fun contextualConstructorsBuildNestedTreesAndPreserveDeclaredResults() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(tree, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals("1\n3\nplain value\n42", IrInterpreter().interpret(if (release) result.optimizedIr else result.ir).trim())
        }
    }

    @Test fun unitConstructorChildrenDiscardOnlyTheirImplicitResult() {
        val source = """
            import std.io
            pack NodeScope { var next: Int = 0 }
            pack Node
            impl Node {
                react ctor (context: NodeScope!).(children: inline react NodeScope!.() -> Unit): Int {
                    context.next += 1
                    using context { children() }
                    return context.next
                }
            }
            react func main() {
                var nodeScope = NodeScope()
                fin caption = "child"
                using nodeScope {
                    fin result = Node {
                        remember var value = 7
                        println(caption)
                        value
                    }
                    println(result)
                }
            }
        """.trimIndent()
        val result = assertIs<CompilationResult.Success>(Compiler().compile(source))
        assertEquals("child\n1", IrInterpreter().interpret(result.ir).trim())
        val failed = assertIs<CompilationResult.Failure>(Compiler().compile(
            source.replace(Regex("(?m)^\\s+value$"), "return value"),
        ))
        assertTrue(failed.errors.any { "Unit lambda" in it }, failed.errors.toString())
    }
}
