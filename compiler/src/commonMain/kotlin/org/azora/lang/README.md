# Azora Architecture

A multiphase compiler pipeline for the Azora language with
multi-pass semantic analysis and compile-time function execution (CTFE).

---

## Language Surface

### Bindings

| Keyword          | Mutability                      | Kotlin Emit |
|------------------|---------------------------------|-------------|
| `var x: Int = 5` | mutable                         | `var`       |
| `var x = 5`      | mutable, type inferred          | `var`       |
| `let x = 5`      | immutable (read-only view)      | `val`       |
| `let x: Int = 5` | immutable, type explicit        | `val`       |
| `fin x = 5`      | deeply immutable                | `val`       |
| `fin x: Int = 5` | deeply immutable, type explicit | `val`       |

### Compile-Time Constructs

| Syntax                   | Description                                                                      |
|--------------------------|----------------------------------------------------------------------------------|
| `inline fin x = 5`       | Compile-time constant. Removed from final AST.                                   |
| `inline let x = 5`       | Compile-time immutable. Removed from final AST.                                  |
| `inline var x = 5`       | Compile-time mutable. Can be reassigned with `inline x = ...`.                   |
| `inline x = 6`           | Compile-time reassignment of an `inline var`.                                    |
| `inline if cond { }`     | Compile-time conditional. Untaken branch removed.                                |
| `inline func f() { }`    | Inline function. Body substituted at call sites, not emitted to IR.              |
| `inline { ... }`         | Compile-time block. All declarations/if/assignment inside are implicitly inline. |
| `deepinline { ... }`     | Recursive compile-time block. Nested `if` branches also inlined.                 |
| `deepinline if cond { }` | Deep compile-time conditional. Taken branch recursively deep-inlined.            |
| `noinline stmt`          | Escape hatch. Inside `deepinline`, marks a statement as runtime.                 |

### Scoping

| Syntax                | Description                                                                                                                                                                                       |
|-----------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `zone { }`            | Scoped block. Introduces a new variable scope. Standalone statement.                                                                                                                              |
| `friend zone { }`     | Friend zone block. Multiple friend zone blocks in the same parent scope share a persistent variable scope. Variables declared in one are visible in others, but not in regular code between them. |
| `::x`                 | Scope resolution. Accesses a variable in the parent scope, skipping the current scope.                                                                                                            |
| `::_::x`              | Accesses a variable two scopes up. `::_::_::x` for three, etc.                                                                                                                                    |
| `inline zone { }`     | Alias for `inline { }`.                                                                                                                                                                           |
| `deepinline zone { }` | Alias for `deepinline { }`.                                                                                                                                                                       |

### Top-Level Bindings

| Syntax      | Description                                 |
|-------------|---------------------------------------------|
| `fin x = 9` | Top-level deeply immutable global. Allowed. |
| `var x = 9` | **Rejected** -- not thread-safe.            |
| `let x = 9` | **Rejected** -- not thread-safe.            |

### Control Flow

| Syntax                 | Description           |
|------------------------|-----------------------|
| `if cond { } else { }` | Runtime conditional.  |
| `return expr`          | Return from function. |

### Functions

```
func add(a: Int, b: Int): Int {
    return a + b
}

func main() {                     // return type inferred as Unit
    println("hello")
}

func main() = deepinline { ... }  // expression body (inline/deepinline only)

inline func square(x: Int): Int { // evaluated at call sites, not emitted to IR
    return x * x
}
```

### Types

| Type      | Size    | Description                      | Kotlin    | TypeScript | LLVM     |
|-----------|---------|----------------------------------|-----------|------------|----------|
| `Byte`    | 8-bit   | Signed integer                   | `Byte`    | `number`   | `i8`     |
| `UByte`   | 8-bit   | Unsigned integer                 | `UByte`   | `number`   | `i8`     |
| `Short`   | 16-bit  | Signed integer                   | `Short`   | `number`   | `i16`    |
| `UShort`  | 16-bit  | Unsigned integer                 | `UShort`  | `number`   | `i16`    |
| `Int`     | 32-bit  | Signed integer (default)         | `Int`     | `number`   | `i32`    |
| `UInt`    | 32-bit  | Unsigned integer                 | `UInt`    | `number`   | `i32`    |
| `Long`    | 64-bit  | Signed integer                   | `Long`    | `bigint`   | `i64`    |
| `ULong`   | 64-bit  | Unsigned integer                 | `ULong`   | `bigint`   | `i64`    |
| `Cent`    | 128-bit | Signed integer                   | `Long`    | `bigint`   | `i128`   |
| `UCent`   | 128-bit | Unsigned integer                 | `ULong`   | `bigint`   | `i128`   |
| `Float`   | 32-bit  | Single-precision float (default) | `Float`   | `number`   | `float`  |
| `Real`    | 64-bit  | Double-precision float           | `Double`  | `number`   | `double` |
| `Decimal` | 128-bit | Quad-precision float             | `Double`  | `number`   | `fp128`  |
| `Char`    | -       | Character                        | `Char`    | `string`   | `i8`     |
| `Bool`    | -       | Boolean                          | `Boolean` | `boolean`  | `i1`     |
| `String`  | -       | String                           | `String`  | `string`   | `i8*`    |
| `Unit`    | -       | Void                             | `Unit`    | `void`     | `void`   |

### Numeric Literals

**Integer literals:**
```
42          // Int (default)
42u         // UInt
42b         // Byte
42ub        // UByte
42s         // Short
42us        // UShort
42L         // Long
42uL        // ULong
42c         // Cent
42uc        // UCent
```

**Floating-point literals:**
```
3.14        // Real (default)
3.14f       // Float
3.14D       // Decimal
1.5e-10     // Real (scientific notation)
```

**Character literals:**
```
'a'         // simple character
'\n'        // escape: newline
'\t'        // escape: tab
'\r'        // escape: carriage return
'\\'        // escape: backslash
'\''        // escape: single quote
'\0'        // escape: null
'\u0041'    // escape: unicode (U+0041 = 'A')
```

**Base prefixes:**
```
0xFF        // hexadecimal (255)
0o77        // octal (63)
0b1010      // binary (10)
```

**Underscore separators:** `1_000_000`, `0xFF_FF`, `0b1111_0000`

**Hex suffix restrictions:** `b`, `c`, and `f` are hex digits, so `b`/`ub`/`c`/`uc`/`f` suffixes
are not available in hex mode. Use `s`, `us`, `L`, `uL`, `D` with hex literals.

### Testing & Debugging

| Syntax | Scope | Description |
|--------|-------|-------------|
| `test "name" { body }` | global only | Test declaration. Runs after `main`. |
| `assert cond { "msg" }` | function/zone | Runtime assertion. Aborts if condition is false. |
| `trace { "msg" }` | function/zone | Runtime trace. Prints `[TRACE] msg`. |
| `inline assert cond { "msg" }` | all scopes | Compile-time assertion. Error if condition is false. |
| `inline trace { "msg" }` | all scopes | Compile-time trace. Produces a compiler warning. |

**Test declaration:**
```
test "addition works" {
    fin result = 2 + 3
    assert result == 5 { "expected 5" }
}
```

**Runtime assertion (inside a function or zone):**
```
func divide(a: Int, b: Int): Int {
    assert b != 0 { "division by zero" }
    return a / b
}
```

**Runtime trace (inside a function or zone):**
```
func process(x: Int): Int {
    trace { "entering process" }
    return x * 2
}
```

**Compile-time assertion:**
```
inline fin SIZE = 16
inline assert SIZE > 0 { "SIZE must be positive" }
```

**Compile-time trace:**
```
inline fin MODE = "release"
inline trace { "compiling in mode: release" }
```

### Operators

- Arithmetic: `+`, `-`, `*`, `/`, `%` (all numeric types)
- Comparison: `==`, `!=`, `<`, `<=`, `>`, `>=` (numeric + Char)
- Logical: `&&`, `||`, `!`
- String: `+` (concat), `*` (repeat with Int)
- Assignment: `=`

### Keywords

`var`, `fin`, `let`, `func`, `return`, `package`, `if`, `else`,
`inline`, `deepinline`, `noinline`, `zone`, `friend`, `true`, `false`,
`test`, `assert`, `trace`

---

## Pipeline Overview

```
Source Code
    |
    v
Phase 1 -- Frontend (source -> AST)
    |  1. Lexer
    |  2. Parser
    |  3. AST Validator
    |
    v
Phase 2 -- Semantic Analysis (multi-pass)
    |  4. Top-Level CTFE            (Pass 0)
    |  5. Symbol Collection         (Pass 1)
    |  6. Import Resolution
    |  7. Fixed-Point CTFE Loop:    (Pass 2)
    |       repeat:
    |         CTFE (compile-time function execution)
    |         fold results back into AST
    |       until AST is stable
    |  8. Type Resolution           (Pass 3)
    |  9. Alloc/Drop Analysis       (Pass 4)
    | 10. Effect Checking           (Pass 5)
    |
    v
Phase 3 -- IR Generation
    | 11. AST -> Typed IR
    | 12. IR Optimization Passes
    |
    v
Phase 4 -- Backend
    | 13. IR -> Kotlin Source
    | 14. IR -> TypeScript Source
    | 15. IR -> LLVM IR
    | 16. IR Interpreter (direct execution)
    v
Output
```

---

## Phase 1 -- Frontend

Transforms raw source text into a structured AST. No name resolution
or type inference happens here -- just structure.

### Step 1: Lexer (`frontend/Lexer.kt`)

Tokenizes source code into a flat list of tokens. Tracks line and column
positions for error reporting. Handles:

- Keywords: `var`, `fin`, `let`, `func`, `return`, `package`, `if`, `else`,
  `inline`, `deepinline`, `noinline`, `zone`, `friend`, `true`, `false`,
  `test`, `assert`, `trace`
- Literals: integers, reals, strings (with escape sequences)
- Operators: arithmetic, comparison, logical, assignment
- Delimiters: `(`, `)`, `{`, `}`, `,`, `:`, `::`, `->`
- Comments: `//` line comments, `/* */` nestable block comments
- Newlines: significant outside brackets (statement separators)

### Step 2: Parser (`frontend/Parser.kt`)

Recursive-descent parser. Produces a raw, unresolved AST.

**Top-level grammar:**
```
program     -> package? topLevel*
topLevel    -> funcDecl | inlineConstruct | deepinlineConstruct
funcDecl    -> "inline"? "func" IDENT "(" params? ")" (":" type)? ( "{" stmt* "}" | "=" inlineBody )
```

**Statement grammar:**
```
stmt        -> varDecl | finDecl | letDecl | returnStmt | assignment
             | ifStmt | exprStmt | inlineStmt | deepinlineStmt | noinlineStmt
             | zoneStmt | friendZoneStmt
varDecl     -> "var" IDENT (":" type)? "=" expr
finDecl     -> "fin" IDENT (":" type)? "=" expr
letDecl     -> "let" IDENT (":" type)? "=" expr
ifStmt      -> "if" expr "{" stmt* "}" ("else" "{" stmt* "}")?
zoneStmt    -> "zone" "{" stmt* "}"
friendZoneStmt -> "friend" "zone" "{" stmt* "}"
```

**Expression precedence (lowest to highest):**

| Level | Operators         |
|-------|-------------------|
| 1     | `\|\|`            |
| 2     | `&&`              |
| 3     | `==` `!=`         |
| 4     | `<` `<=` `>` `>=` |
| 5     | `+` `-`           |
| 6     | `*` `/` `%`       |
| 7     | `!` `-` (unary)   |
| 8     | function call     |

### Step 3: AST Validator (`frontend/AstValidator.kt`)

Catches structural errors that are hard to express in grammar rules:

- Duplicate function names
- Duplicate parameter names within a function
- Non-Unit functions missing return statements (searches through if/zone branches)
- Empty variable names
- Variable redeclaration in the same scope (variables cannot be redeclared)

### AST Node Types (`frontend/Ast.kt`)

**Expressions:** `IntLiteral`, `RealLiteral`, `StringLiteral`, `BoolLiteral`,
`Identifier`, `Binary`, `Unary`, `Call`, `Grouping`, `UpperScopeAccess`

**Statements:** `VarDecl`, `FinDecl`, `LetDecl`, `Assignment`, `Return`,
`ExprStmt`, `If`, `Zone`, `FriendZone`, `InlineFin`, `InlineLet`, `InlineVar`,
`InlineAssignment`, `InlineIf`, `InlineBlock`, `DeepInlineBlock`,
`DeepInlineIf`, `NoInline`, `Assert`, `Trace`, `InlineAssert`, `InlineTrace`

**Top-level:** `Func`, `InlineVar`, `InlineFin`, `InlineLet`,
`InlineAssignment`, `InlineIf`, `InlineBlock`, `DeepInlineBlock`,
`DeepInlineIf`, `Test`, `InlineAssert`, `InlineTrace`

**Type annotations:** `TypeAnnotation.Explicit("Int")` or `TypeAnnotation.Inferred`

All AST nodes carry `line`, `column`, and `length` for error reporting.

---

## Phase 2 -- Semantic Analysis

Multiple semantic passes. Metaprogramming (CTFE)
creates ordering dependencies that can't be resolved in one pass.

### Step 4: Top-Level CTFE -- Pass 0 (`semantic/CtfeEvaluator.kt`)

Resolves top-level compile-time constructs **before** symbol collection.
This flattens conditional function declarations so `SymbolCollector` can see them.

```
inline fin DEBUG = true

deepinline if DEBUG {
    func debugLog(msg: String): Unit {  // conditionally included
        println(msg)
    }
}
```

After Pass 0, `debugLog` appears as a normal `TopLevel.Func` in the program.

Inside `deepinline` blocks at the top level, bare `func` declarations are
marked `isInline = true` (not emitted to IR). Use `noinline func` to emit them.

### Step 5: Symbol Collection -- Pass 1 (`semantic/SymbolCollector.kt`)

Walks all function declarations and registers signatures in the symbol table.
Does **not** look inside function bodies.

- Registers built-in functions (`println`)
- Infers return types from `return` statements when type annotation is omitted
- Records `isInline` flag for inline functions

**Why separate?** Forward references work: `main` can call `add` even if `add`
is defined later, because all signatures are registered before any bodies are analyzed.

### Step 6: Import / Dependency Resolution (`semantic/ImportResolver.kt`)

Resolves cross-module references. Currently, a no-op (single-file, no `use` keyword).
The pass slot exists for when modules are added.

### Step 7: Fixed-Point CTFE Loop -- Pass 2 (`semantic/SemanticPipeline.kt`)

**CTFE runs first, then type resolution.** This is the corrected order --
compile-time constructs must be resolved before type checking sees them.

```
repeat:
    run CtfeEvaluator on current AST
    fold results back into AST
until AST is stable (no changes) OR iteration >= 100
```

The CTFE evaluator handles:

- **Constant folding:** `3 + 4` -> `7`, `"web" == "web"` -> `true`
- **`inline fin`/`let`/`var`:** Stores in compile-time env, substitutes references, removes declaration
- **`inline x = expr`:** Updates compile-time env
- **`inline if`:** Evaluates condition, keeps only taken branch
- **`inline { }`:** One-level inline block -- declarations and if become compile-time
- **`deepinline { }`:** Recursive -- nested if branches also deep-inlined
- **`deepinline if`:** Evaluates condition, deep-inlines taken branch
- **`noinline`:** Escapes to runtime inside inline/deepinline contexts
- **`inline func` body substitution:** Replaces call sites with the function body wrapped in a `zone { }` scope block to prevent variable name collisions
- **Compile-time function evaluation:** If all args are constants, interprets the function body

### Step 8: Type Resolution -- Pass 3 (`semantic/TypeResolver.kt`)

Runs on the CTFE-stabilized AST. All compile-time constructs have been resolved.
Any remaining `inline` nodes are reported as errors.

- Resolves expression types
- Verifies assignments match declared types (or infers types)
- Verifies return types match function signatures
- Verifies function call arguments match parameter types
- Rejects reassignment of `fin` and `let` bindings
- Manages local variable scopes (including `zone` blocks)

### Step 9: Alloc/Drop Analysis -- Pass 4 (`semantic/AllocDropAnalyzer.kt`)

Runs post-CTFE because generated code may introduce new allocations.

Current implementation:
- Warns about unused local variables
- Detects assignment to undefined variables

Future: alloc/drop pair tracking, ownership, lifetime analysis.

### Step 10: Effect Checking -- Pass 5 (`semantic/EffectChecker.kt`)

Runs post-CTFE because generated functions carry effects too.

- Classifies functions as `PURE` or `IMPURE`
- Propagates impurity through the call graph (fixed-point iteration)
- External/unknown function calls (e.g. `println`) are impure

---

## Phase 3 -- IR Generation

### Step 11: AST → Typed IR (`ir/IrGenerator.kt`)

Lowers the CTFE-stabilized, type-checked AST into typed IR.

- Inline functions (`isInline = true`) are **skipped** -- not emitted
- Type annotations resolve to `IrType` objects
- `TokenType` operators become `IrBinaryOp`/`IrUnaryOp` enums
- Every expression carries its resolved type
- Grouping expressions are eliminated
- `zone { }` blocks become `IrStmt.Zone` (scoped variable blocks)
- `friend zone { }` blocks become `IrStmt.Zone` in the IR (friend scope
  sharing is resolved during IR generation)
- Variable shadowing is resolved by name mangling: when a variable shadows
  an outer one, the IR renames the inner one (e.g. `x` becomes `__x0`) so
  all backends work correctly without scope-aware logic

**IR node types:**
- Expressions: `IntLiteral`, `RealLiteral`, `StringLiteral`, `BoolLiteral`, `Var`, `Binary`, `Unary`, `Call`
- Statements: `VarDecl`, `FinDecl`, `LetDecl`, `Assignment`, `Return`, `ExprStmt`, `If`, `Zone`, `Assert`, `Trace`
- Top-level: `IrFunction`, `IrTopLevel.Test`

Note: `UpperScopeAccess` (`::`) from the AST is resolved to a mangled `Var`
reference during IR generation. There is no `UpperVar` in the IR --
variable shadowing is handled by renaming inner variables (e.g. `__x0`).

The IR is **target-agnostic**. Every backend lowers from the same IR.

### Step 12: IR Optimization (`ir/IrOptimizer.kt`)

Four passes, run in sequence:

1. **Constant Folding:** Evaluate constant expressions.
2. **Constant Propagation:** Replace variable reads with known constants.
   `fin`/`let` bindings (never reassigned) are always propagatable.
3. **Dead Code Elimination:** Remove unreachable code after `return`.
   Constant-condition `if` branches are inlined.
4. **Unused Symbol Elimination:** Remove unreachable functions, unused globals,
   and unused local declarations. `main` is always the entry point.

IR optimization only runs in **release** mode (`Compiler().compile(source, release = true)`).
In debug mode (`release = false`) the raw IR is used directly by backends.

---

## Phase 4 -- Backend

All backends are thin lowering passes from the same optimized IR.

### Step 13: IR -> Kotlin (`backend/KotlinCodeGenerator.kt`)

| Azora IR       | Kotlin         |
|----------------|----------------|
| `var`          | `var`          |
| `fin` / `let`  | `val`          |
| `func`         | `fun`          |
| `zone { }`     | `run { }`      |
| `Int`          | `Int`          |
| `Real`         | `Double`       |
| `Bool`         | `Boolean`      |
| `String * Int` | `.repeat(n)`   |
| `println(...)` | `println(...)` |

If `main()` returns non-Unit, the Kotlin backend emits a `__azora_main` wrapper
so the JVM entry point signature remains `fun main(): Unit`.

| Azora IR   | Kotlin                                       |
|------------|----------------------------------------------|
| `test`     | `@org.junit.Test fun \`test name\`()`        |
| `assert`   | `require(cond) { msg }`                      |
| `trace`    | `println("[TRACE] " + msg)`                  |

### Step 14: IR → TypeScript (`backend/TypeScriptCodeGenerator.kt`)

| Azora IR       | TypeScript         |
|----------------|--------------------|
| `var`          | `let`              |
| `fin` / `let`  | `const`            |
| `func`         | `function`         |
| `zone { }`     | `{ }`              |
| `Int` / `Real` | `number`           |
| `Bool`         | `boolean`          |
| `==` / `!=`    | `===` / `!==`      |
| `println(...)` | `console.log(...)` |

Auto-appends `main()` call if a `main` function exists.

| Azora IR   | TypeScript                                       |
|------------|--------------------------------------------------|
| `test`     | `test("name", () => { })`                       |
| `assert`   | `if (!(cond)) { throw new Error(msg); }`        |
| `trace`    | `console.log("[TRACE]", msg)`                    |

### Step 15: IR → LLVM IR (`backend/LlvmCodeGenerator.kt`)

| Azora IR          | LLVM IR                         |
|-------------------|---------------------------------|
| `Int`             | `i32`                           |
| `Real`            | `double`                        |
| `Bool`            | `i1`                            |
| `String`          | `i8*` (null-terminated)         |
| `var`/`fin`/`let` | `alloca` + `store`              |
| `println(String)` | `@puts`                         |
| `println(Int)`    | `@printf` with `%d\n`           |
| `if/else`         | `br i1`, then/else/merge labels |

String constants are emitted as global `@.str.N` arrays.
External declarations (`@puts`, `@printf`) are only emitted when actually used.

| Azora IR   | LLVM IR                                          |
|------------|--------------------------------------------------|
| `test`     | `define void @test_name()`                       |
| `assert`   | branch + `@abort`                                |
| `trace`    | `@printf("[TRACE] %s\n")`                        |

### Step 16: IR Interpreter (`backend/IrInterpreter.kt`)

Directly executes the IR in-memory without code generation. Useful for
testing and the playground. Supports all IR node types including `zone` scoping.

---

## Entry Point (`Compiler.kt`)

Orchestrates all four phases:

```kotlin
when (result = Compiler().compile(source)) {
    is CompilationResult.Success -> {
        result.kotlin      // generated Kotlin source
        result.typescript   // generated TypeScript source
        result.llvm         // generated LLVM IR text
        result.ast          // CTFE-stabilized AST
        result.ir           // typed IR (before optimization)
        result.optimizedIr  // typed IR (after optimization)
        result.effects      // per-function effect classifications
        result.warnings     // non-fatal warnings
    }
    is CompilationResult.Failure -> {
        result.errors       // list of error messages
    }
}
```

The `warningsAsErrors` parameter treats warnings as compilation failures.

---

## Design Principles

1. **Don't resolve everything in one pass.** Multiple passes with a stabilization
   loop is the proven approach for languages with metaprogramming.

2. **Separate declaration semantic from body semantic.** Function signatures are
   registered (Pass 1) before bodies are analyzed (Pass 3). Forward references
   just work.

3. **CTFE runs before type checking.** Compile-time constructs are resolved first
   so the type checker sees clean, fully-resolved code.

4. **CTFE shares the type system.** The compile-time evaluator uses the same
   `IrType` as the rest of the compiler -- no separate interpreter types.

5. **IR is the portability asset.** Target-agnostic typed IR. Backends are thin
   lowering passes. Adding a new target means one new file.

6. **Post-CTFE analysis.** Alloc/drop and effect checking run after CTFE stabilizes,
   because generated code may introduce new allocations and side effects.

7. **Inline function body substitution.** `inline func` bodies are substituted at
   call sites and wrapped in `zone { }` to prevent variable name collisions --
   the function declaration is not emitted to IR.

---

## File Map

```
new_architecture/
|-- Compiler.kt                          Pipeline orchestrator
|-- README.md                            This file
|
|-- frontend/
|   |-- Token.kt                         TokenType enum + Token data class
|   |-- Lexer.kt                         Source -> tokens (with line/column tracking)
|   |-- Ast.kt                           Expr, Stmt, TopLevel, FuncDecl, Program, TypeAnnotation
|   |-- Parser.kt                        Tokens -> AST (recursive descent)
|   |-- AstValidator.kt                  Structural validation
|   |-- AstDumper.kt                    Tree-style AST dump for debugging
|
|-- semantic/
|   |-- SymbolTable.kt                   FunctionSymbol + VariableSymbol + scoped lookup
|   |-- SymbolCollector.kt               Pass 1: register function signatures + return type inference
|   |-- ImportResolver.kt                Import / dependency resolution (no-op for now)
|   |-- TypeResolver.kt                  Pass 3: type resolution + checking + mutability enforcement
|   |-- CtfeEvaluator.kt                 Pass 0 + 2: top-level + body CTFE, inline func substitution
|   |-- AllocDropAnalyzer.kt             Pass 4: liveness + use-before-init
|   |-- EffectChecker.kt                 Pass 5: purity classification + effect propagation
|   |-- SemanticPipeline.kt              Multi-pass orchestrator with fixed-point CTFE loop
|
|-- ir/
|   |-- IrNode.kt                        IrType, IrExpr, IrStmt, IrFunction, IrProgram
|   |-- IrGenerator.kt                   AST -> typed IR (skips inline functions)
|   |-- IrOptimizer.kt                   Constant fold -> const propagation -> DCE
|
|-- backend/
    |-- KotlinCodeGenerator.kt           IR -> Kotlin source
    |-- TypeScriptCodeGenerator.kt       IR -> TypeScript source
    |-- LlvmCodeGenerator.kt            IR -> LLVM IR text
    |-- IrInterpreter.kt                IR -> direct execution
```
