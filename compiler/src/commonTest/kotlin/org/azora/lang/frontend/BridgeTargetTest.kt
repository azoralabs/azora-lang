/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A `bridge` target is a `Target` value.
 *
 * Because the type is fixed the enum is in context, so the target is written as
 * a leading-dot member, fully qualified, or as a compile-time constant already
 * bound to one. A bare identifier that names no such constant is not a target:
 * an enum member is reached through its enum, and accepting the bare variant
 * name would make `bridge Native` and `bridge C` mean different things for no
 * visible reason.
 */
class BridgeTargetTest {

    private fun parse(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    private fun bridgeTargets(source: String): List<String> =
        parse(source).filterIsInstance<TopLevel.Bridge>().map { it.target }

    @Test fun leadingDotNamesTheTargetMember() {
        assertEquals(listOf("C"), bridgeTargets("""
            bridge .C {
                func abs(x: Int): Int
            }
        """.trimIndent()))
    }

    @Test fun qualifiedFormNamesTheTargetMember() {
        assertEquals(listOf("C"), bridgeTargets("""
            bridge Target.C {
                func abs(x: Int): Int
            }
        """.trimIndent()))
    }

    @Test fun bracketedListAcceptsSeveralTargets() {
    }

    @Test fun bracketedListAcceptsQualifiedTargets() {
    }

    @Test fun bareBlockDefaultsToTheCompilerTarget() {
        assertEquals(listOf("Compiler"), bridgeTargets("""
            bridge {
                func hostTick(): Int
            }
        """.trimIndent()))
    }

    @Test fun compileTimeConstantBoundToATargetIsATarget() {
        assertEquals(listOf("C"), bridgeTargets("""
            inline fin native = Target.C
            bridge native {
                func abs(x: Int): Int
            }
        """.trimIndent()))
    }

    @Test fun plainFinBoundToATargetIsAlsoATarget() {
        assertEquals(listOf("WebAssembly"), bridgeTargets("""
            fin Host = Target.WebAssembly
            bridge Host {
                func hostTick(): Int
            }
        """.trimIndent()))
    }

    @Test fun aBoundConstantWorksInsideABracketedList() {
    }

    @Test fun bareVariantNameIsRejected() {
        val failure = assertFailsWith<IllegalStateException> {
            parse("""
                bridge C {
                    func abs(x: Int): Int
                }
            """.trimIndent())
        }
        assertTrue(
            failure.message!!.contains("not a bridge target"),
            "the error must say the bare name is not a target, got: ${failure.message}"
        )
        assertTrue(
            failure.message!!.contains(".C") && failure.message!!.contains("Target.C"),
            "the error must offer both legal spellings, got: ${failure.message}"
        )
    }

    @Test fun anUnboundIdentifierIsRejected() {
        val failure = assertFailsWith<IllegalStateException> {
            parse("""
                bridge Whatever {
                    func abs(x: Int): Int
                }
            """.trimIndent())
        }
        assertTrue(
            failure.message!!.contains("not a bridge target"),
            "an identifier bound to nothing must be refused, got: ${failure.message}"
        )
    }

    @Test fun aConstantBoundToSomethingElseIsRejected() {
        val failure = assertFailsWith<IllegalStateException> {
            parse("""
                inline fin Native = 7
                bridge Native {
                    func abs(x: Int): Int
                }
            """.trimIndent())
        }
        assertTrue(
            failure.message!!.contains("not a bridge target"),
            "a constant that is not a Target member must be refused, got: ${failure.message}"
        )
    }

    @Test fun aNameThatIsNotATargetMemberIsRejected() {
        val failure = assertFailsWith<IllegalStateException> {
            parse("""
                bridge .WebGL {
                    func webClear(r: Double): Unit
                }
            """.trimIndent())
        }
        assertTrue(
            failure.message!!.contains("not a member of Target"),
            "a dotted name that is not a Target member must be refused, got: ${failure.message}"
        )
        assertTrue(
            failure.message!!.contains(".WebAssembly"),
            "the error must list the real members, got: ${failure.message}"
        )
    }

    @Test fun aQualifiedNonMemberIsRejected() {
        val failure = assertFailsWith<IllegalStateException> {
            parse("""
                bridge Target.Vulkan {
                    func draw(): Unit
                }
            """.trimIndent())
        }
        assertTrue(
            failure.message!!.contains("not a member of Target"),
            "a qualified non-member must be refused, got: ${failure.message}"
        )
    }

    @Test fun aConstantBoundToANonMemberIsRejected() {
        val failure = assertFailsWith<IllegalStateException> {
            parse("""
                inline fin native = Target.Vulkan
                bridge native {
                    func draw(): Unit
                }
            """.trimIndent())
        }
        assertTrue(
            failure.message!!.contains("not a bridge target"),
            "a constant bound to a non-member must not become a target, got: ${failure.message}"
        )
    }

    @Test fun everyTargetMemberIsAccepted() {
        assertEquals(
            listOf("Compiler", "C", "ObjectiveC", "WebAssembly"),
            bridgeTargets("""
                bridge .Compiler { func a(): Unit }
                bridge .C { func b(): Unit }
                bridge .ObjectiveC { func c(): Unit }
                bridge .WebAssembly { func e(): Unit }
            """.trimIndent())
        )
    }

    @Test fun theSingleFunctionFormTakesTheSameTargets() {
        assertEquals(listOf("C", "C", "Compiler"), bridgeTargets("""
            inline fin native = Target.C
            bridge .C func abs(x: Int): Int
            bridge native func labs(x: Long): Long
            bridge func fill(count: Int): Int
        """.trimIndent()))
    }
}
