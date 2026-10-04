# Foundation repair — 2026-10-04

Scope: confirmed defects and missing integration gates from the current local
Language, Engine, and Studio inspection. Preserve existing working-tree changes.
A passing parser or unit test does not establish native runtime readiness.

## Confirmed repair list

- [x] F01 — Numeric semantics: make fixed-width arithmetic consistent between
  interpreter, optimized IR, LLVM and WASM; reject out-of-range integer literals.
  This repair qualifies 8/32-bit wrapping and literal ranges; wider execution
  coverage remains F13.
  Reproducer: Int maximum plus one prints 2147483648 in the interpreter and
  -2147483648 in LLVM. An Int literal 2147483648 is accepted.
- [x] F02 — Generic nullability: reject returning T? or null as T. Reproducer:
  `func<T> bad(x: T?): T { return x }` passes `azora check`.
- [x] F03 — Backend support integrity: replace silent unsupported lowering with
  correct execution or an explicit compiler diagnostic. Confirmed LLVM string
  transforms return their input; WASM text operations also contain placeholders.
  Reproducer: strToUpper, strTrim and strReplace disagree with the interpreter.
- [x] F04 — Synchronization/channel foundations: reconcile builtin and library
  channels; provide real locking/atomic semantics and cleanup, or explicit target
  availability. Channel and sync modules fail their own compilation checks.
- [x] F05 — Current language fixtures: repair the anonymous Var/Float fixture,
  migrate the Engine robotics proposal from realm, and migrate channel examples
  while preserving the behavior each test checks.
- [ ] F06 — Engine compatibility: migrate actual packages and templates to the
  current language, then check, compile and link both headless ECS and the game
  template. Both currently stop on removed integer-width suffix syntax.
- [ ] F07 — Native Studio and tooling: implement Studio and Launcher in Azora,
  compiled through LLVM, with no JAR/JVM dependency in the installed product.
  The current Studio is a Kotlin/Compose prototype with no `.az` application
  source. Its JVM build and AZLS JAR bridge do not satisfy this requirement.
  Native compiler/build services and language intelligence are required too.
  Architecture contract: `azora-studio/NATIVE_ARCHITECTURE.md`.
- [x] F08 — Validation/release honesty: CI must run the full compiler/AZLS suites
  and strict real CLI projects, require native tools for native qualification,
  and report unsupported capabilities. Repair stale status/support documents.

- [ ] F09 — Ownership cleanup: explicit purge must consume ownership, reject
  borrowed/double destruction, call pack destructors and free native storage.
  Automatic scope cleanup, failed construction and recursive field destruction
  are still missing and must be qualified before an owning ECS/editor is ready.
- [x] F10 — Pointer capabilities: reject writes through readonly `T*`, allow a
  mutable `T^` to provide a readonly view, and keep nested pointees invariant.
- [ ] F11 — Shared ownership: shared counter storage, per-handle retained
  references, sequentially consistent SyncShared counting, clone/release order
  and moved-task counter stress are repaired and tested. Owned pointee destruction
  and complete leak-free shutdown remain unqualified. Weak currently provides
  a raw non-owning view, not lifetime-safe promotion from a shared control block.
- [x] F12 — Argument evaluation: resolving `println(consume(take buffer))` must
  check the argument once, not consume its ownership twice during type checking.
- [ ] F13 — Native qualification coverage: unsigned/128-bit arithmetic,
  division/shift edge cases, Unicode text, FFI layouts/callbacks, cancellation and
  escaping capture lifetimes and generic erased storage nullability/ownership
  need explicit execution/diagnostic evidence.
  Unsupported string operations must remain compiler errors until implemented.
- [x] F14 — Generic computed properties: instantiate a pack property's result
  using the receiver's type arguments, including when lowering IR. Shared<Int>.get
  previously became Any in native code and printed `<value>` instead of 42.

- [x] F15 — Pointer stores: validate the stored type and index type before
  lowering a pointer write. Both `p[0] = "bad"` and `p.^ = "bad"` previously
  bypassed assignment compatibility for Int^. Readonly pack-field mutation
  through implicit or explicit pointer dereference is also rejected.

## Baseline measured in this assessment

`./gradlew :compiler:desktopTest :azls:test :app:installDist --offline --console=plain --continue`

- Compiler: **2,773 tests, 6 failures**, full run completed in 4m35s.
- AZLS: **91 tests, 0 failures**.
- CLI distribution builds.
- Failures: CollectionCtorTest.var_when_matches_held_type,
  RoboticsEngineTest.geometryLimitsAndTrajectoriesRemainInspectable,
  StdlibSelfTest channel and sync, Tier4ConcurrencyTest.channelWithProducerTask,
  WebsiteExamplesTest.ch31_channel.
- Both ordinary overlapping exclusive-borrow probes are already rejected.
- Serialization execution tests passed in the new full run; prior failures in
  saved reports are not current blockers.

## Readiness gates beyond isolated bug repair

- Native component insert/replace/remove/despawn releases each owned value once;
  stale entity handles and query invalidation/aliasing are tested.
- Representative native layouts, FFI callbacks, closure captures, cancellation,
  destructors and failed partial construction are qualified with execution and
  available sanitizer evidence.
- Create → edit → build → play → save → reopen works from matching installed
  compiler/runtime/stdlib/Engine/AZLS bundles; diagnostics retain source spans.
- Track compile time, frame time, allocation and memory baselines on the first
  supported desktop target before expanding platforms or advanced rendering.

These gates need evidence from real projects; they cannot be closed by changing
checkboxes or treating the number of passing tests as a readiness percentage.

## Repair evidence

Implemented and verified locally; unchecked items above remain open:

- Fixed-width runtime arithmetic and typed constant folding; range checks retain
  wide literal magnitudes without changing the public token spelling contract.
- Generic nullable return/local/assignment checks and null literal typing as
  Nothing?; erased Any display positions continue to accept nullable values.
- Unsupported LLVM/WASM text operations and WASM delay produce target errors;
  the CLI refuses to emit fake target output. Supported interpreter operations
  continue to execute.
- LLVM aligned sequentially consistent atomics and defer-based spin mutexes;
  standard-library owned FIFO channel, explicit ownership transfer between tasks.
  Blocking receive/backpressure and a shared channel handle are not implemented.
- Shared reference counter storage, per-handle release accounting, atomic
  SyncShared counting and generic computed property type substitution.
- Current fixture syntax, readonly buffer declarations, explicit native pack purge,
  borrowed/double-purge rejection and single resolution of print arguments.
- CI full suites, strict CLI stdlib checks, required native tool availability and
  ASan/UBSan cleanup probes. Remote CI has not been run in this checkout.
- Book singleton allocation examples use `let x: Int* = alloc .()`; the example
  runner isolates each program so sibling snippets cannot contaminate a build.
- Native Studio architecture contract; legacy JAR bridge work is excluded.
- Engine build script Java discovery is limited to its legacy compiler fallback.
  With Java absent from PATH, the selected-compiler path compiled, linked and ran
  a native probe using a stub LLVM producer. This qualifies selection behavior,
  not a native Azora compiler.

Final local qualification:

`AZORA_REQUIRE_NATIVE_TESTS=1 ./gradlew :compiler:desktopTest :azls:test :app:installDist --offline --console=plain --continue`

- Compiler: **2,790 tests, 0 failures, 0 skipped**; native tools required.
- AZLS: **91 tests, 0 failures, 0 skipped**. This is bootstrap language tooling,
  not a native Studio language service.
- CLI distribution rebuilt; full run completed in **4m50s**.

- CLI target integrity, LLVM debug/release explicit nested cleanup under
  AddressSanitizer and UndefinedBehaviorSanitizer: **passed**.
- Strict CLI standard-library tests: **16 passed, 0 failed**.
- Book examples: **80 complete programs type-check, 79 execution stages pass**.
- Book production build: **passed** (bundle-size advisory remains).

Fresh builds of the restored game and game-ecs templates still fail on removed
literal width suffixes (lines 78 and 71 of their staged compilation units).

Engine packages/templates still require an API/DSL migration: removed contextual
constructors, composition macros and cast declarations cannot be repaired by
renaming syntax alone. The partial migration is preserved for review in
`azora-engine/proposals/compiler-syntax-migration-2026-10-04.patch`; packages and
templates were restored to their original state. The robotics proposal is
migrated and its compiler regression passes.

**Readiness: not yet ready for the native Engine/Studio product gates.** Explicit
cleanup probes and language unit tests do not close automatic lifetime handling,
real Engine projects, native compiler/language services or the native Studio port.

