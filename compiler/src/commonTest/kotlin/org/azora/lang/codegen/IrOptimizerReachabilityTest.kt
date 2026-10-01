/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.ir.IrExpr
import org.azora.lang.ir.IrFunction
import org.azora.lang.ir.IrOptimizer
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.IrStmt
import org.azora.lang.ir.IrTopLevel
import org.azora.lang.ir.IrType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Roots other than `main` follow the same global/function dependency graph. */
class IrOptimizerReachabilityTest {
    private fun function(name: String, value: IrExpr) = IrTopLevel.Func(
        IrFunction(name, emptyList(), IrType.Int, listOf(IrStmt.Return(value))),
    )

    private val dependencies = listOf(
        IrTopLevel.Global(IrStmt.FinDecl("base", IrType.Int, IrExpr.Call("seed", emptyList(), IrType.Int))),
        function("seed", IrExpr.IntLiteral(42)),
        function("build", IrExpr.Var("base", IrType.Int)),
        function("unused", IrExpr.IntLiteral(0)),
    )

    @Test fun exportedGlobalsRetainTheirInitializerDependenciesWithoutMain() {
        val exported = IrTopLevel.Global(
            IrStmt.FinDecl("exported", IrType.Int, IrExpr.Call("build", emptyList(), IrType.Int)),
            exportName = "exported",
        )
        val optimized = IrOptimizer().optimize(IrProgram(null, dependencies + exported))
        assertEquals(setOf("seed", "build"), optimized.functions.map { it.name }.toSet())
        assertEquals(setOf("base", "exported"), optimized.globals.map { (it as IrStmt.FinDecl).name }.toSet())
        assertEquals("exported", optimized.items.filterIsInstance<IrTopLevel.Global>().last().exportName)
    }

    @Test fun testsRetainTheGlobalsAndFunctionsTheyReachWithoutMain() {
        val test = IrTopLevel.Test("global dependencies", listOf(IrStmt.ExprStmt(IrExpr.Var("base", IrType.Int))))
        val optimized = IrOptimizer().optimize(IrProgram(null, dependencies + test))
        assertEquals(listOf("seed"), optimized.functions.map { it.name })
        assertEquals(listOf("base"), optimized.globals.map { (it as IrStmt.FinDecl).name })
        assertEquals(listOf(test), optimized.items.filterIsInstance<IrTopLevel.Test>())
    }
}
