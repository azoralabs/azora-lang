# Azora GameDev Verse Parity

> **Status:** Living language + engine design note.
>
> This document tracks how Azora should grow toward Verse-level gameplay
> authoring while staying Azora-shaped: decorators, explicit modules, `ref`
> references, IR-first compilation, real tasks, memory controls, DI, and
> reactivity.

---

## Current Baseline

Azora already has several foundations needed for Verse-like gameplay:

| Need | Azora foundation |
|---|---|
| Async work | `task`, `await`, `launch`, channels |
| Data-first gameplay | `pack`, `slot`, `enum`, generics |
| Error-aware control | `fail ErrSet`, `T!ErrSet`, `try/catch`, `guard` |
| Metadata | `deco`, declaration decorators, parameter query decorators |
| Services | `solo`, `wrap`, `inject` |
| Reactivity | `rem`, `effect`, `view` |
| Memory | `alloc`, `drop`, `isolated`, `zone alloc`, pointers |
| Tooling | one AST/IR feeding interpreter, LLVM, WASM, and source backends |

Azora Engine `0.0.3` adds the current gameplay layer:

- folder modules such as `engine.ecs`, `engine.render`, and `engine.jobs`;
- `@Component`, `@System`, `@Resource`, `@Query`;
- `World`, `Entity`, `Storage<T>`, `ResourceCell<T>`, `EventQueue<T>`;
- resource entities and query filters;
- `QueryCursor` runtime parameters;
- query access metadata for read/write scheduling;
- render queues, materials, camera, and simple lighting.

---

## Query Syntax

The correct Azora query syntax is parameter-local:

```azora
@System("Update")
func spinSystem(
    world: ref World,
    q: @Query (mut ref LocalTransform, ref Spin, Without<Paused>),
    dt: Real
) {
    q.reset()
    while q.hasNext() {
        fin e = q.next()
    }
}
```

Important rules:

- `@Query` decorates the parameter type, not the function.
- `(mut ref LocalTransform, ref Spin, Without<Paused>)` is a real tuple type
  shape, not an annotation call argument list.
- Component access uses Azora references: `ref T`, `mut ref T`.
- `&T` and `&mut T` are not Azora query syntax.
- The query shape is stored as typed metadata.
- The parameter erases to `QueryCursor` in the current engine/runtime so
  `q.reset()`, `q.hasNext()`, and `q.next()` work today.

The language parser now accepts the parameter form and rejects `&` inside
`@Query` shapes.

---

## Design Direction

### Decorators Over Gameplay Keywords

Prefer decorators and libraries over adding special-purpose gameplay keywords:

| Gameplay role | Azora spelling |
|---|---|
| Component | `@Component pack Health { ... }` |
| Resource | `@Resource solo Config { ... }` or resource entity storage |
| System | `@System("Update") func movement(...) { ... }` |
| Query | `q: @Query (mut ref Transform, ref Velocity)` |
| Event | future `@Event pack PlayerScored { ... }` |
| Persistence | future `@Persist` / `persist` |

This keeps the core language general and lets Azora Engine provide the first
game-facing framework.

### Effects In Azora Syntax

Verse-style effects are needed, but the spelling should be Azora-native:

```azora
func targetInRange(w: World, from: Vec3): Entity effects reads decides {
    guard w.hasPlayer(from)
    return w.nearestEnemy(from)?
}

task patrol(agent: Entity): Unit effects suspends writes {
    loop {
        moveTo(agent, nextPoint())
        sleep(0.5.seconds)
    }
}
```

Target effect set:

| Effect | Meaning |
|---|---|
| `computes` | deterministic, no mutable reads/writes |
| `varies` | pure-ish but unstable, such as RNG/time |
| `reads` | reads mutable state |
| `writes` | mutates reachable state |
| `allocates` | may allocate or grow containers |
| `decides` | may fail in a failure context |
| `transacts` | writes can be journaled/rolled back |
| `suspends` | may await/sleep/yield over time |
| `no_rollback` | native/I/O effect that cannot be journaled |

### Failure And Transactions

Keep existing Azora error sets separate from Verse-style silent failure:

- `fail ErrSet.Value` remains typed error unwinding.
- `decides` marks silent failure inside a failure context.
- `attempt { ... } else { ... }` opens a transaction.
- `guard cond`, `x?`, and `arr[i]?` can fail the current attempt.

Target example:

```azora
attempt {
    spendMana(player, 20)
    let target = nearestEnemy(player)?
    spawnProjectile(player, target)
} else {
    ui.flash("No valid target")
}
```

On failure, journaled `writes transacts` mutations roll back.

---

## ECS Roadmap

1. **Current:** explicit `QueryCursor` parameters from typed `@Query` metadata.
2. **Compiler validation:** verify query tuple shapes, component refs, filters,
   and broad mutable conflicts.
3. **Scheduler metadata:** emit `QueryAccess` automatically from typed query
   shapes.
4. **Injection:** build/inject query cursors at system call sites.
5. **Parallel scheduling:** run non-conflicting systems concurrently with a
   deterministic commit order.

The key invariant: query metadata must be typed. A query shape should be
resolved like any other tuple type so the compiler can reason about it.

---

## Verse Parity Roadmap

| Phase | Deliverable |
|---|---|
| A | Engine ECS baseline: modules, decorators, `QueryCursor`, filters |
| B | Typed `@Query` compiler validation and scheduler metadata |
| C | Effect lattice and `effects` clauses |
| D | `attempt`, failable expressions, transaction journal |
| E | `sync`, `race`, `rush`, `branch`, `spawn`, `sleep` |
| F | `@Event`, `Event<T>`, `Subscription`, awaitable events |
| G | `persist`, `weak` maps, deterministic modules, seeded RNG |
| H | IR hot reload and state migration through `azls`/Studio |

---

## Target Gameplay Example

```azora
@Component
pack CapturePoint {
    var progress: Real
    var radius: Real
}

@Component
pack Team {
    var id: Int
}

@Event
pack PointCaptured {
    var point: Entity
    var team: Int
}

@System("Update")
func captureSystem(
    world: ref World,
    points: @Query (mut ref CapturePoint, Without<Disabled>),
    dt: Duration
) effects reads writes decides transacts suspends {
    points.reset()
    while points.hasNext() {
        fin point = points.next()

        race {
            block {
                loop {
                    guard anyAttackerOn(point)
                    addProgress(point, dt.seconds / 5.0)
                    guard !isCaptured(point)
                    sleep(1.frames)
                }
            }
            block {
                fin ev = await world.pointCaptured
                guard ev.point == point
            }
        } else {
            resetProgress(point)
        }

        attempt {
            guard isCaptured(point)
            world.pointCaptured.emit(PointCaptured(point, attackingTeam(point)))
        }
    }
}
```

The goal is gameplay code that reads as rules and timelines, with cleanup,
rollback, scheduling, and validation handled by the language and engine.
