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
            import std.container.*
            import std.*

            func verify(): std::Int {
                inline assert std::reflect<std::List>.hasDeco<std::Serializable> { "List metadata missing" }
                inline assert std::reflect<std::MutableList>.hasDeco<std::Serializable> { "MutableList metadata missing" }
                inline assert std::reflect<std::Set>.hasDeco<std::Serializable> { "Set metadata missing" }
                inline assert std::reflect<std::MutableSet>.hasDeco<std::Serializable> { "MutableSet metadata missing" }
                inline assert std::reflect<std::Map>.hasDeco<std::Serializable> { "Map metadata missing" }
                inline assert std::reflect<std::MutableMap>.hasDeco<std::Serializable> { "MutableMap metadata missing" }
                inline assert std::reflect<std::Deque>.hasDeco<std::Serializable> { "Deque metadata missing" }
                inline assert std::reflect<std::Queue>.hasDeco<std::Serializable> { "Queue metadata missing" }
                inline assert std::reflect<std::Stack>.hasDeco<std::Serializable> { "Stack metadata missing" }
                return 1
            }

            func main() {}
        """.trimIndent())
        assertTrue(result.ir.functions.any { it.name == "verify" })
    }

    @Test fun coreContainersExecuteTogether() {
        val result = compile("""
            import std.container.*
            import std.io
            import std.*

            func main() {
                var list = std::listOf(1, 2)
                list.add(3)
                std::println(list[0] + list[2])

                var set = std::setOf(1, 1, 2)
                std::set.add(3)
                std::println(std::set.size)

                var map = std::HashMap<std::String, std::Int>()
                std::map.put("a", 1)
                std::map.put("a", 2)
                std::println(std::map["a"])

                var queue = std::Queue<std::Int>()
                queue.enqueue(4)
                queue.enqueue(5)
                std::println(queue.dequeue())

                var stack = std::Stack<std::Int>()
                stack.push(6)
                stack.push(7)
                std::println(stack.pop())

                var deque = std::Deque<std::Int>()
                deque.pushFront(8)
                deque.pushBack(9)
                std::println(deque.popFront() + deque.popBack())

                fin tuple = std::tupleOf("ok", 10)
                std::println(tuple.0)
                std::println(tuple.1)
            }
        """.trimIndent())

        assertEquals("4\n3\n2\n4\n7\n17\nok\n10", IrInterpreter().interpret(result.ir).trim())
    }
}
