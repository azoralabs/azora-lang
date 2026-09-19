# Azora ecosystem delivery plan

Updated: 2026-09-19. Scope: Azora Language, Engine, Studio, runtime, standard
library, AZLS, packages, and their integration. This is the execution plan for
progressive delivery; the older DIPs/roadmaps remain design evidence and history.

## What completion means

The ecosystem accepts programs only when it can enforce their semantics and
execute them correctly on the selected supported target. Language ownership and
type rules are shared by tooling and Engine APIs. Real projects can be created,
built, edited, run, debugged, profiled, saved, and reopened from compatible installed
artifacts. Advanced rendering claims require measured image, latency, memory, and
performance evidence; resemblance to another engine is not an acceptance test.

There are **150 ordered work items**, grouped by dependency. These are work
packages, not guesses that every task is a one-commit fix. Inspect and split a task
into numbered substeps when its implementation warrants it. Keep its parent open
until all acceptance conditions hold. Do not silently drop difficult features to
make a release look complete. Target/hardware limits must be explicit.

## Execution rules

- `[x]` means the stated acceptance evidence exists; `[ ]` is open. The current
  item is identified below and in the progress log. A design document alone does
  not complete an implementation task.
- Inspect code, current tests, related/newer DIPs, and caller behavior before each
  change. Record contradictions and the chosen interpretation. Escalate genuinely
  ambiguous consequential architecture before making a large irreversible change.
- Tests can be wrong. Classify failures as confirmed defect, stale expectation,
  missing implementation, environment limitation, or cascade. Preserve the
  intended invariant when repairing a test; do not weaken it to pass existing code.
- Follow the dependency gates. Implement each feature through the necessary
  frontend, semantics, ownership, IR, backend, runtime, stdlib, and tooling stages.
  Parsing a feature does not constitute supporting it.
- Preserve pre-existing edits. Make changes and their verification reviewable;
  do not introduce blanket skips, silent backend defaults, or unsafe workarounds.
- Run focused reproducers first, related suites next, and broaden when justified.
  Record commands, results, remaining failures, and unavailable checks. Revisit
  earlier gates if subsequent changes invalidate their evidence.
- Update this checklist and [progress log](ECOSYSTEM_PROGRESS.md) after every
  completed work package. No deadline or total effort estimate is asserted yet.

## Current position

Steps **001–006 and 009** are completed. The complete standard library now parses
and loads from disk and the actual bundled fallback. A restricted `<>` exchange
implementation spans AST, semantics, IR, optimizer, interpreter, LLVM and WASM;
its safety and backend limitations are recorded in GTC §23.2 and the progress log.
This unblocks parsing, not the correctness of every library algorithm.

The latest full compiler run has **2,347 tests: 2,143 passed,
204 failed, 0 skipped**, with no failure identity changed from the preceding
run. The [latest durable inventory](ECOSYSTEM_BASELINE_COLLECTION_FOUNDATIONS_2026_09_15.json)
records the 2,290-test baseline. ArrayList runs on the interpreter, LLVM and
WASM, and both native targets release memory on `purge`. Packs and specs can
declare sequence and associative `literal` factories with `where` clauses; a
literal fails, moves and is constrained as a call to its factory would be. All
four C3.4 substeps are complete. **010** remains open, including the collection
factory substeps below.

**007**, the complete **008** review and module identity/scope work under **014**
remain open. Local imports still leak into unrelated declarations; that requires
the binding repair already outlined in the progress log. Engine and full Studio
build/run qualification also remain open.

The [dated audit](ECOSYSTEM_AUDIT_2026_09_08.md) records the initial evidence:
2,204 compiler tests / 1,749 failures, most obscured by library loading; 26 library
files with parse failures; 337 frontend tests / 20 failures after the delimiter fix.
These are baseline observations, not a count of independent implementation bugs.

## Phase 1: Restore a trustworthy baseline (001–010)

Dependencies: None. Preserve existing local work and the already verified delimiter repair.

- [x] **001. Record repository boundaries, local changes, build commands, and baseline failures.** Acceptance: Inventory and measured compiler/frontend results are recorded in the dated audit.
- [x] **002. Repair nested generic delimiter handling.** Acceptance: Adjacent and spaced closers agree; argument nesting survives symbol collection; nine regressions pass.
- [x] **003. Classify the first blocking failures by provenance.** Acceptance: Separate stdlib loading failures, stale fixtures, confirmed parser defects, and unverified semantic findings.
- [x] **004. Migrate standard-library assertion messages and directly related fixtures to the current contract.** Acceptance: Conditions and messages are preserved; removed assertion-message forms are migrated; focused semantic/interpreter/native checks establish lazy evaluation. Unrelated file parse failures remain tracked under 005–009.
- [x] **005. Repair lifecycle and multiline body parsing/migrations.** Acceptance: Constructor, destructor, property, function, if, and grouped bodies retain the intended ownership and control flow. Receiver modes and statement boundaries are preserved; interpreter/native execution verifies contracts, constructors/properties, and grouped conditions. Full lifecycle cleanup remains under 037.
- [x] **006. Reconcile range syntax and reserved-name collisions in the library.** Acceptance: `>..` excludes the left bound, descends, and includes the right bound; the reverse keyword/modifier is removed. Bounds/step execute once, invalid steps fail, and Int-edge examples agree across interpreter/LLVM/WASM. Explicit member-name positions support `.then()`; unqualified `then` remains reserved. Tooling source migrations and build limits are recorded in the progress log.
- [ ] **007. Reconcile receiver and import syntax across library sources and test fixtures.** Acceptance: Accepted forms match current intent; removed forms have accurate diagnostics; imports retain their scope.
- [ ] **008. Review stale frontend assertions rather than changing code to satisfy them.** Acceptance: Each changed test cites the intended invariant; tests for rejected syntax and evaluation behavior remain meaningful.
- [x] **009. Make the complete standard-library tree parse and load.** Acceptance: Disk and bundled loading both succeed, including compile-time lists and version validation. Evidence: strict disk/bundle tests and full compiler baseline, 2026-09-10; runtime/library semantic failures remain under subsequent packages.
- [ ] **010. Establish reproducible per-stage baseline reports.** Acceptance: A full run classifies independent failures, unavailable native tools, and cascades; no blanket skips conceal defects.

### Collection literal work under 010, 016, 021–026 and 053–056

The user replaced standard collection macros with contextual `[]` and `[:]` and
selected `Array` as the default for a non-empty sequence without context.

- [x] **010.C1. Implement the bracket grammar and migrate obsolete macro fixtures.** Sequence/map shapes, empty associative literals, multiline/trailing commas, member/index chaining and malformed/retired syntax are covered. Keep user-defined macros tested separately.
- [x] **010.C2. Preserve array type context through semantics and lowering.** Check element types/ranges and nested fixed shapes; propagate bindings, assignment, argument, return, branch and pack-field contexts. Execute raw/optimized programs on interpreter, LLVM and WASM, including wide array stores and exchange.
- [ ] **010.C3. Establish canonical target-owned factory resolution.** Resolve declarations by module/target identity, substitute generic constraints and discover factory dependencies before lowering. Do not substitute array layouts for List/Set/Map specs.
  - [x] **010.C3.1. Preserve declaring-module reachability for selected imports.** Selecting a type or factory keeps its module's implementations while unrelated declarations and other-module extensions stay inaccessible.
  - [x] **010.C3.2. Remove implicit collection storage reinterpretation.** Named List/Set/Map targets cannot accept unrelated intrinsic storage by short name; IR retains explicit declared types. Expected named types retain generic/const arguments when reconstructed.
  - [x] **010.C3.3. Preserve generic method signatures and physical return ABI.** Substitute owner arguments in parameters/results and convert explicitly at an erased ABI boundary; validate on all backends. Evidence: the ArrayList regression executes on the interpreter, LLVM and WASM, optimized and unoptimized. WASM uses eight-byte erased slots (user decision under 021) with width-aware pack layout and spec dispatch. Both native targets free on `purge` and zero new buffers. `ArrayList.hash` is parked (user decision). Remaining items moved to 016, 019/022, 023 and 063 (closed 2026-09-19).
  - [ ] **010.C3.4. Register and select target-owned literal factories.** Support sequence/associative shape, constraints and failure/ownership requirements through the complete pipeline.
    - [x] **010.C3.4.1. Sequence factories declared by packs.** `literal` is a reserved keyword. `literal [...elements: T]: Type { … }` inside `impl Type` lifts to a type-scoped member no source can name. A literal whose expected type is that pack (binding, argument or return) selects it; elements are checked against the instantiated element type and passed once, left to right, at the factory's physical width. Missing factories, wrong element or result types, duplicates, misplaced declarations and spreads are diagnosed. Evidence: `LiteralFactoryTest`, `LiteralFactoryExecTest` (interpreter, LLVM, WASM).
    - [x] **010.C3.4.2. Spec-owned factories.** `spec S<T> { literal [...elements: T]: Self { … } }` is the spec's own function, not a requirement; it lifts to `S__literal` and designates the concrete value a `[…]` builds where `S<…>` is expected. Factories are not inherited by child specs. Spec method/property signatures now take the receiver's type arguments (own members), and LLVM converts dispatch results to the call-site type. Evidence: `LiteralFactoryTest`, `LiteralFactoryExecTest` (interpreter, LLVM, WASM).
    - [x] **010.C3.4.3. Associative factories.** A factory whose declared element type is a two-tuple, `literal [...entries: (K, V)]`, is associative (user decision); a type-parameter element is always a sequence. `[k: v, …]` and `[:]` select it, keys and values are checked separately, and each key runs before its value. Tuples now lower as aggregates on LLVM and WASM, and `t.0` reads a structural tuple. Evidence: `LiteralFactoryTest`, `LiteralFactoryExecTest` (interpreter, LLVM, WASM).
    - [x] **010.C3.4.4. Constraints, failure and ownership.** A factory takes a `where` clause, decided at selection for the target's type arguments (undecidable clauses and arguments are accepted, as for any declaration). A failable factory makes the literal fail as a call to it would, and elements are arguments: `take` moves an element and a later use is rejected. Evidence: `LiteralFactoryTest`, `LiteralFactoryExecTest`. Borrowed elements and their lifetimes follow the argument rules and move with 033/035; partial-construction cleanup is 037.
  - [ ] **010.C3.5. Discover selected factory dependencies before specialization/lowering.** Use canonical declarations and avoid unrelated short-name matches.
- [ ] **010.C4. Connect real standard List/Set/Map implementations.** Lower literals through the selected constructor/factory and preserve source order, exactly-once key/value evaluation, duplicate rules, failure behavior and ownership.
- [ ] **010.C5. Qualify factory construction across backends and tooling.** Test nested/empty literals, overloaded/generic contexts, lifetimes, native representation and Studio/compiler diagnostics together.

## Phase 2: Establish core language invariants (011–020)

Dependencies: Steps 001–010; narrow semantic tests can be developed earlier without stdlib injection.

- [ ] **011. Publish a feature and target support matrix from code inspection.** Acceptance: Every language feature identifies responsible stages, tests, unsupported targets, and actual limits.
- [ ] **012. Consolidate lexical vocabulary and source locations.** Acceptance: Compiler, AZLS, Studio, and plugin tokenization agree on names, escapes, Unicode, comments, and spans.
- [ ] **013. Harden parser recovery and AST validation.** Acceptance: Malformed input yields bounded, located diagnostics without corrupting the next declaration.
- [ ] **014. Repair module resolution, visibility, aliases, and symbol identity.** Acceptance: Multi-file ambiguity, shadowing, access checks, and incremental analysis agree with compilation.
- [ ] **015. Enforce all four binding mutability combinations.** Acceptance: Rebinding and mutation are checked separately for locals, globals, fields, and captures.
- [ ] **016. Define and enforce numeric conversion and overflow behavior.** Acceptance: Widths, signedness, literals, promotions, casts, shifts, and overflow have target-consistent tests.
  - From 010.C3.3: 128-bit values (`Cent`, `UCent`, `Quad`) do not fit an eight-byte erased generic slot on either native target.
- [ ] **017. Complete nullable, failable, Unit, and Nothing typing.** Acceptance: Invalid unwraps and missing returns are rejected; propagation and unreachable paths preserve types.
- [ ] **018. Validate packs, enums, variants, errors, and unsafe unions.** Acceptance: Layouts, constructors, payloads, discriminants, exhaustiveness, and unsafe access agree through execution.
- [ ] **019. Repair spec conformance, required members, and operator contracts.** Acceptance: Return types, receivers, associated outputs, overlapping impls, and field capability derivation are checked.
  - From 010.C3.3: member access on an unconstrained type parameter is accepted (`ArrayList.hash` read `.hash` on `T`); LLVM lowers it to a default zero. `ArrayList.hash` is parked until a `Hash` bound can be required and dispatched.
- [ ] **020. Gate core typing with positive and negative program suites.** Acceptance: Invalid programs stop before code generation and valid programs retain the intended type behavior.

## Phase 3: Complete generics and metaprogramming (021–030)

Dependencies: Core type contracts from 011–020; resolve architectural choices before broad rewrites.

- [ ] **021. Resolve generic representation and specialization architecture.** Acceptance: Document a coherent choice for native/WASM/interpreter execution, ABI, ownership, and code-size tradeoffs.
- [ ] **022. Preserve and enforce inline and where bounds.** Acceptance: Constraints survive parsing and reject invalid instantiations; no declared bound is silently discarded.
- [ ] **023. Complete nested inference, defaults, holes, and explicit arguments.** Acceptance: Functions, members, constructors, and expected types resolve consistently with useful ambiguity errors.
  - From 010.C3.3: `apply(1.5, { x -> x * 2.0 })` for `func<T> apply(value: T, change: (T) -> T): T` infers no type argument, so the call and lambda stay erased.
- [ ] **024. Complete const generic identity and layout computation.** Acceptance: Distinct const arguments produce correct layouts and cache keys; invalid values fail at compile time.
- [ ] **025. Complete variadic generic expansion and constraints.** Acceptance: Empty, singleton, nested, and heterogeneous packs preserve arity, order, and element-wise bounds.
- [ ] **026. Enforce associated types, coherence, variance, and object safety.** Acceptance: Ambiguous impls and unsafe spec objects are rejected; substitution respects declaration identity.
- [ ] **027. Make compile-time execution deterministic and bounded.** Acceptance: Dependencies, invalidation, diagnostics, recursion limits, and forbidden runtime effects are tested.
- [ ] **028. Complete annotation and macro expansion semantics.** Acceptance: Expansion preserves source provenance, binding hygiene, evaluation count, and type/ownership checking.
- [ ] **029. Complete reflection and generated-member integration.** Acceptance: Reflection uses compiler metadata; generated code passes the same checks as handwritten code.
- [ ] **030. Gate generics and metaprogramming across targets.** Acceptance: Representative nested programs and Engine query generation pass execution and diagnostic parity tests.

## Phase 4: Make ownership and memory guarantees sound (031–040)

Dependencies: Binding/type foundations; coordinate generic layouts with 021–030.

- [ ] **031. Specify owned, shared, exclusive, raw, and smart-pointer access invariants.** Acceptance: A checked matrix covers moves, lends, clones, aliases, return origins, and unsafe boundaries.
- [ ] **032. Close shared-borrow mutation holes.** Acceptance: Direct/nested fields, indices, method calls, reborrows, parameters, receivers, and shadowing are covered.
- [ ] **033. Enforce borrow lifetime and alias exclusivity.** Acceptance: Overlapping accesses fail; legal disjoint access remains available; escapes and return origins are checked.
- [ ] **034. Complete moves, partial moves, lends, and reinitialization.** Acceptance: Ownership transfers cannot duplicate/drop values twice and branch joins preserve moved-state facts.
- [ ] **035. Validate captures and escaping closures.** Acceptance: Borrow, mutable borrow, move, clone, nested capture, and callback lifetime rules survive lowering.
- [ ] **036. Complete allocation, pointer arithmetic, bounds, and alignment.** Acceptance: Raw operations require the intended access; invalid accesses trap or are rejected as specified.
- [ ] **037. Implement lifecycle ordering and cleanup on every exit.** Acceptance: Constructors, destructors, defer, failure, early return, and partial initialization have exact event traces.
- [ ] **038. Complete unique, shared, atomic-shared, and weak ownership.** Acceptance: Reference counts, upgrades, destruction, cycles policy, and concurrent operations are verified.
- [ ] **039. Complete custom allocator and runtime ABI integration.** Acceptance: Allocator ownership, alignment, failure, reallocation, and cross-language destruction are explicit.
- [ ] **040. Gate memory safety and leak behavior.** Acceptance: Interpreter checks and available native sanitizers validate representative programs; unavailable checks remain open.

## Phase 5: Complete control flow and concurrency semantics (041–050)

Dependencies: Core types and ownership; do not permit async features to bypass either.

- [ ] **041. Complete expression/statement evaluation order.** Acceptance: Calls, assignments, compound operators, increments, short circuiting, and side effects execute exactly once.
- [ ] **042. Complete match and loop semantics.** Acceptance: Patterns, guards, ranges, loop values, labels, break/continue, and else behavior agree across targets.
- [ ] **043. Complete errors, assertions, contracts, and panic paths.** Acceptance: Lazy messages, pre/postconditions, propagation, unwinding/termination, and release behavior are consistent.
- [ ] **044. Complete callable values and dynamic spec dispatch.** Acceptance: Closures and spec objects dispatch correctly; invalid dispatch traps; receiver lifetime is preserved.
- [ ] **045. Define task lifecycle and async state-machine contracts.** Acceptance: Start, suspend, resume, cancellation, completion, and destruction have a testable state model.
- [ ] **046. Implement async ownership checks and lowering.** Acceptance: Borrowed values cannot outlive owners across suspension; cleanup runs on cancellation and failure.
- [ ] **047. Complete generators and iterator suspension.** Acceptance: Yielded borrows, resumption, exhaustion, cancellation, and nested iteration preserve ownership.
- [ ] **048. Implement thread safety and synchronization contracts.** Acceptance: Thread transfer/sharing constraints, atomics, mutexes, and memory ordering are explicit and tested.
- [ ] **049. Complete channels, scheduling, and structured concurrency.** Acceptance: Backpressure, closure, task joining, deadlines, cancellation, and failure propagation avoid deadlocks.
- [ ] **050. Gate concurrent execution deterministically where possible.** Acceptance: Stress tests, race checks, adversarial schedules, and shutdown tests validate the supported model.

## Phase 6: Make execution backends agree (051–060)

Dependencies: Validated language contracts; implement corresponding backend support alongside each feature.

- [ ] **051. Define typed IR invariants and add an IR verifier.** Acceptance: Invalid control flow, operands, ownership operations, and layouts are rejected before emission.
- [ ] **052. Audit and repair the interpreter as a reference execution path.** Acceptance: Value identity, integers, aggregates, pointers, traps, and cleanup follow the language contracts.
- [ ] **053. Complete native ABI and data layouts.** Acceptance: Arguments, returns, aggregates, alignment, closures, spec objects, and foreign calls obey the target ABI.
- [ ] **054. Repair LLVM lowering for every supported IR operation.** Acceptance: No placeholder/default value silently replaces unsupported semantics; native execution tests cover repairs.
- [ ] **055. Complete WASM memory, tables, imports, and layouts.** Acceptance: Address widths, bounds, ownership, strings, aggregates, and host interop obey the target contract.
- [ ] **056. Repair WASM lowering for every supported IR operation.** Acceptance: Dispatch, loops, variants, closures, async, and traps execute correctly or fail compilation explicitly.
- [ ] **057. Validate optimization against unoptimized execution.** Acceptance: Constant folding, inlining, DCE, specialization, and memory transformations preserve observable behavior.
- [ ] **058. Complete runtime error and debug-location parity.** Acceptance: Failures identify the original source and operation consistently across interpreter/native/WASM.
- [ ] **059. Build a differential backend conformance harness.** Acceptance: Identical programs compare output, values, failure modes, and lifecycle traces across available targets.
- [ ] **060. Gate backend support claims with conformance results.** Acceptance: Each claimed feature passes; unsupported target capabilities produce explicit diagnostics.

## Phase 7: Complete the standard library and toolchain (061–070)

Dependencies: Recovered library plus type, memory, concurrency, and backend gates.

- [ ] **061. Audit standard-library contracts and generic capabilities.** Acceptance: Each public operation states mutation, ownership, errors, constraints, and target availability.
- [ ] **062. Repair arrays, lists, tuples, queues, and iterators.** Acceptance: Bounds, growth, aliasing, element drops, iteration invalidation, and empty cases are correct.
- [ ] **063. Repair maps, sets, ordering, and hashing.** Acceptance: Equality/hash agreement, collisions, NaN behavior, resizing, and deterministic contracts are tested.
  - From 010.C3.3: Set and Map read `.hash` on unconstrained element/key types, including Map lookup and insertion; with 019/022 this blocks both on WASM and is a zero on LLVM.
- [ ] **064. Complete strings, Unicode, formatting, and numeric conversion.** Acceptance: Encoding, slicing, round trips, overflow, locale policy, and allocation behavior are explicit.
- [ ] **065. Complete math, time, randomness, and algorithms.** Acceptance: Numerical/domain edge cases, monotonic clocks, reproducibility, and algorithm contracts are tested.
- [ ] **066. Complete I/O, filesystem, OS, and networking boundaries.** Acceptance: Partial operations, failures, cancellation, resource cleanup, and target support are verified.
- [ ] **067. Complete serialization, reflection codecs, and AZON.** Acceptance: Versioning, round trips, invalid input, duplicate policy, numeric precision, and limits are tested.
- [ ] **068. Consolidate package resolution and build configuration.** Acceptance: Manifests, dependency cycles, versions, lockfiles, source discovery, and target link requirements are validated.
- [ ] **069. Complete CLI, project templates, test runner, and formatting.** Acceptance: Create→check→build→run→test works from a clean project; formatting preserves parsed meaning.
- [ ] **070. Gate distributable compiler, runtime, stdlib, and AZLS bundles.** Acceptance: Clean installs resolve matching versions and run the same conformance projects as source checkouts.

## Phase 8: Make Engine ECS storage correct (071–080)

Dependencies: Language safety and basic build/package gates; pure simulation first.

- [ ] **071. Audit actual ECS packages against Engine proposals.** Acceptance: Document entity, storage, query, resource, and lifetime invariants with contradictions resolved.
- [ ] **072. Migrate Engine source to validated compiler APIs and syntax.** Acceptance: Real package graphs type-check without weakening borrow or capability checks.
- [ ] **073. Repair entity allocation, generations, and reuse.** Acceptance: Stale handles never access a new entity; overflow policy and invalid handles are tested.
- [ ] **074. Repair component storage and destruction.** Acceptance: Insert/replace/remove/despawn preserve ownership and release values exactly once.
- [ ] **075. Repair world hierarchy and entity relationships.** Acceptance: Cycles, parent destruction/reuse, reparenting, and child counts preserve valid relationships.
- [ ] **076. Complete structural mutation and deferred commands.** Acceptance: Queries cannot observe invalidated storage; command ordering and failure semantics are defined.
- [ ] **077. Complete safe immutable and mutable queries.** Acceptance: Aliasing, optional/excluded components, nested queries, and disjointness proofs are validated.
- [ ] **078. Complete resources and singleton access.** Acceptance: Resource ownership, replacement, shared/exclusive access, and missing-resource behavior are checked.
- [ ] **079. Complete change tracking and event lifetimes.** Acceptance: Added/changed/removed flags, reader cursors, tick rollover, and deferred writes remain accurate.
- [ ] **080. Gate ECS correctness and performance.** Acceptance: Property/state-machine tests and representative workloads validate lifecycle, query safety, and scaling.

## Phase 9: Make Engine scheduling and reactivity correct (081–090)

Dependencies: ECS access/lifecycle guarantees from 071–080 and language concurrency gates.

- [ ] **081. Define system access metadata and dependency contracts.** Acceptance: Metadata describes actual component/resource reads and writes, including generated queries.
- [ ] **082. Implement dependency graph validation.** Acceptance: Missing names, cycles, phase conflicts, and ambiguous ordering are diagnosed before execution.
- [ ] **083. Implement safe parallel batch construction.** Acceptance: Read/write conflicts serialize; disjoint queries can run together; resources participate in conflicts.
- [ ] **084. Complete worker execution and synchronization.** Acceptance: Dispatch, barriers, cancellation, errors, and shutdown preserve system/world lifetimes.
- [ ] **085. Complete system lifecycle and startup/shutdown semantics.** Acceptance: Startup runs once; enable/disable and plugin removal cannot leak or double-run systems.
- [ ] **086. Complete fixed, variable, and interval execution.** Acceptance: Time accumulation, catch-up limits, zero/negative intervals, and drift have deterministic tests.
- [ ] **087. Complete reactive system scheduling.** Acceptance: Changes trigger intended systems without missed events, unbounded feedback, or stale subscriptions.
- [ ] **088. Complete safe commands between schedule phases.** Acceptance: Structural changes become visible at documented points and cannot race active readers.
- [ ] **089. Expose schedule diagnostics and profiler events.** Acceptance: Users can inspect order, conflicts, runtime failures, and timings through shared Engine APIs.
- [ ] **090. Gate scheduler correctness under stress.** Acceptance: Sequential and parallel results match declared determinism guarantees across repeated runs.

## Phase 10: Complete Engine application, world, and asset foundations (091–100)

Dependencies: Compiler/toolchain distribution, ECS, and scheduling gates.

- [ ] **091. Complete plugin/application initialization and teardown.** Acceptance: Dependency ordering, partial startup failure, repeated runs, and shutdown release resources.
- [ ] **092. Complete platform windows, event loops, and lifecycle.** Acceptance: Focus, resize, suspension, closure, headless mode, and supported OS paths behave consistently.
- [ ] **093. Complete input devices and action mapping.** Acceptance: Keyboard, pointer, controller, capture, rebinding, and frame transitions are tested.
- [ ] **094. Complete scenes, worlds, prefabs, and save/load.** Acceptance: Stable identity, hierarchy, references, overrides, schema migration, and round trips are verified.
- [ ] **095. Complete asset identity, import, dependency, and cache management.** Acceptance: Content changes invalidate dependents; failures and corrupt caches cannot produce stale assets.
- [ ] **096. Complete asynchronous asset loading and streaming.** Acceptance: Cancellation, priorities, memory budgets, unloading, and ownership are safe during active frames.
- [ ] **097. Complete Engine serialization and editor mutation APIs.** Acceptance: Commands, validation, undo data, and transactions are shared with Studio instead of duplicated.
- [ ] **098. Complete retained/immediate UI contracts and accessibility.** Acceptance: Layout, focus, input routing, text, scaling, accessibility, and reactive updates are coherent.
- [ ] **099. Complete Engine profiling, diagnostics, and headless tests.** Acceptance: Subsystem failures and timings are observable without depending on Studio or a renderer.
- [ ] **100. Gate application and asset integration projects.** Acceptance: Headless simulation and windowed projects load, run, save, reload, and shut down correctly.

## Phase 11: Complete the rendering foundation (101–110)

Dependencies: World/assets/platform gates; validate graphics APIs on available hardware.

- [ ] **101. Audit GPU abstraction, capabilities, and backend contracts.** Acceptance: Supported devices/features and fallback behavior are explicit; unsupported paths cannot pretend to render.
- [ ] **102. Complete GPU resource ownership and synchronization.** Acceptance: Buffers, textures, descriptors, queues, fences, hazards, and device loss are handled safely.
- [ ] **103. Complete render graph and frame resource lifetime.** Acceptance: Dependencies, barriers, transient aliasing, resize, and multi-frame use are validated.
- [ ] **104. Complete shader compilation, reflection, and bindings.** Acceptance: Material layouts match shader inputs; errors point to source; cache keys include all dependencies.
- [ ] **105. Complete mesh formats, cameras, transforms, and culling.** Acceptance: Coordinate systems, precision, projections, bounds, instancing, and visibility are consistent.
- [ ] **106. Complete materials and physically based shading.** Acceptance: Parameter binding, textures, transparency, color spaces, and energy behavior have reference scenes.
- [ ] **107. Complete direct lighting and basic shadows.** Acceptance: Light types, attenuation, shadow bias, filtering, and temporal stability meet defined image tests.
- [ ] **108. Complete post-processing and presentation.** Acceptance: HDR, exposure, tonemapping, AA, upscaling, UI composition, and output color are validated.
- [ ] **109. Complete text and 2D rendering.** Acceptance: Fonts, shaping policy, atlas lifetime, clipping, batching, and pixel scaling are tested.
- [ ] **110. Gate rendering quality and performance on named hardware.** Acceptance: Reference images, frame captures, memory budgets, and GPU timings meet documented targets.

## Phase 12: Implement and validate advanced rendering (111–120)

Dependencies: Rendering foundation gate 110; first define measurable goals rather than claiming product equivalence.

- [ ] **111. Specify virtualized geometry architecture and budgets.** Acceptance: Define supported geometry/materials, precision, memory, streaming, and quality targets.
- [ ] **112. Build geometry clustering, hierarchy, and offline processing.** Acceptance: Generated hierarchy bounds, errors, adjacency, and reproducible asset builds are validated.
- [ ] **113. Implement virtualized geometry selection and rendering.** Acceptance: LOD error, culling, rasterization paths, fallback, and seam behavior pass reference scenes.
- [ ] **114. Implement geometry streaming and residency management.** Acceptance: Eviction, missing pages, upload scheduling, and memory pressure do not corrupt rendering.
- [ ] **115. Specify and implement virtual shadow allocation and rendering.** Acceptance: Page tables, caster updates, cache invalidation, and fallback preserve shadow correctness.
- [ ] **116. Validate virtual shadows under dynamic workloads.** Acceptance: Motion, light changes, thin geometry, disocclusion, and memory pressure meet quality/performance targets.
- [ ] **117. Specify dynamic global illumination and reflection architecture.** Acceptance: Define trace representations, update budgets, supported materials, and hardware/software paths.
- [ ] **118. Implement scene representations and GI tracing.** Acceptance: Dynamic geometry, emissives, visibility, bounce behavior, and lighting changes are represented correctly.
- [ ] **119. Implement temporal GI/reflection reconstruction.** Acceptance: History rejection, denoising, leaks, disocclusion, and latency pass adversarial scenes.
- [ ] **120. Gate advanced rendering as integrated systems.** Acceptance: Geometry, shadows, GI, and reflections coexist within measured frame and memory budgets.

## Phase 13: Complete simulation and specialized Engine systems (121–130)

Dependencies: ECS/scheduler/world gates; each subsystem requires a headless test path where applicable.

- [ ] **121. Complete physics integration and collision queries.** Acceptance: Ownership, fixed steps, contacts, constraints, CCD policy, and deterministic replay limits are tested.
- [ ] **122. Complete skeletal animation and animation graphs.** Acceptance: Import, sampling, blending, root motion, skinning, events, and retargeting have reference assets.
- [ ] **123. Complete particles and effects simulation.** Acceptance: CPU/GPU lifetime, bounds, collisions, sorting, spawning, and resource budgets are validated.
- [ ] **124. Complete audio playback, mixing, and spatialization.** Acceptance: Streaming, voices, device changes, synchronization, effects, and shutdown preserve audio/resource correctness.
- [ ] **125. Complete networking and replication.** Acceptance: Authority, serialization, reliability, prediction, reconciliation, security boundaries, and disconnects are tested.
- [ ] **126. Complete procedural generation and navigation.** Acceptance: Seeds, reproducibility, jobs, constraints, generated asset ownership, and path updates are validated.
- [ ] **127. Complete mesh terrain, foliage, and world streaming integration.** Acceptance: LOD seams, collision, editing, serialization, and streaming transitions preserve world consistency.
- [ ] **128. Complete road networks and vehicles.** Acceptance: Road topology, junctions, generated meshes, vehicle dynamics, input, and save/load have scenario tests.
- [ ] **129. Complete voxel storage, meshing, and editing.** Acceptance: Chunk boundaries, compression, streaming, collision, destructive edits, and persistence are validated.
- [ ] **130. Gate specialized-system integration and scripting.** Acceptance: Representative scenarios combine scripting, simulation, rendering, audio, and networking without lifecycle regressions.

## Phase 14: Complete Azora Studio as IDE and Engine editor (131–140)

Dependencies: Compiler/AZLS APIs and Engine editor APIs; UI development may proceed against explicit contracts earlier.

- [ ] **131. Audit and reconcile Studio compiler/frontend integration.** Acceptance: AZLS API/version contracts and vendored node frontend are reconciled without losing local changes.
- [ ] **132. Complete project creation, library management, and build/run.** Acceptance: Installed and source toolchains create, open, build, run, and diagnose projects consistently.
- [ ] **133. Complete editing, highlighting, completion, and diagnostics.** Acceptance: Compiler-owned analysis handles cancellation, versions, partial edits, imports, and accurate spans.
- [ ] **134. Complete navigation, rename, formatting, and refactoring.** Acceptance: Cross-file edits use compiler symbols, preserve syntax, and support undo without stale document writes.
- [ ] **135. Complete debugger and profiler integration.** Acceptance: Breakpoints, stepping, variables, async tasks, source maps, CPU/GPU events, and failures use shared APIs.
- [ ] **136. Complete scene viewport, hierarchy, and inspector.** Acceptance: Selection, transforms, component edits, validation, undo/redo, and play/edit transitions preserve identity.
- [ ] **137. Complete asset browser, import tools, and dependency views.** Acceptance: Preview, reimport, relocation, errors, metadata, and background tasks stay consistent with Engine assets.
- [ ] **138. Complete materials, animation, terrain, road, and voxel editors.** Acceptance: Editors use Engine data models and transactions; saved results reproduce at runtime.
- [ ] **139. Complete editor configuration, extension, recovery, and accessibility.** Acceptance: Settings, plugins, autosave/recovery, keymaps, focus, and DPI behavior are validated.
- [ ] **140. Gate Studio workflows with real Engine projects.** Acceptance: Create→edit→play→debug→profile→save→reopen works without duplicated compiler/engine logic.

## Phase 15: Integrate, harden, and release the ecosystem (141–150)

Dependencies: All applicable capability gates; explicitly unsupported configurations stay visible.

- [ ] **141. Build a curated end-to-end project suite.** Acceptance: Examples cover language semantics, headless ECS, games/apps, advanced rendering, and Studio workflows.
- [ ] **142. Validate supported OS, architecture, and browser combinations.** Acceptance: Clean-machine builds and execution are recorded per target; unavailable configurations remain unverified.
- [ ] **143. Harden external input and extension boundaries.** Acceptance: Parser, packages, assets, serializers, network inputs, FFI, and plugins have appropriate validation and fuzzing.
- [ ] **144. Establish compiler, Engine, and Studio performance budgets.** Acceptance: Compile/analysis latency, frame time, loading, memory, and large-project behavior have tracked baselines.
- [ ] **145. Complete incremental builds, caching, and invalidation checks.** Acceptance: Source, config, ABI, asset, and toolchain changes invalidate exactly the necessary results.
- [ ] **146. Remove obsolete implementations and consolidate shared APIs.** Acceptance: Legacy paths are removed only after consumers migrate; no hidden fallback masks unsupported behavior.
- [ ] **147. Reconcile DIPs, API documentation, examples, and tutorials.** Acceptance: Documents identify implemented contracts, deliberate limits, unresolved questions, and verified workflows.
- [ ] **148. Complete versioning, compatibility, packaging, and upgrade policy.** Acceptance: Artifacts carry compatible compiler/runtime/stdlib/Engine/Studio versions and tested migrations.
- [ ] **149. Run release qualification from clean installations.** Acceptance: Conformance, regression, stress, fuzz, performance, and end-to-end gates pass with reviewed exceptions visible.
- [ ] **150. Deliver a reproducible release and maintenance baseline.** Acceptance: Published support claims match evidence; known limitations, artifacts, and future work are recorded.

## Gate order and evidence ownership

The primary dependency chain is 010 → core types/ownership/generic contracts →
backend and library/toolchain conformance → ECS → scheduler/world → renderer and
specialized systems → Studio integration → clean-install release qualification.
Backend and tooling changes accompany language features throughout that chain;
their later phase gates aggregate the evidence rather than postponing integration.

Useful independent work can proceed when its inputs are stable, but later checkboxes
must not imply an unpassed prerequisite. Hardware execution, architecture choices,
and distribution compatibility are tracked as real dependencies, not paper passes.
The current task does not schedule unattended work or create other tasks.
