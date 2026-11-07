/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.packages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AZON, `azora.azon` and `azora.lock` as PACKAGES_DIP §1, §2 and §4 describe them. */
class ManifestTest {
    private fun manifest(text: String) = Manifest.parse("/p", "/p/azora.azon", text)

    @Test
    fun azonReadsAnImplicitDocumentWithComments() {
        val doc = Azon.parse(
            """
            // a comment
            name: "demo"   // trailing comment
            count: 3
            ratio: -1.5e2
            on: true
            off: false
            none: null
            list: [ "a" "b", 3 ]
            nested: { key-with-dash: "x" }
            /* block
               comment */
            """.trimIndent(),
        )
        assertEquals("demo", (doc["name"] as AzonValue.Str).value)
        assertEquals("3", (doc["count"] as AzonValue.Num).raw)
        assertEquals("-1.5e2", (doc["ratio"] as AzonValue.Num).raw)
        assertEquals(true, (doc["on"] as AzonValue.Bool).value)
        assertEquals(false, (doc["off"] as AzonValue.Bool).value)
        assertIs<AzonValue.Null>(doc["none"])
        assertEquals(3, (doc["list"] as AzonValue.Arr).elements.size)
        assertEquals("x", ((doc["nested"] as AzonValue.Obj)["key-with-dash"] as AzonValue.Str).value)
    }

    @Test
    fun azonAcceptsJson() {
        val doc = Azon.parse("""{ "project": { "name": "j", "version": "1.0.0" }, "dependencies": {} }""")
        assertEquals("j", ((doc["project"] as AzonValue.Obj)["name"] as AzonValue.Str).value)
    }

    @Test
    fun azonErrorsNameTheLine() {
        val error = assertFailsWith<AzonError> { Azon.parse("a: 1\nb: {\n  c: \n") }
        assertTrue(error.message!!.startsWith("line "), error.message)
    }

    @Test
    fun azonQuotesWhatItCannotLeaveBare() {
        assertEquals("\"a\\\"b\"", Azon.quote("a\"b"))
        assertEquals("plain-key", Azon.key("plain-key"))
        assertEquals("\"has space\"", Azon.key("has space"))
    }

    @Test
    fun aProjectTakesItsDefaults() {
        val m = manifest("project: { name: \"game\" }")
        val unit = m.unit!!
        assertEquals("game", unit.name)
        assertEquals("0.0.0", unit.version)
        assertEquals("main.az", unit.entry)
        assertEquals("src", unit.src)
        assertNull(m.pkg)
        assertTrue(m.dependencies.isEmpty())
    }

    @Test
    fun aPackageNamesItsModuleAndWhatItLinks() {
        val m = manifest(
            """
            package: { name: "gfx" version: "0.2.0" module: "lib.gfx" }
            native: { frameworks: [ "Metal" ] libs: [ "objc" ] sources: [ "shim.c" ] }
            """.trimIndent(),
        )
        assertEquals("lib.gfx", m.pkg!!.module)
        assertEquals(Manifest.Native(listOf("Metal"), listOf("objc"), listOf("shim.c")), m.native)
    }

    @Test
    fun aWorkspacesNamelessPackageIsMetadata() {
        val m = manifest(
            """
            workspace: { name: "engine" }
            members: [ "packages/a" "packages/b" ]
            package: { authors: [ "Azora" ] }
            """.trimIndent(),
        )
        assertTrue(m.isWorkspace)
        assertNull(m.unit)
        assertEquals(listOf("packages/a", "packages/b"), m.members)
    }

    @Test
    fun dependenciesNeedOneSourceAndOneReference() {
        assertFailsWith<PackageError> { manifest("project: { name: \"p\" }\ndependencies: { a: { } }") }
        assertFailsWith<PackageError> { manifest("project: { name: \"p\" }\ndependencies: { a: { path: \"x\" git: \"y\" } }") }
        assertFailsWith<PackageError> { manifest("project: { name: \"p\" }\ndependencies: { a: { git: \"y\" tag: \"v1\" branch: \"main\" } }") }
        assertFailsWith<PackageError> { manifest("project: { name: \"p\" }\npackage: { name: \"q\" }") }
        val git = manifest("project: { name: \"p\" }\ndependencies: { a: { git: \"https://example.org/a.git\" tag: \"v1\" } }").dependencies.single()
        assertEquals("tag:v1", git.reference)
        assertEquals("default", Manifest.Dependency("b", git = "u").reference)
        assertEquals("rev:abc", Manifest.Dependency("b", git = "u", rev = "abc").reference)
        assertEquals("branch:dev", Manifest.Dependency("b", git = "u", branch = "dev").reference)
    }

    @Test
    fun addingADependencyKeepsTheRestOfTheFile() {
        val text = """
            // my game
            project: { name: "game" }
            dependencies: {
                // drawing
                gfx: { path: "../gfx" }
            }
        """.trimIndent() + "\n"
        val added = manifest(text).withDependency(Manifest.Dependency("audio", git = "https://example.org/audio.git", tag = "v2"))
        assertEquals(
            """
            // my game
            project: { name: "game" }
            dependencies: {
                // drawing
                gfx: { path: "../gfx" }
                audio: { git: "https://example.org/audio.git" tag: "v2" }
            }
            """.trimIndent() + "\n",
            added,
        )
        val removed = manifest(added).withoutDependency("gfx")
        assertEquals(
            """
            // my game
            project: { name: "game" }
            dependencies: {
                // drawing
                audio: { git: "https://example.org/audio.git" tag: "v2" }
            }
            """.trimIndent() + "\n",
            removed,
        )
        assertEquals(removed, manifest(removed).withoutDependency("missing"))
    }

    @Test
    fun addingReplacesADependencyOfTheSameName() {
        val text = "project: { name: \"game\" }\ndependencies: {\n    gfx: { path: \"../gfx\" }\n}\n"
        val replaced = manifest(text).withDependency(Manifest.Dependency("gfx", path = "../gfx2"))
        val deps = manifest(replaced).dependencies
        assertEquals(listOf(Manifest.Dependency("gfx", path = "../gfx2")), deps)
    }

    @Test
    fun addingCreatesTheDependenciesBlock() {
        val added = manifest("project: { name: \"game\" }\n").withDependency(Manifest.Dependency("gfx", path = "../gfx"))
        assertEquals("project: { name: \"game\" }\n\ndependencies: {\n    gfx: { path: \"../gfx\" }\n}\n", added)
        val json = manifest("{ \"project\": { \"name\": \"game\" } }").withDependency(Manifest.Dependency("gfx", path = "../gfx"))
        assertEquals(listOf("gfx"), manifest(json).dependencies.map { it.name })
    }

    @Test
    fun theLockfileReadsBackWhatItWrites() {
        val lock = Lockfile(
            listOf(
                Lockfile.Locked("b", "1.0.0", "git", "", url = "https://example.org/b.git", reference = "tag:v1", rev = "0123456789abcdef0123456789abcdef01234567"),
                Lockfile.Locked("a", "0.1.0", "path", "../a"),
            ),
        )
        val text = lock.render()
        assertTrue(text.contains("version: 1"))
        // Sorted by name, so the file does not churn with resolution order.
        assertTrue(text.indexOf("name: \"a\"") < text.indexOf("name: \"b\""))
        assertEquals(lock.packages.sortedBy { it.name }, Lockfile.parse("azora.lock", text).packages)
        assertFailsWith<PackageError> { Lockfile.parse("azora.lock", "version: 2\npackages: []") }
    }

    @Test
    fun pathsAreRelativeBothWays() {
        assertEquals("../b/c", Paths.relative("/x/a", "/x/b/c"))
        assertEquals(".", Paths.relative("/x/a", "/x/a"))
        assertEquals("a/b", Paths.relative("/", "/a/b"))
        assertEquals("/x/y", Paths.join("/x/", "y"))
        assertEquals("/abs", Paths.join("/x", "/abs"))
        assertEquals("/x", Paths.parent("/x/y"))
        assertEquals("/", Paths.parent("/x"))
    }
}
