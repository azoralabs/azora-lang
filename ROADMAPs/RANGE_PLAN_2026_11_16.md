# Range<T> Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `a..b`, `a..<b` and `a>..b` build a `Range<T>` value for every ordered type, and make `for` walk ranges of every integer width, `Char`, the floats and any `Step` type, identically on the interpreter, LLVM and WebAssembly.

**Architecture:** `Range<T>` and `Step<S>` are std declarations in a new exposed module. The resolver types a range literal as `Range<T>`; the IR generator builds the value, or - in a `for` header over a std type - keeps the counter loop, now typed by the element (`IrStmt.For.elementType`) and implemented per backend without wrapping. A range value or a user `Step` type is walked through `offset`; generic code reaches `PartialOrder`, `Display` and `Step` through the existing witness descriptors (`semantic/Witnesses.kt`, `IrGenerator.witnessDispatchFunctions`).

**Tech Stack:** Kotlin Multiplatform compiler (`compiler/src/commonMain`), Azora std (`std/*.az`), tests in `compiler/src/commonTest` (interpreter) and `compiler/src/desktopTest` (LLVM via `lli`, WebAssembly via Node: `LlvmExec`, `WasmExec`).

**Spec:** `DIPs/RANGE_DIP.MD` (approved 2026-11-16). Read it first; this plan argues from it.

## Global Constraints

- No git command of any kind and no commits; the owner commits. Leave changes in the working tree.
- No third-party library; no new keyword or syntax. `by` stays where it is parsed today (a `for` header).
- `bridge` only on what has no Azora body (compiler/backend-provided).
- `Int` is 32 bits (`bridge pack Int<N: __uint = 32>`); `Long` is 64; `Cent` 128. `offset`'s count is `Long`.
- `Range` and `Step` are `@Since("0.1")`; regenerate `std/STABLE_SURFACE.txt` with `AZORA_REGENERATE_SURFACE=1 ./gradlew :compiler:desktopTest --offline --tests '*StdSurfaceTest*'` after any std surface change.
- Every behaviour lands on all three backends, optimized and not; a construct one backend cannot express is a compile error there, never a silent difference.
- Never weaken or skip a test to pass. Classify any failure (defect / stale expectation / missing implementation / environment / cascade) before touching it.
- Messages use the DIP's wording: `range bounds must have one type, got Int and Long; convert one, as in 'a as Long'`; `'Vec2' has no order, so 'a..b' cannot be a range of it; a range needs 'PartialOrder'`; `cannot walk a 'Range<String>': 'String' does not implement 'Step'`; `'by 0.5' is a Double, but a 'Range<Int>' steps by Int`; `the step 'by 0' does not move the range forward`; `'offset' of 'Day' did not move past …`; `'oper..' is not declared: every ordered type forms ranges; to walk them, implement 'Step' (RANGE_DIP §5)`.
- Full suite: `AZORA_REQUIRE_NATIVE_TESTS=1 ./gradlew :compiler:desktopTest :azls:test :app:installDist --offline --continue` (~4 min). Never run another gradle build while it runs. `--tests` filters cannot share an invocation with `:app:installDist`.

## Review Focus

1. **Bounds at a type's limits** - `Byte 250..255`, `Long` up to its maximum inclusive, `UInt 3u>..0u`, `Cent` near its limits: every row once, no wrap, the loop ends (Task 7).
2. **NaN** - `0.0..nan` is empty, `nan in 0.0..1.0` is false, walking a NaN-bounded range visits nothing (Tasks 4 and 7).
3. **A bad step on an empty range** - `5..0 by 0` still fails, as today's `Int` rule says (Task 7).
4. **One range value walked twice, or nested over itself** - both walks see every row; there is no shared cursor (Task 8).
5. **`Char` in generic code** - `func<T: Step>` handed a `Range<Char>` is refused with `add 'where T: Step<Int>'`-style guidance, since `Char` steps by `Int` (Task 8).

---

### Task 1: Every `Order` type is a `PartialOrder`

**Files:**
- Modify: `compiler/src/commonMain/kotlin/org/azora/lang/semantic/SymbolCollector.kt` (where conformances are defined, `table.defineConformance`)
- Test: `compiler/src/commonTest/kotlin/org/azora/lang/semantic/RangeValueTest.kt` (new; shared helpers for Tasks 1, 4, 5)

**Interfaces:**
- Produces: `table.conformsTo(T, "PartialOrder")` is true for every `T` with an `Order` conformance (written, derived, or `derives (… Order …)` on a bridge pack), recorded as a `TraitConformance(T, "PartialOrder")` with no type arguments.

- [ ] **Step 1: Write the failing test** `everyOrderTypeIsAPartialOrder`: compiles `func<T: PartialOrder> lesser(a: T, b: T): T = if a < b then a else b` called with `Int`, `Long`, `Char`, `String`, a pack with `derive Order`, and `Double`; asserts interpreter output `1 2 a b P(1) 1.5` (one `print` per call, space-separated).
- [ ] **Step 2: Run it** - `./gradlew :compiler:desktopTest --offline --console=plain --tests '*RangeValueTest'`. Expected: FAIL, `'T is PartialOrder': Int does not implement PartialOrder`.
- [ ] **Step 3: Implement** - when SymbolCollector defines an `Order` conformance for a type, also define `PartialOrder` for it unless one exists. Do not change `std/traits/traits.az`.
- [ ] **Step 4: Run it** - same command. Expected: PASS. Then `--tests '*WitnessTest' --tests '*BoundCheckTest' --tests '*StdSurfaceTest'`: PASS (the stable listing may gain `PartialOrder` lines only if the listing renders conformances; regenerate if so and check the diff is only those).

### Task 2: Strings order on LLVM and WebAssembly

**Files:**
- Modify: `compiler/src/commonMain/kotlin/org/azora/lang/backend/LlvmCodegen.kt` (the string binary-op path that throws `Unsupported string op: LT`), `LlvmUtf8Runtime.kt` if a helper belongs there; `WasmCodegen.kt`, `WasmUtf8Runtime.kt`
- Test: `compiler/src/desktopTest/kotlin/org/azora/lang/codegen/StringOrderExecTest.kt` (new)

**Interfaces:**
- Produces: `<`, `<=`, `>`, `>=` and `<=>` on `String` on both native targets, comparing by code point (byte-wise UTF-8 comparison, shorter prefix first), as the interpreter does.

- [ ] **Step 1: Write the failing test** `stringsOrderByCodePointOnEveryBackend`: one program printing `"a" < "b"`, `"ab" < "a"`, `"a" <= "a"`, `"é" > "z"`, `"" < "a"`, `"Z" < "a"`, `("b" <=> "a")` with values derived at run time (a loop) so CTCE does not fold them; asserts the interpreter's line equals LLVM's and WebAssembly's, optimized and not; expected `true false true true true true Greater` (adjust the last token to how `Compare` prints on the interpreter, and assert all three equal).
- [ ] **Step 2: Run it** - `AZORA_REQUIRE_NATIVE_TESTS=1 ./gradlew :compiler:desktopTest --offline --console=plain --tests '*StringOrderExecTest'`. Expected: FAIL, `Unsupported string op: LT`.
- [ ] **Step 3: Implement** a runtime string comparison returning -1/0/1 per target and lower the four comparisons and `<=>` through it.
- [ ] **Step 4: Run it** - Expected: PASS.

### Task 3: `PartialOrder` and `Display` reach erased code

**Files:**
- Modify: `compiler/src/commonMain/kotlin/org/azora/lang/semantic/Witnesses.kt`, `ir/IrGenerator.kt` (`witnessDispatchFunctions`, the comparison and string-template lowering of witnessed parameters near the existing `Witnesses.ORDER` / `Witnesses.EQUAL` uses), `semantic/TypeResolver.kt` (`isWitnessSpec`)
- Test: extend `compiler/src/commonTest/kotlin/org/azora/lang/semantic/WitnessTest.kt` and `compiler/src/desktopTest/kotlin/org/azora/lang/codegen/WitnessExecTest.kt`

**Interfaces:**
- Produces: `Witnesses.PARTIAL_ORDER = "PartialOrder"`, `Witnesses.DISPLAY = "Display"`; dispatchers `WITNESS_PARTIAL_COMPARE` (two operands → `Int`: -1, 0, 1, or 2 for unordered) and `WITNESS_DISPLAY` (one operand → `String`, the value as `"$x"` renders it for its concrete type). `<`, `<=`, `>`, `>=` on a parameter bounded by `PartialOrder` (and not `Order`) dispatch through the first; `"$x"` on a parameter bounded by `Display` through the second.

- [ ] **Step 1: Write the failing tests** in `WitnessTest`: `partialOrderReachesErasedCode` - `func<T: PartialOrder> within(lo: T, x: T, hi: T): Bool = lo <= x && x <= hi` with `(1.0, 2.0, 3.0)`, `(1.0, nan, 3.0)`, `("a", "m", "z")`, `(1, 5, 3)` prints `true false true false`; `displayReachesErasedCode` - `func<T: Display> tag(x: T): String = "<$x>"` with `7`, `"s"`, `2.5`, `'c'` prints `<7> <s> <2.5> <c>`. Add both programs to `WitnessExecTest`'s LLVM and WebAssembly runs.
- [ ] **Step 2: Run them** - `--tests '*WitnessTest'` then the exec class with native tools. Expected: the interpreter may pass; LLVM prints an address for `<s>` or refuses the comparison.
- [ ] **Step 3: Implement** the two witness specs, their dispatchers and the lowering, following `ORDER`/`HASH`.
- [ ] **Step 4: Run them** - Expected: PASS on all three backends.

### Task 4: `Range<T>` and `Step<S>` in std

**Files:**
- Create: `std/range.az` (`exposed module std.range`)
- Modify: `compiler/src/commonMain/kotlin/org/azora/lang/ir/Mangling.kt` (`object Intrinsics`: `RANGE = "Range"`, `STEP = "Step"`), `std/STABLE_SURFACE.txt` (regenerated)
- Test: `RangeValueTest.kt` (from Task 1)

**Interfaces:**
- Consumes: Task 1 (`Order` ⇒ `PartialOrder`), Task 3 (witnesses).
- Produces, exactly as RANGE_DIP §3 and §5:
  - `pack Range<T> where T: PartialOrder { fin lower: T; fin upper: T; fin upperIncluded: Bool = true; fin descending: Bool = false }`
  - `prop &.start: T`, `prop &.end: T`, `prop &.isEmpty: Bool`, `func &.contains(value: T&): Bool`, `prop &.reversed: Range<T>`
  - `prop &.size: Int where T: Step` - rows walked with the unit step: exponential then binary search over `k` on `first.offset(unit, ±k)` (first = `lower` ascending; `upper`, or `upper.offset(unit, -1)` when excluded, descending), O(log n) `offset` calls; fails with `a range of more than 2147483647 rows has no Int size` past `Int`'s maximum
  - `oper== Range<T>&.(rhs: Range<T>&): Bool where T: PartialEqual` (the four fields)
  - `impl Display for Range<T>`'s rendering behind `where T: Display`: `0..10`, `0..<10`, `10>..0`, and `(0..10).reversed` for a descending closed range
  - `spec Step<S = Self> requires PartialOrder { prop unit: S; func &.offset(step: S&, count: Long): Self? }`
  - All `@Since("0.1")`, documented as the DIP words them.

- [ ] **Step 1: Write the failing tests** (built with the constructor; literals come in Task 5):
  - `aRangeHoldsItsLowerBoundAndMaybeItsUpper`: `Range<Int>(0, 10, upperIncluded: false)` - `contains(0)` true, `contains(10)` false, `contains(-1)` false, `start` 0, `end` 10, `isEmpty` false.
  - `emptyAndNaN`: `Range<Int>(5, 0).isEmpty` true; `Range<Int>(3, 3, upperIncluded: false).isEmpty` true; `Range<Int>(3, 3).isEmpty` false; a `Range<Double>` with a NaN bound is empty and contains nothing; `nan` is in no `Range<Double>`.
  - `reversedFlipsTheDirection`: `Range<Int>(0, 5, upperIncluded: false).reversed` prints `5>..0`, its `reversed` prints `0..<5`; `Range<Int>(0, 5).reversed` prints `(0..5).reversed`.
  - `rangesCompareWhereTheirBoundsDo`: equal and unequal `Range<Int>` pairs; `==` on `Range<P>` for a `P` with no equality is refused with the member's `where` message.
  - `sizeNeedsAStepType`: a `Range<String>`'s `.size` is refused (`String` is not `Step`). (`size`'s values are tested in Task 6, once std's types step.)
- [ ] **Step 2: Run them** - Expected: FAIL, `undefined type 'Range'`.
- [ ] **Step 3: Implement** `std/range.az` and the intrinsic names.
- [ ] **Step 4: Run them** - Expected: PASS. Regenerate `STABLE_SURFACE.txt`; the diff adds exactly `Range` and `Step` and their members. `--tests '*StdlibSelfTest' --tests '*StdSurfaceTest'`: PASS.

### Task 5: Range literals are values, and `in` tests them

**Files:**
- Modify: `semantic/TypeResolver.kt` (`is Expr.Range ->` in `resolveExpr`, now typing `Range<T>`), `ir/IrGenerator.kt` (`is Expr.Range -> error(...)` at the expression lowering, now a `Range<T>` construction with `T` written out so witness lowering knows it), `semantic/SymbolCollector.kt` (`inferExprType`: `is Expr.Range -> null`), `stdlib/StdlibInjector.kt` (a runtime `Expr.Range` - outside a `where` clause, an `inline for` and a `for` header over a std type - pulls in `std.range`), `semantic/CtfeEvaluator.kt` (a range is still not a compile-time value)
- Test: `RangeValueTest.kt`; `compiler/src/desktopTest/kotlin/org/azora/lang/codegen/RangeExecTest.kt` (new; runs `RangeValueTest.programs` and, later, `RangeLoopTest.programs` on LLVM and WebAssembly, optimized and not, like `StructuralEqualityExecTest`)

**Interfaces:**
- Consumes: Task 4's `Range<T>`.
- Produces: the type of `a..b`, `a..<b`, `a>..b` is `IrType.Named("Range", [T])`, `T` the bounds' type after an unsuffixed literal adopts the other bound's; lowering builds `Range<T>(lower, upper, upperIncluded, descending)` per RANGE_DIP §3.1's table. A `companion object { val programs: List<Pair<String, String>> }` in `RangeValueTest` holds every runnable program with its expected output, for `RangeExecTest`.

- [ ] **Step 1: Write the failing tests**:
  - `aRangeIsAValue`: `fin r = 0..<10`, passed to `func inside(r: Range<Int>, x: Int): Bool = x in r`, returned from `func digits(): Range<Int> = 0..9`, stored in a pack field; prints `true false true 0..9`.
  - `everyOrderedTypeFormsARange`: `3 in 0L..5L`, `'q' in 'a'..'z'`, `0u..<9u`, `0.5 in 0.0..1.0`, `"m" in "a".."z"`, `5 !in 0..<5`, a `derive Order` pack; prints `true true 0..<9 true true true true`.
  - `literalsAdoptTheOtherBound`: `fin n: Long = 7` then `(0..n)` prints `0..7` and its type is `Range<Long>` (a declared binding `fin r: Range<Long> = 0..n` compiles).
  - `boundsMustHaveOneType`: `fin a: Int = 1` `fin b: Long = 2` `fin r = a..b` → `range bounds must have one type, got Int and Long; convert one, as in 'a as Long'`.
  - `aRangeNeedsAnOrder`: a pack `Vec2` with no order, `fin r = v..w` → `'Vec2' has no order, so 'a..b' cannot be a range of it; a range needs 'PartialOrder'`.
  - `genericCodePassesTheBoundOn`: `func<U> make(a: U, b: U) = a..b` → `add 'where U: PartialOrder'`; with the bound it compiles and runs.
  - `compileTimeRangesAreUnchanged`: `where N in 2..4` and `inline for i in 0..<3` behave as before (reuse one existing case each from `PackSizeConstraintTest` / an `inline for` test as the expectation).
- [ ] **Step 2: Run them** - Expected: FAIL, `ranges can only be used as for-loop iterables`.
- [ ] **Step 3: Implement** the typing, the lowering, the injection and the inference.
- [ ] **Step 4: Run them** - interpreter tests PASS; `RangeExecTest` (value programs) PASS on LLVM and WebAssembly.

### Task 6: std's types are `Step`

**Files:**
- Modify: `std/range.az` (or `std/primitive.az` beside the numeric types: `inline for Ty in Numbers { bridge impl Step for Ty }` and `bridge impl Step<Int> for Char`), `ir/IrGenerator.kt` / the three backends for the compiler-provided `offset` and `unit`
- Test: `RangeValueTest.kt` (`sizeCountsTheWalk` from Task 4, plus `stepsOfTheStdTypes`)

**Interfaces:**
- Consumes: Task 4's `Step`.
- Produces: for every integer width `W`: `W::unit == 1`, `x.offset(step, k)` = `x + step·k` exactly, null when outside `W`; floats: `unit == 1.0`, `x + step·k` with one rounded multiply and one rounded add; `Char`: `Step<Int>`, `unit == 1`, the code point `step·k` places on skipping U+D800–U+DFFF, null outside U+0000–U+10FFFF. Exact-or-null must hold where `step·k` itself exceeds the width (compute wider, or check before multiplying).

- [ ] **Step 1: Write the failing tests** `sizeCountsTheWalk` - `Range<Int>(0, 10, upperIncluded: false).size` 10; `Range<Int>(10, 0, upperIncluded: false, descending: true).size` 10; `Range<Int>(5, 0).size` 0; `Range<Double>(0.5, 2.2).size` 2; `Range<Long>(0, 4000000000).size` fails with `a range of more than 2147483647 rows has no Int size` - and `stepsOfTheStdTypes`: `(250 as Byte).offset(1, 5)` is `255`, `.offset(1, 6)` is null; `(3 as UInt).offset(1, -3)` is `0`, `.offset(1, -4)` null; `Long` max `.offset(1, 1)` null; `(0.0).offset(0.1, 10)` prints `1.0`; `Char` 55295 `.offset(1, 1)` is code point 57344; `Char` 1114111 `.offset(1, 1)` null; `Int::unit` 1, `Double::unit` 1.0. Add to `programs`.
- [ ] **Step 2: Run it** - Expected: FAIL (`Step` not implemented for `Byte`).
- [ ] **Step 3: Implement** the bridge implementations and their lowering on all three backends.
- [ ] **Step 4: Run them** - interpreter and `RangeExecTest` PASS.

### Task 7: `for` over a literal range of every std type

**Files:**
- Modify: `ir/IrNode.kt` (`IrStmt.For`: add `val elementType: IrType = IrType.Int`; `step` is then of the element's stride type), `semantic/TypeResolver.kt` (the `is Stmt.For` → `is Expr.Range` branch: any `Step` element type, the row's type `T`, the step's type `S`), `ir/IrGenerator.kt` (the `Stmt.For` range branch: replace the `lookupOperator(…, "oper..")` gate with the `Step` requirement), `ir/IrOptimizer.kt`, `ir/IrSymbolCanonicalizer.kt` (carry the field), `backend/IrInterpreter.kt`, `backend/LlvmCodegen.kt` (`emitFor`), `backend/WasmCodegen.kt` (`emitFor`), `semantic/CtfeEvaluator.kt` (folds a loop only over `Int` bounds; any other element type is left to run time)
- Test: `compiler/src/commonTest/kotlin/org/azora/lang/semantic/RangeLoopTest.kt` (new, with `programs`), run by `RangeExecTest`

**Interfaces:**
- Consumes: Task 6's steps (the semantics backends must reproduce).
- Produces: rows per RANGE_DIP §6 step 4 for integer widths (count-based or overflow-checked; never wrap), `Char` (surrogates skipped), floats (`lower + step·k`); the step check of §6 step 3 with its message; descending with an excluded upper starts at `upper` minus one unit.

- [ ] **Step 1: Write the failing tests** (each prints its rows space-separated; all go into `programs`):
  - `everyIntegerWidthWalks`: `for b: Byte in 250..255` → `250 251 252 253 254 255`; `fin hi: UInt = 3` then `for u in hi>..0` → `2 1 0`; `for x: UInt in 0..2` → `0 1 2`; `for l: Long in` (Long max − 2)`..`(Long max) → three rows, the last the maximum; `for s: Short in -2..<1` → `-2 -1 0`; a `Cent` range of three rows at its maximum.
  - `charsSkipTheSurrogates`: from code point 55294 to 57345 → four rows: 55294, 55295, 57344, 57345; `'a'..'e' by 2` → `a c e`.
  - `floatsDoNotDrift`: `for x in 0.0..1.0 by 0.1` visits 11 rows, the last `1.0`; `for x in 0.0..<1.0 by 0.25` → `0.0 0.25 0.5 0.75`; `5.0>..0.0 by 2.0` → `4.0 2.0 0.0`; a NaN bound visits nothing.
  - `stepsAreCheckedEvenWhenEmpty`: `for i in 5..0 by 0 {}` fails at run time with `the step 'by 0' does not move the range forward`; `by -1` the same with `'by -1'`.
  - `todaysIntLoopsAreUnchanged`: `5>..0 by 2` → `4 2 0`; `0..<6 by 2 with index` → rows and indices `0:0 2:1 4:2`; `break:outer` / `continue` across two labelled range loops as an existing test writes them.
  - `aStepOfAnotherTypeIsRefused`: `for i in 0..10 by 0.5` → `'by 0.5' is a Double, but a 'Range<Int>' steps by Int`.
  - `aTypeWithoutStepIsNotWalked`: `for s in "a".."c" {}` → `cannot walk a 'Range<String>': 'String' does not implement 'Step'`.
- [ ] **Step 2: Run them** - Expected: FAIL, `range start must be Int`.
- [ ] **Step 3: Implement** the resolver branch, the IR field and each backend's loop.
- [ ] **Step 4: Run them** - interpreter PASS; `RangeExecTest` PASS on LLVM and WebAssembly, optimized and not. Then the existing loop suites: `--tests '*ControlFlow*' --tests '*Loop*' --tests '*Range*'`: PASS.

### Task 8: Walking range values, user `Step` types and generic code

**Files:**
- Modify: `semantic/TypeResolver.kt` (the `Stmt.For` non-literal branch: an iterable of type `Range<T>`), `ir/IrGenerator.kt` (lower `for x in r`: `T` a std type → read the fields and emit Task 7's `IrStmt.For`; `T` a user type → a `While` over `offset` with the per-row advance check; `T` a type parameter → the same through witnesses), `semantic/Witnesses.kt` (`STEP = "Step"`, dispatchers `WITNESS_STEP_OFFSET` and `WITNESS_STEP_UNIT`, the stride type per concrete type read off its `Step` conformance's argument), `semantic/SymbolCollector.kt` (a second `impl Step<…>` for one type is refused: `'Day' implements Step already; a type steps one way`)
- Test: `RangeLoopTest.kt`, run by `RangeExecTest`

**Interfaces:**
- Consumes: Tasks 3, 4, 6, 7.
- Produces: `for x in r [by s]` for every `r: Range<T>` with `T: Step<S>`; the same rows as a literal of the same bounds.

- [ ] **Step 1: Write the failing tests**:
  - `aRangeValueWalksLikeItsLiteral`: `fin r = 0..<5` walked, then walked again inside a loop over itself → `0 1 2 3 4` and 25 pairs; `r.reversed` walked → `4 3 2 1 0`; a `Range<Char>` field of a pack walked by 2.
  - `aUserTypeSteps`: `pack Day { var n: Int }` with `derive Order`, `impl Step<Int> for Day { prop unit: Int = 1; func &.offset(step: Int&, count: Long): Day? = … }`; `for d in Day(1)..Day(7) by 3` → `1 4 7`; `Day(7)>..Day(1)` → `6 5 4 3 2 1`; `(Day(1)..Day(5)).size` 5.
  - `aBrokenOffsetCannotLoopForever`: an `offset` returning `self` → fails with `'offset' of 'Day' did not move past Day(1)` (shape of the value as `"$value"` renders).
  - `genericCodeWalksARange`: `func<T: Step> rows(r: Range<T>): Int` counting rows → `rows(0..<4)` 4, `rows(0.0..1.0)` 2; `func<T: Step<Int>> chars(r: Range<T>): Int` → `rows('a'..'c')` refused with guidance to bound `Step<Int>`, `chars('a'..'c')` 3.
  - `aTypeStepsOneWay`: two `impl Step<…> for Day` → `'Day' implements Step already; a type steps one way`.
- [ ] **Step 2: Run them** - Expected: FAIL (`for loop iterable must be a range, an array, …`).
- [ ] **Step 3: Implement** the branches, the witnesses and the uniqueness check.
- [ ] **Step 4: Run them** - interpreter PASS; `RangeExecTest` PASS on LLVM and WebAssembly.

### Task 9: `oper..` and `oper>..` are gone

**Files:**
- Modify: `frontend/Parser.kt` (`parseFreeOperator`, the `impl` operator member path and the spec operator path: refuse `..` and `>..` operator declarations with the DIP's message, and refuse `by` after any operator declaration), `std/traits/core.az` (delete the two `bridge oper..` / `bridge oper>..` lines in `inline for Ty in Numbers`), `ir/IrGenerator.kt` and `semantic/TypeResolver.kt` (delete what referred to the removed declarations), `/Users/milkybit/azora/azora-engine/packages/azora-math/src/vec.az` (delete `oper.. &.(rhs: Self&) by Self::one` and `oper>.. &.(rhs: Self&) by Self::one`)
- Test: `RangeValueTest.kt`

**Interfaces:**
- Consumes: Tasks 5-8 (nothing depends on the declarations any more).

- [ ] **Step 1: Write the failing test** `rangeOperatorsAreNotDeclared`: `bridge oper.. P&.(rhs: P&) by 1`, `oper>.. P&.(rhs: P&) { … }` in an impl, and `oper+ P&.(rhs: P&): P by 2 { … }` are each refused with the DIP's message (the last: `'by' does not follow an operator declaration`).
- [ ] **Step 2: Run it** - Expected: FAIL (accepted today).
- [ ] **Step 3: Implement** the refusals and the deletions.
- [ ] **Step 4: Run it** - PASS. `--tests '*StdlibSelfTest' --tests '*StdSurfaceTest' --tests '*Operator*'`: PASS (regenerate the surface; the diff removes only the operator lines). Engine scratch build (copy to the scratchpad, `azora build`): the same 8 of 31 members as before, none of this plan's diagnostics.

### Task 10: Documents and the whole suite

**Files:**
- Test: `azls/src/test/kotlin/org/azora/azls/AzoraLanguageServerTest.kt` (`hoverShowsARangesType`: hovering `r` in `fin r = 0..<10` shows `Range<Int>`; with the existing `hover(source, offset)` helper)
- Modify: `DIPs/RANGE_DIP.MD` (status → implemented, with tests), `DIPs/CONTROL_FLOW_DIP.MD` §2 (ranges are values; every std type; the step rules; pointer to RANGE_DIP), `DIPs/OPERATOR_OVERLOADING_DIP.MD` §12.8 and §17 Q8 (answered), `DIPs/GENERICS_DIP.MD` (§5.3/§21: `PartialOrder`, `Display`, `Step` reach erased code), `ROADMAPs/VERSION_0_1_ROADMAP.MD` §1.1 (the `by` item closed; record that an unbounded erased `T` interpolated on LLVM printed an address - closed by Task 3 only where `T: Display` is declared; the unbounded case stays an open §1.1 miscompile), `ROADMAPs/ECOSYSTEM_PROGRESS.md` (entry with evidence), `ROADMAPs/ECOSYSTEM_DELIVERY_PLAN.md`

- [ ] **Step 1: Write and run** `hoverShowsARangesType` (`./gradlew :azls:test --offline --tests '*AzoraLanguageServerTest*'`); fix AZLS's type rendering if it does not show `Range<Int>`.
- [ ] **Step 2: Run the full suite** (Global Constraints command). Expected: 0 failures, 0 skipped; AZLS 0 failures.
- [ ] **Step 3: Write the documents** with the measured counts and the Engine result.
- [ ] **Step 4: Probe from the CLI** (`./gradlew :app:installDist --offline -q`, then `azora run` / `compile llvm` / `compile wat` on a scratch program using a `Range<Long>` value, a `Char` loop and a `Step` user type): the three outputs agree.
