package org.azora.lang.backend

import org.azora.lang.ir.IrTopLevel
import org.azora.lang.ir.IrType

/** Scalar C ABI; heap aggregates and erased values need explicit C adapters. */
internal object NativeAbi {
    fun checkSignature(extern: IrTopLevel.Extern) {
        extern.params.forEach { (name, type) -> checkType(type, "parameter '$name' of '${extern.name}'") }
        checkType(extern.returnType, "return of '${extern.name}'", result = true)
    }

    fun checkCallback(type: IrType.Function) {
        check(!type.variadic && type.receivers.isEmpty()) {
            "LLVM C callbacks require a fixed signature without contextual receivers"
        }
        type.params.forEach { checkType(it, "C callback parameter", callback = true) }
        checkType(type.ret, "C callback return", result = true, callback = true)
    }

    private fun checkType(type: IrType, position: String, result: Boolean = false, callback: Boolean = false) {
        val supported = when (type) {
            is IrType.Integer -> type.bits in setOf(8, 16, 32, 64)
            IrType.Bool, IrType.Char, IrType.ISize, IrType.USize,
            IrType.Float, IrType.Double, IrType.String, is IrType.Pointer -> true
            IrType.Unit -> result
            is IrType.Function -> if (!result && !callback) { checkCallback(type); true } else false
            else -> false
        }
        check(supported) {
            "LLVM native ABI does not support $type in $position; use scalar values or an explicit pointer and a C layout adapter"
        }
    }

    // These ABI attributes must match on declarations, definitions and calls.
    // https://llvm.org/docs/LangRef.html#parameter-attributes
    fun extension(type: IrType): String = when {
        type is IrType.Integer && type.bits < 32 -> if (type.signed) "signext " else "zeroext "
        type == IrType.Bool || type == IrType.Char -> "zeroext "
        else -> ""
    }
}
