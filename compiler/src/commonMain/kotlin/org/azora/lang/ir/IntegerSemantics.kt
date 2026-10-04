package org.azora.lang.ir

/** Fixed-width integer arithmetic wraps modulo 2^width on every execution path. */
fun IrType.wrapInteger(value: Long): Long {
    val integer = this as? IrType.Integer ?: return value
    if (integer.bits >= 64) return value
    val mask = (1L shl integer.bits) - 1
    val bits = value and mask
    return if (integer.signed && bits and (1L shl (integer.bits - 1)) != 0L) bits or mask.inv() else bits
}
