package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContainerStdlibTest {
    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    @Test fun everyContainerSourceParsesAndIsSerializableMetadata() {
        val directory = java.io.File("../Internal/Std/Container")
        val files = directory.listFiles { file -> file.extension == "az" }.orEmpty()
        assertEquals(11, files.size)
        files.forEach { Parser(Lexer(it.readText()).tokenize()).parse() }

        val result = compile("""
            import std.container::*
            import std::*

            func verify(): Int {
                inline assert reflect<List>.hasAnnot<Serializable> { "List metadata missing" }
                inline assert reflect<MutableList>.hasAnnot<Serializable> { "MutableList metadata missing" }
                inline assert reflect<Set>.hasAnnot<Serializable> { "Set metadata missing" }
                inline assert reflect<MutableSet>.hasAnnot<Serializable> { "MutableSet metadata missing" }
                inline assert reflect<Map>.hasAnnot<Serializable> { "Map metadata missing" }
                inline assert reflect<MutableMap>.hasAnnot<Serializable> { "MutableMap metadata missing" }
                inline assert reflect<Deque>.hasAnnot<Serializable> { "Deque metadata missing" }
                inline assert reflect<Queue>.hasAnnot<Serializable> { "Queue metadata missing" }
                inline assert reflect<Stack>.hasAnnot<Serializable> { "Stack metadata missing" }
                return 1
            }

            func main() {}
        """.trimIndent())
        assertTrue(result.ir.functions.any { it.name == "verify" })
    }

    @Test fun coreContainersExecuteTogether() {
        val result = compile("""
            import std.container::*
            import std.io
            import std::*

            func main() {
                var list = listOf(1, 2)
                list.add(3)
                println(list[0] + list[2])

                var set = setOf(1, 1, 2)
                set.add(3)
                println(set.size)

                var map = HashMap<String, Int>()
                map.put("a", 1)
                map.put("a", 2)
                println(map["a"])

                var queue = Queue<Int>()
                queue.enqueue(4)
                queue.enqueue(5)
                println(queue.dequeue())

                var stack = Stack<Int>()
                stack.push(6)
                stack.push(7)
                println(stack.pop())

                var deque = Deque<Int>()
                deque.pushFront(8)
                deque.pushBack(9)
                println(deque.popFront() + deque.popBack())

                fin tuple = tupleOf("ok", 10)
                println(tuple.0)
                println(tuple.1)
            }
        """.trimIndent())

        assertEquals("4\n3\n2\n4\n7\n17\nok\n10", IrInterpreter().interpret(result.ir).trim())
    }
}
