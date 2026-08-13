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
                std::println(std::path("/usr/local").join("bin").join("azora").text)
                std::println(std::path("/usr/").join("bin").text)
                std::println(std::path("").join("bin").text)
                std::println(std::path("/usr").join("").text)
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
                std::println(std::path("/usr/local").join("/etc").text)
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
                fin file = std::path("a/b/c.az")
                std::println(file.fileName)
                std::println(file.stem)
                std::println(file.extension)
                std::println(file.parent.text)
                std::println(std::path("/x").parent.text)
                std::println(std::path("bare").parent.text)
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
                std::println(std::path(".gitignore").stem)
                std::println("[${'$'}{std::path(".gitignore").extension}]")
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
                std::println(std::path("a/./b/../c").normalized.text)
                std::println(std::path("/a/b/../../../c").normalized.text)
                std::println(std::path("../x").normalized.text)
                std::println(std::path("a/b/..").normalized.text)
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
                std::println(std::path("a/b/c.az").withExtension("ll").text)
                std::println(std::path("c.az").withExtension("ll").text)
                std::println(std::path("plain").withExtension("az").text)
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
                std::println(std::path("/x/y").isAbsolute)
                std::println(std::path("x/y").isAbsolute)
                std::println(std::path("C:/x").isAbsolute)
                std::println(std::path("").isAbsolute)
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
                fin dir = std::createTemporaryDirectory("azfs") catch std::path("")
                fin file = dir.join("hello.txt")

                std::writeText(file, "one\ntwo\n") catch { std::println("write failed") }
                std::println(std::exists(file))
                std::println(std::isFile(file))
                std::println(std::isDirectory(dir))
                std::println(std::trim(std::readText(file) catch "READ FAILED"))

                std::appendText(file, "three\n") catch { std::println("append failed") }
                std::println(std::trim(std::readText(file) catch "READ FAILED"))

                std::removeAll(dir) catch { }
                std::println(std::exists(dir))
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
                fin dir = std::createTemporaryDirectory("azfs") catch std::path("")
                // Bracketed so an empty read is visible rather than blank.
                fin missing = std::readText(dir.join("absent.txt")) catch "MISSING"
                std::println("[${'$'}{missing}]")
                std::writeText(dir.join("empty.txt"), "") catch { }
                fin empty = std::readText(dir.join("empty.txt")) catch "MISSING"
                std::println("[${'$'}{empty}]")
                std::removeAll(dir) catch { }
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
                fin dir = std::createTemporaryDirectory("azfs") catch std::path("")
                std::writeText(dir.join("b.txt"), "b") catch { }
                std::writeText(dir.join("a.txt"), "a") catch { }
                std::createDirectory(dir.join("sub")) catch { }

                fin empty: std::Array<std::Path> = std::Array::fill<std::Path>(0)
                fin entries: std::Array<std::Path> = std::listDirectory(dir) catch empty
                std::println(entries.size)
                var names = ""
                for i in 0..<entries.size {
                    names = names + entries[i].fileName + " "
                }
                std::println(std::trim(names))
                // Full paths, not bare names: each entry sits under the directory.
                std::println(std::startsWith(entries[0].text, dir.text))

                std::removeAll(dir) catch { }
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
                fin dir = std::createTemporaryDirectory("azfs") catch std::path("")
                fin file = dir.join("data.bin")
                std::writeText(file, "12345") catch { }

                fin info = std::fileInfo(file) catch std::FileInfo(std::FileKind.Other, 0L, std::Instant(0L, 0))
                std::println(info.size)
                std::println(info.isFile)
                std::println(info.isDirectory)

                fin about = std::fileInfo(dir) catch std::FileInfo(std::FileKind.Other, 0L, std::Instant(0L, 0))
                std::println(about.isDirectory)

                std::removeAll(dir) catch { }
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
                fin dir = std::createTemporaryDirectory("azfs") catch std::path("")
                fin first = dir.join("first.txt")
                fin copied = dir.join("copied.txt")
                fin moved = dir.join("moved.txt")

                std::writeText(first, "content") catch { }
                std::copyFile(first, copied) catch { std::println("copy failed") }
                std::println(std::readText(copied) catch "?")
                std::println(std::exists(first))

                std::rename(first, moved) catch { std::println("rename failed") }
                std::println(std::exists(first))
                std::println(std::readText(moved) catch "?")

                std::removeAll(dir) catch { }
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
                fin dir = std::createTemporaryDirectory("azfs") catch std::path("")
                std::writeText(dir.join("x.txt"), "x") catch { }

                // A refused remove leaves the directory where it was.
                std::remove(dir) catch { }
                std::println(std::exists(dir))

                std::removeAll(dir) catch { }
                std::println(std::exists(dir))
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
                fin dir = std::createTemporaryDirectory("azfs") catch std::path("")
                fin deep = dir.join("a").join("b").join("c")

                // One level cannot create three, so nothing appears.
                std::createDirectory(deep) catch { }
                std::println(std::isDirectory(deep))

                std::createDirectories(deep) catch { std::println("createDirectories failed") }
                std::println(std::isDirectory(deep))

                std::removeAll(dir) catch { }
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
                fin first = std::createTemporaryDirectory("azfs") catch std::path("")
                fin second = std::createTemporaryDirectory("azfs") catch std::path("")
                std::println(first.text != second.text)
                std::println(std::exists(first) && std::exists(second))
                std::removeAll(first) catch { }
                std::removeAll(second) catch { }
            }
            """,
        )

        assertEquals("true\ntrue", output)
    }
}
