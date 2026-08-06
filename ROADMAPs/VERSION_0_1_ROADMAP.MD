# Azora 0.1 — Road to Production

> Target: **10 months**. Current version `0.0.5`.
>
> This is the complete list of what has to be true before `0.1` ships. Boxes
> are checked only for work that is **implemented and covered by a test**, not
> for work that is designed or partly done. A design document is not a check.

---

## 0. What "production ready" means here

`0.1` is the first version someone outside the project can build something real
on and expect it to keep working. Four promises, and every section below exists
to serve one of them:

1. **It compiles what it accepts.** No construct type-checks and then produces
   wrong output, and no backend disagrees with another about what a program
   means.
2. **It fails clearly.** Every rejection names the problem and the fix.
3. **It does not move under you.** A `0.1` program still compiles on `0.1.x`.
4. **It can be worked in.** Editor, debugger, package manager, docs.

### 0.1 exit gates

These are hard blockers. `0.1` does not ship with any of them open.

- [ ] **Zero failing tests.** 75 are failing today (§9.1); every one is fixed
      or deleted with a stated reason.
- [ ] **No known miscompiles.** The §1.1 list is empty.
- [ ] **Backend parity.** Every language construct behaves identically on the
      interpreter, LLVM and Wasm, or is rejected at compile time on the targets
      that cannot express it (§6).
- [ ] **`README.md` describes reality.** Today it does not (§10.1).
- [ ] **Every shipped language feature has a written DIP.** 11 are empty (§10.2).
- [ ] **A stability policy exists and is published** (§11).
- [ ] **A package manager exists** and `azora new` → `azora build` → `azora
      test` works end to end (§7.4).
- [ ] **Bootstrap decision made and executed or explicitly deferred** (§12.1).

### Current state, measured

|                          |                                                                 |
|--------------------------|-----------------------------------------------------------------|
| Tests                    | 1510 total, **75 failing**                                      |
| Standard-library modules | 47 `.az` files                                                  |
| DIPs                     | 34, of which **11 are empty files**                             |
| Backends                 | interpreter, LLVM, Wasm (**no JS backend**, despite the README) |
| Compiler                 | ~30 Kotlin files, `commonMain` + platform shims                 |

---

## 1. Correctness — the blocking work

### 1.1 Known miscompiles and unsound acceptances

Each of these lets a program be silently wrong today. This list must be empty.

- [ ] **Derivation is not validated against field capabilities.**
      `pack Sample { var value: Double }` + `impl [Order] for Sample` is
      **accepted**, claiming a total order over a field that has only a partial
      one. `sort` then accepts it and NaN sorts as if ordered.
      (`DIPs/OPERATOR_OVERLOADING_DIP.MD` §7.2 specifies the check; it was never
      implemented.)
- [ ] **`hash` disagrees between backends.** In LLVM anything behind a pointer —
      including `String` — hashes by address; the interpreter hashes by content.
      A `String`-keyed `HashMap` therefore behaves differently per target. Same
      class of defect as the `==` one fixed in `0.0.5`.
- [ ] **`Out` conflict rule unimplemented.** Two impls of one operator family
      differing only in `Out` both match, so `a + b` is ambiguous and one
      silently wins (`OPERATOR_OVERLOADING_DIP.MD` §12.2).
- [ ] **`<T: Spec>` in a type-parameter list is parsed and discarded**
      (`Parser.kt`, *"accepted, not yet enforced"*). A bound written there is a
      lie.
- [ ] Audit for the same shape elsewhere: any place a declaration is parsed and
      dropped rather than enforced.

### 1.2 Type system soundness

- [ ] Generic **type erasure** is the language's biggest open question. Field
      types are `Any` at runtime; operators dispatch on the runtime type as a
      patch (`IrInterpreter.dispatchOperatorOnRuntimeType`). Decide and
      implement: monomorphisation, reified generics, or a documented erasure
      model with dictionary passing.
- [ ] `where` clause evaluation is incomplete — `ConstraintEvaluator` has four
      `TODO`s: element-wise conformance for variadic packs, membership in
      array/set literals, unary operators in constraint position, and calls in
      constraints.
- [ ] Variance rules for generic types: stated, checked, documented.
- [ ] Exhaustiveness checking for `when` over enums, variant enums and unions —
      verified complete, including through generic widening.
- [ ] Nullability: no path where `T?` is silently treated as `T`.
- [ ] Integer overflow semantics: defined (wrap / trap / checked-in-debug) and
      identical across backends.

### 1.3 Memory and ownership

`DIPs/OWNERSHIP_BORROWING_DIP.md` is the largest DIP (1494 lines) and the model
is the language's main claim.

- [ ] Borrow checking is complete for every construct: closures, generators,
      `defer`, contracts, `async`, pattern bindings.
- [ ] `take` / `lend` / return-params verified against the DIP end to end.
- [ ] No use-after-move accepted; test matrix per binding kind
      (`var`/`val`/`fin`/`let`).
- [ ] Destructor ordering specified and identical on all backends.
- [ ] Custom allocators (`DIPs/CUSTOM_ALLOCATORS_DIP.MD`) implemented, or
      explicitly deferred past `0.1`.
- [ ] `unsafe` boundaries documented (`UNSAFE.MD`) and enforced.
- [ ] Leak checking in the interpreter for test runs.

---

## 2. Language features

### 2.1 Shipped in `0.0.5` — verify and keep

- [x] Comparison operators: `<=>`, rewriting of `<` `<=` `>` `>=` from it, `!=`
      from `==`
- [x] `Compare` / `PartialCompare` with `isLess`/`reversed`/`then`
- [x] `PartialEqual` / `Equal` / `PartialOrder` / `Order` / `Hash`
- [x] Derivation from a bodyless `impl [Equal, Order, Hash]`
- [x] `==` without a conformance is a compile error
- [x] Operator families: `Arithmetic`, `Neg`, `Bitwise`, `Logical`,
      `Indexable`, `Deref`
- [x] Partial implementation of an operator family
- [x] Unary operator dispatch, `oper!`
- [x] In-place compound assignment (`oper+=` and friends)
- [x] `where T: Spec<Args>` and `where K: [A, B]` bounds
- [x] `oper` members on a `spec`; return type read from the spec
- [x] `Display` + `Formatter`, with `"${v}"` routed through it
- [x] `Into` / `From` (generic `prop` with `use as`; static `from`)
- [x] `Cast` / `CheckedCast` / `BitCast`, target on the impl
- [x] `std/cast.az` intrinsics and `impl as T for Type` retired

### 2.2 Operator work still open

- [ ] Member-selecting bounds — `where T: Arithmetic[+, /]`
      (`OPERATOR_OVERLOADING_DIP.MD` §12.3)
- [ ] `FullCast` and spec-list `typealias` (§11.5)
- [ ] `Iterator` spec (§12.8) — **blocked**: its output is `Range<Self>` and a
      range is a compiler node, not a `std` type, so no user type can define
      `..`. Decide `Range<T>` first (§2.4).
- [ ] Retire `oper as<U>` from `parseOperatorName` (`CAST_DIP.MD` §7 step 4)
- [ ] Close the free-operator form language-wide (§14, phase 11) — every
      operator declared inside its spec's `impl`
- [ ] The remaining §13 diagnostics

### 2.3 Feature areas with no written DIP

Each needs a DIP **and** an implementation audit. The empty files are the gap.

- [ ] `CONCURRENCY_DIP.MD` — empty
- [ ] `PARALLELISM_DIP.MD` — empty
- [ ] `REACTIVE_DIP.MD` — empty
- [ ] `REFLECTION_DIP.MD` — empty
- [ ] `DYNAMIC_DISPATCH_DIP.MD` — empty
- [ ] `DEPENDENCY_INJECTION_DIP.MD` — empty
- [ ] `CONTRACTS_ASSERTION_DIP.MD` — empty
- [ ] `TESTING_TRACING_DIP.MD` — empty
- [ ] `COMPILER_INTRINSIC_DIP.MD` — empty
- [ ] `BRIDGE_DIP.MD` — empty
- [ ] `AZLS_DIP.MD` — empty

### 2.4 Language decisions still open

- [ ] **`Range<T>` as a real type** — unblocks `Iterator`, user-defined ranges,
      and slicing generalisation.
- [ ] **Associated types** — `Out` is a type parameter with a conflict rule
      today. Decide whether the type system gets real associated types.
- [ ] **Fallible conversion** (`TryInto`) — needs the error model settled:
      `?!` and `Result` are currently two different answers.
- [ ] **Error model unification** — pick one of `?!` / `Result` / both, and say
      why.
- [ ] Trailing-closure and named-argument interaction rules.
- [ ] Operator precedence table published and frozen.
- [ ] Reserved-word list frozen.

---

## 3. Concurrency and parallelism

`DIPs/THREAD_SAFETY_DIP.MD` (638 lines) documents that this area is **names
without implementations**.

- [ ] `Mutex` and `Atomic` **do not exist** — `std/parallelism/sync.az` and
      `channel.az` reference them anyway. Implement or remove.
- [ ] `Send` / `Sync` capabilities
- [ ] Real OS threads (`std.thread`), `JoinHandle`, scoped threads
- [ ] `Channel<T>` moved out of the compiler builtin into std
- [ ] `std/parallelism/thread.az`: `sleep`, `spawnAsync`, `joinAll` are task
      helpers wearing thread names; `joinAll(count)` sleeps and joins nothing
- [ ] Fix `module std.pareallelism.sync` (typo — the module cannot be imported
      under the name its path implies)
- [ ] Data-race freedom claim testable: a program without `unsafe` has no data
      races
- [ ] Async: cancellation, structured concurrency, task-local state
- [ ] Backend story for concurrency on Wasm and LLVM

---

## 4. Standard library

47 modules exist. `0.1` needs them coherent, documented and tested, not merely
present.

### 4.1 Core

- [ ] Every public item has a doc comment with an example
- [ ] Every module has tests; coverage target agreed and met
- [ ] Naming consistency pass across all 47 modules
- [ ] `@Since` accurate on every public item
- [ ] Stability markers (`@Experimental` vs stable) audited — what ships as
      stable in `0.1` is a deliberate list

### 4.2 Containers

- [ ] `List`, `Map`, `Set`, `Deque`, `Queue`, `Stack`, `Array` — complete and
      consistent APIs
- [ ] Iteration protocol unified (`reset`/`hasNext` today) and documented
- [ ] Capacity/growth semantics documented
- [ ] `where K: Hash` / `where K: Order` bounds stated on every container that
      needs them
- [ ] Container equality/ordering/hashing via the specs, not ad-hoc members

### 4.3 Gaps to fill or cut

- [ ] `std.io` — files, paths, streams, stdin. Currently minimal.
- [ ] `std.os` / process / environment (README claims it; verify)
- [ ] `std.net` — decide in or out for `0.1`
- [ ] `std.time` — audit; currently thin
- [ ] `std.text` / formatting — `Display` exists; number and date formatting do
      not
- [ ] Regex — in or out
- [ ] `std.testing` — assertions, fixtures, parametrised tests
- [ ] `std.json` — the serializer exists; a user-facing JSON API may not

### 4.4 Known defects

- [ ] `Display` allocates a `Formatter` per interpolated value, so `"${a} ${b}"`
      builds two buffers. The top-level interpolation site should share one.
- [ ] `std/algorithm/sort.az` is insertion sort — O(n²). Needs a real sort.
- [ ] `sortBy`'s key function is typed `(T) -> T` and cannot express a key of
      another type.

---

## 5. Diagnostics

The compiler's error messages are a feature, and the DIPs specify many that do
not exist yet.

- [ ] Every error names the problem **and** the fix (the `0.0.5` comparison
      errors are the standard to hold)
- [ ] Source spans on every diagnostic — line, column, length, and a caret
- [ ] Multi-line snippets with the offending region underlined
- [ ] Error codes (`AZ0001`…) with a documented index
- [ ] Warnings separated from errors; `--warnings-as-errors`
- [ ] No internal exception ever reaches the user (`IllegalStateException`
      from the parser is currently reachable)
- [ ] "Did you mean…" suggestions for unknown names
- [ ] Stale hint text audited — e.g. *"add 'use std.traits'"* when `use` is not
      valid at top level
- [ ] Diagnostics tested: a test per message, asserting the text

---

## 6. Backends

### 6.1 Parity — the release gate

- [ ] A **conformance test suite** run against every backend, asserting
      identical observable behaviour
- [ ] Any construct a backend cannot express is a **compile error for that
      target**, never silently degraded output

### 6.2 Interpreter

- [x] Executes the full language (used by tests, REPL, playground)
- [ ] Performance adequate for test suites and the playground
- [ ] Leak/ownership checking mode

### 6.3 LLVM

Currently *"Partial — placeholders for closures, defer, compound types,
pointers"*.

- [ ] Closures
- [ ] `defer`
- [ ] Compound types (tuples, variants, slots)
- [ ] Pointers and `unsafe`
- [ ] `hash` for pointer-backed values (§1.1)
- [ ] Unsupported global statements are currently emitted as a comment
      (`; unsupported global statement`) — must be an error
- [ ] Debug info (DWARF) for the debugger
- [ ] Optimisation pipeline and `--release`
- [ ] Native linking: produce an executable, not just `.ll`

### 6.4 WebAssembly

Several constructs degrade silently today — each is a potential miscompile.

- [ ] `ForEach` is a no-op (`is IrStmt.ForEach -> {}`)
- [ ] Unsupported methods "degrade to the receiver"
- [ ] `VariantLit` / `SlotPattern` emit `(i32.const 0)`
- [ ] `delay` degrades (no host clock)
- [ ] Strings and memory management beyond the MVP
- [ ] Host/JS interop story
- [ ] `wasm-opt` integration and size budget

### 6.5 JavaScript

- [ ] **The README claims a JS backend with "Full" status. There is no
      `JsCodegen`.** Either implement it or correct the README. Decide for
      `0.1`.

---

## 7. Tooling

### 7.1 CLI

- [x] `azora run` / `check` / `compile` / `repl` / `version` / `help`
- [ ] `azora test` — first-class test runner with reporting
- [ ] `azora fmt` — canonical formatter (no formatter exists)
- [ ] `azora doc` — documentation generator
- [ ] `azora new` — project scaffolding
- [ ] Consistent exit codes, `--json` diagnostics output for tooling
- [ ] `--version` reporting reproducible build info

### 7.2 Language server (`azls`)

Four source files today; the DIP is empty.

- [x] Syntax highlighting, diagnostics, completion, hover, document symbols
- [ ] Go-to-definition
- [ ] Find references
- [ ] Rename
- [ ] Signature help
- [ ] Inlay hints (inferred types)
- [ ] Workspace-wide indexing and cross-file intelligence
- [ ] Incremental parsing — responsiveness on large files
- [ ] Code actions / quick fixes wired to the diagnostics of §5
- [ ] Semantic tokens
- [ ] Formatter integration (needs §7.1)
- [ ] `AZLS_DIP.MD` written
- [ ] LSP conformance tested against a real client

### 7.3 Debugger

- [x] `DebugInstrumenter` line markers, `AzoraDebugSession` step/breakpoint
- [ ] Variable inspection
- [ ] Call stack
- [ ] Conditional breakpoints
- [ ] Native debugging via DWARF (needs §6.3)
- [ ] DAP conformance

### 7.4 Package manager — **does not exist**

`build-tool` is four files (`Main`, `AzoraConfig`, `TomlParser`,
`HtmlGenerator`). This is the largest missing piece of tooling.

- [ ] Manifest format (`azora.toml`) specified
- [ ] Dependency resolution with a lockfile
- [ ] Semantic versioning policy
- [ ] Registry: hosting, publishing, authentication, yanking
- [ ] Local path and git dependencies
- [ ] Build profiles (debug/release), feature flags
- [ ] Reproducible builds
- [ ] Vendoring / offline mode
- [ ] Workspaces (multi-package repos)
- [ ] Transitive stdlib injection interaction with packages

### 7.5 Editor support

- [ ] Azora Studio tracks `0.1`
- [ ] IntelliJ plugin (`org.azora.lang.idea`) tracks `0.1`
- [ ] VS Code extension
- [ ] TextMate / tree-sitter grammar for third-party editors

---

## 8. Performance

Nothing here is measured today.

- [ ] Benchmark suite: compile time and runtime
- [ ] Published baselines, tracked per commit, regression alerts
- [ ] Compile-time target for a 10k-line project
- [ ] Runtime within a stated factor of C for a defined benchmark set
- [ ] Binary size budget
- [ ] Incremental compilation
- [ ] Parallel compilation
- [ ] IR optimisation passes measured (`IrOptimizer` exists; effect unmeasured)

---

## 9. Testing and CI

### 9.1 The 75 failing tests — a release gate

Every one fixed or deleted with a stated reason. By area:

- [ ] `TypeRefTest` (11)
- [ ] `LlvmAggregateExecTest` (8)
- [ ] `StdlibInjectionTest` (6)
- [ ] `ArrayStdlibTest` (5)
- [ ] `WebsiteExamplesTest` (4)
- [ ] `ArrayTest`, `CollectionCtorTest`, `Feature003SyntaxTest`,
      `Tier1PolishTest` (3 each)
- [ ] `ArrayStdlibExecTest`, `ConstGenericArrayTest`, `ContainerStdlibTest`,
      `LlvmRegressionExecTest`, `MacroTest`, `MetaprogrammingTest`,
      `StdlibMethodsTest` (2 each)
- [ ] 15 single failures across `CodegenGolden`, `ImplMethod`, `LlvmStdlibExec`,
      `Loop`, `Modules`, `Pack`, `PlaygroundExamples`, `StabilityDecorator`,
      `TestAssertTrace`, `Tier4Concurrency`, `TimeStdlib`, `Traits`, `Tuple`,
      `VariadicLambda`, `SerializationDeriver`

### 9.2 Test infrastructure

- [ ] CI on every push: all backends, all platforms
- [ ] Coverage measured and a target agreed
- [ ] Fuzzing the parser (no input crashes the compiler)
- [ ] Property-based tests for the ownership checker
- [ ] Golden-file tests for codegen on all backends
- [ ] A conformance suite that a third-party implementation could run
- [ ] Performance regression tests (§8)
- [ ] Nightly builds

---

## 10. Documentation

### 10.1 `README.md` — currently inaccurate

- [ ] Claims a **JavaScript backend with "Full" status**; none exists
- [ ] Claims **534 tests**; there are 1510
- [ ] Claims the stdlib lives in `Internal/Std/` with **35 modules**; it is
      `std/` with 47
- [ ] "Missing Features (Roadmap)" is far shorter than reality — this document
      replaces it

### 10.2 Specification

- [ ] The 11 empty DIPs written (§2.3)
- [ ] Stale DIPs corrected:
  - [ ] `TYPE_DIP.MD` §9 documents `std::cast`/`dyncast`/`bitcast` (deleted) and
        `oper as<U>` as the declaration form
  - [ ] `FUNCTIONS_DIP.MD` §5 and `PACK_IMPL_DIP.MD` document
        `impl oper[] for X { self&, index -> }`, a form that now errors
  - [ ] `SYNTAX.MD` still shows `where T: oper<` and operator-list typealiases,
        rejected by `OPERATOR_OVERLOADING_DIP.MD` §11.4
- [ ] A single normative **language reference** assembled from the DIPs
- [ ] Grammar published (EBNF), and the parser verified against it
- [ ] Memory model written down
- [ ] `GAMEDEV_VERSE_PARITY.md`, `LAMBDA.MD`, `Realm.md`, `RealmEnum.md`,
      `StdDynamic.MD`, `UNSAFE.MD` folded into the reference or archived

### 10.3 Learning material

- [ ] Tutorial: install → first program → first package
- [ ] The book (separate repo) tracks `0.1`
- [ ] Playground tracks `0.1`
- [ ] Cookbook / examples covering every major feature
- [ ] Migration guide `0.0.x` → `0.1`
- [ ] Full stdlib API docs, generated (`azora doc`, §7.1)

---

## 11. Stability and compatibility

- [ ] **Stability policy published**: what `0.1` guarantees, and for how long
- [ ] Deprecation process defined (warn → error → remove, with versions)
- [ ] `@Since` / `@Experimental` semantics documented and enforced
- [ ] The public surface of `0.1` explicitly enumerated — everything else is
      internal and may change
- [ ] Feature-gating for experimental features
- [ ] A compatibility test suite that `0.1.x` must keep passing
- [ ] Release process: versioning, changelog, signed artefacts, announcement

---

## 12. Strategic decisions

These shape the schedule and should be settled early, not late.

- [ ] **12.1 Self-hosting.** `experimental_self_hosted/` exists. Is the `0.1`
      compiler written in Kotlin or in Azora? Bootstrapping is a multi-month
      commitment; deferring it is legitimate but must be a decision.
- [ ] **12.2 Generics: erasure or monomorphisation** (§1.2). Affects
      performance, FFI, reflection and the ABI.
- [ ] **12.3 Primary backend.** LLVM for native, Wasm for the web, JS possibly
      not at all. Effort follows this.
- [ ] **12.4 ABI stability** — needed for a package registry distributing
      binaries. In or out for `0.1`.
- [ ] **12.5 Target platforms** for `0.1`: which OS/arch triples are supported,
      tested and released.
- [ ] **12.6 FFI scope.** C interop is claimed; verify, specify, test.

---

## 13. Suggested sequencing

Ten months. The ordering is driven by what unblocks other work and what is
cheapest to fix early.

**Months 1–2 — stop the bleeding**
- §1.1 miscompiles (start with derivation validation — it is the only one that
  makes a correct-looking program wrong today)
- §9.1 the 75 failing tests
- §10.1 README accuracy
- §12.1–12.3 strategic decisions

**Months 3–4 — foundations**
- §1.2 type-system soundness, generics decision implemented
- §1.3 ownership completeness
- §6 backend parity suite, and closing the Wasm silent degradations

**Months 5–6 — the missing systems**
- §3 concurrency: `Mutex`, `Atomic`, real threads
- §7.4 package manager
- §2.3 write the 11 empty DIPs

**Months 7–8 — surface and polish**
- §4 standard-library completeness and docs
- §5 diagnostics
- §7.1–7.3 CLI, `azls`, debugger

**Months 9–10 — hardening**
- §8 performance baselines
- §9.2 CI, fuzzing, conformance
- §10.2–10.3 reference, tutorial, migration guide
- §11 stability policy, release process
- Release-candidate cycle

---

## 14. Explicitly out of scope for 0.1

Recording these keeps them from creeping in.

- Incremental/parallel compilation beyond a stated baseline (§8 targets only)
- A GUI framework or game engine in the standard library
- Full C++ interop (C only)
- Distributed/actor concurrency beyond threads and tasks
- A JIT
- Formal verification of the ownership model
