# Azora ecosystem execution log

Plan: [150-step delivery plan](ECOSYSTEM_DELIVERY_PLAN.md).
Initial evidence: [2026-09-08 audit](ECOSYSTEM_AUDIT_2026_09_08.md).

## Current status — 2026-09-19

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
- In progress: 007/014. A program's block imports bind lexically. Library-module
  block imports, receiver syntax, scope members and unknown-module imports remain.
- The remaining 008 fixture review and 010 (C4–C5) remain open. Older entries
  below preserve the evidence at each stage.
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
| `os.az` | grouped if-expression using `then` |
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
conditionals. Grouped conditional bindings accept `then {a, b} else {c, d}`,
retain arity validation, and use the existing single condition temporary.
Single-statement `unsafe` bodies lower to the existing explicit unsafe scope;
the following statement remains outside that boundary.

Corrected malformed library bodies: removed the premature opening brace before
the contracts of `groverIterations`, removed two extra allocator property closing
braces, and removed `return` from an allocator expression body. Pre-existing user
edits remain preserved. Updated the related DIPs to describe the implemented
forms and existing assertion message requirements.

Evidence:

- Ten initial declaration regressions failed before the repair; two grouped
  conditional regressions failed before that extension. All 12 now pass.
- Interpreter tests execute optimized and unoptimized IR, covering successful
  and failing single-statement pre/postconditions, constructor contracts and
  property access, both grouped branches, and condition evaluation count.
- Semantic checks reject an unsafe call immediately outside a single-statement
  unsafe body. Native LLVM executes the contract, constructor/property, grouped
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
| `quantum.az` | grouped method-name shorthand `result.{h,x}(j)` | 008–009; inspect intended evaluation and existing grouped-operation semantics |

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
| `quantum.az` | grouped method-name shorthand `result.{h,x}(j)` |

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
- Grover's grouped method names use the documented GTC §7.4 call sequence:
  `result.{h(j), x(j)}` and `result.{x(j), h(j)}`. `h`/`x` receive Int by value;
  neither changes the loop binding, so repeating that read preserves the argument.
  Their mutable calls retain written order on the same receiver. A dedicated
  regression executes both sequences inside single-statement loops on the
  interpreter, LLVM, and WASM. This migration does not introduce a general
  `receiver.{methodNames}(args)` feature or assert quantum simulation correctness.

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
