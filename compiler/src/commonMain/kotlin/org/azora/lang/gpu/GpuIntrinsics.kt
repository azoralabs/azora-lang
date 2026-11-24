/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.gpu

/** What a built-in function answers, given the type T its arguments agree on. */
internal enum class GpuResultShape {
    /** T itself. */
    SAME,
    /** T's scalar (length, dot, distance). */
    SCALAR,
    /** A Bool vector as wide as T (isNan, isInf). */
    BOOL,
}

/**
 * A component-wise built-in: [arity] arguments of one numeric type T (a scalar argument is spread
 * over T's width), Float components only unless [integers]. Spellings per target; "" is handled
 * specially by [GpuBody].
 */
internal data class GpuIntrinsic(
    val name: String,
    val arity: Int,
    val result: GpuResultShape = GpuResultShape.SAME,
    val integers: Boolean = false,
    val metal: String = name,
    val glsl: String = name,
    val hlsl: String = name,
)

internal val gpuIntrinsics: Map<String, GpuIntrinsic> = listOf(
    GpuIntrinsic("abs", 1, integers = true),
    GpuIntrinsic("sign", 1),
    GpuIntrinsic("floor", 1),
    GpuIntrinsic("ceil", 1),
    GpuIntrinsic("fract", 1, hlsl = "frac"),
    GpuIntrinsic("round", 1),
    GpuIntrinsic("trunc", 1),
    GpuIntrinsic("sqrt", 1),
    GpuIntrinsic("rsqrt", 1, glsl = "inversesqrt"),
    GpuIntrinsic("exp", 1),
    GpuIntrinsic("exp2", 1),
    GpuIntrinsic("log", 1),
    GpuIntrinsic("log2", 1),
    GpuIntrinsic("sin", 1),
    GpuIntrinsic("cos", 1),
    GpuIntrinsic("tan", 1),
    GpuIntrinsic("asin", 1),
    GpuIntrinsic("acos", 1),
    GpuIntrinsic("atan", 1),
    GpuIntrinsic("sinh", 1),
    GpuIntrinsic("cosh", 1),
    GpuIntrinsic("tanh", 1),
    GpuIntrinsic("saturate", 1, glsl = ""),
    GpuIntrinsic("normalize", 1),
    GpuIntrinsic("length", 1, GpuResultShape.SCALAR),
    GpuIntrinsic("dpdx", 1, metal = "dfdx", glsl = "dFdx", hlsl = "ddx"),
    GpuIntrinsic("dpdy", 1, metal = "dfdy", glsl = "dFdy", hlsl = "ddy"),
    GpuIntrinsic("fwidth", 1),
    GpuIntrinsic("isNan", 1, GpuResultShape.BOOL, metal = "isnan", glsl = "isnan", hlsl = "isnan"),
    GpuIntrinsic("isInf", 1, GpuResultShape.BOOL, metal = "isinf", glsl = "isinf", hlsl = "isinf"),
    GpuIntrinsic("pow", 2),
    GpuIntrinsic("atan2", 2, glsl = "atan"),
    GpuIntrinsic("min", 2, integers = true),
    GpuIntrinsic("max", 2, integers = true),
    GpuIntrinsic("step", 2),
    GpuIntrinsic("fmod", 2, glsl = ""),
    GpuIntrinsic("distance", 2, GpuResultShape.SCALAR),
    GpuIntrinsic("dot", 2, GpuResultShape.SCALAR),
    GpuIntrinsic("reflect", 2),
    GpuIntrinsic("clamp", 3, integers = true),
    GpuIntrinsic("mix", 3, hlsl = "lerp"),
    GpuIntrinsic("smoothstep", 3),
    GpuIntrinsic("fma", 3, hlsl = "mad"),
).associateBy { it.name }

/** Built-ins with their own typing rules (vectors of Bool, matrices, bits, atomics, statements). */
internal val gpuSpecialIntrinsics = setOf(
    "cross", "refract", "select", "any", "all", "transpose", "determinant",
    "asFloat", "asInt", "asUInt", "countBits", "reverseBits",
    "discardFragment", "workgroupBarrier", "storageBarrier",
    "atomicAdd", "atomicMin", "atomicMax", "atomicAnd", "atomicOr", "atomicXor", "atomicExchange",
    "atomicLoad", "atomicStore",
)

internal val gpuIntrinsicNames: Set<String> = gpuIntrinsics.keys + gpuSpecialIntrinsics

/** Texture methods: `texture.sample(sampler, uv)` and the rest. */
internal val gpuTextureMethods = setOf(
    "sample", "sampleLevel", "sampleBias", "sampleGrad", "sampleCompare", "gather", "read", "write",
    "size", "width", "height", "depth",
)
