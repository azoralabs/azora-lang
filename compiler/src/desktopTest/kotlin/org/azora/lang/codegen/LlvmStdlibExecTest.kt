package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Executes injected stdlib functions natively via `lli` — also the end-to-end
 * proof of the LLVM if-expression lowering (floor/ceil/round are built on it).
 * Generic stdlib functions (erased to Any) are interpreter/JS-only for now.
 */
class LlvmStdlibExecTest {

    private fun check(expected: String, source: String) {
        if (!LlvmExec.available) return
        assertEquals(expected, LlvmExec.run(source))
    }

    @Test fun floorCeilRoundNative() = check(
        "3\n4\n4",
        "use std.math\nfunc main() {\n    println(floor(3.7))\n    println(ceil(3.2))\n    println(round(3.6))\n}"
    )

    @Test fun ifExpressionNative() = check(
        "small\n99",
        "func pick(x: Int): Int = if x > 10 { x } else { 99 }\nfunc main() {\n    println(if 3 > 10 { \"big\" } else { \"small\" })\n    println(pick(5))\n}"
    )
}
