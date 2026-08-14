# Azora Effects and Execution Model - DIP

> Status: **design proposal.** Existing compiler and engine behavior is called
> out explicitly. Everything else is a target design, not accepted syntax.

Azora needs one universal effect and execution model for ordinary application
code, libraries, concurrency, reactivity, databases, networking, allocators,
tools, simulations, and engines. Effects must be inferred without compiler
knowledge of domain type names, remain visible in public APIs, and provide
enough information for safety checks and deterministic scheduling.

The engine ECS is used throughout as a demanding integration case. It is not
the scope of the effect system. `effect []`, inference, latent async effects,
and upper-bound contracts apply to every Azora callable.

This DIP makes four central decisions:

1. Effects are inferred for every callable by default; `effect [...]`
   optionally pins an upper bound and is mandatory only when no body exists.
2. Effect declarations are open and library-defined. The compiler never
   dispatches on domain names such as `System`, `Query`, or `EventWriter`.
3. Async tasks retain latent effects, while actual suspension contributes the
   intrinsic `Suspend` effect.
4. Macros and annotations may contribute and consume resolved effect metadata
   through universal compiler protocols.

The design deliberately rejects postfix failure punctuation such as
`condition?`. Azora already uses `?` for optional types and safe access and uses
`?!` plus `try` for typed failures. Domain conditions should not introduce a
third unrelated meaning.

---

## 1. Current foundation

Azora already has most of the general machinery this execution model needs:

| Need | Current Azora foundation |
|---|---|
| Data-oriented values | `pack`, `enum`, `variant`, generics |
| Ownership | `T`, `T&`, `T!`, pointers and smart pointers |
| Async work | `async func`, async lambdas, `await`, cancellation runtime |
| Typed failure | `error`, `T ?! Error`, `try`, `catch`, `rescue` |
| Reactivity | `react`, `remember`, `retain`, `preserve`, runtime `effect` |
| Metadata | `annot`, reflection, meta and proc-meta direction |
| Services | `graph`, `solo`, `factory`, `inject` |
| Compile-time behavior | `inline`, `deepinline`, macros, derives |
| Targets | interpreter, LLVM, and WebAssembly |

Azora Engine already has ECS concepts such as `World`, `Entity`, component
storage, queries, resources, events, phases, and jobs. This DIP requires
`@System` on every scheduled system, `@Component` and `@Event` on their packs,
and engine macro types for query shape.

---

## 2. Separate four concepts

The original example mixed four unrelated mechanisms in one declaration:

```azora
@System(.Update)
react async func captureSystem(...) effect [...] { ... }
```

Each word makes a different promise:

| Construct | Promise |
|---|---|
| system | the engine schedules this callable |
| `react` | the callable owns reactive state and may only be called reactively |
| `async` | calling it creates work which may suspend |
| `effect [...]` | the body stays within a statically declared capability set |

They must compose, but none should imply the others. Most ECS update systems
are neither reactive nor asynchronous. A long-lived domain process is not an
update system merely because it reads components.

This ordinary deterministic system is the common case:

```azora
@System(.Update)
func movementSystem(
    movers: @query<Transform!, Velocity&>,
    dt: Duration
) {
    loop movers {
        fin row = movers.current()
        row.transform.position += row.velocity.value * dt.seconds
    }
}
```

A reactive UI adapter is different:

```azora
react func scoreLabel(score: Read<Int>&): String {
    return "Score: ${score.value}"
}
```

A long-running domain process is different again:

```azora
async func roundTimer(clock: Clock&, events: EventWriter<RoundEnded>!) {
    await clock.sleep(std::seconds(90))
    events.emit(RoundEnded())
}
```

---

## 3. Components and events use mandatory annotations

### 3.1 Components

Every ECS component pack is marked with the engine-declared `@Component`
annotation:

```azora
@Component
pack CapturePoint {
    var progress: Double
    fin radius: Double
}
```

`@Component` is annotation metadata, not a spec. Azora Engine declares and
processes it with annotation/meta facilities. The language compiler only
supplies the general annotation and macro mechanisms.

The annotation generates the engine metadata needed for storage layout,
change ticks, reflection, serialization hooks, query validation, and editor
inspection. A type without `@Component` cannot appear as a component term in
`@query`.

### 3.2 Events

Events follow the same annotation model:

```azora
@Event
pack PointCaptured {
    fin point: Entity
    fin team: Int
}
```

An unannotated pack cannot instantiate `EventReader<T>` or `EventWriter<T>`.
The annotation generates event identity and schema metadata used by recording,
replication, tooling, and deterministic event merging.

### 3.3 Annotations still have a place

Annotations remain appropriate for declarative metadata which is not type
identity:

```azora
@EditorRange(min: 0.0, max: 20.0)
@Replicate(.Authority)
@Save
var progress: Double
```

`@Component`, `@Event`, and `@System` identify engine roles. Editor ranges,
replication, and save policies add role-specific metadata. All are declared by
the engine library rather than reserved by the language.

---

## 4. Systems are discovered through reflection

The currently executable system shape is a function marked with the
engine-declared `@System` decorator and receiving `World!`:

```azora
@System(.Update)
func captureSystem(world: World!) {
    // ...
}
```

There is no registration call. The engine builds `SystemPlan` once with:

```azora
inline for S in std::reflect<*>.withAnnot<System> {
    // Read std::reflect<S>.decoMeta<System> and record S.
}
```

The same reflection enumeration is unrolled by `runPhase` to dispatch the
resolved system index to `S(world)`. Adding a system means applying `@System`;
removing the annotation means the function is an ordinary function and is not
scheduled. The compiler understands reflection and decorator metadata, not the
engine-specific name `System`.

`@System` is mandatory for every scheduled callable and is declared in
`engine.core` as a function decorator with these fields:

| field | current meaning |
|---|---|
| `phase` | execution phase; defaults to `.Update` |
| `order` | stable tie-break when constraints do not decide order |
| `runBefore` | comma-separated system or set names this system precedes |
| `runAfter` | comma-separated system or set names this system follows |
| `inSet` | optional system-set name |

The current dispatcher invokes reflected systems with `World!`. Parameter
injection through `@query`, resources, event readers, and event writers is the
target lowering performed for that reflected call; it must not introduce a
second registration model.

### 4.1 Schedule entries

A reflected `ScheduledSystem` currently contains:

- function declaration name
- phase
- `order`
- `runBefore` and `runAfter`
- `inSet`
- runtime `enabled` state

```azora
@System(
    phase: .FixedUpdate,
    order: 20,
    runAfter: "inputSystem",
    runBefore: "collisionSystem",
    inSet: "simulation"
)
func movementSystem(world: World!) {
    // ...
}
```

`systemPlan.resolve()` performs a stable topological sort. Phases are absolute;
constraints apply only inside one phase. `order` breaks ties and reflection
enumeration order breaks any remaining tie.

The current engine records `cyclic` and `cycleReport`, then falls back to stable
enumeration order for unresolved systems. The target tooling should diagnose
the complete cycle at compile time, while retaining the runtime report for
defensive validation.

Resolved effect sets and query access descriptors extend this reflected plan.
They permit independent systems to run concurrently when their access does not
conflict; they do not replace reflection discovery.

### 4.2 Update systems cannot suspend

`.Update`, `.FixedUpdate`, and render-extraction systems are bounded schedule
steps. They cannot carry `Suspend`. This prevents one forgotten `await` from
holding a frame or retaining query borrows after the world advances.

Long-lived behavior is an `async func` owned by an entity, scene, match, or
application lifecycle. It communicates with frame systems through events,
commands, channels, or owned snapshots.

---

## 5. Query design

An unfiltered query lists its yielded components directly:

```azora
@query<Transform!, Velocity&>
```

The square brackets are grouping syntax used only when `@with` or `@without`
filters follow the yielded component list:

```azora
@query<[CapturePoint!, Team&] @without Disabled>
```

Without that grouping, the macro could not distinguish yielded component terms
from the filter expression which follows them. Brackets are unnecessary and
should not be written when there is no filter.

Meaning:

| Argument | Access |
|---|---|
| `T&` | read `T` |
| `T!` | exclusively write `T` |
| `@with T` | require `T` without yielding it |
| `@without T` | reject entities containing `T` |
| `T&?` | yield an optional shared borrow when `T` is present |
| `Changed<T>` | filter by change version |
| `Added<T>` | filter by insertion version |

`@query`, `@with`, and `@without` are macros declared by Azora Engine. They are
not annotations, and none is hardcoded by the language compiler. They lower to
the engine's typed query-plan representation before normal type checking.

```azora
enemies: @query<[Transform!, Enemy&] @with Visible @without Disabled>
```

Multiple `@with` and `@without` filters compose in source order. Duplicate
filters, requiring and excluding the same component, an unannotated component,
or incompatible borrows of one component are compile-time errors produced by
the engine macros and their generated types.

The compiler validates one query for contradictory access such as
`Transform&` plus `Transform!`. The engine validates conflicts between systems
from the resolved query types and effect sets.

Query items borrow storage. They cannot be retained across:

- `await`
- world structural mutation
- query reset
- the end of the system call

The type checker should report the exact borrow and invalidating operation.

---

## 6. Effect inference and contracts

### 6.1 Inference is the default

Application and library code should not repeat effects which the compiler can
derive. Once reflected system-parameter lowering is implemented, the target
engine source is:

```azora
@System(.Update)
func captureSystem(
    points: @query<[CapturePoint!, Team&] @without Disabled>,
    captured: EventWriter<PointCaptured>!,
    dt: Duration
) {
    loop points {
        fin point = points.current()
        captured.emit(PointCaptured(point.entity, attackingTeam(point.entity)))
    }
}
```

After macro expansion and body analysis, the resolved callable metadata is:

```azora
effect [Read<Team>, Write<CapturePoint>, Emit<PointCaptured>]
```

This inference applies to `func`, `async func`, `react func`, properties,
operators, lambdas, and generated callables. `react` does not enable effect
inference; it establishes a reactive owner. An ordinary system receives the
same inference without becoming reactive.

The empty set is inferred just as naturally outside an engine:

```azora
func add(left: Int, right: Int): Int {
    return left + right
}
```

The resolved effect set of `add` is `effect []`. Writing the clause explicitly
is optional unless the author wants to pin purity as a stable API guarantee.

### 6.2 Explicit clauses are optional upper bounds

An explicit clause is a promise that inferred behavior will not exceed the
listed set:

```azora
func simulate(...) effect [Read<State>, Write<State>] {
    // Adding file IO later is an error.
}

func checksum(data: Buffer&): UInt effect [] {
    // Guaranteed to remain effect-free.
}
```

Rules:

- no clause means infer the complete set
- `effect [...]` is an upper bound, not permission to perform those effects
- `effect []` is an explicit purity guarantee
- inferred effects are recorded in module interfaces and Azora IR
- widening a published inferred set is an API change reported by tooling
- overrides may preserve or narrow the base contract, never widen it
- bridge/native declarations must write an explicit clause because no Azora
  body exists to inspect

Public functions may use inferred effects. Library authors pin a clause when
they want API stability independent of implementation changes.

`effect` in a callable clause remains distinct from runtime reactive effects:

```azora
react func example(signal: Read<Int>&) effect [ReactiveRead<Int>, Trace] {
    effect signal {
        trace { "value = ${signal.value}" }
    }
}
```

The outer clause is a static upper bound. The inner block is a runtime
subscription.

### 6.3 Effects are open declarations

Effects are not a closed compiler-owned enum. Libraries declare nominal,
compile-time-only effect specs:

```azora
effect spec Read<T>
effect spec Write<T> require Read<T>
effect spec Emit<T>
effect spec Receive<T>
effect spec Allocate
effect spec ClockRead
effect spec RandomRead
effect spec Io
effect spec Trace
effect spec Suspend
effect spec ReactiveRead<T>
effect spec ReactiveWrite<T> require ReactiveRead<T>
```

`effect spec` is a distinct declaration kind despite reusing `spec` syntax. It
has no runtime witness, members, instances, conformance table, or implementation
block. It must be bodyless and may appear only in effect declarations, effect
clauses, effect metadata, reflection, and effect-algebra operations.

An effect spec is not an ordinary behavioral spec and cannot be implemented:

```azora
impl Read<State> for State // error: an effect spec cannot be implemented
```

Likewise, an effect spec cannot implement another spec or be used as an ordinary
type bound. Contextual `require` expresses a prerequisite: a callable carrying
`Write<T>` must also carry `Read<T>`. Effect-set normalization adds and checks
that prerequisite without creating inheritance or an implementation
relationship.

`require` is the singular replacement for the existing contextual `requires`
clause throughout Azora:

```azora
spec Copy require Clone
effect spec DatabaseWrite<T> require [DatabaseRead<T>, TransactionalWrite]
```

This keeps declaration words consistently singular and imperative: `derive`,
`include`, `effect`, and `require`. `include` remains composition and `derive`
remains generated behavior; neither substitutes for a prerequisite.

Requirements are generic, transitive, and normalized before subset checking.
Duplicates are ignored after normalization. A requirement cycle is a
compile-time error naming the complete cycle.

The compiler understands effect identities, generic arguments, implication,
union, subset checking, and fixed-point inference. It does not contain strings
such as `"System"`, `"Query"`, `"EventWriter"`, or `"Emit"`.

The standard prelude binds `Suspend` to the language's intrinsic suspension
effect identity. The compiler resolves that declaration symbolically; it does
not recognize the spelling `Suspend`. Engine, database, networking, allocator,
and user packages may declare all other domain effects normally.

### 6.4 How engine code contributes effects

Azora Engine supplies effect metadata through general macro, annotation, and
callable contracts.

#### Query macros

```azora
@query<[CapturePoint!, Team&] @without Disabled>
```

The engine macro expands before effect inference and attaches:

- `Write<CapturePoint>` for `CapturePoint!`
- `Read<Team>` for `Team&`
- query storage and borrow metadata
- a scheduler access descriptor
- filter metadata for `@without Disabled`

The filter does not imply `Read<Disabled>` because it tests archetype
membership rather than yielding component data. Merely naming an engine type
does nothing; the imported macro contributes resolved metadata through the
general macro protocol.

#### Effectful operations

`EventWriter` is an engine type unknown to the compiler. Its `emit` operation
declares `Emit<E>` in its own callable contract. Calling
`captured.emit(value)` therefore contributes `Emit<PointCaptured>` through
ordinary transitive call inference. If the writer is never used, its parameter
alone does not imply emission.

Conceptually, the engine declaration carries this contract:

```azora
func emit[self: Self!](event: E) effect [Emit<E>]
```

The same rule applies to event readers, resources, clocks, random streams,
files, sockets, allocators, and third-party packages. Effects come from resolved
operations, not type-name tests in the compiler.

#### System reflection

`@System` does not create effects and has no compiler-specific annotation
processor. It is ordinary decorator metadata. After effect resolution, the
engine's existing reflection loop consumes:

- query access metadata generated by `@query`
- effects inferred from the body and its callees
- latent effects of child tasks
- ownership, borrow, phase, and determinism metadata

`std::reflect<S>.decoMeta<System>` supplies the real scheduling fields. Resolved
callable effects and query access must also be available through general
reflection metadata, allowing `systemPlan()` to extend `ScheduledSystem` and
validate phase rules. The compiler never calls engine code or recognizes the
spelling `System`.

### 6.5 Compilation pipeline

The normative order is:

1. Resolve modules and imported declarations.
2. Expand macros and annotations which produce syntax or declaration metadata.
3. Resolve generated types, callables, ownership, and generic arguments.
4. Seed effects from language operations, macro metadata, and callable
   contracts.
5. Infer transitive effects to a fixed point across calls, recursion,
   overrides, and higher-order callables.
6. Validate explicit upper bounds.
7. Store resolved effects in module interfaces, Azora IR, and reflection
   metadata.
8. Let library compile-time reflection, such as `systemPlan()`, consume that
   metadata while expanding ordinary library code.

Macro expansion cannot erase effects. Generated operations participate in the
same analysis as handwritten code. Unknown bridge operations without an
explicit effect clause are compile-time errors.

### 6.6 Suspension and latent async effects

`Suspend` is inferred from the possibility of suspending, not from the word
`async`.

```azora
async func immediate(): Int {
    return 7
}
```

`immediate` has no `Suspend` effect.

```azora
async func delayed(): Int {
    await std::sleep(std::seconds(1))
    return 7
}
```

`delayed` includes `ClockRead` and `Suspend`.

Sources of suspension are:

- `await`, which is an Azora IR suspension node and always contributes the
  intrinsic suspension effect even when a task happens to be complete at run
  time
- language suspension operations such as `delay`
- imported operations whose resolved contract includes `Suspend`
- bridge/native operations which explicitly declare `Suspend`
- higher-order async callables whose effect sets include `Suspend`

Creating a task does not suspend its caller. The task carries the body's
resolved effects as a latent effect set in Azora IR. Awaiting the task adds
`Suspend` to the awaiting callable, while schedulers and owner validation can
still inspect the task's latent reads, writes, IO, and suspension.

`std::branch` retains its async callback's latent effects. `std::race`,
`std::sync`, and `std::rush` combine the branch sets. A frame system cannot hide
a suspension by moving it into a child task: `@System` validates escaping task
ownership, borrowed captures, and latent branch effects.

### 6.7 Scheduler use

For the inferred set:

```azora
effect [Read<Team>, Write<CapturePoint>, Emit<PointCaptured>]
```

the scheduler may run the system beside readers of `CapturePoint`, readers or
writers of unrelated components, and emitters of unrelated events. It may not
run beside another writer of `CapturePoint` unless both use command buffers
with a declared deterministic merge strategy.

`Write<T>` implies `Read<T>`. The scheduler uses the resolved set and generated
query descriptor, never an unchecked annotation string. Explicit clauses
cannot lie to unlock parallelism because they are validated against inference.

### 6.8 What should not be an effect

- Typed failure already appears as `T ?! Error`; a `Decides` effect would be
  less precise duplication.
- A transaction is a lexical execution context. Its operations still carry
  `Read<T>`, `Write<T>`, `Emit<E>`, and other ordinary effects.
- Rollback safety is a property of an effect implementation for an
  `AttemptSource`. It is not a `NoRollback` effect.
- `react` is an ownership and invalidation model, not shorthand for any static
  effect set.

`std::attempt` rejects `Suspend`, external `Io`, or any other effect for which
its source has no rollback adapter.

---

## 7. Failure and rollback

### 7.1 Do not add postfix `?`

In the original example:

```azora
!isCaptured(point)?
```

the intended meaning was:

> Negate `isCaptured(point)`. If the result is false, fail the current
> speculative branch and roll back its journaled mutations.

That is too much invisible behavior for two punctuation marks. It is also easy
to misread as optional propagation, while Azora already has `T?`, `?.`, and
`T ?! Error`.

Use ordinary control flow and a typed reason:

```azora
error CaptureRejected {
    NoAttacker,
    AlreadyCaptured
}

func advanceCapture(point: CapturePoint!, dt: Duration): CaptureResult ?! CaptureRejected {
    if !anyAttackerOn(point) { return .NoAttacker }
    if isCaptured(point) { return .AlreadyCaptured }

    addProgress(point, dt.seconds / 5.0)
    return CaptureResult(progress: point.progress)
}
```

The call site chooses the state source whose writes must be journaled:

```azora
fin result = std::attempt<CaptureResult, CaptureRejected>(world) { attempt ->
    fin point = attempt.get<CapturePoint!>(pointEntity)
    return advanceCapture(point, dt)
} catch {
    resetProgress(pointEntity)
}
```

No silent branch failure, no optional ambiguity, and diagnostics can name
`CaptureRejected`.

### 7.2 `std::attempt`

`std::attempt` is the standard transactional operation from `std.reactive`,
re-exported through realm `std`. It is not an ECS keyword and does not replace
language `try`.

Conceptually:

```azora
spec AttemptSource

func attempt<T, E, S>(
    source: S!,
    body: (Attempt<S>!) -> T ?! E
): T ?! E
where S: AttemptSource
```

Its trailing lambda receives the only mutable transaction view. A world view
provides journaled component access, structural commands, and staged events. A
reactive store view provides journaled `State<T>` writes. Editors, simulations,
forms, and future database packages can implement the same `AttemptSource`
contract without compiler hardcoding.

```azora
fin result = std::attempt(world) { attempt ->
    fin point = attempt.get<CapturePoint!>(entity)

    if !anyAttackerOn(point) { return .NoAttacker }
    if isCaptured(point) { return .AlreadyCaptured }

    addProgress(point, dt.seconds / 5.0)
    return CaptureResult(progress: point.progress)
}
```

The three nearby constructs have distinct jobs:

| construct | job |
|---|---|
| `try value` | propagate or handle an existing typed failure |
| `std::attempt(source) { ... }` | journal writes, commit success, roll back typed failure |
| `std::batch(source) { ... }` | always commit and publish once after the body |

Rules:

1. Successful completion commits the journal and returns the body value.
2. A typed failure rolls it back before propagating or entering `catch`.
3. `defer` runs whether the transaction commits or rolls back.
4. `Suspend`, `Io`, and non-staged external mutation are forbidden inside.
5. Events are visible only after commit.
6. Entity creation/destruction uses provisional IDs until commit.
7. Nested attempts create savepoints when supported; otherwise they are
   rejected before the inner body runs.
8. An attempt has bounded journal memory.

Ordinary per-frame ECS command buffers already provide much of this shape. The
world's `AttemptSource` adapter should build on them rather than snapshotting
the whole world. Reactive invalidation and observer delivery remain buffered
until commit, so effects cannot observe a value which is later rolled back.

### 7.3 Assertions remain different

`assert` means a violated programmer contract. It may panic and is not normal
domain branching.

```azora
assert point.radius > 0.0 { "capture radius must be positive" }
```

Do not use `assert` for "no attacker is present" or "the point was captured by
another team". Those are expected domain outcomes.

---

## 8. Structured concurrency

### 8.1 Does Azora need a `race` keyword?

Not initially.

`race`, `sync`, `rush`, and `branch` are typed functions supplied through
realm `std` by `std.concurrency`. Their trailing lambdas expand to task
attachment, cancellation, awaiting, and cleanup. Their semantics require
runtime support, but their names do not need to be reserved by the lexer.

Canonical form:

```azora
fin outcome = await std::race<CaptureOutcome> {
    std::branch {
        return await captureLocally(point)
    }

    std::branch {
        return await capturedEvents
            .first { event -> event.point == point }
            .map { event -> CaptureOutcome.Captured(event.team) }
    }

    std::branch {
        await clock.sleep(timeout)
        return .TimedOut
    }
}
```

Every branch is introduced with the fully qualified `std::branch`; unqualified
`branch` is never magic syntax. Within the `std::race` configuration lambda it
registers one async child body and returns its typed descriptor.

The result is handled normally:

```azora
when outcome {
    CaptureOutcome.Captured(team) -> { awardPoint(point, team) }
    CaptureOutcome.Abandoned -> { resetProgress(point) }
    CaptureOutcome.TimedOut -> { resetProgress(point) }
}
```

There is no `race { ... } else { ... }`. A race returns a typed winner or a
typed failure. Normal `when` and `catch` make every outcome visible.

### 8.2 `race` semantics

1. All branches become children of the current async owner.
2. They start before the parent awaits another operation.
3. The first branch to complete wins.
4. A simultaneous completion is resolved by source order for determinism.
5. Losing branches are cancelled.
6. The race waits for losing branches to finish cancellation cleanup.
7. Every branch's `defer` runs.
8. The winner's value determines the race value.
9. A winning typed failure is propagated after loser cleanup.
10. Parent cancellation cancels and joins every branch.

These semantics are identical under the interpreter, LLVM, and WebAssembly. A
target which cannot provide them must reject the operation rather than run the
branches serially without warning.

### 8.3 `sync` and `rush`

```azora
fin loaded = await std::sync {
    std::branch { return await loadMap() }
    std::branch { return await loadAudio() }
    std::branch { return await loadShaders() }
}
```

`sync` waits for all branches and returns `std::Tuple<...T>` in source order.
If one fails, siblings are cancelled and joined before failure propagates.

```azora
fin first = await std::rush<Result> {
    std::branch { return await teamOne.finish() }
    std::branch { return await teamTwo.finish() }
}
```

`rush` returns the first completion but allows losing branches to continue as
children of the surrounding async owner. Because its lifetime is less local,
`rush` should be uncommon and produce a lint when a surrounding owner is not
obvious.

### 8.4 No `scope` or `std::block`

The original nested `scope {}` blocks are unnecessary. A `std::branch` body is
already a lexical child-task lifetime, and its parent `std::race`, `std::sync`,
or `std::rush` owns cancellation. A `std::block` wrapper would add no lifetime
or ordering semantics.

---

## 9. Correct capture-point design

The original function was both an update system and a long-running async race.
That creates a serious lifecycle bug: every frame can start another infinite
process for every capture point.

Choose one of two designs.

### 9.1 Deterministic frame system

This is the recommended capture-point implementation:

```azora
@Component
pack CapturePoint {
    var progress: Double
    fin radius: Double
    var ownerTeam: Int?
}

@Component
pack Team {
    fin id: Int
}

@Event
pack PointCaptured {
    fin point: Entity
    fin team: Int
}

@System(.Update)
func captureSystem(
    points: @query<[CapturePoint!, Team&] @without Disabled>,
    captured: EventWriter<PointCaptured>!,
    dt: Duration
) {
    loop points {
        fin point = points.current()
        fin attacker = attackingTeam(point.entity)

        if attacker == null {
            point.progress = 0.0
            continue
        }

        if point.ownerTeam == attacker { continue }

        point.progress += dt.seconds / 5.0
        if point.progress < 1.0 { continue }

        point.progress = 1.0
        point.ownerTeam = attacker
        captured.emit(PointCaptured(point.entity, attacker))
    }
}

```

Properties:

- one bounded pass per frame
- no suspension while query storage is borrowed
- deterministic event order
- no duplicate task per frame
- scheduler-visible component access
- easy replay and rollback

### 9.2 One async process per entity

Use this only when the rule genuinely waits on several timelines:

```azora
variant enum CaptureOutcome {
    Captured(team: Int)
    Abandoned
    Removed
}

async func captureProcess(
    point: Entity,
    removed: EventReader<EntityRemoved>&,
    captured: EventReader<PointCaptured>&
): CaptureOutcome {
    return await std::race<CaptureOutcome> {
        std::branch {
            loop {
                if !anyAttackerOn(point) { return .Abandoned }
                await std::nextFrame()
            }
        }

        std::branch {
            fin event = await captured.first { it.point == point }
            return .Captured(event.team)
        }

        std::branch {
            await removed.first { entity -> entity == point }
            return .Removed
        }
    }
}
```

The scene or entity lifecycle starts this process exactly once and owns its
task. Removing the entity cancels the process even if the explicit removal
event is missed.

Do not mark this long-lived process with `@System(.Update)`.

---

## 10. Reactivity and ECS

`std.reactive` is application reactivity, not UI reactivity. The same owner,
state, derived-value, subscription, batching, and attempt model serves UI,
tools, editor inspectors, domain state, and server processes. A renderer is a
consumer of the graph, never part of its semantics.

`react` is still not the default ECS iteration mechanism. ECS already has:

- component change ticks
- added/removed component streams
- events
- commands
- fixed schedules

Wrapping every system in `react` would create two invalidation schedules for
the same data. Engine adapters therefore publish ECS changes into the same
`std.reactive` graph at deterministic phase boundaries:

```azora
react func captureHud(source: Read<CaptureSnapshot>&): String {
    return "${source.value.percent}%"
}
```

An ECS extraction system publishes an owned snapshot to reactive state. UI,
audio, debug tooling, and domain observers may read that same state without
retaining component borrows. Publication is batched once per configured phase,
not performed after every internal component write.

The reverse direction uses `std::attempt(world)`: reactive commands enter a
journaled world view, and observers see the resulting state only after commit.
Rollback publishes nothing. This is the unification point between reactive
application state and transactional simulation state.

Reactive owner identity must be keyed by entity, scene, or application owner
before entity-local reactive behavior is supported. Process-wide state keyed
only by callable identity would incorrectly make every entity share one
`remember` cell.

Static callable `effect [...]` contracts remain separate from runtime reactive
`effect {}` blocks. The former proves access and scheduling safety; the latter
subscribes to changing values. A reactive callable may use both.

---

## 11. Events

`EventWriter<E>` stages events in a system-local buffer. The scheduler merges
buffers deterministically by phase, system order, worker index, and emission
index.

`EventReader<E>` maintains an explicit cursor. Reading an event does not delete
it for other readers.

Required operations:

```azora
writer.emit(value)
reader.next(): E?
reader.drain(): List<E>
await reader.first(predicate)
writer.close()
```

Awaiting an event requires an owned reader or subscription. A frame-borrowed
query/event cursor cannot cross `await`.

Events are transient. Persistent state belongs in components, resources, or a
save model. Network replication may derive messages from events but is not
implicitly enabled by `@Event`.

---

## 12. Determinism and simulation

Every scheduled system has one determinism class:

| Class | Allowed behavior |
|---|---|
| deterministic | fixed inputs produce fixed outputs |
| seeded | randomness comes from an explicit seeded stream |
| authority | may read authoritative clock/network state |
| presentation | may read wall time, audio, rendering, device state |

The class can be represented as effects rather than an annotation:

- `RandomRead<SimulationRng>` is deterministic when the stream is part of the
  world snapshot.
- `ClockRead` is not deterministic unless it reads the fixed simulation clock.
- `Io` is forbidden in deterministic simulation phases.
- `Trace` is observational and cannot change simulation values.

Deterministic phases use:

- fixed timestep
- stable entity and query order
- stable command/event merge order
- deterministic floating-point policy or fixed-point values where required
- seeded random streams stored in the world
- no wall clock, filesystem, network receive, or untracked parallel reduction

These rules make replay, rollback netcode, multiplayer verification, and editor
time travel possible without making the entire application deterministic.

---

## 13. Parallel scheduling

Two systems may overlap only when all of these hold:

1. neither writes a component/resource read or written by the other
2. their event channels have deterministic merge behavior
3. their ordering constraints do not create a path between them
4. neither uses an exclusive world operation
5. both are valid for the phase's determinism class
6. their values satisfy the language's thread-safety requirements

The scheduler derives a conflict graph from resolved effects. It never trusts a
string in an annotation.

Parallel systems write through exclusive query rows or local command buffers.
They do not share raw mutable world access.

---

## 14. Diagnostics

Required diagnostics include:

```text
captureSystem declares effect [] but writes CapturePoint.progress
  add Write<CapturePoint> or remove the mutation
```

```text
captureSystem cannot suspend in phase Update
  await occurs while @query<CapturePoint!> is borrowed
  move the wait to an entity-owned async process
```

```text
movementSystem and teleportSystem both write Transform
  add an ordering constraint or use deterministic command buffers
```

```text
postfix '?' does not create a domain failure context
  use typed failure: if condition { return .Reason }
```

```text
std::branch captured 'point' by exclusive borrow
  race branches may outlive this borrow; capture an owned Entity instead
```

Diagnostics should name the complete inferred path through macro metadata,
operations, and transitive calls. They must distinguish a direct suspension
point from a latent suspension captured by a child task.

---

## 15. Lowering model

### Interpreter

- macro-provided and callable-provided effects are resolved before execution
- effect sets are Azora IR metadata and optional runtime debug assertions
- `await` lowers to a suspension node carrying the intrinsic effect identity
- tasks retain their body's latent effect set
- queries borrow engine storage for one call
- race/sync functions create structured child tasks through std::branch
- cancellation runs branch cleanup before parent continuation
- attempt journals record component and structural writes

### LLVM

- resolved effect sets do not add runtime tags
- latent task effects are compile-time descriptors, not heap objects
- the scheduler uses static access descriptors to build parallel batches
- async races use the native task scheduler and cancellation tokens
- attempt journals use owned arena memory and deterministic destruction
- no garbage collector is introduced

### WebAssembly

- the same resolved effect sets, suspension nodes, and cancellation state are used
- browser hosts drive timers and frame wakeups through declared capabilities
- no silent synchronous fallback for `race`, `sync`, or `await`
- attempt and event ordering matches interpreter and LLVM

---

## 16. What becomes language syntax

Proposed general language additions:

```text
effect spec Name<...>
callable declaration effect [Effect, Effect<Arg>, ...]
```

Not language keywords:

- `Component`
- `Event`
- `System`
- `Query`
- `@query`
- `@with`
- `@without`
- `Without`
- `race`
- `sync`
- `rush`
- `branch`
- `attempt`

Those are library specs, types, functions, or macros. The language should only
gain syntax for the general effect system because it applies equally to simulations,
databases, networking, allocators, and application code.

The language also needs macro-produced metadata and resolved callable effects
to be visible through ordinary reflection. These are not ECS features: any
package may use them, and the compiler never dispatches by a library
declaration's spelling.

---

## 17. Delivery order

### Phase A: clean engine contracts

- define mandatory `@Component`, `@Event`, and `@System` annotations
- define `@query`, `@with`, and `@without` macros over typed query plans
- preserve reflection-only discovery through `withAnnot<System>`
- lower reflected system parameters without introducing registration

### Phase B: effect declarations

- parse bodyless, non-implementable `effect spec`
- parse callable `effect [...]`
- bind the prelude `Suspend` declaration to the intrinsic suspension identity
- accept macro-generated effect and scheduler metadata
- infer effects through calls, ownership operations, and higher-order callables
- retain latent effect sets on async tasks and branch callbacks
- compute recursive call graphs to a fixed point
- enforce empty and upper-bound contracts
- persist resolved sets in module interfaces and Azora IR
- expose inferred and declared effects through reflection and AZLS

### Phase C: scheduler integration

- derive query read/write effects
- expose effects and query access through callable reflection
- extend `systemPlan()` to consume that metadata beside `decoMeta<System>`
- generate conflict graphs
- deterministic command/event merge
- phase restrictions such as no direct or hidden `Suspend` in `.Update`

### Phase D: structured concurrency

- finish cancellation parity on all targets
- implement `std::branch`, `std::sync`, `std::race`, and `std::rush`
- guarantee loser cleanup and deterministic ties
- test entity/scene/application task ownership

### Phase E: transactional execution

- journal component and structural writes
- stage events until commit
- reject non-rollback-safe effects
- implement the world `AttemptSource` adapter
- integrate typed failure and savepoints

### Phase F: deterministic simulation

- seeded random streams
- replay recording
- rollback snapshots
- fixed simulation clock
- deterministic parallel reductions

---

## 18. Final target example

The canonical capture system should be the bounded frame version, not the
original async race:

```azora
@Component
pack CapturePoint {
    var progress: Double
    fin radius: Double
    var ownerTeam: Int?
}

@Component
pack Team {
    fin id: Int
}

@Event
pack PointCaptured {
    fin point: Entity
    fin team: Int
}

@System(.Update)
func captureSystem(
    points: @query<[CapturePoint!, Team&] @without Disabled>,
    captured: EventWriter<PointCaptured>!,
    dt: Duration
) {
    loop points {
        fin point = points.current()
        fin attacker = attackingTeam(point.entity)

        if attacker == null {
            point.progress = 0.0
            continue
        }

        if point.ownerTeam == attacker { continue }

        point.progress += dt.seconds / 5.0
        if point.progress < 1.0 { continue }

        point.progress = 1.0
        point.ownerTeam = attacker
        captured.emit(PointCaptured(point.entity, attacker))
    }
}

```

The source writes no static clause. The compiler records the resolved set:

```azora
effect [Read<Team>, Write<CapturePoint>, Emit<PointCaptured>]
```

It is shorter than the original because each abstraction has one job. Effects
describe access. The query borrows components. The schedule owns invocation.
Events communicate outcomes. Reactivity and asynchronous races remain available
where their lifetimes actually fit.

---

## 19. Influence and deliberate differences

Verse demonstrates the value of explicit effect contracts, transactional
failure contexts, and structured concurrency. In Verse, `decides` marks
failable functions, `transacts` enables rollback, and `race` cancels losing
async expressions. Azora keeps the capabilities while using its existing typed
errors, ownership, async functions, and libraries instead of copying Verse's
postfix failure and concurrency vocabulary.

Official references:

- <https://dev.epicgames.com/documentation/en-us/fortnite/specifier>
- <https://dev.epicgames.com/documentation/fortnite/transacts>
- <https://dev.epicgames.com/documentation/fortnite/structured-concurrency>

---

## 20. Related DIPs

- `DIPs/CONCURRENCY_DIP.MD`
- `DIPs/PARALLELISM_DIP.MD`
- `DIPs/THREAD_SAFETY_DIP.MD`
- `DIPs/REACTIVE_DIP.md`
- `DIPs/ERROR_HANDLING_DIP.MD`
- `DIPs/OWNERSHIP_BORROWING_DIP.md`
- `DIPs/LAMBDA_CONTEXT_CAPTURE_DIP.MD`
- `DIPs/ANNOTATIONS_DIP.MD`
- `DIPs/COMPILER_INTRINSIC_DIP.MD`
