plugins {
    alias(libs.plugins.convention.kmp.library)
}

val generateStdlib = tasks.register("generateAzStdlib") {
    val stdDir = rootProject.file("Internal/Std")
    val outputDir = layout.buildDirectory.dir("generated/stdlib")

    inputs.dir(stdDir)
    outputs.dir(outputDir)

    doLast {
        val stdFiles = listOf(
            "Math/Math.az",
            "Math/Extra.az",
            "Math/Trig.az",
            "Container/Tuple.az",
            "Container/List.az",
            "Container/Map.az",
            "Container/Set.az",
            "Container/Stack.az",
            "Container/Queue.az",
            "Container/Deque.az",
            "IO/IO.az",
            "IO/Convert.az",
            "Traits/Traits.az",
            "Traits/Core.az",
            "Traits/TraitsExtra.az",
            "Algorithm/Sort.az",
            "Algorithm/Search.az",
            "Algorithm/AlgorithmExtra.az",
            "Concurrency/Coroutines.az",
            "Concurrency/Async.az",
            "Parallelism/Thread.az",
            "Parallelism/Sync.az",
            "Parallelism/Channel.az",
            "Char/Char.az",
            "String/String.az",
            "String/StringExtra.az",
            "Functional/Functional.az",
            "Functional/FunctionalExtra.az",
            "Result/Result.az",
            "Random/Random.az",
            "Allocator/Allocator.az",
            "Ui/Ui.az",
            "Os/Os.az",
            "Gfx/Gfx.az",
        )

        data class AzSource(val baseDir: java.io.File, val relPath: String, val prefix: String)
        val azFiles = stdFiles.map { AzSource(stdDir, it, "STD") }

        fun uniqueName(prefix: String, relPath: String): String {
            val parts = relPath.replace("/", "_").replace(".az", "").uppercase()
            return "${prefix}_${parts}_SOURCE"
        }

        val entries = azFiles.map { (baseDir, relPath, prefix) ->
            val file = baseDir.resolve(relPath)
            val name = uniqueName(prefix, relPath)
            val content = file.readText()
            val escaped = content.replace("$", "\${'$'}")
            "    private val $name = \"\"\"\n$escaped\"\"\".trimIndent()"
        }

        val sourcesList = azFiles.map { (_, relPath, prefix) ->
            uniqueName(prefix, relPath)
        }

        val kt = buildString {
            appendLine("package org.azora.lang.stdlib")
            appendLine()
            appendLine("import org.azora.lang.frontend.Lexer")
            appendLine("import org.azora.lang.frontend.Parser")
            appendLine("import org.azora.lang.frontend.Program")
            appendLine()
            appendLine("object AzStdlib {")
            appendLine()
            for (entry in entries) {
                appendLine(entry)
                appendLine()
            }
            appendLine("    val sources = listOf(${sourcesList.joinToString(", ")})")
            appendLine()
            appendLine("    private val cachedPrograms: List<Program> by lazy { loadProgramsInternal() }")
            appendLine()
            appendLine("    fun loadPrograms(): List<Program> = cachedPrograms")
            appendLine()
            appendLine("    private fun loadProgramsInternal(): List<Program> {")
            appendLine("        return sources.mapNotNull { source ->")
            appendLine("            try {")
            appendLine("                val tokens = Lexer(source).tokenize()")
            appendLine("                Parser(tokens).parse()")
            appendLine("            } catch (_: Exception) { null }")
            appendLine("        }")
            appendLine("    }")
            appendLine("}")
            appendLine()
        }

        val outFile = outputDir.get().asFile
            .resolve("org/azora/lang/stdlib/AzStdlib.kt")
        outFile.parentFile.mkdirs()
        outFile.writeText(kt)
    }
}

val generateAzTests = tasks.register("generateAzTests") {
    val testDir = rootProject.file("Internal/Testing")
    val outputDir = layout.buildDirectory.dir("generated/aztests")

    inputs.dir(testDir)
    outputs.dir(outputDir)

    doLast {
        val azFiles = testDir.walkTopDown()
            .filter { it.isFile && it.extension == "az" }
            .sortedBy { it.name }
            .toList()

        if (azFiles.isEmpty()) {
            logger.warn("No .az test files found in Internal/Testing")
            return@doLast
        }

        val entries = azFiles.map { file ->
            val name = file.nameWithoutExtension.uppercase() + "_SOURCE"
            val content = file.readText()
            val escaped = content.replace("$", "\${'$'}")
            Triple(file.nameWithoutExtension, name, "    private val $name = \"\"\"\n$escaped\"\"\".trimIndent()")
        }

        val kt = buildString {
            appendLine("package org.azora.lang")
            appendLine()
            appendLine("import org.azora.lang.interpreter.AzInterpreter")
            appendLine("import org.azora.lang.lexer.Lexer")
            appendLine("import org.azora.lang.parser.Parser")
            appendLine("import org.azora.lang.preprocessor.AzPreprocessor")
            appendLine("import org.azora.lang.stdlib.AzStdlib")
            appendLine("import org.azora.lang.runtime.ConsoleOutputManager")
            appendLine("import kotlinx.coroutines.test.runTest")
            appendLine("import kotlin.test.Test")
            appendLine("import kotlin.test.assertFalse")
            appendLine("import kotlin.test.assertTrue")
            appendLine()
            appendLine("class InternalAzTestRunner {")
            appendLine()
            appendLine("    private suspend fun runAzSource(source: String): List<String> {")
            appendLine("        val ppResult = AzPreprocessor().process(source, AzStdlib.sources)")
            appendLine("        check(ppResult.errors.isEmpty()) {")
            appendLine("            \"Preprocessor errors: \${ppResult.errors.map { it.message }}\"")
            appendLine("        }")
            appendLine("        val tokens = Lexer(ppResult.generatedSource).tokenize()")
            appendLine("        val parser = Parser(tokens)")
            appendLine("        val program = parser.parse()")
            appendLine("        check(parser.errors.isEmpty()) {")
            appendLine("            \"Parser errors: \${parser.errors.map { it.message }}\"")
            appendLine("        }")
            appendLine("        val console = ConsoleOutputManager()")
            appendLine("        val interpreter = AzInterpreter(console)")
            appendLine("        interpreter.runTests(program)")
            appendLine("        return console.messages.value.map { it.text }")
            appendLine("    }")
            appendLine()
            for ((baseName, constName, constDecl) in entries) {
                appendLine(constDecl)
                appendLine()
                appendLine("    @Test")
                appendLine("    fun `run ${baseName}`() = runTest {")
                appendLine("        val output = runAzSource($constName)")
                appendLine("        output.forEach { println(it) }")
                appendLine("        assertTrue(output.any { it.startsWith(\"PASS\") || it.contains(\"passed\") }, \"No tests ran. Output: \$output\")")
                appendLine("        assertFalse(output.any { it.startsWith(\"FAIL\") }, \"Failures: \${output.filter { it.startsWith(\"FAIL\") }}\")")
                appendLine("    }")
                appendLine()
            }
            appendLine("}")
            appendLine()
        }

        val outFile = outputDir.get().asFile
            .resolve("org/azora/lang/InternalAzTestRunner.kt")
        outFile.parentFile.mkdirs()
        outFile.writeText(kt)
    }
}

val generateAzCodegenTests = tasks.register("generateAzCodegenTests") {
    val testDir = rootProject.file("Internal/Testing")
    val outputDir = layout.buildDirectory.dir("generated/azcodegentests")

    inputs.dir(testDir)
    outputs.dir(outputDir)

    doLast {
        val azFiles = testDir.walkTopDown()
            .filter { it.isFile && it.extension == "az" }
            .sortedBy { it.name }
            .toList()

        if (azFiles.isEmpty()) {
            logger.warn("No .az test files found in Internal/Testing")
            return@doLast
        }

        val entries = azFiles.map { file ->
            val name = file.nameWithoutExtension
            val constName = name.uppercase() + "_SOURCE"
            val content = file.readText()
            val escaped = content.replace("$", "\${'$'}")
            Triple(name, constName, "    private val $constName = \"\"\"\n$escaped\"\"\".trimIndent()")
        }

        data class Target(val className: String, val helperMethod: String)
        val targets = listOf(
            Target("InternalAzJSCodegenRunner", "runJSTest"),
            Target("InternalAzKotlinCodegenRunner", "runKotlinTest"),
            Target("InternalAzCSharpCodegenRunner", "runCSharpTest"),
            Target("InternalAzLlvmCodegenRunner", "runLlvmTest"),
            Target("InternalAzPythonCodegenRunner", "runPythonTest"),
            Target("InternalAzSwiftCodegenRunner", "runSwiftTest")
        )

        for (target in targets) {
            val kt = buildString {
                appendLine("package org.azora.lang.codegen")
                appendLine()
                appendLine("import kotlin.test.Test")
                appendLine()
                appendLine("class ${target.className} {")
                appendLine()
                for ((baseName, constName, constDecl) in entries) {
                    appendLine(constDecl)
                    appendLine()
                    appendLine("    @Test")
                    appendLine("    fun `${baseName}`() {")
                    appendLine("        AzCodegenTestHelper.${target.helperMethod}(\"$baseName\", $constName)")
                    appendLine("    }")
                    appendLine()
                }
                appendLine("}")
                appendLine()
            }

            val outFile = outputDir.get().asFile
                .resolve("org/azora/lang/codegen/${target.className}.kt")
            outFile.parentFile.mkdirs()
            outFile.writeText(kt)
        }
    }
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
    }
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generateStdlib.map { it.outputs.files.singleFile })
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            // NOTE: generateAzTests emits InternalAzTestRunner.kt referencing the legacy
            // interpreter/preprocessor/runtime API that does not exist in the new compiler.
            // Re-enable once those tests are rewritten against the new IrInterpreter.
            // kotlin.srcDir(generateAzTests.map { it.outputs.files.singleFile })
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val desktopTest by getting {
            // NOTE: generateAzCodegenTests emits runners calling a non-existent
            // AzCodegenTestHelper. Re-enable once the codegen test helper is restored.
            // kotlin.srcDir(generateAzCodegenTests.map { it.outputs.files.singleFile })
        }
        val jvmCommonMain by getting {
            dependencies {
                implementation(libs.bytedeco.llvm)
                implementation(libs.bytedeco.llvm.platform)
            }
        }
    }
}
