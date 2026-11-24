/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.gpu

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuCompilerTest {
    @Test
    fun taggedVariantsAndStorageLowerToEachBackend() {
        val shader = """
            /** Runs on the GPU. */
            @Execution("GPU")
            module test.storage

            import gpustdlib.math

            inline fin fixedValue: Float = 0.5

            variant enum Mode {
                Fixed(Float)
                Automatic
            }

            pack State {
                fin mode: Mode
            }

            impl State {
                func &.value(): Float {
                    return when self.mode {
                        .Fixed(v) -> saturate(v)
                        .Automatic -> 0.0
                    }
                }
            }

            @Compute(8, 4, 2)
            func write(id: @GlobalInvocationId UInt3, output: @Storage(3) Float) {
                fin mode: Mode = .Fixed(fixedValue)
                output[id.x] = State(mode).value()
            }
        """.trimIndent()
        val metal = GpuCompiler.compile(shader, "storage.az", GpuTarget.METAL)
        val vulkan = GpuCompiler.compile(shader, "storage.az", GpuTarget.VULKAN)
        val dxil = GpuCompiler.compile(shader, "storage.az", GpuTarget.DIRECTX)

        assertTrue(GpuModule.isGpuSource(shader))
        assertFalse(GpuModule.isGpuSource("// @Execution(\"GPU\")\nmodule test.cpu"))
        val wrongSource = assertFailsWith<GpuCompileException> {
            GpuCompiler.compile("@Compute(1, 1, 1)\nfunc invalid() {}", "unannotated.az", GpuTarget.METAL)
        }
        assertContains(wrongSource.message.orEmpty(), "@Execution")
        assertEquals("write", vulkan.entry)
        assertEquals(GpuStage.COMPUTE, vulkan.stage)
        assertContains(metal.text, "[[buffer(3)]]")
        assertContains(metal.text, "[[host_name(\"write\")]]")
        assertContains(vulkan.text, "set = 0, binding = 3")
        assertContains(vulkan.text, "local_size_x = 8, local_size_y = 4, local_size_z = 2")
        assertContains(dxil.text, "[numthreads(8, 4, 2)]")
        assertContains(dxil.text, "register(u3, space0)")
        assertContains(vulkan.text, ".az_tag")
    }

    @Test
    fun selectingAnEntryControlsItsInterface() {
        val shader = """
            @Compute(1, 1, 1)
            func fill() {}
            @Vertex
            func draw(): Float4 { return Float4(1.0) }
        """.trimIndent()
        val error = assertFailsWith<GpuCompileException> {
            GpuCompiler.compile(shader, "multiple.azgpu", GpuTarget.VULKAN)
        }
        assertContains(error.message.orEmpty(), "--entry")
        val vertex = GpuCompiler.compile(shader, "multiple.azgpu", GpuTarget.VULKAN, "draw")
        val library = GpuCompiler.compileAll(shader, "multiple.azgpu", GpuTarget.METAL).single()
        assertEquals(listOf("fill", "draw"), library.entries)
        assertEquals(GpuStage.VERTEX, vertex.stage)
        assertContains(vertex.text, "gl_Position")
        assertFalse(vertex.text.contains("local_size_x"))
    }

    @Test
    fun rejectsCpuFeaturesAndInvalidGpuProgramsBeforeInvokingAToolchain() {
        val invalid = listOf(
            "async func f() {}" to "unavailable on the GPU",
            "func<T> id(x: T): T { return x }" to "unsupported function modifier",
            "func f(): Int { return f() }" to "recursive GPU function",
            "func f(): Int { return 18446744073709551615 }" to "outside the GPU range",
            "func f() {\n fin value = 1\n value = 2\n}" to "writable",
            "import std.io" to "unknown GPU module",
            """
                @Compute(1, 1, 1)
                func f(input: @ReadOnlyStorage(0) UInt) { input[0] = 1 }
            """.trimIndent() to "writable",
            """
                variant enum Choice {
                    One(Float)
                    Two
                }
                func f(value: Choice): Float {
                    return when value {
                        .One(x) -> x
                    }
                }
            """.trimIndent() to "must return Float on every path",
        )
        for ((source, expected) in invalid) {
            val error = assertFailsWith<GpuCompileException> { GpuCompiler.check(source, "invalid.azgpu") }
            val message = error.message.orEmpty()
            assertTrue(message.startsWith("invalid.azgpu:"))
            assertContains(message, expected)
        }
    }
}
