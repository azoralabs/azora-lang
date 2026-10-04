# Azora ecosystem execution log

Plan: [150-step delivery plan](ECOSYSTEM_DELIVERY_PLAN.md).
Initial evidence: [2026-09-08 audit](ECOSYSTEM_AUDIT_2026_09_08.md).

## Current status — 2026-10-04

See [the foundation repair inventory](FOUNDATION_REPAIR_2026_10_04.md) for the
current measured results, repaired defects and remaining native qualification
work. Earlier entries below are historical observations.

Studio's required architecture is Azora compiled through LLVM to native code,
with no JAR/JVM runtime or tooling dependency in the installed product. The
existing Kotlin/Compose Studio is a legacy prototype and does not meet that gate.
Engine package/template compatibility and native Studio implementation remain
open; green language suites alone do not establish readiness for either product.

## Status — 2026-09-19

- Completed: 001–006 and 009; strict disk and bundled standard-library loading pass.
- Completed substeps: 010.C1–C2, bracket grammar and contextual array execution.
- Completed: 010.C3.1–C3.2, selected-import implementation reachability and
  removal of implicit collection storage reinterpretation.
- Completed: 010.C3.3 (closed 2026-09-19 by user decision). ArrayList runs on
  the interpreter, LLVM and WASM; its remaining items moved to 016, 019/022,
  023 and 063.
- Completed: 010.C3.4 (closed 2026-09-19 by user decision), target-owned literal
  factories for packs and specs, sequence and associative, with `where`,
  failure and ownership behaving as a call to the factory.
- Completed: 010.C3.5. Factory dependencies are discovered and carry canonical
  identities; nothing injected becomes nameable, and program names capture no
  library reference.
- In progress: 007/014. Block imports bind lexically in every file, for
  declarations, type functions and named type macros, and an import that names
  nothing is an error (2026-09-22). §5.2 keeps `func .name()`. 007 is proposed
  for closure; 014's scope members and access checks remain.
- Completed substep: 021.1. A generic call with inferred type arguments is typed
  by them in IR, so its value is no longer erased on LLVM and WASM.
- Completed substeps: 010.C4.1–C4.2. List and Set literals build the standard
  collections on the interpreter, LLVM and WASM.
- Completed substep: 010.C4.3. Map literals build the standard maps on every
  target. 010.C4.4 (untyped `[k: v]`) needs a decision.
- Completed substep: 022.1. `Hash`, `Equal` and `Order` bounds reach erased
  generic code through witness descriptors and are checked where types are
  chosen.
- Completed substep: 010.C4.4. An untyped `[k: v]` builds the standard
  `LinkedHashMap`. 010.C4 is closed; C5 (qualification) remains.
- Completed substep: 018.1. `ctor .()` runs on the interpreter, LLVM and WASM
  wherever a construction writes no arguments, once.
- The remaining 008 fixture review and 010.C5 remain open. Older entries below
  preserve the evidence at each stage.
- Engine/Studio build and release qualification remain open.

## 004 — assertion migration completed

The parser and current assertion DIP agree on `assert condition panic message`.
Migrated 172 literal-message brace forms and one `then` message in 18 library
files. The condition and message text were preserved and compared against a
reviewed before/after inventory. Existing edits in `std/quantum.az` were preserved.

Updated directly related contract/repeated-construction/test-scope fixtures and
stale comments. Assertions about the actual contracts were retained. Runtime
assertion parsing now restores trailing-lambda parser state on exceptions, like
the inline forms already do.

Added isolated tests through AST validation, semantic analysis, IR generation,
optimization, and interpreter execution. They cover condition evaluation count,
lazy messages, computed failure messages, Bool/String requirements, rejected old
forms, compile-time assertions, and pre/postconditions. Added real LLVM execution
of the stateful condition and lazy-message example, optimized and unoptimized.
The LLVM harness can execute already-emitted IR so stage tests need no stdlib.
These tests do not replace the full compiler/import integration suite.

Validation:

```sh
./gradlew :compiler:desktopTest --offline --console=plain \
  --tests '*AssertionSemanticsTest' --tests '*AssertionNativeExecTest' \
  --tests '*ContractScopeBodyTest' --tests '*RepeatConstructionTest' \
  --tests '*GenericDelimiterTest'
```

Result: **42 passed, 0 failed, 0 skipped**. Native tests executed with LLVM `lli`.
The broader run resolved six stale frontend failures: **14 remain** from the
earlier 20. Standard-library files with parse failures fell from **26 to 11**;
the entire library still does not load. The full 2,204-test baseline has not been
rerun because library loading still blocks its interpretation.

Historical library parse blockers observed after step 004:

| Source | First blocking construct |
|---|---|
| `algorithm/search.az` | `arr.size>..0` range spelling |
| `algorithm/sort.az` | call used as a condition in a `when` expression |
| `allocator/allocator.az` | single-statement destructor `scope` body |
| `char.az` | single-statement contract clause and `scope` body |
| `container/array.az` | single-statement `unsafe` body |
| `container/queue.az` | loop variable initialized with `=` |
| `core.az` | function named with the reserved `then` token |
| `filesystem.az` | newline before `then` in returned if-expression |
| `os.az` | explicit conditional bindings using `then` |
| `quantum.az` | single-statement contract clause and `scope` body |
| `serializer.az` | newline after an assertion's `panic` introducer |

## 005 — lifecycle and multiline body repair completed

Constructors, destructors, and properties now share a body parser accepting a
block or one statement after `scope`. Function `scope` bodies also permit a
newline before the body. Existing receiver modes, contract rewriting, and
rejection of receivers moved inside bodies are retained.

Contract clauses accept one statement or a block, including a newline before
the block. Duplicate `in`/`out` clauses remain errors; named results still use
the braced form and single-statement postconditions use `it`. Runtime and inline
assertion messages may continue after a newline following `panic`.

Fixed newline handling before `then` in statement, expression, and returned
conditionals. Explicit bindings may reuse a named condition when it should
be evaluated once.
Single-statement `unsafe` bodies lower to the existing explicit unsafe scope;
the following statement remains outside that boundary.

Corrected malformed library bodies: removed the premature opening brace before
the contracts of `groverIterations`, removed two extra allocator property closing
braces, and removed `return` from an allocator expression body. Pre-existing user
edits remain preserved. Updated the related DIPs to describe the implemented
forms and existing assertion message requirements.

Evidence:

- Declaration regressions verify repaired body and condition parsing.
- Interpreter tests execute optimized and unoptimized IR, covering successful
  and failing single-statement pre/postconditions, constructor contracts and
  property access, both conditional branches, and condition evaluation count.
- Semantic checks reject an unsafe call immediately outside a single-statement
  unsafe body. Native LLVM executes the contract, constructor/property, shared
  conditional, and lazy assertion programs in both optimization modes.

```sh
./gradlew :compiler:desktopTest --offline --console=plain \
  --tests '*AssertionSemanticsTest' --tests '*AssertionNativeExecTest' \
  --tests '*ContractScopeBodyTest' --tests '*RepeatConstructionTest' \
  --tests '*GenericDelimiterTest' --tests '*DeclarationBodyTest' \
  --tests '*StdlibParseDiagnosticTest'
```

Result: **59 passed, 1 failed, 0 skipped**; the failure is the aggregate library
parse check. The broader frontend plus semantic/native/library run had **363
tests, 15 failures**: the same 14 previously observed frontend failures and that
library check. After the last allocator source correction, rerunning the library
check confirmed **five files** still fail (down from 11 after step 004 and 26 at
the initial baseline). These are first errors per file, not necessarily all errors:

| Source | First remaining blocker | Follow-up |
|---|---|---|
| `algorithm/search.az` | `arr.size>..0` unsupported range spelling | 006; validate empty, singleton, and descending boundaries before migration |
| `algorithm/sort.az` | guard `when` misclassifies a call condition as payload destructuring | 008–009; distinguish guards from patterns without relaxing pattern safety |
| `container/queue.az` | loop uses `=` instead of `in` | 006–007; inspect remaining scoped imports too |
| `core.az` | comparison member named `then`, which is a reserved token | 006; reconcile member naming with operator DIP and callers |
| `quantum.az` | explicit `result.h(j)` and `result.x(j)` calls | 008–009; inspect intended evaluation and written call order |

Historical inspection before the latest instruction found that the language used `reverse for` with
`..`/`..<`, while the search source alone uses `>..`. The operator DIP explicitly
proposes comparison chaining named `then`, so that collision is a design conflict
to resolve from naming rules and member-call behavior. The 2026-09-09 instruction
below supersedes the initial reverse-loop migration idea.

Full stdlib loading, the full compiler suite, WASM parity, lifecycle destruction
ordering, and Engine/Studio integration remain open. Parser body support is not
a claim that every operation inside the library is semantically implemented.

## 006 — descending ranges and member-name conflicts completed

The user's latest instruction explicitly removes the reverse keyword and loop
modifier in favor of `>..`. Implemented `for i in size>..0`, which visits
`size - 1` through `0`. Equal or inverted bounds are empty; `5>..0 by 2`
visits `4, 2, 0`. `reverse` now lexes as an ordinary identifier and remains usable
as a library function or local name. The old modifier and old operator declaration
are rejected, including when a variable named `reverse` exists.

The range AST carries direction in source-bound order, and the IR carries the
same boundary contract. The three backends now evaluate bounds and step once,
in source order; reject nonpositive steps even on empty ranges; and use widened
progression so iteration terminates correctly at Int limits. LLVM no longer
reevaluates the bound inside the loop. Compile-time loop expansion, argument
expansion, and generic constraints share descending constant progression.

The header regressions exposed passes that ignored the existing `by` expression.
Repaired step type checking, usage/effect/access analysis, relevant transformations,
and optimizer reference collection/folding. Functions referenced only from a step
are retained. Header state changes do not change an already-started range. Tests
cover ordinary and labeled continue, ordinal indices, and returned-loop break/else.

Kept `.then()` as the comparison API. `then` is allowed in explicit member-name
positions and still rejected as an unqualified local/function name. Its runtime
branch meaning is unchanged. The real comparison implementation executes in
interpreter, LLVM, and WASM with and without optimization.

Migrated the existing loop/operator tests without changing their intended output;
added the descending operator declaration beside the ascending one in the library;
corrected the queue's two `for … =` headers. The original search `size>..0` source
now parses without a workaround. Updated the controlling DIPs and superseded the
contradictory older upgrade-plan direction proposal.

Tooling source changes:

- AZLS no longer classifies `reverse` as a keyword.
- Studio's vendored lexer/parser/AST and source printer preserve `>..`. Node
  conversion uses its existing source fallback for unsupported structured range
  forms, preserving the operator instead of silently turning it into an ascending
  visual node. Full compiler-API consolidation remains step 131.
- The IDE lexer recognizes `>..` as one token, treats `reverse` as an identifier,
  and offers a descending-range snippet. Its range/member lexer probe passed
  against locally installed IntelliJ platform libraries.
- The playground's CodeMirror and Prism source definitions recognize `>..` and
  no longer reserve `reverse`; local Node checks passed. Existing generated and
  published AZLS binaries/snapshots were preserved; rebuilding installed artifacts
  remains an integration/release gate after the library loads.
- The Engine math range declaration uses the new operator spelling; this does
  not claim that generic vector iteration or the Engine build is complete.

Validation:

```sh
./gradlew :compiler:desktopTest --offline --console=plain \
  --tests 'org.azora.lang.frontend.*' \
  --tests '*RangeBoundaryTest' --tests '*ThenMemberTest' \
  --tests '*RangeAndMemberExecTest' \
  --tests '*AssertionSemanticsTest' --tests '*AssertionNativeExecTest' \
  --tests '*StdlibParseDiagnosticTest'
```

**376 tests: 361 passed, 15 failed, 0 skipped.** The failure names are unchanged
from step 005: 14 frontend failures plus the aggregate library parse diagnostic.
All 13 new range/member cases pass. Combined with the prior focused cases this
gives 72 passing focused checks. LLVM `lli` and Node/WASM actually executed,
including expected invalid-step failures, optimized and unoptimized.

Studio and IDE-plugin Gradle builds could not configure offline: the cached
artifacts lack Kotlin DSL plugin 6.5.2 and Kotlin JVM plugin 2.1.10 respectively.
Isolated Kotlin compilation of Studio's frontend/printer and the IDE lexer uses
real local dependencies, not stubs; this is not full application qualification.
Both committed Studio range round-trip tests passed under the local Kotlin/JUnit
runtime, including rejection of the former modifier. Prism and CodeMirror token
checks also verify that `..<` and `...` retain their complete token boundaries.

First library parse errors at the end of step 006 (superseded below):

| Source | Remaining blocker |
|---|---|
| `algorithm/sort.az` | call condition in guard `when` treated as payload destructuring |
| `container/queue.az` | stale `import std.{…}` selector spelling |
| `quantum.az` | explicit `result.h(j)` and `result.x(j)` calls |

Current executable numeric ranges require Int bounds. Other widths, general
user-defined iterators, and general compile-time membership assertions remain
separate completion work. Constraint-range membership was verified through the
constraint evaluator; inline assertions currently do not evaluate general `in`
expressions, including ascending ones.

## 007 — grammar repairs verified; import scope remains open

Updated five stale import fixtures from `path.{…}` to `path::{…}` without changing
path/selector expectations. Corrected receiver fixtures to use explicit prefix
receivers and compare genuinely distinct shorthand/long forms. The removed
body-receiver diagnostic now recommends the current `func &.name(...)` spelling.
Import groups now require a comma or physical newline between members, including
nested groups and multiline comments. The module DIP no longer advertises square
selector groups. The initial focused run reproduced the separator and diagnostic
bugs: **50 tests, 2 failed** before the fixes.

A verified scope defect remains: `Parser.parseStmt` appends block imports to
`pendingTopLevels`, replacing the local statement with an empty scope. The
resolver therefore sees a file-wide import. `TestScopedImportTest` even asserts
that hoisted shape in two cases; passing those tests does not prove correctness.
Its remaining obsolete bracket fixture was not simply migrated to make this
incorrect contract pass. The next import repair must preserve lexical scope in
both the AST and name resolution, with tests for sibling tests/functions,
shadowing, imported types, and nested scopes. This brings the necessary portion
of 014 forward; 007 is deliberately not marked complete.

A separate receiver discrepancy also needs reconciliation: FUNCTIONS_DIP §5.2
requires an explicit type for owned receivers (`Self.member`), while the parser
and a shorthand fixture still accept `func .member`. No owned-receiver removal
or lifecycle change is claimed in this iteration.

## 009 — library unblocking and cross-stage guard repairs

The subjectless `when` form and `when true` now treat their arms as Boolean guards
rather than classifying every call with arguments as payload destructuring.
They require `else`; previously the last guard was discarded and its value
returned even when false. Guard order, comma-arm short circuiting, fallback, and
lazy branch values are exercised through AST validation, semantic analysis,
IR generation, optional optimization, and all three execution backends.

Those tests exposed and repaired two underlying defects:

- `TypeResolver` resolved an if-expression condition without requiring Bool.
  It now enforces the same requirement as an if-statement, including lowered guards.
- WASM implemented logical AND/OR using eager integer instructions. It now emits
  conditional expressions; bitwise `&` and `|` retain their integer instructions.
  Tests cover both taken/skipped RHS paths with stateful calls in both optimization modes.

Before the guard repair, **4 of 5 new guard tests failed**. After the parser
repair, tests caught the missing Bool check and WASM's eager evaluation rather
than weakening their assertions. All five now pass.

Library migrations preserve the intended operations:

- Queue's local selector uses `std::{…}` (lexical import scope remains unresolved).
- Sorting's obsolete `if condition -> return` uses `then return`.
- Quantum's final loop uses `in` rather than `=`.
- Grover invokes `result.h(j)` and `result.x(j)` as separate statements in
  a braced loop body. Both calls retain their written order and Int arguments.
  The sequence harness executes the interpreter, LLVM, and WASM forms.

The new sequence harness initially omitted the primitive range bridge required
by the language, and failed at IR generation. It now supplies the same explicit
bridge used by the existing isolated range harness; no production range bypass
was added to satisfy the test.

Final validation:

```sh
./gradlew :compiler:desktopTest --offline --console=plain \
  --tests 'org.azora.lang.frontend.*' \
  --tests '*RangeBoundaryTest' --tests '*ThenMemberTest' \
  --tests '*RangeAndMemberExecTest' \
  --tests '*AssertionSemanticsTest' --tests '*AssertionNativeExecTest' \
  --tests '*WhenGuardSemanticsTest' --tests '*WhenGuardExecTest' \
  --tests '*MemberSequenceSemanticsTest' --tests '*StdlibParseDiagnosticTest'
```

Result: **384 tests, 377 passed, 7 failed, 0 skipped**, versus step 006's
376 tests / 15 failures. All eight added tests pass; eight old failures are
resolved and no new failure remains. Remaining failures: five increment AST/
expression fixtures, one obsolete scoped-import fixture, and the aggregate
stdlib parse gate. Logs: `/tmp/azora-step007-final.log` and
`/tmp/azora-step007-results.json`. `git diff --check` passes.

Only **one library source** now fails parsing: `algorithm/sort.az:246`,
`result[i] <> result[j]`. GTC §23.2 requires evaluating both mutable locations
once before moving either value, alias/borrow checks, and no cloning or extra
destruction. Implement the exchange through typed locations and all backends;
do not replace it with ordinary assignments that can duplicate move-only owners.
This is the next library gate dependency, not a completed swap implementation.
Full disk/bundled loading and the full compiler suite remain blocked and have
not been claimed as passing. Studio's vendored frontend and published tooling
artifacts were not rebuilt in this iteration.

Other match work remains under 042: subject-based when expressions currently
reuse the scrutinee across conditions and assume an omitted final test is
exhaustive without proof. The guard-only repair does not certify those forms.

## 007 follow-up — full-compiler scope leak confirmed

A controlled desktop probe used `Compiler` with a test-owned minimal stdlib
manifest (the bundled version), an empty `std.core` module, and an additional
`helpers` library containing `func answer(): Int { return 42 }`. This isolates
import resolution from the unrelated sorting parser blocker without changing
production loading rules.

| User source | Required result | Observed result |
|---|---|---|
| `main` calls `answer()` without an import | Reject | Rejected |
| `main` imports `helpers` and calls `answer()` | Accept | Accepted |
| `allowed` imports `helpers`; unrelated `main` calls `answer()` | Reject | **Accepted: leak** |
| A test imports `helpers`; unrelated `main` calls `answer()` | Reject | **Accepted: leak** |

The exact probe and output are archived at
`/tmp/azora-step008-ScopedImportProbeTest.kt` and
`/tmp/azora-step008-import-probe.log`. The investigation probe was removed from
the regression suite: a test that merely reports acceptance is not a passing
isolation regression. It restores the previous stdlib override and invalidates
the cache in `finally`; production strict loading remains unchanged.

Removed the unused semantic `ImportResolver` class and its pipeline invocation.
It stored a module map but never read it, never received a Program, and always
returned an empty error list. No compiler, AZLS, Engine, or Studio caller used
its registration API. Pipeline documentation now accurately identifies
`Compiler`/`StdlibInjector` as the current import implementation and documents
the unresolved lexical-scope defect. Removing that placeholder is not a scope fix.

Required resolution work, before closing 007:

1. Preserve lexical import declarations and their enclosing scope in the AST;
   do not append block imports to `pendingTopLevels`.
2. Separate declaration identity `(module, namespace, declaration)` from the
   source spelling visible in each lexical scope. The current single map of
   imported short names cannot represent two scopes importing different modules'
   same-named declarations.
3. Resolve values, functions, types, extensions, annotations, and macros against
   that scope's bindings; local declarations and parameters retain shadowing.
4. Carry resolved identities through injection, macro/CTCE rewriting, semantics,
   and lowering. Injecting a dependency must not grant source-level access to it.
5. Turn the two failing probe scenarios into negative compiler regressions and
   add sibling-test, nested-scope, same-name-provider, and imported-type cases.
   Correct `TestScopedImportTest`'s hoisting assertions as part of that repair.

This brings 014's binding/identity foundation forward. A parser-only change
would leave the injection leak in place, so no such partial support is claimed.
Independent work on 008 proceeded while 007 remains open.

## 008 — increment fixtures and execution repaired

Five frontend failures assumed local increments were assignments and that
`counts[i++]` had to be rejected because the backends lacked a value-producing
node. The current AST, semantic resolver, and all three IR backends already
have variable `IncDec` support. Corrected those fixtures to retain postfix/prefix
metadata and distinguish increments from compound-assignment overload dispatch.
Added an explicit prefix/postfix parser test.

Execution tests exposed real defects rather than merely blessing the existing AST:

- WASM postfix increments/decrements emitted a read after the write and therefore
  returned the new value. Lowering now saves the old value and the updated value,
  writes once, and returns the correct one for the written form.
- WASM also unconditionally declared the target as a local. Reads and writes now
  use the existing local/global/boxed-storage rules; assignment and increment
  share the storage-write helper. Thread-local global mutation is executed in
  the tests; cross-thread TLS behavior and reactive notification are not certified.
- Constant propagation attempted to replace an increment target with a literal,
  then cast it back to `IrExpr.Var`, crashing optimized compilation. Targets now
  remain locations. Expression-nested increments invalidate old constant facts,
  including those in loop headers, call arguments, branches, and nested scopes.
  Assigned-name collection also recognizes these mutations at control-flow joins.

Four new semantic/backend tests initially produced **three failures** (two
optimizer crashes and the WASM postfix failure). They now pass optimized and
unoptimized, covering old/new values, both directions, Int and Double examples,
left-to-right call arguments, short-circuit skipping, conditional branches,
while-header increments, scope/branch exits, and thread-local global storage.
Immutable/non-numeric targets are rejected. During test development, corrected
a reserved `scoped` variable name and changed an invalid unrestricted global to
`threadlocal var`; no language restrictions were weakened for the tests.

The non-variable statement tests still describe the existing assignment
lowering. They are explicitly not proof of one-time evaluation for complex
receivers, indices, or pointers. General lvalue increments, numeric overflow,
reactive notification, and escaping-capture mutation remain under 032–034/041/056.

Final broad command is the step-007 command plus `--tests '*IncrementSemanticsTest'`
and `--tests '*IncrementExecTest'`. Result: **389 tests, 387 passed, 2 failed,
0 skipped**. All five added tests pass; the five stale increment failures are
resolved. The remaining failures are `TestScopedImportTest.aTestMayOpenWithItsOwnImports`
and the aggregate stdlib parse gate (`algorithm/sort.az:246`, `<>`). Logs:
`/tmp/azora-step008-final.log`, `/tmp/azora-step008-results.json`.
`git diff --check` passes. The full suite remains blocked by strict library
loading; the isolated backend checks do not replace it.

## Finding to retain: primitive mutable-borrow lowering

An initial assertion-condition test exposed this separate reproducer:

```azora
func increment(value: Int!) { value = value + 1 }
func main() {
    var count = 0
    increment(count)
    assert count == 1 panic "the caller must observe the write"
}
```

Through `SemanticPipeline` → `IrGenerator`, the parameter is emitted as an
ordinary value parameter (`i32 %arg.value`), so the caller's value does not change
in LLVM execution. The initial report also implicated the interpreter; the
2026-09-10 full baseline corrects that: all `ParamModifiersTest` interpreter cases
pass, including caller mutation. Its RefCell/copy-back path is separate from
native address passing. LLVM and WASM do not consume `IrFunction.refParams`;
backend parity and alias-preserving borrow lowering remain unverified.
No borrow-lowering repair is claimed here; follow up under 032–034 and 053–054,
including a full-compiler reproducer once the library loads.

The assertion evaluation test uses an explicit Counter pack to test its own
evaluation-order contract independently. This does not waive the primitive
borrow failure. WASM assertion-message loss also remains open under 043/056.


## 2026-09-10 — Step 009 complete; first useful full baseline under 010

The final parse blocker, `algorithm/sort.az`'s `<>`, is now a dedicated exchange
operation through the lexer, statement AST, semantic analysis, typed IR,
optimizer and all three execution backends. It is not desugared into ordinary
assignments, which could introduce ownership copies/drops or repeat location
evaluation. Relevant AST/IR walkers, macro/substitution paths, symbol validation,
stdlib name discovery and effect analysis now account for both operands.

The supported storage forms are mutable ordinary bindings, stored pack fields
and built-in array elements with the same static type. Each backend captures the
left location, then the right, before reading and writing the values. Array
indices are checked before writes. Identical complete locations preserve their
value after both expressions have run. Optimizer constant propagation keeps
locations intact and invalidates exchanged roots through scopes and control flow.
Exchange is conservatively effectful and is not compile-time evaluated yet.

Safety restrictions are explicit in GTC §23.2. Shared/conflicting borrows,
immutable storage, type mismatches and known owner/projection overlap are
rejected. Calls, overloaded index arithmetic, raw dereferences, borrowed binding
exchange, lazy/reactive storage and custom accessors require further location
loan work. LLVM rejects physical/resolved generic field type mismatches; WASM
rejects aggregate elements wider than its current four-byte slot layout.
Ordinary Double variable exchange works. These tests do not establish general
move-only destruction, pointer provenance, reactive notifications or all capture
and borrow forms. Packages 032–034/041/053–056 remain open.

Verification:

- **19 focused compiler tests passed, 0 skipped**, using
  `./gradlew :compiler:desktopTest --offline --console=plain --tests '*ExchangeSemanticsTest' --tests '*ExchangeExecTest' --tests '*StdlibResolutionTest' --tests '*StdlibParseDiagnosticTest'`.
  Log: `/tmp/azora-step009-public.log`. Eight exchange tests cover raw/optimized
  execution on interpreter/LLVM/WASM, variable and field/array storage, handle
  preservation, mixed/identical locations, side-effect order, scope/loop
  invalidation, bounds traps and semantic rejection. A simple exchange also runs
  through the public `Compiler` API and real stdlib injection on all backends.
- All **49 library source files** parse. Strict production loading succeeds for
  both the explicit disk root and the actual bundled fallback, preserving the
  `Numbers` compile-time environment. Version/manifest/root-switch tests pass.
  The old bundled test was misleading: clearing the explicit override still
  found the checkout. Resolution now accepts an internal explicit search path;
  an empty path tests fallback and verifies its origin before strict parsing.
- Studio preserves exchange statements when parsing/printing variables, fields
  and indices. **Four Studio range/exchange tests passed** in isolated compilation
  of its real vendored frontend, helper files and source printer. Its older
  parser still rejects `i++` inside an index; that separate expression-parity gap
  is recorded rather than obscured by the round-trip fixture. Full Studio Gradle
  qualification remains blocked by the previously recorded offline dependency.
  Log: `/tmp/azora-step009-studio.log`.
- The actual IDE lexer compiles against installed IDE platform jars and passes
  exchange/range/comparison checks; its checked-in lexer regression now covers
  atomic `<>` and `<=>`. This is not a full plugin build.
  Log: `/tmp/azora-step009-ide.log`.
- Playground CodeMirror and Prism recognize atomic exchange, spaceship and
  descending-range operators, including CodeMirror interpolation. The durable
  command `npm run test:lexer` passes. Published AZLS artifacts were not rebuilt.

Test development exposed an unrelated repeated-construction issue in the
isolated pipeline (`.(0) * 3` became array-literal multiplication); the storage
regressions use explicit `Array(0, 0, 0)` so they measure exchange. Native borrow
limitations and the interpreter correction are recorded above. No tests were
skipped or weakened to certify unsupported ownership semantics.

### Full compiler baseline

Command: `./gradlew :compiler:desktopTest --offline --console=plain`.
Result: **2,271 tests, 2,001 passed, 270 failed, 0 skipped**, in 3m39s.
This snapshot precedes the additional strict disk-loader and public-pipeline
exchange tests; their later focused result is reported separately above.
Log: `/tmp/azora-step009-full.log`; original XML snapshot:
`/tmp/azora-step009-full-xml`. A durable per-suite/per-failure inventory, with
explicitly truncated long diagnostic excerpts, is checked in as
[ECOSYSTEM_BASELINE_2026_09_10.json](ECOSYSTEM_BASELINE_2026_09_10.json).

| Test source area | Tests | Passed | Failed |
| --- | ---: | ---: | ---: |
| Frontend | 356 | 355 | 1 |
| Semantic | 112 | 103 | 9 |
| IR | 6 | 6 | 0 |
| Backend | 20 | 20 | 0 |
| Codegen / full compiler / execution | 1,752 | 1,492 | 260 |
| Diagnostics | 16 | 16 | 0 |
| Library resolution | 9 | 9 | 0 |

These are test-directory groupings, not proof that every compiler stage is
complete. Initial diagnostic groups are: 79 unresolved `arr` macros; 28 removed
assertion-message syntax cases; 113 other compilation failures; 7 expected
rejections that were accepted; 9 parser/runtime exceptions; 34 other assertions
or execution failures. Several failures in one group can share a cause, while a
single test can hide additional defects. Some negative tests fail before their
intended invariant is reached (for example signature access is masked by `arr`).

The frontend's remaining failure is the scoped-import fixture already tracked
under 007. Semantic test failures include old decorator binding/impl forms and
an old operator declaration; each needs comparison with current design before
fixture changes. Collection/serialization injection, numeric argument typing,
namespace visibility, metaprogramming constraints and backend execution have
independent work remaining. Sorting execution is still masked by `arr` failures;
loading its source does not certify sorting's runtime behavior.

**Next:** continue 010 triage with the 28 assertion fixtures and 79 `arr` failures,
reviewing intended invariants before changes. Keep 007/014's lexical imports and
identity redesign open. The full baseline is now reproducible, but 010 is not
marked complete until independent causes and cascades are sufficiently resolved.

## 2026-09-14 — Contextual array literals and collection migration (010.C1–C2)

The user's collection decision supersedes older macro and List-default designs:
`[]` supplies sequence syntax for arrays/lists/sets, `[:]` is the empty map shape,
and a non-empty sequence without a target defaults to `Array`. Standard `arr`,
`vec`, `map` and `set` construction macros are absent; user-defined macros remain
supported. DIPs are design intent: GTC §8.4/§20 now record the Array default, and
§8 distinguishes implemented array behavior from the pending factory protocol.

### Completed changes

- Bracket parsing accepts multiline contents and trailing commas, distinguishes
  keyed entries from elements, and recognizes `[:]`. Literal member/index access
  no longer conflicts with removed receiver-list call syntax or capture headers.
  The removed `![...]` set form has a targeted diagnostic. Studio's vendored
  parser and source-printer round-trip tests follow the same literal shapes.
- Expected array types survive AST copies and propagate to elements, nested
  literals, global/local bindings, assignments, function arguments/returns, conditional
  branches and pack-construction fields. Context-free non-empty sequences infer
  Array; empty sequences require an element context. Fixed nested lengths,
  incompatible primitive elements and out-of-range integer constants are checked.
  Unsupported boxing/spec targets are not silently admitted as array storage.
- IR lowering retains contextual numeric widths, including literal constants
  beyond Int's range, and converts indexed-assignment literals consistently.
  WASM array allocation, element addressing, loads, stores and exchange use the
  resolved element width instead of an unconditional four-byte i32 slot.
- The Int<7> regression exposed finite named-width sets being used as numeric
  predicates. Classification now recognizes all IrType.Integer values in the
  affected semantic/IR/native paths. Promotion uses actual integer bit widths;
  LLVM integer casts compare bits, avoiding missing sext/trunc between Int<7>
  and Byte. Named widths retain their canonical source names when reconstructed
  for type functions; unnamed widths retain structured Int<N>/UInt<N> arguments.
  Full-suite comparison caught and verified this distinction in generic promote
  resolution. This does not certify every arbitrary-width arithmetic/ABI path.
- Migrated 110 obsolete arr invocations in 31 test files, plus stale map uses and
  removed arrayOf fixtures. The macro suite defines its own `batch` macro and
  checks that standard collection macros are unavailable. Two array-property
  tests now explicitly import their declared std extensions. No compiler-only
  property shortcuts or blanket skips were added to satisfy those fixtures.
- Migrated the remaining native map macros and retired ![...] set fixtures to
  contextual Map/MutableMap and Set/MutableSet forms. Their deduplication,
  insertion/removal, string equality, iteration and global-initialization
  assertions remain intact; failures still expose unfinished collection factories.
  Six of these set checks passed with the old intrinsic punctuation and now fail
  under the required contextual syntax. This is a recorded implementation gap,
  not evidence that removing the old spelling completes its replacement.

### Validation and remaining work

Focused command: `./gradlew :compiler:desktopTest --offline --tests
'*ContextualArrayLiteral*' --tests '*CollectionLiteralSyntaxTest' --tests
'*MacroTest' --tests '*Exchange*' --tests '*ArrayTest' --tests '*ArrayStdlib*'
--tests '*WideInt*' --tests '*Numeric*'`.
Result: **70 tests, 70 passed, 0 skipped**. Log:
`/tmp/azora-collections-width-final.log`. Public-Compiler regressions execute the
same array program with and without optimization on interpreter, LLVM and WASM.
They cover Byte/Double/Long/Int<7>, same-byte-width casts, nested fixed-size arrays,
empty returns, context propagation and typed indexed writes/exchange.

**Six Studio tests passed** using isolated compilation of the real vendored
frontend, helpers and source printer: range, exchange and collection round trips.
Log: `/tmp/azora-collections-studio-final.log`. Full Studio/IDE Gradle qualification
remains subject to the previously recorded offline dependency limitations.

List/Set/Map/custom target-owned factory registration and allocation are **not
implemented**. Those contextual literals receive a diagnostic rather than a
mismatched physical representation. Next: 010.C3 canonical target/factory
resolution and generic substitution, then 010.C4 actual standard implementation
construction, then 010.C5 backend/ownership/tooling qualification. The existing
ordinary listOf/setOf/mapOf functions are not proof of those requirements: they
rely on generic/spec layout and module dependency handling that remain open.

A migrated global Set fixture revealed that non-lambda top-level initializers
were seeded but never semantically resolved. This allowed a declared Set to
receive array storage and print the wrong runtime result. Global initializers
now run expression resolution and declared-type validation, with callable context
preserved for lambdas. New negative tests cover invalid global element ranges,
fixed lengths, heterogeneous/untyped-empty literals, unsupported targets and a
scalar type mismatch. A valid global Byte array executes on all three backends.
The global Set fixture now fails compilation instead of emitting incorrect code.
Focused global/type-function/native validation passed all five contextual-array
tests; its 21 remaining failures are the existing type-function constraint and
native collection/aggregate cases. Log: `/tmp/azora-collections-globals.log`.

Remaining baseline failures now expose actual sorting index-out-of-bounds errors
and removed Array::fill fixtures that were previously hidden by arr failures.
Lexical imports (007/014), generic identity, complete ownership/boxing, wide WASM
pack fields, arbitrary-width native layout and Engine integration remain open.

### Final compiler baseline for this change

Command: `./gradlew :compiler:desktopTest --offline --console=plain`.
Result: **2,280 tests, 2,075 passed, 205 failed, 0 skipped**.
Log: `/tmp/azora-collections-full-final.log`; XML snapshot:
`/tmp/azora-collections-full-final-xml`. The durable
[2026-09-14 inventory](ECOSYSTEM_BASELINE_2026_09_14.json) records suite totals,
individual failures and the comparison with September 10.

Compared with the earlier 270-failure baseline, **62 previously failing test
identities now pass**, 9 old failing identities were replaced/renamed, and
6 newly failing identities remain. Renamed tests are not counted as fixed bugs.
The contextual Set migration retains six formerly passing old-syntax checks as
acceptance tests for real set factories; their behavior assertions are unchanged.
No tests were skipped to obtain this result. Full-suite failures still mean the
compiler and ecosystem are not release-qualified.

The final full run also passes all **70 tests** selected by the focused array,
collection syntax, macro, exchange and numeric suite patterns, including the new
global-initializer regression. Studio's six round-trip tests remain separately
verified; no full Studio/Engine build claim is made.

## 2026-09-15 — Collection factory prerequisites (010.C3.1–C3.2)

The implementation audit found independent blockers beneath contextual factory
syntax. The work below repairs those foundations; it does not mark List/Set/Map
literal construction complete or add a parser-only literal-factory declaration.

### Selected declarations retain their implementations

`import std.container.list::ArrayList` injected the pack but omitted its impls,
while a whole-module import included them. Declaration selection resolved the
full path, but implementation reachability only recognized module/folder paths.
The reachability pass now resolves a selected declaration's owning module. This
change affects the dependency closure, not which unrelated names are imported.

Three regressions cover a selected type's own methods, a selected factory's
result methods, and rejection of unrelated declarations/other-module extensions.
They execute through the public compiler and interpreter. Full lexical import
scope and canonical identity work under 007/014 remains open.

### Collection storage identity

Removed six implicit compatibility shortcuts between intrinsic Array/Map/Set
storage and named List/MutableList/Map/MutableMap/Set/MutableSet declarations.
Those checks only examined the short type name and performed no construction or
conversion. IR lowering also stopped overriding explicit named collection types
with the initializer's unrelated storage type. Named expected types reconstructed
for contexts now retain generic and const arguments.

Regressions use independent user-defined packs named List/MutableList/Map rather
than relying on standard-library injection. They reject storage mismatches in
bindings, assignments, arguments and returns, and verify explicit pack
construction still works. Broader generic nominal identity is still open.

### Real ArrayList growth

The default list has capacity zero; grow previously computed zero times two and
then insertion wrote to an empty buffer. Growth now allocates an initial capacity
of eight, matching clear's reusable capacity, and subsequent growth doubles it.
Capacity checks reject negative capacity and Int overflow before multiplication.
The existing mutable-list test now passes. A new interpreter regression covers
first insertion, multiple growth boundaries, order preservation, insertion at the
front, clear and reuse, with and without optimization.

The native counterpart remains enabled as acceptance work. Actual execution found:

- LLVM emits a generic get result as i8* and then compares it directly with an
  integer literal. The owner type arguments and physical return ABI must remain
  distinct and be converted explicitly. The module is rejected by lli.
- WASM emits undefined __allocBuffer and __purge calls and an undefined __null
  local. Buffer allocation and lifetime lowering are not implemented there.
- Source inspection also confirms LLVM's raw-pointer purge currently emits only
  an advisory comment. Passing scalar behavior alone would not qualify ownership
  or reclamation. Some repeated-constructor allocation expressions also lower to
  unsupported pointer multiplication; those constructors require separate repair.

No native test is disabled, and no no-op purge was added to make a test pass.
The next work is generic method/constructor type preservation and real allocation
and deallocation across targets, followed by the target-owned factory protocol.

Focused command: `./gradlew :compiler:desktopTest --offline --tests
'*CollectionTargetSafetyTest' --tests '*SelectedImportImplementationTest' --tests
'*ListConstructionTest' --tests '*ContextualArrayLiteral*' --tests
'*TypeFunctionTest.genericFunctionCallUsesTypePropertyForItsResult'`.
Result: **14 passed, 0 failed, 0 skipped**. Log:
`/tmp/azora-collection-target-safety.log`. Earlier native reproducers:
`/tmp/azora-list-selected-imports.log` (6 tests, 4 passed, 2 failed).

### Full compiler validation

`./gradlew :compiler:desktopTest --offline --console=plain` completed with
**2,290 tests: 2,084 passed, 206 failed, 0 skipped**.
Log: `/tmp/azora-collection-foundations-full.log`; raw XML:
`/tmp/azora-collection-foundations-full-xml`. The
[durable inventory](ECOSYSTEM_BASELINE_COLLECTION_FOUNDATIONS_2026_09_15.json) compares test identities
with the preceding 2,280-test/205-failure snapshot. It separates formerly passing
regressions from newly added native acceptance failures; it does not count a
new failing test as a previously supported behavior regression.

This run fixes the existing `CollectionCtorTest.mutable_list_pack_exists` failure.
**No previously passing test now fails.** The two new failures are the enabled
LLVM/WASM list-execution regressions documented above; all eight other new tests
pass. No prior tests were removed or skipped.


## 010.C3.3 — generic constructor and direct method signatures (partial)

Constructor results now retain explicit owner type/const arguments. Source
parameter and return type references are retained on member symbols, and a shared
substitution helper supplies call-site types to semantic analysis and IR lowering.
The registered function retains its physical signature. LLVM calls use that
physical return type and explicitly convert the result into the call-site type.
This fixes `ArrayList<Int>().get(...)` returning a pointer-shaped value that was
previously compared directly with an integer.

Constructor checking now validates fixed and every variadic argument against the
instantiated parameter type. Variadic construction always packs its tail, including
zero and one element, and uses the callee's physical element slots. This avoids
passing `Array<Int>`'s four-byte slots to a generic constructor reading erased
pointer-sized slots. Numeric literals take their logical parameter type before
packing or boxing; direct and named method arguments follow the same rule.

New tests exercise declared generic constructors, mutable methods and results for
Int, Double and String, rejection of mismatched constructor/method arguments and
results, and all positions in a variadic tail. The native regression exposed a
separate missing literal conversion: passing 2.5 to a Double member had boxed its
Float bits and then read them as Double. Method argument lowering now preserves
the expected representation before crossing the erased boundary.

The list lifecycle test additionally checks one-element and multi-element Int
construction and multi-element Double construction. Interpreter and LLVM execute
these and the existing growth/insert/clear/reuse checks, both optimized and
unoptimized. The WASM acceptance test remains enabled and failing on undefined
`__allocBuffer`, `__purge` and `__null` references. No WASM allocation/lifetime
implementation is claimed here; LLVM raw-pointer purge also remains advisory.
Generic property/index/spec-dispatch paths, aggregate parameters/results and
values wider than the erased slot still require ABI work. List/Set/Map literals
are not yet connected to real target-owned factories.


### Broader regression check and correction

The first full run exposed six LLVM async regressions introduced by looking up
body return types at the call boundary. An async function's public symbol returns
a task handle, while its body returns the payload. The return-type index now
records the spawner ABI for those symbols. All six existing regressions pass in
the follow-up focused run; no async tests were changed.

The new extra-argument test initially required constructor-specific wording even
though the resolver correctly rejected the call with a field-count diagnostic.
Its assertion now checks rejection and the reported extra count. This is a test
expectation correction, not a relaxed acceptance rule.

Focused follow-up command covered `GenericMemberSignature*`, `ListConstruction*`
and the six affected async tests: **14 tests, 13 passed, 1 failed, 0 skipped**.
The sole failure is the documented WASM list acceptance test. Log:
`/tmp/azora-generic-member-async-focused.log`. The earlier array/generic/list run
was **12 tests, 11 passed, 1 failed, 0 skipped**; log:
`/tmp/azora-generic-member-focused.log`.


## 2026-09-19 — Full check of 010.C3.3 and the WASM heap allocator

The generic-signature changes above had only a focused run. A full run before
this work (2,295 tests, 205 failed) showed **no previously passing test now
fails**; `concreteListRunsOnLlvm` changed to passing. All work to this point is
committed as `29f57876` on branch `collection-foundations`.

### Allocator

WASM previously had a bump allocator that never freed memory. It never grew
memory past its initial 16 pages and never checked a request. The IR's raw-pointer
intrinsics had no WASM lowering: `__allocBuffer`, `__purge`, `__deref`,
`__derefAssign` and `__null` referenced undefined names. `__alloc(value)` fell
through to the runtime's `__alloc(size)`, so `alloc^ 5` allocated five bytes and
never stored the value. That was a silent miscompile.

The runtime is now a segregated free-list allocator (`WasmCodegen.ALLOCATOR_RUNTIME`):

- A block has an 8-byte header (size class, live/free tag) and a power-of-two
  payload from 8 bytes to 1 GiB. Payloads are 8-aligned.
- `__alloc` reuses the most recently freed block of the same class, zeroing the
  requested bytes, or takes new memory. When needed it grows memory with `memory.grow`.
  Oversized or negative requests and exhaustion execute `unreachable`, like
  LLVM's abort on allocation failure.
- `__free` accepts null. It traps on a second free, an interior or misaligned
  pointer, and memory outside the heap. The tag check detects misuse; it is not a
  proof against every forged pointer.
- Freed blocks are not coalesced or returned to the host. Power-of-two classes
  trade up to half of each payload for constant-time allocation and free.

Lowering by operand type:

- `alloc .() * n` computes a checked `n × element width` (8 bytes for Long/Double)
  and yields zeroed memory. A Long count is range-checked before narrowing.
- `alloc value` stores the value in a block of its width.
- `alloc array` moves the elements into a buffer of their own instead of aliasing
  the array's storage, so purging it releases exactly that buffer.
- `purge` of a raw (or nullable raw) pointer releases storage only. It does not
  destroy elements, which containers that moved them out rely on. Purging any other
  type is an explicit WASM codegen failure, not a no-op.
- `__null` is address 0; strings and the heap start above it.
- A module that calls through a callable parameter without creating a lambda now
  declares its function table.

### Evidence

- `WasmAllocatorExecTest` (11 tests) runs the emitted runtime directly. It covers
  class-local and most-recent-first reuse, alignment and disjointness for sizes
  0–64, zeroing on reuse, growth from one page, null free, and traps for double
  free, interior/misaligned/foreign pointers, oversized and negative requests
  and exhaustion under a memory maximum. The trap helper requires
  `RuntimeError: unreachable`, so unrelated crashes do not count. Removing zeroing
  and the live-tag check made two of these tests fail; both were restored.
- `RawPointerExecTest` (5 tests) compiles programs through the public compiler.
  Single values (Int, Double), Int and Long buffers, and a purged allocated array
  give identical output on interpreter, LLVM and WASM, optimized and unoptimized.
  A 300,000-element buffer grows WASM memory and is reallocated after purge. A pack
  purge is rejected by the WASM backend.
- Full run: **2,311 tests, 2,106 passed, 205 failed, 0 skipped**. The 16 new tests
  pass; no failure identity changed relative to the preceding run.

`ListConstructionExecTest.concreteListRunsOnWasm` stays enabled and failing. Its
allocation, purge, null and table errors are gone. The remaining errors are not
allocator defects:

1. WASM erases a generic `T` to a four-byte slot. `ArrayList<Double>(1.25, 2.5)`
   stores `f64` into those slots and compares the `i32` result of `get` with `f64`.
   LLVM's eight-byte erased slot holds a double bit for bit. WASM needs boxing or
   eight-byte erased slots. That is the generic representation choice of 021 and
   is not decided here.
2. `ArrayList.hash` reads `self._data[i].hash` although `T` has no `Hash` bound.
   Semantic analysis accepted it and lowered it as a field load (019/022).

### Recorded for follow-up

- LLVM `purge` still emits an advisory comment. LLVM `alloc array` returns the
  array's data pointer (`array + 8`), so implementing `free` there first requires
  the same fresh-buffer ownership as WASM.
- Buffer contents differ by backend: the interpreter fills with null, LLVM uses
  uninitialized `malloc` memory and WASM zeroes. `alloc .() * n` names a default
  construction, so the three need one defined contract.
- A pointer's declared type does not reach the `alloc` operand: `var r: Double^ =
  alloc^ 2.5` infers `Float^`, and `alloc^ [3, 4, 5]` gives the literal an `Int`
  target.
- `Tier3MemoryTest.dropIsAdvisoryNoOp` asserts that reading through a pointer after
  `purge` returns the old value. That contradicts LIFETIMES_ALTERNATIVE_DIP (a
  purged binding is dead). It passes only on the interpreter and needs 008 review
  together with use-after-purge checking.
- Whether pointer purge should also run the pointee's destructor, as
  CUSTOM_ALLOCATORS_DIP's `purgeWith` does, remains open under 037.

```sh
./gradlew :compiler:desktopTest --offline --console=plain \
  --tests '*WasmAllocatorExecTest' --tests '*RawPointerExecTest' \
  --tests '*ListConstructionExecTest'
```


## 2026-09-19 — Eight-byte erased generic slots on WASM (021 decision)

The user chose eight-byte erased generic slots for WASM over boxing wide values.
This matches LLVM, where an erased slot is a pointer-sized `i8*`, and needs no
per-value heap lifetime. The allocator work above is committed as `24b01472`.

### Representation

- `Any`, the IR type of an erased type parameter, is an `i64` value and an
  eight-byte slot in arrays, buffers and pack fields.
- Crossing into or out of `Any` preserves bits rather than converting a number.
  `f64` uses `reinterpret`, `f32` goes through its 32-bit pattern, and 32-bit
  values are extended and wrapped. Casting an erased value uses the same rule.
- Conversions happen where values land: local, global, lazy and reactive
  initializers, assignments, returns, direct and indirect call arguments and
  results, array elements, pack fields, dereference, `if` branches and `when`
  subjects. A mixed `T`/concrete binary operation is computed at the concrete
  type and erased again when its result is `Any`. The WASM backend now records
  each callee's declared parameter and physical return types, as LLVM does.
- Packs previously stored every field with `i32.store` at `index × 4`, so any
  Long, Double or erased field failed to assemble. Each field now sits at an
  offset aligned to its width; a union's size is its widest member. Two-field
  and three-field Int packs keep their previous sizes. Exchange uses the typed
  layout instead of rejecting wide fields.
- `__null` is an eight-byte zero narrowed by its destination. A closure
  environment is an explicit pointer type rather than `Any`.
- Reading a member the layout does not contain is now a codegen error. It
  previously loaded offset 0 (or −4 for a missing field) silently.

### IR corrections exposed on the way

- `Box<Long>(5000000000)` typed its literal by the template field (`Any`) and
  therefore as `Int`. **LLVM silently printed 705032704**; WASM emitted an
  invalid `i32.const`. Construction now types a type-parameter field by the
  call's type argument, as member reads already did (`typeParamIndex`).
- `box.value = 4.25` on a `Box<Double>` stored a `Float` literal. The assignment
  now takes the instantiated field type too.

### Evidence

`ErasedGenericExecTest` (5 tests) runs generic functions returning Int, Double,
Long and String values; `Box<Double/Long/String>` construction, reads and writes;
a Mixed Int/Double/Bool/Long pack; an erased field between concrete ones; and a
Double field exchange. Interpreter, LLVM and WASM agree, optimized and
unoptimized. LLVM is excluded only from the generic-function case (see below).
The 177 existing WASM-executing or WAT-inspecting tests kept every pass/fail status
at each step.

Full run: **2,316 tests, 2,111 passed, 205 failed, 0 skipped**; no failure
identity changed.

### Remaining and recorded

- `concreteListRunsOnWasm` now stops explicitly at
  `no WebAssembly storage for member 'hash' of Any`. `ArrayList.hash` reads
  `self._data[i].hash` although `T` has no `Hash` bound, and the method is kept
  even in release builds. LLVM compiles the same access to a default zero
  (`member .hash … not lowered`), so its list test passes over a silent
  placeholder. Rejecting or dispatching that access belongs to 019/022/044.
- `apply(1.5, { x -> x * 2.0 })` for `func<T> apply(value: T, change: (T) -> T): T`
  infers no type argument, so the call and lambda stay `Any`. The interpreter
  prints 3.0; WASM prints the Float's raw bits (1077936128) and LLVM prints
  `<value>`. Inference with lambda arguments is 023.
- LLVM prints `<value>` for a Long returned from a generic function.
- Passing an `Array<Int>` where `Array<T>` is expected mismatches element
  widths on both native targets: ordinary generics are erased, not specialized.
- Printing an `Any` value on WASM prints its bits; only integers read correctly.
- `T?` (`Nullable(Any)`) is still a four-byte WASM value.

```sh
./gradlew :compiler:desktopTest --offline --console=plain \
  --tests '*ErasedGenericExecTest' --tests '*RawPointerExecTest' \
  --tests '*WasmAllocatorExecTest' --tests '*ListConstructionExecTest'
```


## 2026-09-19 — `ArrayList.hash` parked; WASM spec dispatch; list runs on WASM

The eight-byte slot work is committed as `923047bd`.

### `ArrayList.hash` parked (user decision)

`ArrayList.hash` summed `self._data[i].hash` although `T` carries no `Hash`
requirement. The type checker accepted the read; LLVM compiled it to zero and
WASM could not lower it. The user chose to remove it until a `Hash` bound can be
required and called through a generic slot (019/022/044), rather than keep an
implementation that is wrong on LLVM. It was an inherent property, not a `Hash`
conformance; no derive, test or caller used it. `list.az` notes the absence.
Set (three `hash` properties) and Map (three aggregate `hash` properties and the
`key.hash` reads its lookup and insertion depend on) have the same unconstrained
reads. They are not changed here and will block those containers on WASM.

### Spec dispatch on WASM

With the member access removed, the next WASM error exposed another silent
miscompile. `ArrayList ==` reads its right side, a `List<T>`, through
`rhs.size` and `rhs.get(i)`. WASM lowered every `MethodCall` by returning the
receiver, so the equality compared elements with the list pointer. Under
four-byte erased slots this assembled; the eight-byte slots made it a type error.
A `size` member on a spec value also loaded the box's first word.

WASM now follows LLVM's design:

- A pack converted to a spec it implements is boxed as `[type id, pack pointer]`.
  The conversion lives in `coerceWasm`, so every destination boxes.
- Each spec method or property used gets a `__dyn_Spec_member` dispatcher. It
  switches on the type id and calls the implementer's function, converting
  arguments and results at the erased boundary. An unknown id traps; LLVM's
  dispatcher instead returns a default zero.
- A method call on any other receiver is an explicit WASM codegen error instead
  of evaluating to the receiver. No existing test depended on the old behavior.

### Evidence

- `SpecDispatchExecTest` (2 tests): a user spec with two implementers, a method
  and a property, with upcasts at a call and a declaration. The second test
  covers `ArrayList ==` and `!=` over equal, different and shorter lists.
  Interpreter, LLVM and WASM agree, optimized and unoptimized.
- `ListConstructionExecTest.concreteListRunsOnWasm` now passes, optimized and
  unoptimized. The 176 other WASM-executing or WAT-inspecting tests kept their status.
- Full run: **2,318 tests, 2,114 passed, 204 failed, 0 skipped**. The only change
  in failure identities is the list test now passing.

```sh
./gradlew :compiler:desktopTest --offline --console=plain \
  --tests '*SpecDispatchExecTest' --tests '*ListConstructionExecTest' \
  --tests '*ErasedGenericExecTest'
```


## 2026-09-19 — LLVM `purge` releases memory

Dispatch and the parked hash are committed as `bcbd8340`.

LLVM lowered `purge` to an advisory comment, so native programs never freed raw
memory. Three changes make freeing safe:

- `purge` of a raw (or nullable raw) pointer calls `__azora_free`, the existing
  null-safe `free` wrapper. Purging any other type is an explicit LLVM codegen
  failure, matching WASM.
- `alloc array` returned `array + 8`, a pointer into the array, which `free`
  cannot release. The elements are now copied into a buffer of their own, like
  WASM; a pointee whose width differs from the array's elements is rejected.
- `alloc .() * n` used uninitialized `malloc` memory. Once blocks are reused,
  such buffers can hold old values, and Map relies on zeroed `occupied` flags.
  Buffers now come from `__azora_alloc_zeroed`, a `calloc` wrapper that aborts on
  a negative count or failed allocation, like `__azora_alloc`.

Evidence:

- `RawPointerExecTest` gains two tests (7 total). After a purge, a second 64-Long
  buffer sums to 0 on LLVM and WASM. A second purge of one pointer stops the
  program on both: WASM traps, and libc aborts LLVM's double free, which also
  shows `free` runs. The existing single-value, buffer and allocated-array tests
  now execute real frees on LLVM, and LLVM rejects a pack purge.
- Three mutations were run separately and restored. Returning the interior
  pointer made `anAllocatedArrayCanBePurged` fail (free of an interior pointer),
  and removing the free call made `aSecondPurgeStopsTheProgram` fail. Removing
  zeroing was **not** detected by execution: this macOS allocator zeroes memory
  on free, and the reused block came back at the same address holding zeros. The
  test therefore also asserts that the buffer is allocated through
  `__azora_alloc_zeroed`; glibc and WASM keep the execution check meaningful.
- Full run: **2,320 tests, 2,116 passed, 204 failed, 0 skipped**; no failure
  identity changed. Stdlib containers whose LLVM execution tests already fail
  (several Set/Map cases in `LlvmAggregateExecTest`) are not yet evidence that
  their purges are correct.

Still open here: the interpreter fills new buffers and `Array.fill` slots with
null rather than each element type's zero (052). Whether purging a pointer
should also run the pointee's destructor remains 037.

```sh
./gradlew :compiler:desktopTest --offline --console=plain \
  --tests '*RawPointerExecTest' --tests '*ListConstructionExecTest'
```


## 2026-09-19 — 010.C3.4.1: sequence literal factories declared by packs

010.C3.3 is closed (commit `c3c75b8d`); LLVM purge is `1a0c1f1a`. C3.4 is split
into four substeps in the plan.

GTC_DIP §5.7 and §8.7 make a collection literal's target own its construction
through a static `literal` factory selected from the expected type. This substep
implements that for packs:

- `literal` is a reserved keyword with its own token (the DIP rules out a
  contextual keyword). AZLS's two `rangeIs`/`rangeStarts` parameters named
  `literal` are renamed, and AZLS highlights the keyword. One test fixture used
  `literal` as a variable name. It is renamed `inferred`, which matches the
  annotated/inferred comparison the test makes.
- `literal [...elements: T]: Type { … }` is accepted inside `impl Type { … }`. It
  has no receiver and lifts like any type-scoped member, to `Type__literal` with
  the impl's type parameters. Because `literal` is a keyword, `Type::literal(…)`
  cannot be written. Top-level and pack-body declarations, duplicates, a
  non-variadic parameter and (for now) a two-tuple element type get targeted errors.
- The resolver selects the factory when a sequence literal's expected type is a
  pack, from bindings, arguments and returns. It computes the element type with
  the target's type arguments in place, checks each element against it (literal
  adoption included) and checks that the factory builds the target. It records
  the selection on the literal, and IR lowering reads it rather than selecting
  again (§14.2).
- Lowering is one direct call. The elements are evaluated once, left to right,
  into an array at the factory's physical element width (the erased slot for a
  generic factory). Probing an ordinary `Type::make(...)` static call showed it
  packs at the logical width instead, which misreads on native targets; the
  factory path does not use it.
- Top-level function symbols now record their source parameter types, as members
  already did, so a lifted type-scoped member can be instantiated for its owner.
  Without this, `Bag<Double>` elements stayed `Float` and read back as garbage.
- A spread inside `[…]` was parsed as the inclusive-range operator. It is now a
  parse error until literals have the builder form §8.11 requires.

Evidence:

- `LiteralFactoryTest` (11): a generic `Bag<T>` built through a binding, an
  argument and a return (Int and Double elements). A `Digits` factory shows
  elements run once, in order, into the factory, and that an empty literal works.
  Also: the missing-factory, element-type, result-type, duplicate, misplaced,
  non-variadic, associative and spread diagnostics, the reserved keyword, and
  that the factory has no callable name.
- `LiteralFactoryExecTest` (2): both programs on LLVM and WASM, optimized and
  unoptimized. The ordering side effect is a field write through an exclusive
  borrow. A `threadlocal var` written from a function **segfaults LLVM under
  `lli` on this machine**; that pre-existing TLS defect is recorded, not fixed.
- AZLS tests pass. Full run: **2,333 tests, 2,129 passed, 204 failed, 0 skipped**;
  no failure identity changed.

```sh
./gradlew :compiler:desktopTest --offline --console=plain \
  --tests '*LiteralFactoryTest' --tests '*LiteralFactoryExecTest'
./gradlew :azls:test --offline --console=plain
```


## 2026-09-19 — 010.C3.4.2: spec-owned literal factories

C3.4.1 is committed as `757f5a02`.

A spec may now declare its own factory (GTC_DIP §8.7):

```azora
spec Stack<T> {
    func &.top(): T
    literal [...items: T]: Self { return ArrayStack<T>(items.size, items[items.size - 1]) }
}
fin ints: Stack<Int> = [1, 2, 3]
```

- In a spec body, `literal` is not a requirement. It is the spec's own function,
  lifted to `Stack__literal` with the spec's type parameters, and `Self` means the
  spec. Selection, element checking and lowering are the C3.4.1 path. The body's
  concrete result is converted to the spec on return, which boxes it for dispatch
  on the native targets. The DIP's `bridge literal` spelling is not accepted;
  plain `literal` is the one form in both packs and specs.
- A child spec does not inherit its parent's factory. The parent's builds a
  parent value, and the child target asks for more (DIP: each spec defines its own).
- Owner substitution (`instantiateMember`) now accepts a spec owner as well as a
  pack. Without it, a spec target's element type stayed erased: every element
  type-checked and Double literals kept their Float bits.
- Spec method and property signatures now keep their source types.
  `Stack<Double>.top()` is typed Double in the resolver and IR, and arguments
  such as `choose(1.25, true)` take `T` = Double. Before, the result was `Any`: WASM
  printed the value's raw bits and LLVM printed `<value>`. LLVM now converts spec
  dispatch results (methods and properties) to the call-site type, as WASM does.
  Members a child spec inherits from a parent still use the parent's erased
  signature; mapping parent arguments through `spec Child<T>: Parent<T>` is open.
- `isUnboundTypeParam` treated any argument-less name that is not a pack or enum
  as a type variable, including a spec such as `Shape`. A `Shape` literal
  therefore skipped factory selection. It also let `Array<SomeSpec>` unify with
  any declared array element. Specs are now excluded.

Evidence: `LiteralFactoryTest` adds the `Stack` program and three rejections: a
spec without a factory, a child spec without its own, and a factory body
returning a non-implementation (15 tests). `LiteralFactoryExecTest` runs the
`Stack` program on LLVM and WASM, optimized and unoptimized, with interpreter
parity (`3, 3, 2.5, 1.25, 2`). `SpecDispatchExecTest` still passes. Full run:
**2,337 tests, 2,133 passed, 204 failed, 0 skipped**; no failure identity changed.

## 2026-09-19 — 010.C3.4.3: associative literal factories

C3.4.2 is committed as `d3e8ecca`.

**Declaration rule (user decision).** A factory is associative when its declared
element type is written as a two-tuple: `literal [...entries: (K, V)]`. A factory
whose element is a type parameter, `literal [...items: T]`, is a sequence even
when `T` is instantiated as a pair, so `Bag<(Int, Int)> = [(1, 2), (3, 4)]`
remains a sequence literal. The associative member lifts to
`Type__literal_entries`. That name contains an interior underscore, which the
token validator rejects in source, so no program can call it; a lifted declaration
passes program validation. Duplicate associative factories get their own message.

**Selection and lowering.** `[k: v, …]` and `[:]` whose expected type is not map
storage select the target's associative factory, from packs or specs alike. The
key and value types come from the instantiated `(K, V)` and each is checked on
its own. The literal lowers to one direct call. Each entry is a tuple built at
the factory's *physical* entry layout (erased `(K, V)` for a generic factory),
its key evaluated before its value and each entry before the next. A literal of
the other shape reports that the target lacks that kind of factory.

**Tuples, which this required.** Tuple values worked only in the interpreter.
LLVM lowered a tuple literal to `null` and a component read to a default value,
each with a "not lowered" comment; WASM lowered both to `(i32.const 0)`. `t.0`
parsed as a member named `0`, which the resolver rejected on a structural tuple.
Now:

- The resolver and IR read a numeric member of a structural tuple as a
  positional component (out-of-range index diagnosed). Nominal `__Tuple_…` packs
  keep their numeric fields.
- LLVM and WASM lower tuples as heap aggregates like packs, each component at an
  offset aligned to its width, with components evaluated left to right. A
  component read loads the stored type and converts it to the use's type.

Still open: a concrete tuple passed across a generic boundary keeps its concrete
layout while the callee reads erased eight-byte slots. `second((1, 2.5))` for
`func<K, V> second(entry: (K, V)): V` gives wrong native results, like
`Array<Int>` passed as `Array<T>`. Factories avoid this by building entries at the
erased layout directly. The general fix is a layout conversion at the boundary,
which belongs with the erased-aggregate work (021/053).

Evidence: `LiteralFactoryTest` (20) adds a generic `Ledger<K, V>` (String/Double,
binding and argument contexts), an ordering program (`1234` from keys and values
in order, plus `[:]`) and the sequence-of-pairs rule. It also adds shape-mismatch,
key-type, value-type and duplicate rejections and the uncallable
`literal_entries`. `LiteralFactoryExecTest` runs all six programs on LLVM and
WASM, optimized and unoptimized, with interpreter parity. Full run: **2,342 tests,
2,138 passed, 204 failed, 0 skipped**; no failure identity changed.

## 2026-09-19 — 010.C3.4.4: factory constraints, failure and ownership

C3.4.3 is committed as `952d3ff9`.

GTC_DIP §8.7/§8.10 require a factory to take part in ordinary `where`
constraints and failure, and its elements to follow ordinary argument
ownership. Measured against an equivalent call first:

- **Failure already matched.** The literal lowers to a direct call of the
  factory, so a failable factory (`: Small ?! SizeError`) behaves like a failable
  call. `[…] catch fallback` gives the fallback on the interpreter and LLVM, and
  `try { … } catch { e -> … }` catches on the interpreter. On LLVM, that `try`
  form exits with code 1 for **any** failing call, not only factory literals. This
  pre-existing error-transport defect is recorded here, not fixed. WASM has no
  exception support; a failure traps instead of reaching the fallback.
- **Ownership already matched.** Elements resolve as arguments do: `[take res]`
  moves `res` and a later use gets the same "use of taken value" error as
  `hold(take res)`. A plain `[res]` is accepted, as `hold(res)` is. Borrowed
  elements and their lifetimes follow the argument rules and their open work
  (033/035). Destroying already-built elements after a later failure is 037.
- **Constraints were missing.** A factory now takes a `where` clause after its
  result type (`literal [...items: T]: Index<T> where T is Keyed { … }`). The clause
  is stored on the function symbol and decided at selection by `ConstraintEvaluator`,
  with the factory's type parameters bound to the target's arguments. Nominal
  conformance means `T is Keyed` holds only where `impl Keyed for T` exists.
  Undecidable clauses and still-generic arguments (a literal inside `func<T>`) are
  accepted, as for any declaration. A violation reports that the literal does not
  satisfy its factory's `where` clause.

Evidence: `LiteralFactoryTest` (25) adds the `where` clause satisfied, violated and
undecided inside a generic function. It also adds a failable factory (success and
`catch`, plus interpreter `try`/`catch`) and a moved element with its use-after-take
rejection. `LiteralFactoryExecTest` runs the `where` and ownership programs on LLVM
and WASM. The failable program gives `2, 0` on LLVM and traps after printing `2`
on WASM. Full run: **2,347 tests, 2,143 passed, 204 failed, 0 skipped**; no failure
identity changed.

## 2026-09-19 — 010.C3.5: factory dependency discovery; identity gap measured

C3.4.4 is committed as `ef276a7f` and C3.4 is closed in `679b9d9d`.

A literal needs its target's factory and whatever that factory builds, though
the program names neither. Measured with a test-owned library (`lib.seq`):

- A pack's factories were already injected with a selected pack. Its lifted
  `Pack__literal` / `Pack__literal_entries` members are type-scoped statics, which
  `attachStaticMembersForType` attaches.
- A spec's factory was never injected, even with a whole-module import.
  `attachStaticMembersForType` returned early for anything but a pack or enum.
  Specs now own static members too, and the transitive walk then reaches what
  the factory body builds (`Vector` and its impls).

`FactoryDependencyTest` covers a selected spec with sequence and associative
factories, a selected pack with both factory shapes, a whole-module import, and
an unrelated declaration that stays unimported; all four pass.

Two invariants C3.5 names ("canonical declarations, no unrelated short-name
matches") fail. Both are recorded as enabled acceptance tests:

1. **Injected dependencies leak into source.** After `[1, 2, 3]` injects
   `Vector`, the program can write `Vector<Int>(3)`. This is not specific to
   factories: `import lib.values::makeValue` followed by `Value(7)` compiles
   when `makeValue()` is also called (which injects `Value`) and is rejected when
   it is not.
2. **A program's own declaration captures the library's reference.** With
   `pack Vector<T>` in the program, the injector skips the library `Vector` as
   shadowed. The factory body's `Vector<T>(…)` then binds to the program's type
   (rejected here only because that type does not implement `Seq`; a conforming
   type would be silently used).

Both follow from identifying declarations by short name. That is the redesign
outlined under 007/014: lexical import scope, declaration identity separate from
source spelling, and identities carried through injection. It is not attempted
here without a decision.

Full run: **2,353 tests, 2,147 passed, 206 failed, 0 skipped**. No previously
passing test fails; the two new failures are the acceptance tests above.

## 2026-09-19 — 007/014: declaration identity and lexical imports; 010.C3.5 closed

C3.5 discovery is committed as `813ee22d`. This entry is the redesign outlined
under 007/014, done now by user decision.

**Declaration identity.** A library declaration the program cannot name gets a
canonical identity, its module path plus its name: `lib.seq`'s `Vector` becomes
`lib__seq__Vector`. Source cannot spell it (an identifier rejects `_` after its
first character). `DeclarationRenamer` rewrites the declaration and every
reference to it within the modules that bind it. Lifted statics (`Owner__member`)
follow their owner, and parameters, locals, lambda parameters and type
parameters shadow.

- A library reference resolves in its own module's scope: the module's
  declarations, its imports and their re-exports, then the implicit root. It no
  longer resolves in the program's scope, so a program's `pack Vector<T>` does not
  capture the factory's `Vector<T>(…)`.
- A declaration the program imports and does not shadow keeps its short name.
  So do compiler-known declarations: bridge packs, specs and decorators, the
  collection specs, `Formatter` and `Channel`.
- An impl attaches when its target resolves to the injected type, and a static
  member only from its owner's module.
- Injection runs up to four times per compile and is now idempotent.
  `Program.injectedNames` records what injection added, so a later pass does not
  mistake it for the program's own declaration and inject a second copy.

**Lexical imports.** In a program (a file without a `module` header), an
`import` inside a block is a `Stmt.Import` that stays in the block. It binds in
that block and the blocks it encloses, and locals shadow it. The injector
resolves each importing block, injects what the block uses, rewrites those uses
to the identities they resolved to, and drops the statement. A name imported only
in a block carries a hidden identity everywhere else. An outside use gets the
same diagnostic as a program with no import:
`undefined function 'answer' - 'answer' is provided by 'lib.one': add 'import lib.one::answer'`.
Namespace and selection validation, module cycles, library reachability and
type access also see block imports; type access is checked per block. The
language server counts a block import file-wide for completion and hover, as the
old hoisting did, and the compiler reports a use outside the block.

The 007 follow-up probe, rerun with test-owned libraries:

| User source | Required | Observed |
|---|---|---|
| `main` calls `answer()` without an import | Reject | Rejected |
| `main` imports the module and calls `answer()` | Accept | Accepted |
| `one` imports the module; unrelated `main` calls `answer()` | Reject | Rejected |
| A test imports the module; unrelated `main` calls `answer()` | Reject | Rejected |

Evidence:

- `FactoryDependencyTest`: 6/6. Both C3.5 acceptance tests pass: an injected
  `Vector` is not nameable, and a program's own `Vector` does not capture the
  factory's.
- `LexicalImportTest`, new: 13/13. Covers a function's and a test's import, both
  leak rows, sibling blocks importing different providers of `answer`, nested
  blocks (inward and not outward), an imported type and its leak, local
  shadowing, block plus file import, selected imports and namespace validation.
- `TestScopedImportTest` asserts the lexical shape, 4/4. Its obsolete bracket
  fixture is migrated to `std::{…}`. A new case checks that library modules still
  hoist.
- AZLS: `completesWhatABlockImports` added; 91/91.
- Full compiler run (`./gradlew :compiler:desktopTest --offline --console=plain`):
  **2,367 tests, 2,164 passed, 203 failed, 0 skipped**. Compared by test identity
  with the previous run, no failure is new. Now passing:
  `TestScopedImportTest.aTestMayOpenWithItsOwnImports` (its obsolete fixture) and
  the two `FactoryDependencyTest` acceptance tests.

Remaining, with owners:

- Library modules still hoist block imports to module scope (007).
- Type functions and type macros imported in a block are not in scope there;
  import them at file scope (007).
- Scope members (`std__math__floor`) and scope types keep their names. A
  scope-qualified path to an injected dependency's scope member is not verified
  as closed (014).
- Macro templates are not renamed (028).
- The FUNCTIONS_DIP §5.2 receiver discrepancy remains (007).
- Found here and pre-existing: an import naming no module compiles silently, at
  file and block scope alike (`import std.nothere`, `import lib.missing`). This
  is 014; only namespace paths are rejected.

The full suite now runs in about 1m50s instead of about 4 minutes, because far
less is injected.

## 2026-09-19 — 021.1: inferred generic calls typed in IR

A generic call whose type arguments were inferred got the callee's erased result
type in IR. The resolver inferred `T` and typed `wrap(4)` as `Box<Int>`, but
`IrGenerator` substituted type arguments only when the call wrote them. An
unannotated binding then took the erased `Box<Any>`, and a field or element read
through it was lowered as `Any`.

**Change.** Inference stays in the resolver. Where it already binds type
parameters from the arguments (the `isGeneric` block of `Expr.Call`), it now
records them on the call as `Expr.Call.inferredTypeArgs`. This follows the
`ArrayLiteral.literalFactory` precedent: a constructor field the resolver writes
and `copy` carries through `InlineCallables`. It records only when:

- the call wrote no type arguments;
- every type parameter is bound to a single type; and
- the callee has no variadic type parameter.

Otherwise the field is null. `IrGenerator` reads the written arguments, or the
inferred ones when none were written, through its existing branch for written
arguments. Two cases keep that rule unchanged:

- A call that writes a hole is not completed: `pairOf<Int, _>(1, "two")` is still
  `Pair<Any, Any>`.
- Arguments naming the enclosing function's type parameters erase: `wrap(u)`
  inside `func<U> rewrap` is `Box<Any>`, the same as the written `wrap<U>(u)`.

Nothing in the IR re-derives inference. No Expr- or declaration-keyed hash
collection exists after resolution, so the mutable field does not disturb one.

Before and after, unoptimized and optimized alike (the interpreter was already
right in every row):

| Program | LLVM before | WASM before | After, both targets |
|---|---|---|---|
| `fin b = wrap(4)`, `wrap("hey")` | `<value>`, `<value>` | 4, 1024 | 4, hey |
| `fin xs = listOf(1, 2, 3); xs.get(0)` | `<value>` | 8589934593 | 1 |
| `identity(big)`, a Long | `<value>` | 5000000000 | 5000000000 |
| `rewrap(9).item` (generic calling generic) | `<value>` | 9 | 9 |
| The `listed` test program | invalid IR (`ptr` used as `i32`) | wrong | correct |

**Changed and still wrong (023).** The call `apply(x, { … })` for
`func<T> apply(value: T, change: (T) -> T): T` is now typed by the `T` inferred
from `x`. The lambda is still checked and lowered against the erased
`(Any) -> Any`, as recorded under 010.C3.3.

| Call | Before, LLVM / WASM | After, LLVM / WASM | Written `apply<T>`, before and after |
|---|---|---|---|
| `apply(1.5, { x -> x * 2.0 })` | `<value>` / 1077936128 | 0.0 / 3.0 | 0.0 / trap |
| `apply(d, { x -> x * 2.0 })`, a Double | `<value>` / 0 | 0.0 / 0.0 | 0.0 / trap |
| `apply(4, { x -> x + 1 })` | `<value>` / 5 | 0 / 5 | 5 / trap |

The LLVM zero is computed in the erased lambda; `<value>` used to hide it. It is
now a plausible wrong number rather than a placeholder, which makes 023 more
urgent. Written type arguments behave the same before and after this change.

**Not changed, measured.** `listOf` packs its variadic arguments at their own
width while its body reads `Array<T>` as erased slots (the known
`Array<Int>`-as-`Array<T>` mismatch). The annotated form behaves identically
before and after this change:

- `fin xs: List<Int> = listOf(1, 2, 3)` reads `xs.get(2)` as 0 on LLVM and
  1098542913 on WASM.
- Float lists are wrong past the first element.
- A String `listOf` traps on WASM.

`listOf(1, 2, 3).get(0)` reads 1 only because the first Int is the low half of
the first slot. Long lists, and lists built from erased values inside a generic
body, read correctly at every index.

### Evidence

- `InferredGenericCallTest` (commonTest, 6 tests): IR binding types written out
  with their type arguments. `IrType.Named` equality ignores type arguments, so
  comparing types directly would pass on the erased result. The tests cover
  `Box<Int>`/`Box<String>`, and inferred and written forms agreeing. They cover
  `List<Int/Long/String/Float>` from `listOf` and a user `twice`, a `Long`
  result, the generic-body and hole rules above, and interpreter output
  optimized and unoptimized.
- `InferredGenericCallExecTest` (desktop, 2 tests): the same programs on LLVM and
  WASM, optimized and unoptimized.
- `ErasedGenericExecTest.genericFunctionsReturnWideValues` now runs on LLVM too.
  Its exclusion was this defect: "LLVM prints `<value>` for a Long returned from
  a generic function" (above). This resolves that item.
- With the change reverted, 7 of the 13 focused tests fail (the hole and
  interpreter tests pass either way, by design).
- Full compiler run: **2,375 tests, 2,172 passed, 203 failed, 0 skipped**,
  against **2,367 / 2,164 / 203** at `f7b26292` measured just before. Compared by
  (suite, test) identity, no previously passing test fails and no failure
  changed its message. The 8 new tests pass.
- AZLS: 91/91 (`Expr.Call` gained a field).

This was developed and measured in a separate worktree at `f7b26292`. Another
session had uncommitted edits to `TypeResolver.kt` and `IrGenerator.kt` in the
main tree (list literal factories, spec ancestor members), and both measurements
exclude them.

The patch was also applied to a snapshot of those edits, and it applies cleanly.
There, the reported `func<T> twice(v: T): List<T> { return [v, v] }` prints
`5`/`hey` for `twice(5).get(1)` and `twice("hey").get(0)` on all three targets,
optimized and unoptimized. Without this change it prints `<value>` twice on LLVM
and `1024` for the string on WASM. `GenericMemberSignature*`,
`StdCollectionLiteral*` and `LiteralFactory*` pass there with and without it.

```sh
./gradlew :compiler:desktopTest --offline --console=plain \
  --tests '*InferredGenericCall*' --tests '*ErasedGenericExecTest'
./gradlew :compiler:desktopTest --offline --console=plain
./gradlew :azls:test --offline --console=plain
```

## 2026-09-19 — 010.C4.1–C4.3: standard collection literals

The 007/014 identity work is committed as `f7b26292`. This entry was written in
the same working tree as 021.1 above, which is also uncommitted; each lists its
own files.

**Factories.** GTC §8.4 and Phase 4 name the defaults; each is a `literal` on
the spec, with a module-private builder so the spec and its default pack share
one body. Spread arguments are not implemented, so the builder takes the
factory's variadic `Array<T>`.

| Expected type | Builds | Duplicates |
|---|---|---|
| `List<T>`, `MutableList<T>`, `ArrayList<T>` | `ArrayList<T>` | kept |
| `Set<T>`, `MutableSet<T>`, `LinkedHashSet<T>` | `LinkedHashSet<T>` | first kept |
| `HashSet<T>`, `TreeSet<T>` | themselves | first kept |
| `Map<K, V>`, `MutableMap<K, V>`, `LinkedHashMap<K, V>` | `LinkedHashMap<K, V>` | last value wins |
| `HashMap<K, V>`, `TreeMap<K, V>` | themselves | last value wins |

`Set<T>` defaults to `LinkedHashSet<T>`: §8.4 asks for a deterministic default,
and insertion order is what `setOf` already chose. Duplicates collapse through
each pack's own `add`, and repeated map keys through `put`.

**Compiler defects found and repaired on the way:**

- *Inherited spec members were erased.* `MutableList<String>.get` is declared on
  `List<T>`, so it kept the erased `T` and printed `<value>` on LLVM and a pointer
  on WASM. Specs now record their parents with arguments
  (`SpecSymbol.parents`), and a member is read where the receiver is seen as its
  declaring spec (`MutableList<String>` as `List<String>`).
- *`oper[]` results were erased* in both the resolver and IR generation; they are
  now instantiated like a method call's.
- *The interpreter ran a global initializer before later functions existed.*
  `fin primes: List<Int> = [2, 3, 5]` failed there alone (`Undefined function`),
  and so did `fin xs = listOf(…)`. Every function is now registered before any
  initializer runs.
- *A type's own impls did not follow it into a library.* An impl was attached
  only if the program imported its module, so a program that reached
  `ArrayList` through the serializer got it without `add`, `size` or `get`. This
  was the pre-existing "no method 'add' on ArrayList" in the filesystem,
  reactive, generator and quantum tests. Impls declared in the type's own module
  now come with it; other modules' impls still need the program to import them.
- *A library reference fell back to the global flat index.* A name missing from a
  library's scope (a member name the collector gathered, say) pulled in any
  module's top-level declaration of that spelling. That is why using a map
  injected `std.filesystem`. Library references now resolve only in their
  module's scope, plus compiler-known declarations (`Copy`, bridges). This
  exposed `std.filesystem` using `mutableListOf` without importing it; it now
  imports `std.container.list::{MutableList, mutableListOf}`.
- *Parameters and locals did not shadow compile-time constants.* CTFE replaced
  every identifier named like a seeded `std.config` constant, so `var target = 0`
  read the compiler target (`CompilerTarget`). That was the pre-existing error
  in the map, filesystem and quantum tests. A body's parameters and locals now
  shadow them for the whole body (`localNamesDeclaredIn`, now shared with the
  renamer). `std.filesystem`'s `fileInfo` then reached `fin kind = when … { … ->
  .File }` with nothing stating the type; it is annotated `FileKind`.
- *A bridge function lost its declaration under the identity rename.* Quantum's
  `sqrt` reference became `std__math__sqrt` while `bridge func sqrt` kept its
  name, a regression from `f7b26292` hidden inside an already-failing test. A
  bridge function is called by its foreign symbol, which is also its source name,
  so it is compiler-known and keeps it. An injected bridge function remains
  nameable (014).

**Standard library repairs.** The map module did not compile on any backend:
`keys()`/`values()` returned an `Array` where `List` is declared (implicit
conversion was removed in C3.2), and `TreeMap`'s rotations declared their node
indices `Bool`. Both are fixed. The three set `hash` properties read `.hash` on an
unconstrained `T`, as `ArrayList.hash` did; they are parked by the same decision,
which the C3.3 entry anticipated would otherwise block sets on WASM.

**Harness.** `LlvmExec` and `WasmExec` wait at most 60 s for a program and fail
its test with what it printed. Before, a program that did not end hung the suite.

**Maps natively (C4.3, open).** Measured with `HashMap`, `LinkedHashMap` and
`TreeMap` programs:

- WASM cannot lower `key.hash` on an unconstrained `K` (019/022/044). The `Map`
  factory designates `LinkedHashMap`, so every program that reaches `Map` meets
  it.
- An explicit `ctor .()` never runs on LLVM or WASM, only in the interpreter,
  which calls `Type_ctor` implicitly after construction. `LinkedHashMap`'s
  buckets therefore stay zero, and `_insertBucket` does not end. A separate
  session is repairing this.
- `HashMap` and `TreeMap` insert and look up on LLVM, but `get` returns `V?` and
  LLVM prints a nullable as `<value>`.

Three `LlvmAggregateExecTest` map tests now time out at 60 s instead of stopping
at "does not define a factory". `mapInsertsMissingEntry` stops at `values[3] = 30`
on a `MutableMap`: the spec declares no `oper[]=`.

**Not changed, recorded:**

- *for-in over a collection.* `for x in set` is rejected: `List` and `Set` offer
  no iteration protocol (`setForEachSum`, `setRemoveReturnsWhetherRemoved`).
- *TreeSet is not sorted.* It keeps insertion order despite its documentation.
- *Spec-typed elements.* `List<Shape> = [Square(2), Rect(2, 3)]` upcasts and runs
  on the interpreter. An `ArrayList<Shape>` segfaults on LLVM even when built by
  `add` (044/053).
- *Stale fixtures.* `ContainerStdlibTest` calls `add` on a read-only `List`/`Set`.
  `FilesystemStdlibTest.metadataReportsKindAndSize` calls `Instant(…)` without
  importing `std.time`, which the injected dependency used to allow.

**Untyped associative literals (C4.4, decision needed).** GTC §8.4 infers
`Map<K, V>` backed by `LinkedHashMap` for `["a": 1]`. The compiler makes it the
structural `IrType.Map`, which programs mutate (`values["b"] = 99`,
`LlvmAggregateExecTest.mapUpdatesExistingEntry` and others pass that way). A
read-only `Map<K, V>` would reject those writes.

### Evidence

- `StdCollectionLiteralTest` (5), new: list, set and map literals in every
  context, element and entry checks, spec element upcast, empty literal without
  context. `StdCollectionLiteralExecTest` (2), new: the list and set programs on
  LLVM and WASM, optimized and unoptimized.
- `GenericMemberSignatureTest.inheritedMembersAndIndexingUseTheReceiversArguments`
  and two exec tests (LLVM, WASM). With the fix stashed, both exec tests fail.
- Full compiler run: **2,385 tests, 2,193 passed, 192 failed, 0 skipped**, with
  021.1's changes in the tree. Against the C4.1-stage run (2,376 / 203), no test
  newly fails, and 11 now pass: five `LlvmAggregateExecTest` set tests and
  its `packedCollectionsSupportWideValues` (which builds a set),
  `decimalCollectionsUseExplicitPackedAlignment`,
  `CollectionCtorTest.mutable_map_pack_exists`, `PlaygroundExamplesTest.generators`,
  `WebsiteExamplesTest.ch31_flow` and `ReactivityTest.stdStateObservationAndDisposalWork`.
- Every failure message was compared error by error with a full run of the
  pre-identity commit `813ee22d` in a separate worktree. Errors new inside an
  already-failing test are:
  - compilation reaching further (the for-in and `oper[]=` items above);
  - the generated `std::serialAsInt` call, of the existing `std::serial*` family;
  - `metadataReportsKindAndSize`'s missing `std.time` import and its cascade.

  This comparison is what found the `sqrt` regression.
- AZLS: 91/91.

```sh
./gradlew :compiler:desktopTest --offline --console=plain --tests '*StdCollectionLiteral*' --tests '*GenericMemberSignature*'
./gradlew :compiler:desktopTest --offline --console=plain
./gradlew :azls:test --offline
```

## 2026-09-19 — 018.1: `ctor .()` runs on every target

A pack's `ctor .()` ran only in the interpreter, and only on unoptimized IR.
`IrInterpreter` called `<Type>_ctor` whenever it evaluated a `StructCtor` and
that function took nothing but the receiver. Nothing in the IR asked for the
call, so LLVM and WASM never made it. In release, the optimizer removed the
uncalled ctor as dead code, so the interpreter skipped it there too. A ctor with
parameters was already lowered to a factory call.

**Change.** Lowering asks for the call. A construction that writes no
arguments is one of:

- `.()` where the type is stated: a binding, a field default, a return, an
  argument or a default parameter;
- `Type()`;
- `Type<Args>()`.

Such a construction builds the value exactly as before. It then passes the value
to `__ctor_<Type>_run` (`ctorRunSymbol`), a generated function that runs the ctor
on it and returns it. The interpreter's implicit call is gone, so the ctor runs
once. `receiverOnlyCtorSymbol` finds the ctor in the symbol table for both the
resolver and the IR generator, so the two stages agree on which calls run it.

- *Beside other ctors.* `ctor .()` is emitted as `<Type>_ctor_0` when the type
  declares another ctor. The interpreter's lookup of `<Type>_ctor` never found it,
  and the resolver rejected `.()` with "'P' has no argument for 'v'". It now
  answers a call writing no arguments, ahead of a ctor whose parameters all have
  defaults. A call with arguments runs only the ctor it selected.
- *Memberwise construction does not run it.* `Q(8, 9)` fills the fields it
  names. The unoptimized interpreter used to run `ctor .()` afterwards and
  overwrite them. That is not what the call says, and no other target did it.
- *A default parameter `p: Plain = .()`* lowered to a member named `''` and
  failed on every target, with or without a ctor. It now lowers through the same
  helper as every other `.()` (`lowerInferredMember`).

Measured on `f7b26292` without and with the change, unoptimized / optimized:

| Program | Interpreter before | LLVM, WASM before | After, all three |
|---|---|---|---|
| The reported program: `Counter` and `Gen<Int>` via `.()`, `Counter()`, `Gen<Int>()` | 5, -1, 5, 7, 7 / 1, null, 1, 1, 1 | 1, 0, 1, 1, 1 | 5, -1, 5, 7, 7 |
| `.()` beside `ctor .(v: Int)` | rejected | rejected | runs `ctor .()` |
| `Q(8, 9)` beside `ctor .()` | x = 50 / x = 8 | x = 8 | x = 8 |
| `p: Plain = .()` | fails | fails | 4 |
| Open-addressing table whose buckets `ctor .()` sets to -1 | correct / "Cannot compare null and 0" | nothing stored | correct |

**The `LinkedHashMap` consequence.** `LinkedHashMap`'s `ctor .()` sets its
buckets to -1. The program builds `var m: LinkedHashMap<Int, Int> = .()`, puts
40 keys, then re-puts one. Measured on 010.C4's map module (then uncommitted):

- *Before:* `lli` did not finish within 60 s, unoptimized and optimized. The
  optimized interpreter failed with "Cannot compare null and 0".
- *After:* LLVM stores 40 entries, deduplicates the re-put, and `keys()` returns
  40. A looked-up value still prints as `<value>`, the C4.3 nullable item.

WASM still stops at `key.hash` on an unconstrained `K`.

**Not changed, recorded under 018.** A receiver-only ctor may declare a return
type (`ctor .(): Int`). Construction yields the value the ctor filled and ignores
the returned value, although a ctor with parameters that declares a return type
yields its result. Decide whether to reject the declaration or honor it.

### Evidence

- `ReceiverOnlyCtorTest` (2), new: five programs on the interpreter, optimized and
  unoptimized, plus the IR shape. `main` calls `__ctor_Counter_run`, which calls
  `Counter_ctor`. The programs cover:
  - plain and generic packs, with the ctor running exactly once;
  - overloads, a defaulted overload and named arguments;
  - memberwise construction;
  - a field default, `return .()`, `Self()`, default parameters and
    `Box<T>()` in a generic function;
  - the open-addressing table.
- `ReceiverOnlyCtorExecTest` (2), new: the same programs on LLVM and WASM,
  optimized and unoptimized.
- With the compiler change reverted, all four new tests fail.
- Focused suites pass: `CtorOverloadTest`, `RepeatedConstructionTest`,
  `LiteralFactory*`, `ListConstruction*`, `RepeatConstructionTest`,
  `DeclarationBodyTest`.
- No existing test exercised the implicit call. With it instrumented, a full
  run on `f7b26292` reached it zero times.
- Full compiler run on `f7b26292`: 2,367 → 2,371 tests, 2,164 → 2,168 passed,
  203 failed before and after. No test newly fails.
- Full compiler run on `2b89a938`: **2,385 → 2,389 tests, 2,193 → 2,199 passed,
  192 → 190 failed**, 0 skipped. No test newly fails. `LlvmAggregateExecTest`'s
  `mapLengthAndEmptyProperties` and `typedStdlibCollectionLiteralsExposeSize` now
  pass. Both used to stop at the 60 s limit, as did `mapLiteralReadsIntegerKeys`.
  That test now finishes in 0.1 s and fails on the C4.3 `<value>` item.
- `StdCollectionLiteralTest` still says `LinkedHashMap`'s `ctor .()` does not run
  natively. That reason no longer holds; its maps remain interpreter-only for the
  other two C4.3 items.

```sh
./gradlew :compiler:desktopTest --offline --console=plain --tests '*ReceiverOnlyCtor*' --tests '*CtorOverload*'
./gradlew :compiler:desktopTest --offline --console=plain
```

## 2026-09-19 — 022.1: `Hash`, `Equal` and `Order` through erased generic code; 010.C4.3 closed

C4.1–C4.3 are committed as `2b89a938`. The 018.1 patch (`ctor .()` on every
target) was applied from its session's worktree, uncommitted, and is recorded in
its own entry above.

**The defect.** A generic value is an erased eight-byte slot. `key.hash` inside
`HashMap<K, V>` read the slot's bits and `==` compared them. That is right for an
`Int` and wrong for anything else:

- a `String` built at run time hashed and compared by its address on LLVM;
- WASM could not lower `.hash` on a slot at all;
- `TreeMap` ordered its keys by address on LLVM.

**Design.** Descriptors, the shared-code form GENERICS_DIP §21.3 permits beside
the §21.1 specialization strategy, and the one erased slots (021) allow:

- *Bounds.* A type parameter bounded `where K: Hash`, `Equal` or `Order` carries
  a descriptor, an `Int` naming the concrete type. `Hash` and `Order` imply
  `Equal`. `Witnesses` reads bounds from the `where` clause, which the parser
  already lowers to `K is Hash`.
- *Where descriptors live.* A bounded pack gets a hidden `__witness_K` field
  (default `-1`) added to its AST, so layout and copying treat it like any other
  field; its methods read `self.__witness_K`. A bounded function, and a bounded
  pack's lifted statics (`literal`), take it as a hidden trailing IR parameter.
  A function whose receiver or parameter is a bounded pack reads it from that
  value (`oper+ HashMap<K, V>&.(…)`).
- *Filling them.* Constructions fill the field from written type arguments or
  the declared type they are built for (`var m: HashMap<K, V> = .()`). Calls pass
  the parameter, taking the type arguments in this order: written, bound by
  matching the callee's parameter types against the arguments' declared types
  (variadic elements included), then 021.1's inferred ones. A literal passes its
  target's.
- *Knowing an operand is a `K`.* IR types erase `K` to `Any`, so lowering reads
  declarations: parameters, locals, pack fields (with the owner's arguments
  substituted), and elements of `K*` and `Array<K>`.
- *Dispatch.* `x.hash`, `==`/`!=` and `<`/`<=`/`>`/`>=` on such an operand call
  `__witness_hash`, `__witness_equal` or `__witness_compare`. Each is generated
  last, with one branch per descriptor that needs its operation. A branch binds
  the slot at the concrete type and lowers the ordinary `x.hash`, `a == b` or
  `a < b` on it, so a pack's derived or written operator is exactly what
  concrete code would call. An unset descriptor panics.

**Built-in hashes** now agree on every backend:

- integers, `Char` and `Bool` hash to their value;
- floats hash to their bit pattern (a `Float` as a `Float`);
- a string hashes to the 64-bit FNV-1a of its UTF-8 bytes (`__azora_str_hash`
  on LLVM, `__str_hash` on WASM, the same in the interpreter).

WASM had no `.hash` lowering before this.

**Enforcement.**

- *Concrete types* are checked against the bound, reusing `ConstraintEvaluator`.
  This covers written types (`HashMap<Double, Int>`, now with a line), explicit
  constructions (`HashSet<Double>()`, new), literal factories (C3.4.4) and
  generic calls (`code(2.5)` with `where T: Hash`, new; 022 had no call-site
  check).
- *Width aliases.* `Long` is `Int<64>`, so a width answers with its family's
  conformances. Before, `Long does not implement Equal`.
- *Generic code* passing a type parameter to a bound it does not declare is
  rejected: `'T' of 'HashSet' must be Hash, and 'T' is not declared to be; add
  'where T: Hash'`. The IR generator's `WitnessError` is reported as a compile
  error.

**Standard library.**

- `HashMap` and `LinkedHashMap` require `K: Hash`, and `HashSet` requires `T: Hash`.
- `LinkedHashSet` and the `Set`/`MutableSet` factories require `T: Equal`.
- `TreeMap` and `TreeSet` require `Order`, as their documentation already said.
- Factories and helpers that build those collections declare the same bounds.
- A `TreeMap` slice now builds a `TreeMap`. It built a `LinkedHashMap`, which
  needs a `Hash` its keys do not promise.

**Defects found by running maps natively:**

- *Each buffer allocation has its own typed statement.* The map rehash path
  allocates its key, value, hash and occupied buffers separately.

- *`HashMap._rehash` looped over `0..newCapacity` and `0..oldCapacity`*,
  inclusive ranges, one slot past each buffer. It never ran natively before.
- *`m[k] = v` passed its value at the literal's width.* `3.5` is a `Float` there.
  It is now coerced to the operator's instantiated parameters, as a method call is.
- *WASM held every nullable as an `i32`,* truncating an erased `V?` and a
  `Long?`. A nullable now has its inner type's representation, null being zero.
- *The interpreter did `ULong` arithmetic as signed.* Division, remainder,
  ordering and right shift on `ULong`/`USize` are now unsigned. FNV hashes made
  bucket indices negative.
- *LLVM `boxToI64` for `Float`* took its result temporary before the bitcast's,
  numbering them out of order.

**Not changed, recorded:**

- `.hash`/`==` on an unbounded type parameter still compares the erased bits;
  the DIP asks for a bound there.
- A bounded pack whose ctor takes arguments cannot be given descriptors yet
  (`WitnessError`).
- `<=>` on a bounded parameter is not dispatched.
- A generic key type (`Pair<Int, String>`) hashes through its own member, whose
  erased fields are not dispatched.
- LLVM prints a nullable as `<value>`, and `??` does not lower natively
  (`__nullCoalesce`); `LlvmAggregateExecTest.mapLiteralReadsIntegerKeys` reads
  `values[1]` through a `Map<Int, String>` that way.
- `mapInsertsMissingEntry` writes `values[3] = 30` through `MutableMap`, which
  declares no `oper[]=`.
- On WASM a null value-type nullable is indistinguishable from zero.

**Decision needed.** `LlvmRegressionExecTest.decimalCollectionsUseExplicitPackedAlignment`
passed after C4 and now fails. It checks packed `fp128` stores and builds
`Set<Quad>`. `Quad` is `PartialEqual`, not `Equal`, so a set of it no longer
satisfies the `Equal` bound GTC §8.4 implies ("duplicates collapse according to
`Equal`"). Either the fixture drops `Set<Quad>` (the array and map lines still
produce the stores it checks), or sets accept `PartialEqual`. The test is
unchanged.

### Evidence

- `WitnessTest` (5), new:
  - bounded packs and functions over run-time strings, integers and a derived
    pack, including a bounded function calling bounded ones;
  - native maps and sets with run-time keys, grown past their first buffers;
  - built-in hash values;
  - explicitly typed buffer assignment IR;
  - rejected key types and unbounded generic code;
  - unsigned `ULong` arithmetic in the interpreter.
- `WitnessExecTest` (2), new: the programs on LLVM and WASM, optimized and
  unoptimized.
- `StdCollectionLiteralTest`/`ExecTest` run the map programs on every target. The
  set program's `LinkedHashSet<Double>` is `LinkedHashSet<Long>`, since `Double`
  is not `Equal`.
- Full compiler run: **2,396 tests, 2,205 passed, 191 failed, 0 skipped**, against
  2,385 / 192 before 018.1 and this. Newly failing: the `Set<Quad>` fixture above.
  Newly passing: `LlvmAggregateExecTest.typedStdlibCollectionLiteralsExposeSize`
  and `mapLengthAndEmptyProperties`. Errors new inside already-failing tests were
  compared line by line; there are none.

```sh
./gradlew :compiler:desktopTest --offline --console=plain --tests '*Witness*' --tests '*StdCollectionLiteral*'
./gradlew :compiler:desktopTest --offline --console=plain
```

## 2026-09-22 — 010.C4.4: untyped associative literals build the standard map

022.1 is committed as `95ab0bad`. Both open decisions from that entry are taken.

**Sets keep their `Equal` bound.** `LlvmRegressionExecTest.decimalCollectionsUseExplicitPackedAlignment`
built a `Set<Quad>` to check packed `fp128` stores. A float is `PartialEqual`,
not `Equal`, and a set decides membership by equality (GTC §8.4), so the fixture
drops its set line and keeps the array and the map, which store the same
`fp128`. It passes again.

**An untyped `[k: v]` builds `LinkedHashMap<K, V>`.** GTC §8.4 names that as the
backing implementation. The inferred type is the concrete pack, as a sequence
without a context infers `Array<E>` rather than `List<E>` (the 2026-09-10
decision), so `m["b"] = 99` and `scores[40] = 40` keep working - a read-only
`Map<K, V>` view would have rejected the website and playground examples that
write to a map they just wrote down. The compiler's structural `IrType.Map`
remains only where the standard library is absent, as in a stage test. Keys must
be `Hash`, as for any map. The literal's target is injected without an import,
and stays unnameable: it is retained through the visibility filter by a new
`implicitLiteralTargets`, not by the compiler-known set that would have kept its
short name.

**Defects this uncovered, all fixed:**

- *Two declarations, one symbol.* The IR canonicalizer collapsed every run of
  underscores, so a module's public `linkedHashMapOf` and its private
  `_linkedHashMapOf` both became `__std_container_map_linkedHashMapOf` once the
  identity rename joined them to their module - `invalid redefinition` at link.
  A run of three or more now keeps one underscore of its own.
- *A program's own name is the program's.* A user `pack Map` and the injected
  standard `Map` spec shared the short name, because compiler-known declarations
  are never renamed; `fin value: Map<Int, Int> = data` then accepted a standard
  map (`CollectionTargetSafetyTest.keyedValuesCannotMasqueradeAsNamedMaps`).
  A compiler-known name the program declares now takes its module's identity.
- *A `using` block's members lost to globals.* Inside `using self { purge (keys,
  values, …) }`, `values` resolved to a global of that name rather than the
  field, so any program with a global named `values` failed as soon as the map's
  destructor was injected. A member a `using` block opens now stands closer than
  a global (and a parameter or local closer than either), in the resolver and in
  lowering. This also fixed a latent defect: a global holding any non-`Copy`
  pack (`fin values: HashMap<String, Int> = …`) could not be used at all.
- *LLVM printed a nullable as `<value>`.* It now branches on null and prints
  `null` or the value at its own type, which is what `println(map.get(k))`
  asked for (`LlvmAggregateExecTest.mapGetPutAndContainsKey`).
- *Global map literals* were typed as the structural map by symbol collection,
  which disagreed with the resolver; it names the standard map too.

**Open, recorded.** A global initialized through a literal factory does not link
on LLVM: the factory is defined in the module and the initializer's call still
reports `use of undefined value`. `globalSetInitializerRunsBeforeMain` has failed
that way since C4.2; `globalMapInitializerRunsBeforeMain` now joins it, having
passed while untyped maps were structural. Every other context - local bindings,
arguments, returns, fields - links and runs.

### Evidence

- `StdCollectionLiteralTest.untypedMap`, new, runs on the interpreter, LLVM and
  WASM: reads, an update, a new key, growth, `Double` values and insertion order.
- `CollectionTargetSafetyTest` 4/4, `WitnessTest` 5/5, `StdCollectionLiteral*`
  7/7, `LlvmRegressionExecTest.decimalCollectionsUseExplicitPackedAlignment`.
- Full compiler run: **2,396 tests, 2,205 passed, 191 failed, 0 skipped**,
  against 2,396 / 191 at `95ab0bad` measured in a scratch worktree. One test
  newly passes (the `Set<Quad>` fixture) and one newly fails (the global map
  initializer above).

```sh
./gradlew :compiler:desktopTest --offline --console=plain
```

## 2026-09-22 — 014: an import that names nothing is an error

010.C4.4 is committed as `c6550959`; this entry starts from it.

**The defect.** `validateImports` rejected one kind of bad import, a bare
namespace (`import std`). Anything else that named nothing imported nothing and
compiled. Measured on `c6550959`, each of these compiled:

- `import std.nothere`, `import lib.missing` and `import nowhere`, at file scope
  and inside a function;
- `import std.nothere::*`, `import nowhere::{*, without x}`;
- `import std::{math, nothere}`;
- `import std.math::nothere`, `import std.math.nothere`,
  `import std.math::{abs, nothere}` and `import lib.one::nothere`.

**Change.** Each clause of an import must name one of:

- a module;
- a namespace, for a wildcard (a plain namespace keeps its existing error);
- something a module declares: a declaration, type function, type macro, scope,
  scope member or scope type.

The modules are the standard library's plus the compilation's library sources:
the project's other files in `azora build`/`run`, and the workspace catalog in
the language server. The error is reported at the clause's own line, so a
multi-line group names the member that is wrong. It names the first missing
piece: `std.nothere::thing` reports `std.nothere`.

```text
line 2: there is no module 'std.nothere' to import
line 4: module 'std.math' has nothing named 'nothere' to import
line 2: there is no module or namespace 'nowhere' to import from
```

Two kinds of name are not missing although no library module declares them:

- *The program's own module,* and anything the program declares. That includes
  its scopes: `import Const` beside `scope Const { … }` stays a no-op, as
  `ModulesTest.useDoesNotCreateBareAlias` expects. A module *below* the
  program's own (`app.util` from `module app`) is looked up like any other.
- *A module that exists but cannot be imported from here* (a `confined`
  module). It still imports nothing and compiles; see below.

Every standard-library module's own imports pass the check. The check sees only
imports the program wrote. Imports the compiler generates (a derived
serializer's) are added after it runs.

**Stale fixtures.** Four fixtures imported modules that do not exist. Only
their imports changed. What each asserts is unchanged, and each passes again:

| Fixture | Import | Where the declarations are |
|---|---|---|
| `ConvertSpecTest` (3 programs) | `std::convert` | `From` and `Into` are in `std.core`, which every program sees |
| `CastSpecTest` (3) | `std::convert` | `Cast`, `CheckedCast` and `BitCast` are in `std.core` |
| `Feature003SyntaxTest` (2) | `std::convert` | `Cast`, in `std.core` |
| `LlvmRegressionExecTest.decimalCollectionsUseExplicitPackedAlignment` | `std.container.core` | nothing is used from it |

`Feature003SyntaxTest.implAsStringDoesNotCreateToString` expects a failure
mentioning `toString`. It would have reported the import instead, so the
missing-import error could not stand in for the error it checks.

**Decision needed: `std.convert`.** CAST_DIP §1 says `Into` and `From` are
`std.convert`'s, and `std/serializer.az` declares
`@Derive(conversionModule: "std.convert", conversionProvider: "convert")`. A
derived serializer therefore imports `std.convert` and calls
`convert::toString`. No `std.convert` module exists. The generated import is not
checked (see above), and serializer derivation already fails in the baseline.
There are two options:

- add a `std.convert` module that owns or re-exports the specs, and gives the
  serializer its `toString`;
- correct CAST_DIP §1 and the serializer's metadata to `std.core`.

**Not changed, recorded:**

- `ResultUnwrapOrTest` (2) imports `std::result` and calls `ok`, `err` and
  `unwrapOr`. None of them exists anywhere. The test failed before and now
  fails at the import. It is a missing implementation or an obsolete test.
- Importing a `confined` module from outside its package imports nothing and
  compiles. MODULES_DIP says the folder comparison behind module visibility does
  not exist yet, and a same-package entry must still be able to import it.
- `import std::*` and `import std::{math, time}` fail with the serializer's
  derive errors (`undefined function 'std::serialFieldAt'`, …) on `95ab0bad`
  as now.
- These errors are legacy strings. In the editor, a missing item underlines its
  name and a missing module underlines the whole import line.

### Evidence

- `UnknownImportTest` (13), new:
  - each form above, with its message and line;
  - a block import and a test's import;
  - a module file;
  - a module below the program's own;
  - a scope the program declares;
  - every accepted form, which still compiles and runs: module, namespace
    wildcard, selected and dotted item, type function, scope, scope member, and
    a library whose own imports resolve.
- Full compiler run: **2,409 tests, 2,218 passed, 191 failed, 0 skipped**,
  against 2,396 / 191 at `c6550959` in a scratch worktree. The 13 added tests
  pass. The same 191 tests fail, none newly. Only the two `ResultUnwrapOrTest`
  messages changed, as described above. Before the fixtures were repaired,
  the same run showed exactly the eight stale imports above and
  `useDoesNotCreateBareAlias`. That test is why the program's own scopes are
  not missing.
- AZLS: 91/91, as at `c6550959`.

```sh
./gradlew :compiler:desktopTest --offline --console=plain --tests '*UnknownImportTest' --tests '*ModulesTest' --tests '*CastSpecTest' --tests '*ConvertSpecTest'
./gradlew :compiler:desktopTest --offline --console=plain
./gradlew :azls:test --offline
```

## 2026-09-22 — 007: block imports in module files, type-level imports, §5.2 receivers

014's import check is committed as `c45f1b6`; this entry starts from it. It
covers the three items the 2026-09-19 007/014 entry left open.

### Block imports in a file that declares a module

The parser hoisted every block import in a file with a `module` header to module
scope. That covers every library module and any program that declares one.
One function's import then reached its siblings. Measured on `c45f1b6` with
the new `ModuleBlockImportTest`, 5 of 8 failed:

- two library functions importing `answer` from `lib.one` and from `lib.two`
  both called `lib.one`'s, and printed `1` and `1`. That is a silent miscompile;
- a library function used `answer` that only its sibling imported, and compiled;
- a `module app` program's block import reached `main`.

**Change.** A block import is a `Stmt.Import` in every file. A library body now
resolves like a program's:

- As the dependency walk adds a library declaration, it finds the blocks in it
  that import.
- It binds each block's imports as that library's imports bind. That allows
  its own library's `confined` modules and follows re-exports
  (`libraryImportBindings`, now shared with `libraryScope`).
- It injects what the block uses from them.
- The module's renamer then renames those uses within the block alone. A
  block's binding stands closer than the module's, and a local closer still.
- The blocks are keyed by module, because an import compares by value and two
  files may write the same one at the same position.

The standard library's one block import, `std.container.queue`'s metadata test
importing `reflect`, now binds in that test.

### Type functions and type macros

Probed on `c45f1b6` with a test library, `lib/rows.az` in
`TypeLevelImportTest`. It declares a type function `wider<A, B>` and a type
macro `macro @rows { $T => Rows<$T> }`. `rows` is the test's name, not
language syntax, and a use is written `@rows Int` since the entry below.

| Program | Before | After |
|---|---|---|
| file `import lib.rows` | works | works |
| block `import lib.rows` | not in scope | works, in that block |
| `import lib.rows::wider`, `::{Rows, rows}` | imported nothing | selects it |
| use outside the importing block | rejected (as `type mismatch … declared wider`) | rejected; the macro now says `undefined type macro 'rows'` |

- *Selection.* File-level and block imports share one helper
  (`typeLevelImportedBy`). It takes a module's type functions and named
  macros, all below a namespace, or the one a dotted path selects, and follows
  re-exports. Type functions did not follow re-exports before; type macros did.
- *Block scope.* Inside the block, each use is renamed to the module-qualified
  spelling a program may write in full (`std.traits.promote<…>`), and a copy of
  the declaration is added under that name. It is not renamed to
  `module__name`: `TypeFunctionEvaluator` resolves a bare use to the one
  declaration ending in `__name`, the scope suffix, which would have leaked it
  to the whole program. A shape macro has no name to scope, so a block cannot
  import one.
- *Macro templates read their own module.* A named type macro's template now
  resolves in the module that declares it, at file scope and in a block. Its
  names are walked in that module's scope, injected, and renamed to the
  identities they were injected with. Before, `Rows<$T>` meant whatever the
  use site called `Rows`. That was nothing when the program imported only the
  macro, and the program's own `Rows` when it declared one. This is 028's
  "macro templates are not renamed", for type macros only; expression macros
  are unchanged.

### FUNCTIONS_DIP §5.2: the owned receiver keeps its bare spelling

§5.2 said there is no `func .consume()`. The parser accepted it, and
`ReceiverShorthandTest` and `PropReceiverTest` used it; all three arrived in
`e849c53`. User decision: keep it. §5.2 and §5.5 now say that `func .name()`,
`func Self.name()` and `func (self: Self).name()` are the same owned
receiver. `ReceiverShorthandTest.theOwnedSpellingsAgree` checks it. A property
still only observes; `prop .name` stays rejected.

### Not changed, recorded

- `std.traits`'s `promote` resolves in a program that imports nothing
  (probed; 014). A type function that injected library code names is added
  under its short name, visible to the whole program
  (`dependencyTypeFunctions`).
- A type function or macro that is not in scope is reported as a type mismatch
  against its bare name (`declared wider but initializer is Float`), not as an
  undefined type (020).
- `fin r: @rows Int = .(4)` failed; fixed in the entry below.
- A type function's body names sibling type functions by short name, program
  wide. A block-imported one whose body calls a sibling that nothing else
  imported would not find it. No standard type function does.
- A block's imports do not bring shape macros (unnamed type grammar); import
  those at file scope (028).

### Evidence

- `ModuleBlockImportTest` (8), new: 5 fail on `c45f1b6`. The cases:
  - sibling providers;
  - a sibling leak;
  - nested blocks, both ways;
  - a block-imported type;
  - local shadowing;
  - a library block import that the program cannot name;
  - a `module app` program.
- `TypeLevelImportTest` (8), new:
  - block type functions and macros, and their leaks;
  - selection by name;
  - a macro template reading its own module, and one not captured by the
    program's `Rows`;
  - a library block importing both.

  With the template renaming disabled, four of them fail.
- `TestScopedImportTest.aModuleKeepsItsBlockImportsInTheBlock` replaces the
  test that asserted the hoisting. The 007 entry had already said that passing
  it proved nothing.
- `ReceiverShorthandTest` 9/9, `PropReceiverTest`.
- Full compiler run: **2,425 tests, 2,234 passed, 191 failed, 0 skipped**,
  against 2,409 / 191 at `c45f1b6`. The same 191 fail, and none newly. The
  receiver test was added after it; its suite passes.
- AZLS: 91/91.

```sh
./gradlew :compiler:desktopTest --offline --console=plain --tests '*ModuleBlockImportTest' --tests '*TypeLevelImportTest' --tests '*LexicalImportTest' --tests '*TestScopedImportTest' --tests '*ReceiverShorthandTest'
./gradlew :compiler:desktopTest --offline --console=plain
```

## 2026-09-22 — Type macros are invoked with `@`; `.(…)` builds their type

007 is committed as `b0df2c3`. MACRO_DIP §3.2 said type macros were removed.
The compiler kept them, and the engine's `macro @query` (2026-08-22) uses them.
User decision: they stay, and a use leads with the macro's sigil like any other
macro call, `fin r: @rows Int = .(4)`.

**The sigil is required.** The parser read a type macro both as `@rows Int` and
bare, as `rows Int`: any lowercase name followed by a type in a type position.
A bare use is now an error:

```text
a type macro is invoked with '@': write '@rows …' instead of 'rows …' at line 4
```

No source had to change apart from `TypeLevelImportTest`. The engine declares
`@query` and its documentation already writes `@query [...]`; nothing calls it.
The standard library and the compiler tests have no other type macros.
`azora-engine/proposals/ARCHITECTURE.MD` still writes `res Time` and
`query [...]`. It is a proposal, and `res` has no declaration.

**`.(…)` builds the expanded type.** A binding's `.(args)` was turned into
`<declared type>(args)` by `parseInitializer` while parsing. For
`fin r: @rows Int = .(4)`, the declared type is still the macro call at that
point, so the call named the macro's encoded name:
`undefined function '__azora_named_type_macro__Prefixrows'`. An argument and a
return already kept the leading dot for the resolver, which sees the expanded
type, and both worked. A binding whose type is a macro call now does the same.

**MACRO_DIP.** New §1.3, *Type arms*, covers:

- uppercase holes, and the arm's `with`/`without` clauses;
- the `@` rule;
- that the template reads its module;
- imports: by module, by name, in a block;
- `.(…)`.

§3.2 is now *Built-in type grammars*: `macro .Type` and the grammars it built in
(`[T]`, `{T}`, …) stay removed, and `res`/`query` point to §1.3.

### Evidence

- `TypeLevelImportTest` (10):
  - `aTypeMacroIsInvokedWithItsSigil`, new, rejects `rows Int`;
  - `theInferredConstructorBuildsTheExpandedType`, new, runs `.(4)` in a
    binding and `.(5)` as an argument. Before the parser change it failed with
    the error above;
  - the six other tests that use the macro now write `@rows`.
- A probe of `.(…)` against `@rows Int` in `fin`, `var`, argument and return
  position printed 4, 4, 5 and 6. Against `Rows<Int>` it was already correct.
- Full compiler run: **2,428 tests, 2,237 passed, 191 failed, 0 skipped**. The
  same 191 fail as in 007's run; the three added tests pass.
- AZLS: 91/91.

```sh
./gradlew :compiler:desktopTest --offline --console=plain --tests '*TypeLevelImportTest' --tests '*MacroTest' --tests '*MacroFormTest'
./gradlew :compiler:desktopTest --offline --console=plain
```

## 2026-09-27 — 010.C4.5 / 057: global initializer dependencies survive release optimization

The workspace starts at `ad7b0af`, with existing edits to the Azora AZLS source.
A fresh full compiler run reproduces **2,428 tests, 2,237 passed, 191 failed,
0 skipped**. `Bugs.MD` and the release roadmap carry older inventories; their
headers now point to the current delivery plan and progress log.

**The global set/map failure is an optimizer defect.** The two existing LLVM
tests pass on raw IR and fail on optimized IR: unused-symbol elimination visits
function bodies, then gathers references from global initializers after the
function worklist has finished. A factory referenced only by a global is
therefore removed, leaving a call to an undefined symbol. Adding its name to a
used-name set does not retain the factory or its transitive dependencies.

Functions and global initializers now share a traversal. A reachable global
can call a function that reads an earlier global, whose initializer calls
another function; all are followed before filtering declarations. Test bodies,
spec implementation methods and exported bridge globals retain their roots.
Indirect singleton dependencies are followed from global/test references as
well as function bodies. Unreferenced functions and globals are still removed.
This changes reachability, not initializer order.

**Regression evidence:**

- `GlobalInitializerTest`, three new cases, and `GlobalInitializerExecTest`,
  two new cases: interpreter, LLVM and WASM execution of raw and actual
  optimized IR. They check transitive function/global dependencies, global
  List/Set/map factories, duplicate set elements, exactly-once evaluation in
  declaration/element order, and thread-local custom factories with empty and
  nonempty literals.
- `IrOptimizerReachabilityTest`, two new cases: exported bridge globals and
  test roots keep transitive dependencies without `main`; unrelated functions
  are still removed and export metadata survives.
- Both existing `LlvmAggregateExecTest` global set/map regressions now pass.
- Initial focused run: **43 tests, 0 failures, 0 skipped**.

**The shared release helpers now execute optimized IR.**
`StdCollectionLiteralTest`, `LiteralFactoryTest` and `WitnessTest` compiled with
`release = optimized` but returned `result.ir` in both branches. Their native
and interpreter loops therefore exercised raw IR twice. They now return
`result.optimizedIr` for the release branch. The follow-up run covering these
three families and the new regressions passes **48 tests, 0 failures, 0 skipped**.
Earlier claims of release execution through these helpers should be read with
that correction; the new run supplies actual optimized execution evidence.

**Full verification:** **2,435 tests, 2,246 passed, 189 failed, 0 skipped**.
Compared by `(suite, test)` identity against the freshly measured pre-change
run, the global set/map tests are the only previous failures now passing, no
previously passing test fails, and all seven new tests pass. AZLS is **91/91**.
The full run precedes the helper corrections; the affected suites were rerun
afterwards and all still pass. The inventory, failure messages and comparison
are saved in
[`ECOSYSTEM_BASELINE_GLOBAL_INITIALIZERS_2026_09_27.json`](ECOSYSTEM_BASELINE_GLOBAL_INITIALIZERS_2026_09_27.json).

**Recorded for follow-up (055/059):** `WasmExec` obtains the exported memory
only after instantiation, but a WASM start function runs global initializers
during instantiation. An initializer printing a string therefore reaches the
host's `readStr` before its memory variable is assigned. The ordering regression
uses numeric initialization logs, which do not require host memory access.
String output during WASM start remains unqualified. Collection lifetime and
remaining factory/backend/tooling qualification remain under 010.C5.

```sh
./gradlew :compiler:desktopTest --offline --console=plain
./gradlew :compiler:desktopTest --offline --console=plain --tests '*GlobalInitializer*' --tests '*IrOptimizerReachability*' --tests '*LlvmAggregateExecTest.globalMapInitializerRunsBeforeMain' --tests '*LlvmAggregateExecTest.globalSetInitializerRunsBeforeMain' --tests '*StdCollectionLiteral*' --tests '*LiteralFactory*'
./gradlew :compiler:desktopTest :azls:test --continue --offline --console=plain
./gradlew :compiler:desktopTest --offline --console=plain --tests '*GlobalInitializer*' --tests '*IrOptimizerReachability*' --tests '*StdCollectionLiteral*' --tests '*LiteralFactory*' --tests '*Witness*'
```

## 2026-09-27 — 022.2 / 025: enforce variadic pack-size constraints

The prior work package leaves **2,435 compiler tests, 189 failures**. Three
existing `TypeFunctionTest` failures accept invalid concrete specializations:
`stdlibPromoteRequiresTwoTypes`, `variadicConstraintProducesDiagnostic` and
`aConcreteInvalidSpecializationIsAnError`. GENERICS_DIP §12 and the standard
library use `.size`, but `ConstraintEvaluator` reads only `.length`; the
canonical spelling therefore yields an unknown constraint and is accepted.

The evaluator now reads both `T.size` and `(...T).size`, retaining `.length`
compatibility. A shared binding adapter binds fixed parameters individually and
counts only the variadic tail. Previously, pack specialization counted the
fixed prefix, then overwrote its pack binding with the first element's type.
Type properties and specialized impl-method filtering now use the same adapter.
Generic function calls bind the tail even when it has zero or multiple elements;
an undecidable fixed argument no longer suppresses a decidable size predicate.
A runtime spread leaves its cardinality unbound instead of counting it as one
written argument.

**Regression evidence:** `PackSizeConstraintTest` adds eight cases and
`PackSizeConstraintExecTest` two. They cover canonical/grouped/legacy queries,
empty/singleton/oversized calls, both equality boundaries, fixed-prefix exclusion,
a generic fixed argument, constrained type-property overload selection,
specialized method availability, and deferred runtime-spread compilation. A valid
heterogeneous pack/type-property program and a generic forwarding call print
`42`, `7`, `2` on the interpreter, LLVM and WASM with raw and actual optimized IR.
The focused qualification also covers `TypeFunctionTest`, witness dispatch and
literal-factory bounds: **71 tests, 0 failures, 0 skipped**.

**Final full verification:** **2,445 tests, 2,259 passed, 186 failed, 0 skipped**.
Compared by `(suite, test)` identity with the prior 2,435-test / 189-failure
inventory, the three existing failures above now pass, no previously passing
test fails, and all ten new tests pass. AZLS is **91/91**. The inventory,
diagnostic messages, comparison and focused qualification are saved in
[`ECOSYSTEM_BASELINE_PACK_SIZE_2026_09_27.json`](ECOSYSTEM_BASELINE_PACK_SIZE_2026_09_27.json).

**Remaining scope:** element-wise variadic conformance still evaluates as
unknown, as do unsupported constraint expressions. Runtime-spread compilation
is covered; its execution is not qualified. A probe calling
`count(...values)` with `fin values = [10, 20]` throws
`Spread should be handled by evalCall, not evaluated directly` in the
interpreter. `IrGenerator` nests the spread inside the variadic `ArrayLiteral`,
while `evalCall` only splices spreads that are direct call arguments. This
lowering defect remains under 025. Tasks 022 and 025 stay open.

```sh
./gradlew :compiler:desktopTest --offline --console=plain --tests '*PackSizeConstraint*' --tests '*TypeFunctionTest'
./gradlew :compiler:desktopTest --offline --console=plain --tests '*PackSizeConstraint*' --tests '*TypeFunctionTest' --tests '*Witness*' --tests '*LiteralFactory*'
./gradlew :compiler:desktopTest :azls:test --offline --console=plain --continue
```

## 2026-09-27 — 013.1 / 025.1 / 053.1 / 056.1: 28 roadmap checks

The user requested at least ten checks before stopping. This work package checks
**28 concrete behaviors**, rather than marking 28 broad roadmap items complete.
The starting inventory is **2,445 compiler tests, 186 failures**, from the
pack-size work package. Existing workspace edits, including the Azora AZLS
source, are preserved.

**Repairs:**

- The interpreter expands `IrExpr.Spread` segments within the array packed for a
  variadic tail. Each source is evaluated once and copied before the next
  argument, so a later argument's mutation cannot change an earlier segment.
- Semantic inference reads the source array's element type, diagnoses non-array
  sources, and rejects incompatible homogeneous or explicitly typed arguments.
- Variadic calls use the callee's physical element type. A generic callee reads
  erased slots even when the caller knows `T`; packing narrow typed slots had
  supplied the wrong ABI. LLVM and WASM load each spread's source width and
  convert into the destination slots, retaining floating-point bits at erased
  boundaries. The native builders free replaced temporary buffers and preserve
  the caller's source array; the final buffer follows the existing array
  allocation/lifetime policy. Growth is per segment, not an amortized builder.
- WASM emits array `for-in` loops instead of silently dropping them. It evaluates
  the source and length once, loads typed elements, binds the ordinal and routes
  `continue` through progression. Nested loops and `break` keep their targets.
  Unsupported non-array iteration now reports a backend error.
- Control-header brackets no longer absorb the following loop body as a capture
  lambda. Explicit argument, grouping and literal delimiters restore lambda
  parsing internally and then restore the header's suppression state.
- A spread occupying a fixed slot of a variadic function is explicitly rejected
  until that expansion is implemented. Ordinary fixed-arity expansion still
  executes on the interpreter; LLVM/WASM generators now report their unsupported
  expansion instead of emitting a null/pointer placeholder.

**Individually checked behaviors:** I/L/W means interpreter, LLVM and WASM,
each on raw and actual optimized IR. Compiler rejection rows run in both modes;
parser rows inspect the AST and preserve the distinction they name.

| # | Check | Roadmap | Result / evidence |
|---|---|---|---|
| 1 | Nonempty runtime array spread | 025/052/054/056 | `123`; I/L/W |
| 2 | Empty spread has zero elements | 025 | `0`; I/L/W |
| 3 | Singleton spread retains its element | 025 | `7`; I/L/W |
| 4 | Scalars, multiple spreads and an empty segment preserve order | 025/041 | `123456`; I/L/W |
| 5 | Explicit fixed prefix stays outside the variadic tail | 025 | `9123`; I/L/W |
| 6 | Homogeneous inference uses spread element type | 023 | Inferred Int result `20`; I/L/W |
| 7 | Double values cross erased slots | 016/053 | `2.5`; I/L/W |
| 8 | Byte source width converts to erased slot width | 016/053 | `9`; I/L/W |
| 9 | Float values retain their bits at erased boundaries | 016/053 | `2.5`; I/L/W |
| 10 | String inference and result ABI | 023/053 | `two`; I/L/W |
| 11 | Ordinary generic variadic calls retain physical slots | 053 | Byte/Double/Float/String: `9`, `2.5`, `2.5`, `two`; I/L/W |
| 12 | Argument expressions run exactly once, left to right | 041/057 | Trace `1, 2, 4, 5, 7`, then `1234567`; I/L/W |
| 13 | Callee mutation affects its packed copy, not the source | 025/062 | `9`, then unchanged source `1`; I/L/W |
| 14 | An earlier spread is copied before a later argument mutates its source | 041/057 | `123`, then source `9`; I/L/W |
| 15 | Nested generic forwarding preserves the full tail | 025 | `123`; I/L/W |
| 16 | Variadic closure accepts a runtime spread | 025/044 | Length `3`; I/L/W |
| 17 | Array ordinal, `continue` and `break` progression | 042/056 | `134`; I/L/W |
| 18 | Nested array iteration preserves outer and inner rows | 042/056 | `13142324`; I/L/W |
| 19 | Iterable expression is evaluated once | 041/042 | Trace `99` once, then `123`; I/L/W |
| 20 | Typed array iteration loads Byte, Float and String correctly | 053/056/062 | `7, 9, 1.5, 2.5, one, two`; I/L/W |
| 21 | Scalar cannot be spread as an array | 017/025 | Located compiler diagnostic |
| 22 | Homogeneous spreads cannot mix Int and String elements | 023/025 | Compiler diagnostic |
| 23 | Int variadic parameter rejects a String spread | 017/025 | Compiler diagnostic |
| 24 | Unsupported spread into a fixed variadic slot is explicit | 025/060 | Compiler diagnostic; fixed arguments must be passed separately |
| 25 | Literal loop headers retain array shape and body | 013 | Numeric, identifier and String arrays; parser AST checks |
| 26 | Capture lambda inside a header's call argument remains a lambda | 013/044 | Parser AST check |
| 27 | Array-valued header can contain capture lambdas | 013/044 | Parser AST check |
| 28 | Fixed-arity native expansion is explicitly unsupported | 054/056/060 | Interpreter prints `6`; both generators reject, raw/optimized |

`VariadicSpreadTest` supplies twenty positive cases and four diagnostic cases.
`VariadicSpreadExecTest` independently reports every positive case, backend and
optimization mode: **80 native test cases**, alongside interpreter execution in
both modes, for **120 program executions** across the three backends. Three new
`CollectionLiteralSyntaxTest` cases check parser shape; two
`FixedSpreadBackendDiagnosticTest` cases qualify the explicit target limitation.
The earlier `PackSizeConstraintTest` spread case now executes and prints `2`.
The integer encoding fixtures explicitly cast their erased values to Int;
unbounded generic arithmetic is not qualified by those fixtures.

**Focused verification:** **122 tests, 0 failures, 0 skipped**. The broader
callable/lambda qualification has **223 tests, 220 passed, 3 failed, 0 skipped**;
all three failure identities already occur in the starting baseline:
`LambdaTest.callableKindsAreStorablePackFields`,
`LambdaTest.aReceiverIsSuppliedByUsingOrByAReceiverCall` and
`PropCallTest.anArrayEmptinessPropertyIsNotCallable`. The full run also includes
the closure and fixed-expansion additions made after that broader check.

**Final full verification:** **2,554 tests, 2,369 passed, 185 failed, 0 skipped**.
Compared by `(suite, test)` identity against the starting inventory,
`LoopTest.forArrayWithADeclaredRowType` now passes, all **109 new tests** pass,
no previously passing test fails, and no tests were removed. AZLS is **91/91**.
The dated inventory includes all test identities, remaining diagnostic messages,
the comparison and both focused qualifications:
[`ECOSYSTEM_BASELINE_VARIADIC_SPREAD_2026_09_27.json`](ECOSYSTEM_BASELINE_VARIADIC_SPREAD_2026_09_27.json).

**Remaining:** full compile-time type-pack expansion, element-wise conformance,
constraints whose spread cardinality is unresolved, fixed-parameter expansion,
non-array WASM iterators, labeled for-in metadata, iterator invalidation,
unbounded generic arithmetic and complete temporary-array lifetime qualification
remain open. Tasks 013, 025, 053 and 056 keep their broader acceptance gates.

## 2026-09-27 — 025.2 / 062.1: 34 spread-constraint roadmap checks

Continued from the 2,554-test / 185-failure inventory and the earlier request to
check at least ten things. This package checks **34 individually named
behaviors**, preserves existing workspace edits and advances narrow parts of
025 and 062. Their broader acceptance gates remain open.

The first twenty reproducers exposed thirteen failures. Six invalid size calls
compiled because any written spread suppressed the pack binding. Pack-wide
Hash predicates also requested a single descriptor for the heterogeneous pack,
giving an inference error instead of checking its elements. Follow-up cases
exposed stale array-size metadata after resizing and missing fixed-head witness
inference when a heterogeneous tail prevented `inferredTypeArgs` from being a
single list.

**Repairs:**

- Resolve each spread source once during argument checking and keep its element
  type separate from cardinality. Count scalars and stable spread segments,
  excluding fixed parameters. Canonical `.size`, grouped queries, `.length`,
  comparisons and range membership use the resulting count.
- Track stable immutable array bindings by symbol identity, including global
  bindings and immutable aliases. Fresh literals and simple fresh-literal
  function results have a known count. Mutable bindings, aliases of mutable or
  resized arrays, fields, casts, lazy storage and more complex producers remain
  undecided. A declared `Array<T, N>` alone is insufficient: existing resizing
  can retain N while the runtime header changes.
- Pack bindings carry known element types. Nominal bounds inspect every known
  member, use integer-family conformances for widths, and accept an empty pack
  vacuously. A known violation is retained beside an unresolved member. Unknown
  cardinality does not pretend to be the number of written arguments; an
  unresolved spread may be empty, so its nonconforming type alone does not
  disprove a universal bound.
- Pack-wide predicates do not request a singular runtime descriptor. Fixed
  arguments still pass their own descriptors, inferred from the already lowered
  arguments when necessary. Indexed and iterated heterogeneous pack elements
  explicitly diagnose unsupported Hash/Equal/Order witness dispatch. Loop
  lowering retains the declared element reference used for this check.
- Builtin array `add`, `insert`, `remove` and `fill` use the existing receiver
  mutability checker. A `fin` array cannot be resized through its binding.

**Individual checks:** I/L/W means interpreter, LLVM and WASM on raw and actual
optimized IR. Compiler diagnostics run in both compilation modes. The three
resizing rows execute on interpreter/LLVM and assert WASM's existing unsupported
`add` diagnostic; they are not counted as WASM executions.

| # | Check | Result / evidence |
|---|---|---|
| 1 | Stable local spread satisfies its size | `2`; I/L/W |
| 2 | Empty Double spread satisfies a universal Hash bound | `0`; I/L/W |
| 3 | Scalars and multiple spreads contribute expanded counts | `6`; I/L/W |
| 4 | Fresh literal function result is evaluated once | Trace `99`, then `3`; I/L/W |
| 5 | Grouped pack size supports range membership | `2`; I/L/W |
| 6 | Legacy `.length` uses expanded cardinality | `2`; I/L/W |
| 7 | Unknown-size array forwarding remains undecided | `2`; I/L/W |
| 8 | Int, Byte and String pack members satisfy Hash | `4`; I/L/W |
| 9 | Fixed prefix is excluded from tail size and bounds | Double head, Hash tail: `2`; I/L/W |
| 10 | A satisfied size disjunct admits non-Hash Double elements | `2`; I/L/W |
| 11 | Mutable resized source does not use its stale type size | `3`; I/L, W diagnostic |
| 12 | Immutable alias of resized source stays undecided | `3`; I/L, W diagnostic |
| 13 | Declared producer size alone does not prove its live count | Declared size 2, live count `3`; I/L, W diagnostic |
| 14 | Empty direct pack satisfies its universal bound | `0`; I/L/W |
| 15 | Fixed-head witness inference survives a heterogeneous tail | `true`, then `2`; I/L/W |
| 16 | Immutable global source retains its size | `2`; I/L/W |
| 17 | Immutable alias of a stable source retains its size | `2`; I/L/W |
| 18 | Short known spread is rejected | Located where diagnostic: `1 vs 2` |
| 19 | Long known spread is rejected | Located where diagnostic: `3 vs 2` |
| 20 | Empty spread contributes zero arguments | Located where diagnostic: `0 vs 0` for `> 0` |
| 21 | A scalar beside a spread increases cardinality | Located where diagnostic: `3 vs 2` |
| 22 | Multiple spreads add their lengths | Located where diagnostic: `4 vs 3` |
| 23 | Known fresh result size is checked | Located where diagnostic: `3 vs 2` |
| 24 | Every scalar member must conform | Double does not implement Hash |
| 25 | Every member of a known nonempty spread must conform | Double does not implement Hash |
| 26 | Empty good segment cannot hide a bad scalar | Double does not implement Hash |
| 27 | Unknown spread cannot hide a known bad scalar | Double does not implement Hash |
| 28 | Shadowed bindings retain independent sizes | Inner size `1 vs 2` rejected |
| 29 | Fixed-head violation is checked beside a spread tail | Double head does not implement Hash |
| 30 | Immutable array resizing is rejected | `add`, `insert`, `remove`, `fill`; located mutability diagnostics |
| 31 | Indexed pack Hash dispatch is explicitly unsupported | Located compiler diagnostic |
| 32 | Iterated pack Hash dispatch is explicitly unsupported | Located compiler diagnostic |
| 33 | Pack Equal dispatch is explicitly unsupported | Located compiler diagnostic |
| 34 | Pack Order dispatch is explicitly unsupported | Located compiler diagnostic |

`PackSpreadConstraintTest` has **34 tests**. `PackSpreadConstraintExecTest`
independently reports **68 native execution/diagnostic cases**: 62 executions
and six WASM resize diagnostics. Together with both interpreter modes, the
positive fixtures have **96 program executions** across available targets.
The focused qualification also runs existing `ArrayTest`,
`ConstGenericArrayTest`, `BindingMutabilityTest`, witness, pack-size and runtime
spread suites: **259 tests, 0 failures, 0 errors, 0 skipped**.

**Full verification:** **2,656 compiler tests, 2,471 passed, 185 failed, 0
errors, 0 skipped**. All **102 new tests** pass. Compared by `(suite, test)`
identity with the preceding 2,554-test inventory, the same 185 pre-existing
failures remain, no previously passing test fails and no tests were removed.
AZLS passes **91/91**, with no skips. The compiler task exits with failure
because those existing failures remain; this is not a clean full-suite gate.

Commands:

```text
./gradlew :compiler:desktopTest --tests '*PackSpreadConstraint*' --tests '*ArrayTest*' --tests '*MutabilityTest*' --tests '*WitnessTest*' --tests '*WitnessExecTest*' --tests '*PackSizeConstraint*' --tests '*VariadicSpread*' --offline --console=plain
./gradlew :compiler:desktopTest :azls:test --offline --console=plain --continue
git diff --check
```

The full-suite comparison and remaining failure identities are recorded in
[`ECOSYSTEM_BASELINE_SPREAD_CONSTRAINTS_2026_09_27.json`](ECOSYSTEM_BASELINE_SPREAD_CONSTRAINTS_2026_09_27.json).

**Remaining:** unknown-cardinality constraint obligations are deferred rather
than proved; complete type-pack expansion, per-element runtime descriptors,
fixed-parameter spread expansion, const-size preservation under mutable
resizing, comprehensive alias/cardinality tracking and WASM resize execution
remain open. Pack/spec specialization conformance still needs symbol-table
evaluation in the earlier expansion stages. This package qualifies stable
function-call predicates, not the complete 022/025/062 acceptance gates.

```sh
./gradlew :compiler:desktopTest --offline --console=plain --tests '*VariadicSpread*'
./gradlew :compiler:desktopTest --offline --console=plain --tests '*VariadicSpread*' --tests '*PackSizeConstraint*' --tests '*CollectionLiteralSyntaxTest' --tests '*Callable*' --tests '*Lambda*'
./gradlew :compiler:desktopTest --offline --console=plain --tests '*VariadicSpread*' --tests '*FixedSpreadBackend*' --tests '*PackSizeConstraint*' --tests '*CollectionLiteralSyntaxTest'
./gradlew :compiler:desktopTest :azls:test --offline --console=plain --continue
```

## 2026-10-01 — Loop variables, `.Variant` shorthands, and the standard library they broke

Starts from `ad7b0af` plus the uncommitted 2026-09-27 work above. A fresh full
run reproduces that work's inventory exactly: **2,656 tests, 185 failed**.
Grouped by message, the 185 point to several shared defects. This package
fixes six of them, and the stale fixtures in front of them.

### 1. A `for` loop's variable accepted writes that did nothing

All three backends bind a loop variable afresh on every iteration. Writing it
never changed what the loop visits. The resolver nevertheless declared a
*range* loop's variable mutable, so `i += 2`, `i++` and `i = 0` compiled and
were ignored. (An array or iterator loop's row was already immutable.) Measured:
`for i in 0..6 { …; i += 2 }` visits all seven values on the interpreter, LLVM
and WASM.

The standard library had four loops written as if the write stepped them:

- *`sortBy`* (`std.algorithm`) stepped its merge windows with
  `lo += width * 2` inside `for lo in 0..count`. It merged every overlapping
  window instead. On 14 elements the old code printed `12 12 12 12 …`,
  duplicating some elements and dropping others.
- *`_decimalValue`* (`std.os`) used its loop variable as the accumulator and
  returned it after the loop, out of scope. That is the `undefined value
  'value'` behind the `OsStdlibTest` failures.
- *`Path.normalized`* (`std.filesystem`) ended its body with a leftover
  `index++`.
- *`mergeSort`* (`std.algorithm`, below) did not compile at all.

**Change.** Every loop's own row and `with` index is immutable, range loops
included. `=`, compound assignment, `++` and `--` on one are rejected:

```text
line 5: 'i' is the loop's variable, bound afresh on every iteration - writing it
would not change what the loop visits; step a 'while' loop by hand, or copy it into a 'var'
```

`sortBy` steps a `while` loop. `_decimalValue` keeps a `var value` and loops
over its indices.

### 2. Inclusive ranges one past the end

`..` includes its upper bound. Three `std.algorithm` loops used it as if it
were `..<`:

- `_insertionSortRange` iterated `start + 1..stop`, reading `values[stop]`.
  The `IndexOutOfBoundsException` in `SortStdlibTest` came from this, on
  every input whose last run ends at the array's end.
- `mergeSort` copied `0..mid` into a `mid`-element half and `0..arr.size` into
  the `arr.size - mid` right half.

### 4. A capture list read the next statement as its lambda

`isReceiverLambdaAhead` decides whether `[…]` opens a lambda's capture list
by what follows the `]`. It skipped line breaks, and after a name it took the
first `{` it met, even inside an argument list:

```azora
fin values: Array<Int> = [5, 3]
println(f(values, { x -> 0 - x }))   // [5, 3] read as captures of a receiver lambda
```

That failed with `Expected a capture name in a lambda bracket list, got '5'`
(`ForExpressionTest.itWalksWhateverForWalks`). Now a receiver name must stand on
the same line as the `]`, and a `{` counts only outside parentheses.

### 5. `.Variant` without its type, in two places

- *`when` patterns.* `.Flag(v) -> …` matched, but its binding was never defined
  (`undefined value 'v'`). The resolver and the IR generator bound payloads
  only for `Value.Flag(v)`. Both now read patterns through `SlotPatterns`, where
  `.Flag(v)` means a variant of the scrutinee's type. A `when` *expression*
  rejects `.Flag(v)` with the same message it gives `Value.Flag(v)`, instead of
  comparing against a construction.
- *`return .Variant` in a `T ?! E` function.* The parser wrote every such return
  as a throw, so returning a variant of the *success* type failed the function.
  `return .Nothing` from a `Value ?! ParseError` function ran the caller's
  `catch`. `std.serializer`'s parser returned `.Object(fields)` and so could not
  compile. The parser now marks these throws, and `ReturnedVariants` decides
  them once the program is complete:
  - a variant of the error set fails the function, as ERROR_HANDLING_DIP says;
  - otherwise a variant of the success type is returned;
  - a name both declare, or that no declared set has, is an error.

### 6. Serializer derivation and host bridges

- *Derived serializers named their helpers `std::serialFieldAt`*, a `std` scope
  that no longer reaches `std.serializer`'s declarations. They also imported
  `std.convert`, which does not exist, to call `convert::toString`. The
  `@Derive` metadata in `std/serializer.az` now names the helpers through their
  module (`std.serializer::serialFieldAt`), which a program's own declaration
  of that name cannot capture. Conversion uses the compiler's `toString`.
  `StdlibInjectionTest.serializerFixturesProduceGeneratedCodecMethods` asserted
  the `std.convert` import; it now asserts that it is absent. 20 failing tests
  were stopped here.
- *The interpreter's filesystem and process bridges never matched.* It looked
  up `fsRead`, `fsPathQuery`, `commandParts` and the rest by their local name.
  The library declares them `_fsRead`, … and a bridge keeps its declared name.
  Every interpreted file or process operation failed with `Undefined function`.
  LLVM strips the prefix itself (`substringAfterLast('_')`). The interpreter now
  uses the declared names; `symbolDenotes` is unchanged, so a program's own
  `_println` does not become the intrinsic.
- *Typo:* `Path.withExtension` declared `fin renamed: Bool` for a `String`.

### Smaller repairs

- A library that fails to parse is now reported to a program that selects a
  declaration from it (`import lib.vals::describe`). Before, the program saw
  014's `there is no module 'lib.vals.describe'`.
- Stale fixtures:
  - `Array::fill<T>(n)` was removed from std on 2026-08-17 and is now `.() * n`
    (`SortStdlibTest` ×4, `FilesystemStdlibTest` ×1);
  - `FilesystemStdlibTest.metadataReportsKindAndSize` imports the
    `std.time::Instant` it constructs.

### Decision needed: string index type

`std.string`'s `strCharAt` and `strSlice` take `UInt` indices since `46a372b`
(2026-08-26). Everything around them takes `Int`: `substring` and `charAt` in
`std.core`, `strLength`, `std.decimal`'s callers. So `strSlice` cannot compile,
and the six `DecimalStdlibTest` failures stop there. There are three options:

- revert the two functions to `Int`;
- convert at each call site;
- move string indices and lengths to `UInt` throughout.

The functions are unchanged.

### Not changed, recorded

- `.() * n` builds an array only in a binding. As a return value or an
  argument (`return .() * 3` from a function returning `Array<Int>`) it reports
  `cannot tell what '.' belongs to`. Without `import std.container.array`, even
  `var a: Array<Int> = .() * 1` reports a misleading
  `cannot infer element type of empty sequence literal`.
- The LLVM and WASM generators still carry `Array::fill` lowering that no
  library declaration reaches.
- `std.convert`: the serializer no longer needs it. CAST_DIP §1 still places
  `Into` and `From` there.

### Evidence

- `LoopVariableTest` (6), new: writes to range rows, array rows and `with`
  indices are rejected; reading, a `var` copy and a stepped `while` run.
- `AlgorithmStdlibTest` (3), new: `mergeSort` on 7 and 13 elements, `sortBy`
  across several passes, and `sortBy` stability. The two `sortBy` tests fail on
  the old `std/algorithm.az` with the old range-loop rule.
- `VariantReturnAndPatternTest` (7), new:
  - success and error variants returned from `if` and `when`;
  - an ambiguous name, and a name no set declares;
  - `.Flag(v)` bindings, in a program and in a library;
  - the `when`-expression rejection.
- Now pass: `SortStdlibTest` (6), `FilesystemStdlibTest` (15),
  `OsStdlibTest` (4), `TimeStdlibTest` (3), `ScopeQualifiedAccessTest` (4),
  `ThirdPartyFormatTest` (2), `CollectionCtorTest` (3), and the
  `StdlibInjectionTest` serializer tests, among others.
- Full compiler run: **2,672 tests, 2,528 passed, 144 failed, 0 skipped**, against
  2,656 / 185 measured on the same starting tree. 41 earlier failures pass, none
  newly fails, and all 16 added tests pass. Three still-failing tests lost their
  serializer errors; their remaining errors are unrelated (`tupleOf`, a
  missing `ArrayList` import).
- AZLS: 91/91.

```sh
./gradlew :compiler:desktopTest --offline --console=plain --tests '*LoopVariableTest' --tests '*AlgorithmStdlibTest' --tests '*VariantReturnAndPatternTest' --tests '*SortStdlibTest' --tests '*FilesystemStdlibTest' --tests '*OsStdlibTest'
./gradlew :compiler:desktopTest --offline --console=plain
./gradlew :azls:test --offline
```

## 2026-10-01 — String indices are `Int`; a failable `= expr` body is a return

Committed by the user as `0b25a97` on `0.1.0-dev`, which now carries all work.
The decision recorded above was taken: string indices are `Int`, as in
Kotlin, Java, C#, Swift and Go.

- `strCharAt` and `strSlice` take `Int` again. That matches `strLength`,
  `charAt` and `substring`, which they call and which their callers pass
  through. A negative index is out of bounds, like one past the end.
  `strSlice`'s precondition now states the whole range,
  `0 <= start <= end <= length`. `std.decimal` compiles.
- *Found by the new test:* `strCharAt` was written
  `= if … then .IndexOutOfBounds else charAt(…)`. A function's `= expr` body
  became a plain `return expr`, which skipped the parsing that recognizes
  `.Variant` in an `if` or `when` branch. In a function that declares `?!`,
  `= expr` is now read as `return expr`, so its branches fail or return as
  they would after `return` (`parseReturnTail`).

### Evidence

- `StdStringIndexTest` (2), new:
  - a length used as an index;
  - `-1`, `length` and an empty string are out of bounds.
- `VariantReturnAndPatternTest.anExpressionBodyFailsOrReturnsTheSameWay`, new.
- `DecimalStdlibTest` 6/6, previously 0/6. `StringIndexTest` 4/4.
- Full compiler run: **2,675 tests, 2,537 passed, 138 failed, 0 skipped**,
  against 2,672 / 144. The six decimal tests now pass, none newly fails, and
  the three added tests pass. AZLS: 91/91.

## 2026-10-01 — 008 assertion fixtures, `alloc [..]` literals, array `clone`

On `0.1.0-dev`, after the string-index entry above (2,675 / 138).

### 008: the removed `assert cond { "msg" }` form

33 failing tests did not reach what they test. Their programs still wrote the
message form step 004 removed (`Expected 'panic' after assert condition, got
'{'`). 46 assertions in 7 files became `assert cond panic "msg"`, inline ones
included. Every condition and message is kept, and the program around them
is unchanged:

- `TestAssertTraceTest`, `ContractsTest`, `TestMethodTest`, `TestScopeTest`;
- `WebsiteExamplesTest` and `PlaygroundExamplesTest`;
- `LlvmCodegenExecTest`.

`assert_messageMustBeString` keeps its non-`String` message (`panic 42`), which
is what it checks. Passing tests that check the old form is rejected were not
touched. All 33 now reach their own assertions and pass. The website and
playground pages these examples mirror may still show the removed form; they
live in other repositories.

### `alloc [10, 20, 30]` read its literal as one pointee

`var p: Int^ = alloc^ [10, 20, 30]` reported `type 'Int' does not define a
sequence literal factory`. The literal was seeded with the pointee type `Int`,
so 010.C3's factory lookup asked `Int` for a sequence factory.
`alloc .(a, b, c)` already treats its arguments as the run of slots the
pointer points at. A literal under `alloc` is now seeded the same way, as
`Array<pointee>`. Six pointer-arithmetic tests pass: `Tier3MemoryTest` ×4,
`PlaygroundExamplesTest.pointers` and `WebsiteExamplesTest.ch29_pointer_arithmetic`.

### `clone` on an array

A pack is clonable without an import when its fields are, and the compiler
supplies the body. `[1, 2, 3].clone()` reported `no method 'clone' on array`,
with or without `import std.container.array`. `Array`'s `derives Clone` sits on
the library declaration, which a program that never spells `Array` does not
pull in. An array, set, map or tuple is now clonable by what it holds
(`SymbolTable.isClonable`). That is the rule `SymbolCollector.fieldHasCapability`
applies to a pack's fields, and the resolver and the IR generator both use it.

That exposed the backends:

- *LLVM aliased.* Its copy (`__isolated`) passed an array's pointer through,
  so a clone shared its original's slots: `99|99` where `1|99` was meant. It
  now copies the buffer. Each element that is an array or a pack is copied in
  turn, matching the interpreter's deep copy on these programs. (The loop's
  phi learns its back-edge block after the body is emitted, since a nested copy
  opens blocks of its own.)
- *WebAssembly* has no `__isolated` lowering at all, for packs as for arrays.
  The assembler used to refuse a call to nothing. It now reports `WebAssembly
  cannot copy a … for 'clone' yet`.

### Not changed, recorded

- *Copy depth differs between targets.* LLVM copies a pack one level deep, so
  an array field stays shared with the original. The interpreter copies deep.
  OWNERSHIP_BORROWING_DIP promises that both values "own independent state"
  after `clone`. A pack's array field on LLVM does not keep that promise yet.
- *`std.parallelism.channel` is broken.* Its members read `queue`, `mutex` and
  `closed`, but its fields are `_queue`, `_mutex` and `_closed`.
  `WebsiteExamplesTest.ch31_channel` and
  `Tier4ConcurrencyTest.channelWithProducerTask` use an API that no longer
  exists (`channel()`, an unparameterized `Channel`). Both are 049 work.

### Evidence

- `ArrayCloneTest` (2) and `ArrayCloneExecTest` (2), new:
  - independent slots;
  - growth through `add`;
  - nested arrays and packs, on the interpreter and on LLVM, raw and optimized;
  - the WebAssembly diagnostic.
- Now pass:
  - all 33 assertion-form tests;
  - `Tier3MemoryTest`'s two `clone` tests and four pointer tests;
  - `WebsiteExamplesTest.ch29_clone` and `ch29_pointer_arithmetic`;
  - `PlaygroundExamplesTest.pointers`.
- Full compiler run: **2,679 tests, 2,583 passed, 96 failed, 0 skipped**,
  against 2,675 / 138. 42 earlier failures pass, none newly fails, and the four
  added tests pass. AZLS: 91/91.

## 2026-10-03 — Soundness: bounds, type arguments, shared borrows, receivers; std modules test themselves

Starts from `047f38c` on `0.1.0-dev`. A fresh full run measured **2,655 tests,
83 failing**. Every one of the 83 was already failing in the 2026-09-27
inventory, so the user's grouping and tuple-syntax commits regressed nothing.

### 1. A safe index names one of the array's own elements, on every target

- *LLVM read and wrote past the buffer.* `xs[i]` computed an address and
  never looked at the length header. It now compares unsigned against the
  size and calls `__azora_index_fail`, which prints
  `panic: index N out of bounds for size M` and stops.
- *WebAssembly* traps on the same comparison. The check is one folded `block`
  expression, so the array and the index are still evaluated where the source
  has them, and once.
- *The interpreter* surfaced the host's `IndexOutOfBoundsException`. It now
  panics with the same message.
- *LLVM lost a failing program's output.* `abort` does not flush stdio, so a
  program whose output was a pipe or a file lost everything it printed,
  including the panic's own message. Every stop now goes through
  `__azora_abort`, which flushes first.

### 2. `azora run` reports failure and keeps output

- Every failure exited with status 0. Statuses are now 1 for a program with
  errors, 2 for a wrong command line or file, and 101 for a program that ran
  and failed (`ExitStatus` in `Main.kt`).
- Output reaches the terminal as the program writes it (`IrInterpreter.outputSink`).
  It was buffered until the program ended, and lost when it failed.
- A runtime failure prints its message, not a JVM stack trace.

### 3. Type arguments are checked

`IrType.Named` identity leaves type arguments out so that storage can stay
erased. Every check that compared types with `==` therefore accepted a
`Box<Int>` as a `Box<String>`. The interpreter printed the int; LLVM ran
`strlen` on it and crashed.

- `isCompatible` now rejects a value whose type arguments disagree with the
  declared ones (`typeArgumentsConflict`). Arguments are invariant. A type
  parameter nothing has bound, the erased `Any`, and a use written without
  arguments agree with anything.
- Spec conformance reads the spec's arguments through the impl:
  `impl List<T> for ArrayList<T>` makes an `ArrayList<Int>` a `List<Int>`, not
  a `List<String>`. `TraitConformance` now records the impl's type parameters
  for this.
- A spec-typed value is accepted where a spec it refines is expected
  (`MutableList<Int>` as `List<Int>`). This was rejected outright before. LLVM
  passes the box through, since type ids are per concrete type.
- Diagnostics name the arguments (`IrType.shown()`), so a mismatch no longer
  reads `expected Box, got Box`.
- Inference unifies structurally. `Box<T>` against `Box<Int>`,
  `MapEntry<K, V>` through each variadic entry, `T?`, tuples and function types
  all bind their parameters. Arguments typed by the call go first, so a literal
  does not decide `T`. `hashMapOf(1 to "a")` and `first(box)` now infer.
- A generic pack's construction infers its arguments from its fields
  (`Box(7)` is a `Box<Int>`) and checks each field against them. Before this,
  generic constructions checked no field at all. `StructField.typeRef` keeps
  the field as written for this.
- A literal whose expected element type is an unbound `T` (erased to `Any`)
  takes its type from its elements. Before, every element was rejected as
  "must have type Any".

### 4. Nothing changes through a shared borrow

Through a `p: T&` parameter, every write compiled: field assignment, compound
assignment, `p.data[0] = …`, and a call to a `!.` member. Through `self&` only a
direct field assignment was refused. A `fin` binding could call a `!.` member.
Method symbols recorded no borrow modes, so method arguments went unchecked
too.

- `VariableSymbol.sharedBorrow` marks `p&` parameters and `&.` receivers.
  `checkValueMutable` refuses a write through any path rooted in one, naming
  the fix. A write behind a pointer is exempt: it changes the pointee, which
  the pointer's own type governs.
- Calling a member that changes its receiver needs a receiver that may change,
  whether the member is concrete or dispatched through a spec. Method arguments
  get the borrow checks function arguments get.
- `sharedBorrowedNamesOf` counted a `T!`-typed parameter as shared. Fixed with
  `isSharedBorrow`.
- *Conformance checks receivers.* An `!.` member cannot implement a `&.`
  requirement: a caller holding the value as the spec through a shared borrow
  would see it change. A member of the type cannot implement a member of a
  value, nor the reverse. A receiver-free member of a spec impl is no longer
  callable on a value.
- *std broke these rules.* `MutableList`, `MutableSet`, `MutableMap` and
  `MutableSortedMap` declared their mutators `&.`, against their own
  documentation. `HashMap`'s `put`, `putAll`, `remove`, `_putValue` and
  `_ensureInsertCapacity` mutated through `&.`. So did `Random.nextInt`,
  `nextRange` and `nextBool`. All now take `!.`.

### 5. Subscripts are declared, on specs too

- `List`, `MutableList`, `Map` and `MutableMap` declare `oper[]` / `oper[]=`.
  `xs[i]` on a spec-typed value dispatches the spec's own `index` member. LLVM's
  convention of calling a member that happened to be named `get` is gone. It
  also returned an erased word the call site never converted, so
  `t += xs[i]` on a `List<Int>&` did not assemble.
- `m[k] = v` on a `MutableMap` was refused ("not an array"). It now works on
  every target. A `Map<Int, String>` read printed `<value>` on LLVM. It now
  prints the string.
- Any pack could be indexed with any key and was typed `Any`. Now a type with
  no subscript cannot be indexed, and a subscript's operands are checked
  against the operator's parameters.
- A map's `oper[]` returned `get`'s `V?` as a `V`. It now panics on a missing
  key; `get` remains the way to ask.

### 6. Expected types build values

- `return .()` and `return .() * n` never compiled, and neither did
  `Array<Int>()` or `fin x: Array<Int> = .()`. The owner of an inferred
  construction was read only off a `Named` type, and `Array(…)` dropped its
  type arguments. The whole expected type is now recorded
  (`SymbolTable.inferredConstructionCall`), and the resolver and IR read the
  same record.
- `fin s: V = .Text("x")` bound `V.Text` and left `("x")` behind as a
  statement of its own (`parseInitializer`). The parser now reads only
  `.(args)` there.
- A conditional whose branches are all literals takes the type it lands in.
  For example, a `when` of `1.0`/`2.0` can fill a `Double` field. This is what
  `std.quantum` needed.

### 7. Diagnostics

- *Decorators are validated against declarations.* `AstValidator` kept a fixed
  list of decorator names. It now takes the libraries' declared decorators. One
  from a module the program did not import names the import. A misspelling
  gets a suggestion, from a new edit-distance helper (`diagnostics/Suggestions.kt`).
- *Scope members are named with their scope.* An undefined bare name now reads
  `'five' is part of scope 'Const', use 'Const::five' instead`. A program's own
  scope member wins over a library provider.
- *Keywords used as names.* "got the keyword 'scope', which cannot be used as a
  name" replaces `got 'scope' (SCOPE)`.
- *Keyword-named free functions are rejected.* They could be declared and
  never called: `take(3)` is the ownership operator and returned `3`, and
  `alloc(1)` printed a pointer. `std.functional`'s `take` is now `takeFirst`.
- *Constructing a spec* says it is a spec and names its implementations,
  instead of "undefined function 'List' … add an import".
- *`arr[Int]`* gets its removed-syntax message again; the type-macro message
  had shadowed it.

### 8. Array members without an import

`[1].isEmpty`, `.isNotEmpty` and `unsafe xs.data` needed
`import std.container.array`, although a literal builds an `Array` without
one. `std.container.array` is now `exposed`, as `std.container.tuple` already
is, and a sequence literal references `Array` as a string literal references
`String`.

### 9. std modules test themselves

`StdlibSelfTest` compiles each of the 49 std modules on its own and runs its
`test` blocks, as `azora test std` does. A program reaches only the
declarations it names, so the rest of a module was never compiled. At the
start, only 3 tests passed and 25 modules did not compile.

Repaired:

- *Missing imports.* `Clone` was missing in `list`, `map`, `set` and
  `memory/*`; `Clone`/`Copy` in `traits.core` (a mutual import);
  `Serializable` in `reactive`.
- *`Double`s that were `Float`s.* The default real literal is `Float`
  (`Literals.DEFAULT_FLOAT`), so `ml` and `serializer` now state `Double`.
- *The serializer could not decode any object with two fields.* A `,` between
  fields returned `InvalidSyntax`; a refactor had inverted
  `if separator != ','`.
- *Capture lists.* Lambdas in `reactive`, `async` and `generators` reached
  outer locals without the capture lists LAMBDA_CONTEXT_CAPTURE_DIP requires.
- *`async`* functions dropped their obsolete "unused type tag" parameters.
- *`time`'s tests* annotated a `Duration` as a `MonotonicInstant`.
- *`gpu`* relied on overloading, which the language does not have. The bridge
  functions are renamed; `compile` takes a default `options` argument.
- *Reflection on library types.* A type's free-standing `oper[]` registered its
  reflection site under the type's own name. That hid the type's decorators,
  so `reflect<LinkedHashSet>.hasAnnot<Serializable>` was false. An operator's
  site is now `Type.index`.
- *An exposed module compiled on its own* had its always-injected blocks
  injected a second time (`StdlibInjector.alwaysInjectedFor`).

Still failing: `ai`, `allocator`, `container/tuple`, `parallelism/channel`,
`parallelism/sync`, `primitive`, `string` (see below).

### 10. Stale fixtures (008)

Each fixture keeps the invariant it tested and adopts the current form:

- `tupleOf` is still pending the tuple work below.
- `![…]` became a `Set<T>` context, and `Array::fill` became a literal.
- `impl oper… for` became `oper[] T&.(…)`, in `Tier1PolishTest` and the
  serializer-deriver fixture. That fixture also used `[].fill` and `panic { … }`.
- Collection annotations gained their imports (`TypeRefTest`, `Tier1PolishTest`).
- `(Int, String)` is now asserted to be the tuple type, not a removed spelling.
- `using` is no longer asserted to be reserved: it opens a receiver context.
- `scope` is now asserted to be a keyword that opens a namespace.
- Scope members are reached qualified (`std::abs`).
- `TraitsTest`'s impl members gained their `&.` receivers.
- The README's bracketed receivers became `func &.greet()`, and
  `ReadmeSnippetTest`'s context keys follow the README's current snippets.
- `@experiemntal` became `@Experiemntal`, since decorators are capitalised.
- `SymbolTest` reads `println(` in IR.

### Not changed, recorded

- **Decision needed: integer overflow.** `Int` is 32-bit (BASE_SYNTAX §2). The
  interpreter computes in 64 bits (`2147483647 + 1` is `2147483648`), while LLVM
  wraps `i32`. An out-of-range literal is accepted (`fin b: Int = 2147483648`).
  Options are to wrap, to trap, or to trap in debug and wrap in release.
- Inside a generic body `T` checks as `Any`, so returning a `T?` as `T` is
  accepted.
- LLVM still lowers `defer` and `yield` to comments. Several other paths
  (member, method, index, binary on some types) emit a default value, and
  WebAssembly emits `0` for variant literals. Each should be an explicit
  unsupported-target error until it is lowered.
- Two `[done.!]` exclusive captures of one binding are accepted
  (`std.concurrency.async.race`).
- `concurrency::cancel` has an empty body.
- Channels have two designs: the compiler's `channel()` builtin, which only
  the interpreter implements, and a `std.parallelism.channel` pack that does
  not compile. `std.parallelism.sync` names `Mutex` and `Atomic`, which do
  not exist. These belong to 048/049.
- GTC §6.3's identity of `(A, B)` with `Tuple<A, B>` is not implemented.
  `Tuple<…>` is still a monomorphised std pack, so `std/container/tuple.az`'s
  own tests and the `tupleOf` fixtures fail. *(Done in the follow-up entry.)*
- `primitive` and `string` cannot be compiled from their own source: the
  program's declarations take module identities that the compiler's
  primitive names do not match.
- An index write through a read-only `T*` is not refused.

### Evidence

- New tests:
  - `IndexBoundsTest` (4) and `IndexBoundsExecTest` (4);
  - `SharedBorrowWriteTest` (9) and `GenericSoundnessTest` (8);
  - `ExpectedTypeConstructionTest` (5);
  - `SpecSubscriptTest` (4) and `SpecSubscriptExecTest` (2);
  - `SuggestionsTest` (6);
  - `StdlibSelfTest` (one case per std module);
  - two more `StabilityDecoratorTest` cases and one more `ModulesTest` case.
- Full compiler run before the last three test files: **2,731 tests,
  62 failing**. 28 of the 83 starting failures pass, nothing that passed
  fails, and the 7 failures outside the starting set are `StdlibSelfTest`
  modules that still do not compile. The last three files, 19 tests, pass on
  their own. AZLS: 91/91.

```sh
./gradlew :compiler:desktopTest --offline --console=plain --continue
./gradlew :azls:test --offline --console=plain
./gradlew :compiler:desktopTest --offline --console=plain --tests '*StdlibSelfTest'
```

## 2026-10-03 (continued) — Tuples are structural, errors reach WebAssembly, collections walk, programs carry only the library they reach

Continues the entry above. Full run before: 2,731 tests, 62 failing. Last full
run: **2,758 tests, 15 failing**; the three fixed after it pass on their own.

### 1. A tuple is its shape (GTC §6.3/§6.4)

- `(A, B)` and `Tuple<A, B>` are one type. `Tuple` is a `bridge pack` with
  no fields; `Tuple<A, B>` resolves to `IrType.Tuple`, and fewer than two
  elements is an error naming the rule.
- `impl … for Tuple<...T>` is held back and specialised per shape a program
  uses a member on (`VariadicMonomorphizer.specializeTuples`); `self` in a
  specialisation is the structural tuple.
- Display is the same on every backend: strings and primitives natively, enum
  values as their qualified case, aggregates rendered in IR (`__render_*`).
- `for (a, b) in pairs` destructures, nested too; `for [a, b]` names the new
  spelling.

### 2. Failures reach the fallback on every backend

- *WebAssembly had no error transport.* `throw` was `unreachable` and
  `expr catch fallback` evaluated only `expr`. Now a raised error sets a
  pending flag and an erased error slot (a flag, so no value can read as "no
  error"), every call to a failable function checks the flag, and a handler or
  `catch` expression is a block the failure branches out of. A failing literal
  factory now reaches its `catch` like a call does.
- *LLVM never bound the caught error.* `try { … } catch { e -> … }` referenced
  an undefined `@e`. The handler now binds it before clearing the slot. (`e`
  is typed `Any`, so natively it prints `<value>`; see the open items.)
- `std.result` was removed with this error model; `ResultUnwrapOrTest` became
  `FailableFallbackExecTest`, which runs `catch` on all three backends.

### 3. A program carries only the library it reaches

Mentioning a library type brought every member of it into IR, so every
program carried `Int_rank`, `Compare_isLess` and the like, and every backend
lowered them. A library function a target could not lower spoiled programs
that never called it.

- `IrFunction.isLibrary` marks injected functions and members of library
  impls. `IrOptimizer.shakeLibrary` removes the unreached ones in every build;
  the program's own code stays as written until release shaking.
- Reachability now follows the references IR leaves implicit: a member asked
  for by name (`x.m`, `x.m()`, `x[i]`, `x[i] = v`, an operator on a pack,
  `purge`) is paired with every type that can be the receiver, including types
  only known at run time.
- *Release builds deleted functions reached through an enum interpolated in a
  template* (`EnumToString` was skipped). The reference walk is now exhaustive
  over `IrExpr`, so a new expression kind cannot silently hide calls.
- LLVM declares an extern only when the module references it, so a bridge the
  compiler lowers itself (`println` → `puts`) no longer declares `@println`.

### 4. `for x in xs` over the standard collections

`List` and `Set` became packs behind specs, and `for` walked only ranges,
arrays and iterators, so neither could be looped over. `std` itself counted
`for i in 0..<xs.size`.

- `spec Indexed<T>` in `std.traits.core` (a `size` and a `get(index)`);
  `List` and `Set` refine it. A collection that is `Indexed` is walked by
  position: `size` is re-read before every row, so removing elements ends the
  walk rather than reading past it, and nested walks share no cursor.
- The protocol is a spec, not member names: a `Map<Int, V>` has the same
  spellings and is not walked.
- *`continue:outer` inside an inner loop repeated the outer row forever* when
  the outer loop kept an index (`rewriteContinuesForIndex` did not look inside
  nested loops). Fixed.
- *A label on `for x in array` named nothing.* `IrStmt.ForEach` had no label:
  WebAssembly threw and took the whole compilation down, LLVM left the wrong
  loop, and the interpreter's `break:outer` stopped only the inner loop. The
  label is now carried and honoured by all three, and IR prints loop labels.
- LLVM emitted a comment instead of a loop for a `for … in` it could not walk;
  it now says it cannot, as WebAssembly does.

### 5. Smaller fixes

- `scope vha` mangles to `__vha_sin`, the name of WebAssembly's own helper, so
  the module defined it twice. The runtime's routines are now `rt.…`: a `.`
  cannot appear in an Azora name.
- An undefined bare name that a scope of an *imported* module declares now
  reads `'area' is part of scope 'shapes', use 'shapes::area' instead`
  (`StdlibInjector.importedScopeMembers`, replacing an unused lookup that
  mapped the other way).
- `requires [A, B]` (the old spelling) says `write 'requires (A, B)'`.
- The filtered IR printer could not select a `fin` or `let` global.

### 6. Stale fixtures

Each keeps its subject and states the rule as it now stands: `std.result` →
failables; `import std.math::abs` is the item-import form and `std.*` the slip;
the collections live in `std.container.{list,map,set}` and need their imports;
`std.core` predefines `Target` (was `BridgeTarget`) and `@Derive`, and no
longer `HasDeco`/`DecoMetadata`; `std` is no longer a scope, so reflection and
`println` are bare; `requires (A, B)`; a library fixture replaces the
serializer's now-private one; the scalar LLVM golden gained checked
allocation, the 64-bit range step check and the flushing abort, and was
verified by running it; IR comparisons print only the file's own items.

### Open

- **Decision needed: anonymous `Var<A, B, C>`.** `CollectionCtorTest` uses it,
  `IrType.Variant` and `IrType.resolve` remnants exist, but no DIP describes it
  and the front end rejects it (failing since the 2026-09-10 baseline). Tagged
  unions are `variant enum`. Implement it or retire the test.
- The caught error in `catch { e -> … }` is typed `Any`. A typed binding (the
  error set) needs errors to travel as values of that set rather than as case
  names.
- `RoboticsEngineTest` reads `azora-engine/proposals/robotics.az`, which uses
  the pre-rename `realm` keyword; the fixture moved to the engine repository
  and needs migrating there.
- Still failing: `ai`, `allocator`, `primitive`, `string`, `parallelism/channel`
  and `parallelism/sync` self-tests; `AiMlStdlibTest`; the channel examples
  (048/049); `SerializationDeriverTest`'s primitive-codec fixture (rewrite
  against the real `std.serializer`).

### Evidence

- New tests: `FailableFallbackExecTest` (3), `IndexedWalkExecTest` (4),
  `LabelledArrayLoopExecTest` (3), one more `OwnershipTest` case, and the
  `StdlibInjectionTest`/`ScopeQualifiedAccessTest` migrations above.

```sh
./gradlew :compiler:desktopTest --offline --console=plain --continue
```

