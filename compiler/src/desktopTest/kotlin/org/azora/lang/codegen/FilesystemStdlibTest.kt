package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `std.filesystem` (VERSION_0_1_ROADMAP §4.4, DIPs/FILESYSTEM_DIP.MD).
 *
 * The path algebra is pure and could be tested anywhere; the rest needs a disk,
 * which is why this is a desktop test. Web and iOS implement the same surface by
 * raising `FileError.Unsupported`.
 */
class FilesystemStdlibTest {
    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source.trimIndent(), release = false)
        return assertIs(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
    }

    private fun run(source: String): String =
        IrInterpreter().interpret(compile(source).ir).trim()

    // ── Path algebra: no disk involved ──────────────────────────────────

    @Test
    fun joiningInsertsExactlyOneSeparator() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                println(path("/usr/local").join("bin").join("azora").text)
                println(path("/usr/").join("bin").text)
                println(path("").join("bin").text)
                println(path("/usr").join("").text)
            }
            """,
        )

        assertEquals("/usr/local/bin/azora\n/usr/bin\nbin\n/usr", output)
    }

    /** An absolute segment replaces rather than appends - as every path library does. */
    @Test
    fun anAbsoluteSegmentReplacesThePath() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                println(path("/usr/local").join("/etc").text)
            }
            """,
        )

        assertEquals("/etc", output)
    }

    @Test
    fun theSegmentsOfAPathAreReadable() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                fin file = path("a/b/c.az")
                println(file.fileName)
                println(file.stem)
                println(file.extension)
                println(file.parent.text)
                println(path("/x").parent.text)
                println(path("bare").parent.text)
            }
            """,
        )

        assertEquals("c.az\nc\naz\na/b\n/\n.", output)
    }

    /** A leading dot is a hidden file, not an extension. */
    @Test
    fun aDotFileHasNoExtension() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                println(path(".gitignore").stem)
                println("[${'$'}{path(".gitignore").extension}]")
            }
            """,
        )

        assertEquals(".gitignore\n[]", output)
    }

    @Test
    fun normalizationResolvesDotsTextually() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                println(path("a/./b/../c").normalized.text)
                println(path("/a/b/../../../c").normalized.text)
                println(path("../x").normalized.text)
                println(path("a/b/..").normalized.text)
            }
            """,
        )

        // `..` cannot climb above a root, but a relative path keeps it - `../x`
        // names something real.
        assertEquals("a/c\n/c\n../x\na", output)
    }

    @Test
    fun anExtensionCanBeReplacedOrAdded() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                println(path("a/b/c.az").withExtension("ll").text)
                println(path("c.az").withExtension("ll").text)
                println(path("plain").withExtension("az").text)
            }
            """,
        )

        assertEquals("a/b/c.ll\nc.ll\nplain.az", output)
    }

    @Test
    fun absolutenessIsRecognisedOnBothPlatforms() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                println(path("/x/y").isAbsolute)
                println(path("x/y").isAbsolute)
                println(path("C:/x").isAbsolute)
                println(path("").isAbsolute)
            }
            """,
        )

        assertEquals("true\nfalse\ntrue\nfalse", output)
    }

    // ── The disk ────────────────────────────────────────────────────────

    @Test
    fun textRoundTripsThroughAFile() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                fin dir = createTemporaryDirectory("azfs") catch path("")
                fin file = dir.join("hello.txt")

                writeText(file, "one\ntwo\n") catch { println("write failed") }
                println(exists(file))
                println(isFile(file))
                println(isDirectory(dir))
                println(trim(readText(file) catch "READ FAILED"))

                appendText(file, "three\n") catch { println("append failed") }
                println(trim(readText(file) catch "READ FAILED"))

                removeAll(dir) catch { }
                println(exists(dir))
            }
            """,
        )

        assertEquals("true\ntrue\ntrue\none\ntwo\none\ntwo\nthree\nfalse", output)
    }

    /**
     * The whole reason this module exists: a missing file and an empty file are
     * different answers. `engine.io` gives the same empty `Blob` for both.
     */
    @Test
    fun aMissingFileIsNotFoundRatherThanEmpty() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                fin dir = createTemporaryDirectory("azfs") catch path("")
                // Bracketed so an empty read is visible rather than blank.
                fin missing = readText(dir.join("absent.txt")) catch "MISSING"
                println("[${'$'}{missing}]")
                writeText(dir.join("empty.txt"), "") catch { }
                fin empty = readText(dir.join("empty.txt")) catch "MISSING"
                println("[${'$'}{empty}]")
                removeAll(dir) catch { }
            }
            """,
        )

        // The point: one is a failure, the other is a successful empty read.
        assertEquals("[MISSING]\n[]", output)
    }

    @Test
    fun directoryListingsAreFullPathsAndSorted() {
        val output = run(
            """
            import std.filesystem
            import std.container.array
            import std.io

            func main() {
                fin dir = createTemporaryDirectory("azfs") catch path("")
                writeText(dir.join("b.txt"), "b") catch { }
                writeText(dir.join("a.txt"), "a") catch { }
                createDirectory(dir.join("sub")) catch { }

                fin empty: Array<Path> = Array::fill<Path>(0)
                fin entries: Array<Path> = listDirectory(dir) catch empty
                println(entries.size)
                var names = ""
                for i in 0..<entries.size {
                    names = names + entries[i].fileName + " "
                }
                println(trim(names))
                // Full paths, not bare names: each entry sits under the directory.
                println(startsWith(entries[0].text, dir.text))

                removeAll(dir) catch { }
            }
            """,
        )

        assertEquals("3\na.txt b.txt sub\ntrue", output)
    }

    @Test
    fun metadataReportsKindAndSize() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                fin dir = createTemporaryDirectory("azfs") catch path("")
                fin file = dir.join("data.bin")
                writeText(file, "12345") catch { }

                fin info = fileInfo(file) catch FileInfo(FileKind.Other, 0L, Instant(0L, 0))
                println(info.size)
                println(info.isFile)
                println(info.isDirectory)

                fin about = fileInfo(dir) catch FileInfo(FileKind.Other, 0L, Instant(0L, 0))
                println(about.isDirectory)

                removeAll(dir) catch { }
            }
            """,
        )

        assertEquals("5\ntrue\nfalse\ntrue", output)
    }

    @Test
    fun copyAndRenameMoveContent() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                fin dir = createTemporaryDirectory("azfs") catch path("")
                fin first = dir.join("first.txt")
                fin copied = dir.join("copied.txt")
                fin moved = dir.join("moved.txt")

                writeText(first, "content") catch { }
                copyFile(first, copied) catch { println("copy failed") }
                println(readText(copied) catch "?")
                println(exists(first))

                rename(first, moved) catch { println("rename failed") }
                println(exists(first))
                println(readText(moved) catch "?")

                removeAll(dir) catch { }
            }
            """,
        )

        assertEquals("content\ntrue\nfalse\ncontent", output)
    }

    /** `remove` refuses a non-empty directory; `removeAll` is the one that clears it. */
    @Test
    fun removeRefusesANonEmptyDirectory() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                fin dir = createTemporaryDirectory("azfs") catch path("")
                writeText(dir.join("x.txt"), "x") catch { }

                // A refused remove leaves the directory where it was.
                remove(dir) catch { }
                println(exists(dir))

                removeAll(dir) catch { }
                println(exists(dir))
            }
            """,
        )

        assertEquals("true\nfalse", output)
    }

    @Test
    fun nestedDirectoriesAreCreatedInOneCall() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                fin dir = createTemporaryDirectory("azfs") catch path("")
                fin deep = dir.join("a").join("b").join("c")

                // One level cannot create three, so nothing appears.
                createDirectory(deep) catch { }
                println(isDirectory(deep))

                createDirectories(deep) catch { println("createDirectories failed") }
                println(isDirectory(deep))

                removeAll(dir) catch { }
            }
            """,
        )

        // One level cannot create three; createDirectories can.
        assertEquals("false\ntrue", output)
    }

    @Test
    fun eachTemporaryDirectoryIsDistinct() {
        val output = run(
            """
            import std.filesystem
            import std.io

            func main() {
                fin first = createTemporaryDirectory("azfs") catch path("")
                fin second = createTemporaryDirectory("azfs") catch path("")
                println(first.text != second.text)
                println(exists(first) && exists(second))
                removeAll(first) catch { }
                removeAll(second) catch { }
            }
            """,
        )

        assertEquals("true\ntrue", output)
    }
}
