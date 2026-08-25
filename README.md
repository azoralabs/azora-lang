# Azora Language

A statically-typed, multi-target programming language with an IR-based compiler
pipeline, an ownership model with no garbage collector, and compile-time
execution.

> **Status: pre-release (`0.0.5`).** The language is usable and heavily tested,
> but nothing here is stable yet: syntax still changes between versions, the
> standard library's APIs are not settled, and two of the three backends have
> gaps. See `ROADMAPs/VERSION_0_1_ROADMAP.MD` for exactly what stands between
> today and a production release.

## Quick Start

```bash
# Build
./gradlew :app:installDist

# Run a program
app/build/install/azora/bin/azora run hello.az

# Type-check
app/build/install/azora/bin/azora check hello.az

# Emit code for a target
app/build/install/azora/bin/azora compile llvm hello.az

# REPL
app/build/install/azora/bin/azora repl
```

## Hello World

```azora
import std.io

func main() {
    println("Hello, Azora!")
}
```

The standard library is import-gated: a file sees a module's names only after
importing it, and only the items actually referenced are injected. A program
that never touches the stdlib compiles as if it did not exist.

## Architecture

```
Source -> Lexer -> Parser -> AST Validator
                     |
                Stdlib Injection (only the modules you import, transitively)
                     |
                Symbol Collection -> Type Resolution <-> CTCE -> Alloc/Drop -> Effects
                     |
                Derivers (comparison, display, cast, serialization)
                     |
                IR Generator -> IR Optimizer
                     |
        +------------+------------+
        |            |            |
      Wasm         LLVM      Interpreter      (+ IR / AST dump)
```

The IR is target-agnostic. One `Compiler.compile()` lowers the optimized IR to
every active backend in a single pass. Adding a target means one new file under
`backend/`.

---

# Language

Ground truth for this section is the compiler: 93 reserved keywords, listed in
`AzoraSyntaxVocabulary.kt`.

## Types

- **Primitives**: `Int` `UInt` `Long` `ULong` `Byte` `UByte` `Short` `UShort`
  `Cent` `UCent` `Float` `Double` `Quad` `Bool` `Char` `String` `Unit`
- **Numeric literals** carry an optional suffix: `3L` `7u` `1.5f` `9D` `2c`
- **Compound**: `Array<T, N>`, `List<T>` / `Set<T>` / `Map<K, V>` and their
  mutable forms, `Tuple<A, B>`, function types `(A) -> B`. Types are always
  written with generics: there is no invented type syntax to learn, and a
  type's spelling does not depend on what a file imports
- **User-defined**: `pack` (a struct), `enum`, `variant enum`, `error` sets,
  `variant error`, `unsafe union`, `typealias`
- **Nullable** `T?` with `null`, `??` (coalesce), `?.` (safe access)
- **Failable** `T ?! E`, a `T` or an error from the set `E`
- **Pointer** `T*`, with `alloc` and `*ptr`
- Integer and float promotion: `2 + 1.5` is `3.5`

```azora
pack Point {
    var x: Int
    var y: Int
}

enum Direction {
    Up
    Down
}

variant enum Shape {
    Circle(Int)
    Rect(Int, Int)
}
```

## Bindings

Two axes: whether the **name** can be rebound, and whether the **value** can be
written.

| | value mutable | value frozen |
|---|---|---|
| **name rebindable** | `var` | `val` |
| **name fixed** | `let` | `fin` |

```azora
var count = 0          // rebindable, mutable
fin limit: Int = 10    // fixed, frozen
```

`threadlocal var` and `threadlocal fin` give per-thread storage.

### Grouping

A bracketed list stands for the lines it would have been written as. It works
on either side, and nothing past the parser knows about it.

```azora
fin [oldKeys, oldValues] = using self { [keys, values] }  // read members of one value
let [keys: K*, values: V*] = alloc .() * capacity         // each name may state its type
fin [a, b] = [1, 2]                                       // one value per name

self.[keys, values] = alloc .() * capacity                // the expression, per member
self.[capacity, size] = [newCapacity, 0]                  // one value per member
self.[keys[i], values[i]] = using self { [keys[i + 1], values[i + 1]] }
[newKeys[i], newValues[i]] = using self { [keys[i], values[i]] }
purge [oldKeys, oldValues]                                // release several at once
```

One expression on the right is *written* to each target rather than evaluated
once and shared: `alloc .() * n` allocates per member, which is the only reading
under which four buffers are four buffers.

## Functions

```azora
func add(a: Int, b: Int = 0): Int {
    return a + b
}

func<T> identity(value: T): T { return value }
```

- Default parameters, named arguments (`add(b: 2, a: 1)`)
- Generics with call-site inference
- Variadic generics `...T`, variadic parameters, and the spread `f(arr...)`
- `inline func` for call-site substitution
- Trailing lambdas and implicit `it` in single-parameter lambdas
- Infix calls are declared as macros, not as a function form: `macro $a @to $b`
- Contracts: `in { }` preconditions, `out { }` postconditions with `it`,
  `scope { }` bodies

### Receivers and borrows

A method declares its receiver before the member name, and a borrow is written with a
sigil rather than a keyword:

- `&` shared, read-only
- `!` exclusive, the callee may write through it

```azora
impl Point {
    prop &.magnitude: Int = self.x * self.x + self.y * self.y
    func !.moveBy(dx: Int) { self.x = self.x + dx }
}
```

## Control flow

- `if` / `else if` / `else`
- `while`, `loop { }`, `loop { } while cond` (do-while)
- `loop <iterable> { }`, which drives the iterable's `reset` and `hasNext`
- `for x in a..b`, `for x in a..<b`, `for x in array`
- `for x in a..b by N` (step), `reverse for`
- `for` / `while` / `loop` with an `else` that runs unless `break` fired
- Labeled loops: `lbl: for …`, `break:lbl`, `continue:lbl`
- `when expr { pattern -> { } else -> { } }`, exhaustive over enums and variants,
  with payload destructuring

## Types with behaviour

- `impl Type { }` adds members; `impl Spec for Type { }` implements a spec
- Receiver-free members inside `impl Type { }` and `impl Spec for Type { }` declare statics, reached as
  `Type::member`
- `spec` declares a capability: `func`, `prop` and `oper` requirements. A spec
  may `require` another - `spec Copy requires Clone` - which states what an
  implementer must already have, not what the spec grants
- A receiver-less `func` in a spec is a **static** requirement
- `prop` computed properties, `ctor` secondary constructors, `dtor` destructors
- Dynamic dispatch on a spec-typed value: the value is a fat pointer carrying
  a type id and the data, and the call goes through the spec's dispatch table.
  There is no keyword for it - using a spec as a type is what selects it
- `scope Name { }` namespaces, members reached as `Name::member`

```azora
spec Greet {
    func greet[self: Self&](): String
}

impl Greet for Point {
    func greet[self: Self&](): String { return "a point" }
}
```

## Operators

Every comparison operator is a member of the spec that governs it, and a type
states its comparison **once**.

- Arithmetic `+ - * / %`, bitwise `& | ^ ~ << >>`, logical `&& || !`
- Assignment `= += -= *= /= %=`, `++`, `--`
- Comparison `== != < <= > >=` and the three-way `<=>`
- Null-conditional `??` `?.` and compound forms `?+= ?-= ?*= ?/= ?%= ?++ ?--`
- Casts `as` `as?` `as*`; type tests `is`, `is!`
- Ranges `a..b` (inclusive), `a..<b` (exclusive), `reverse..`

### Overloading

An operator is declared with `oper`, and the specs that own them are in
`std.traits`:

Every operator writes its operand parentheses. Unary operators and other
zero-operand declarations use `()`, for example `oper- &.(): Self`; the
parenthesis-free `oper- &.: Self` form is invalid.

```azora
import std.traits

pack Version {
    var major: Int
    var minor: Int
}

derive Equal for Version         // Order requires Equal

impl Order for Version {
    oper<=> &.(rhs: Self&): Compare {
        if self.major < rhs.major { return Compare.Less }
        if self.major > rhs.major { return Compare.Greater }
        if self.minor < rhs.minor { return Compare.Less }
        if self.minor > rhs.minor { return Compare.Greater }
        return Compare.Equal
    }
}
```

A spec may require another, and `Order requires Equal`: a type that orders must
also say what equal means. Write the return type - omitting it is currently
accepted and then fails at runtime (roadmap §1.1).

That one member gives `<`, `<=`, `>` and `>=`: they are rewritten to a single
`<=>` call, never six separate members that could disagree. `!=` is likewise
rewritten from `==`.

The spec you implement decides the result type, so it cannot be got wrong:
`Order` fixes `Compare`, `PartialOrder` fixes `PartialCompare` - which has a
fourth case, `Unordered`, so `NaN` makes all four relational operators false.

Derivation is explicit and separate from manual implementation:

```azora
derive (Equal, Order) for Point  // == , <=> , < <= > >= , != , hash
```

`==` on a pack that never said what equal means is a **compile error**, not a
silent structural or address comparison.

**Operator families** group the operators that differ only in which symbol they
spell, and a type implements the part it wants:

| spec | operators |
|---|---|
| `Arithmetic` | `+ - * / %` and their `=` forms |
| `Bitwise` | `& \| ^ << >>` and their `=` forms, `~` |
| `Neg` / `Logical` | unary `-` / unary `!` |
| `Indexable` | `[]` `[]=` `[:]` |
| `Deref` / `DerefMut` | `.*` / `.^` |
| `PartialEqual` / `Equal` | `==` |
| `PartialOrder` / `Order` | `<=>` |
| `Hash` | `hash` |
| `Display` | string interpolation |
| `Cast` / `CheckedCast` / `BitCast` | `as` / `as?` / `as*` |

```azora
impl Arithmetic for Matrix {
    oper+= !.(rhs: Self&) {
        for i in 0..<self.data.length { self.data[i] += rhs.data[i] }
    }
}
```

`m + n` is then built from `+=` and a clone; `m % n` is an error naming the
member, not a `%` nobody meant.

## Conversion

Five spellings, and the distinction between them is the point: a **cast** is
about representation and never allocates; a **conversion** is about meaning and
may.

| spelling | kind |
|---|---|
| `value as T` | cast, total |
| `value as? T` | cast, checked at runtime, `T?` |
| `value as* T` | cast, bit reinterpretation |
| `value.into<T>` | conversion, may allocate or consume |
| `T::from(v)` | conversion, constructs a `T` |

## Display

A type says how it prints by implementing `Display`, which writes into a
`Formatter` rather than returning a `String` - so a composite renders its parts
into one buffer.

```azora
import std.format

impl Display for Point {
    func display[self: Self&](formatter: Formatter!) {
        formatter.write("(")
        formatter.write("${self.x}")
        formatter.write(")")
    }
}
```

`"${value}"` calls `Display` and nothing else. A pack that has not said how it
prints is a compile error, not a leak of its field layout into output.

## Memory and ownership

No garbage collector. Ownership is tracked and checked.

- `alloc <expr>` heap-allocates, yielding `T*`; `*ptr` reads, `*ptr = v` writes
- `purge <expr>` releases
- Pointer arithmetic: `ptr + n`, `ptr - n`, `ptr1 - ptr2`, `ptr1 == ptr2`
- `take <expr>` transfers ownership; `lend` hands a borrow the callee returns
- `expr.clone()` produces an independent deep copy; a `pack` gets it
  field-wise, and the built-in aggregates always have it
- `defer { }` runs at exit, LIFO, through returns and throws
- `unsafe { }` is an opt-in block

## Error handling

- `throw value`, `try { } catch { e -> }`, `expr catch fallback`
- `rescue { }` catches and suppresses
- `error ErrSet { A, B }` declares an error set; `T ?! ErrSet` is a failable
  return, and membership is enforced
- `error defer { }` runs only on an error exit
- `return .Variant` where the set is known from context

## Metaprogramming

Compile-time execution runs to a fixed point with type resolution, before code
generation.

**Bindings**: `inline fin`, `inline let`, `inline var`, `inline val`, and
`inline name = expr` to re-assign one.

**Blocks**: `inline { }`, `inline scope { }`, and `inline scope { }` at top
level. `deepinline { }`, `deepinline scope { }` and `deepinline prop` evaluate
through nested declarations. `noinline` escapes back to runtime.

**Branching and iteration**: `inline if` / `else if` / `else`, and `inline for`,
which unrolls. There is no `inline while`, `inline loop` or `inline when`.

`inline for` works at statement level, at top level, where each iteration
generates a declaration:

```azora
inline for Ty in [A, B] {
    derive Equal for Ty
}
```

and **inside an `impl` body**, where each iteration generates a member and
`$name` splices the loop variable into it:

```azora
impl Vec3 {
    inline for axis in @arr["x", "y", "z"] {
        prop double$axis[self: Self&]: Double = self.$axis * 2.0
    }
}
```

It iterates a compile-time type list (`[A, B]`), a value list (`@arr[…]`), or
several lists in parallel, and `with index` binds the position.

**Diagnostics**: `inline assert`, `inline trace`, `inline panic`.

**Splicing**: `inline "…"` splices a string as source, including into a
signature fragment. Names splice too: `oper$op` builds an operator name from a
loop variable.

**Macros** come in two kinds, and the `@` always leads on both the declaration
and the call. A prefix macro takes arms:

```azora
macro @arr {
    []            => emptyArray()
    [...$items]   => arrayOf(...$items)
}

@arr[1, 2, 3]        // arrayOf(1, 2, 3)
@vec[]               // an empty List
@vec![1, 2]          // a MutableList
```

> `@map` and `@map!` are declared in `std`, but the `key: value` argument form
> they need is **not implemented** at the call site, so a map literal does not
> compile yet. Build one with `mapOf(…)`. Two of the failing tests track
> this.

An infix macro puts its holes around the name, so the declaration reads like
the call it enables:

```azora
macro $a @to $b => mapEntry($a, $b)

"key" @to 42         // mapEntry("key", 42)
```

Dropping the `=>` registers the name without a rewrite, so `a @op b` calls the
free function `op(a, b)`.

A name may end in one of `! ? & * ^`, and the sigil is part of it: `@vec` and
`@vec!` are two macros. Any word works as a name, including keywords - `@with`,
`@to`, `@in` - because the leading `@` has already said a name follows. A
specific container implementation is reached by naming it, `hashSetOf(…)`
or `treeMapOf(…)`, and a type is always written with generics rather than
invented syntax: `List<T>`, `MutableList<T>`, `Array<T, N>`.

**Compile-time type lists** ship with the standard library: `Numbers`,
`Integers`, `FloatingPoints`, `SignedIntegers`, `UnsignedIntegers`.

**Reflection**: handles, and `inline for … in reflect<*>.withDeco<D>` to
iterate every type carrying a decorator.

Constant folding, constant propagation and dead-code elimination run on the IR.

## Concurrency

- `async func` and `await`; `async { }` and `async func { }` spawn a task
- A discarded handle still runs - the program waits for it before exiting
- `delay <ms>` suspends a task
- `channel()` with `send` / `receive` / `close`
- Streams are **library types**, not language constructs: `Sequence<T>` is the
  synchronous series and `Flow<T>` the asynchronous one, so a producer is an
  ordinary `func` whose return type says which it is

> Threads, `Mutex` and `Atomic` **do not exist yet**. `std/parallelism` names
> them but does not define them. See the roadmap.

## Reactivity

- `react func` and `react async func`
- `remember` / `retain` / `preserve` lifetimes on a `var`/`val`/`let`/`fin` binding
- `effect { }` with automatic dependency tracking, `effect x { }` explicit,
  `effect defer { }` cleanup

## Dependency injection

- `solo pack Name { }` a type there is one of
- `graph Graph { solo|factory|scope Type(args) [binds Spec] }` a dependency graph;
  the first word is the provider's lifetime
- `graph Graph includes [A, B]` composes graphs
- `inject Type` resolves where evaluated; `lazy fin value = inject Type`
  defers the entire initializer to first read

## Decorators

- `annot @Name { fields }` declares an annotation, optionally `binds` it to a spec
- `@Name`, `@Name(args)`, `@target:Name`
- Decorator applications may target fields individually, as a list, or with a wildcard;
  target lists form a cross-product
- Serialization decorators generate value-tree and AZON methods at
  compile time

## Modules and visibility

```azora
module std.math
import std.container.array
exposed import std.traits.core   // re-exported to importers
```

- Public by default. A single leading underscore makes a declaration private
  where that declaration supports privacy.
- User symbols cannot start with `__`, and `_` is not allowed after the first
  character.
- `confined` narrows a declaration or module to its package.
- `exposed` marks a module or import as auto-imported.
- `scope` groups members under a `::` namespace.
- `scope Name { }` groups members under a `::` namespace; the same scope may
  be reopened and the contributions merge.

## FFI

`bridge <target> { func sigs }` declares extern functions for the active
backend. The interpreter resolves common C math intrinsics directly.

---

# Standard library

48 modules under `std/`, one per file, grouped as: `math` `string` `char`
`container` `algorithm` `functional` `memory` `convert` `io` `time` `random`
`reactive` `reflection` `serializer` `concurrency` `parallelism` `allocator`
`traits` `format` `primitive` `result` `error` `config` `core`.

Highlights:

- Containers are a spec plus its implementations, not one concrete type:
  `List` `Map` `Set` are the specs, `ArrayList` `HashMap` `HashSet` the
  defaults, with `LinkedHashMap` / `LinkedHashSet` and `TreeMap` / `TreeSet`
  alongside them. `Deque` `Queue` `Stack` `Array` are packs
- Smart pointers: `Unique` `Shared` `Weak` `SyncShared`
- Capabilities: `Clone` `Copy` `Equal` `Order` `Hash` `Display` `Into` `From`
  `Cast`
- Algorithms: sort, search, min/max, folds
- Serialization: a `SerialValue` tree plus **AZON**, the one built-in text
  format. Any other is written outside `std` by implementing `Serializer<T>`

> The standard library is **not stable**. APIs will change before `0.1` and
> coverage is uneven. `std.io` is minimal, and `std.filesystem`, `std.os`,
> `std.testing` and number formatting do not exist yet.

---

# Backends

| Target | Output | Status |
|---|---|---|
| **Interpreter** | in-memory execution | Complete. Drives tests, the REPL and the playground |
| **LLVM** | `.ll` text, runnable under `lli` | Partial. Placeholders remain for closures, `defer`, compound types and pointers |
| **WebAssembly** | WAT, linear memory and host imports | Partial. `ForEach`, variant literals and `delay` degrade rather than compile |

`azora compile <wasm\|wat\|llvm\|ll\|ir\|ast> <file.az>`

Backend parity is a release gate for `0.1`: a construct a backend cannot
express should be a compile error for that target, not silently different
output.

---

# Tooling

- **CLI**: `azora run` / `check` / `compile` / `test` / `repl` / `version` /
  `help`, with `--debug`, `--release`, `--test`, `--strict` and `--link`.
  `azora test` runs a file's or directory's `test` blocks. A bare `azora
  file.az` is the same as `azora run file.az`. Multi-file projects are
  discovered automatically.
- **`azls`**: language server with error-tolerant highlighting, diagnostics,
  completion, hover and document symbols. Go-to-definition, rename and find
  references are not implemented yet.
- **Debugger**: `DebugInstrumenter` marks statements in debug builds and
  `AzoraDebugSession` drives stepping and breakpoints.
- **Azora Studio** and an IntelliJ plugin host `azls`.

> There is **no package manager** and **no formatter**. Both are `0.1` blockers.

---

# Testing

```bash
./gradlew :compiler:desktopTest
```

**1519 tests** covering the language, the standard library and all three
backends, including execution tests through the interpreter, `lli` and
`wat2wasm`.

**75 currently fail.** They are tracked in `ROADMAPs/VERSION_0_1_ROADMAP.MD`
§10.1 and are a release gate.

---

# Roadmap

`ROADMAPs/VERSION_0_1_ROADMAP.MD` is the complete accounting of what `0.1`
needs, what is done, and what is only partly done. The largest open items:

- Known miscompiles and unsound acceptances
- Generic erasure: monomorphisation or a documented erasure model
- Real threads, `Mutex`, `Atomic`
- A package manager
- Backend parity, and the LLVM and Wasm gaps
- 11 unwritten DIPs

---

# Project structure

```
azora-lang/
├── compiler/     IR-based compiler (commonMain + wasmJs), stdlib injector
├── std/          the standard library, 48 .az modules
├── app/          CLI entry point
├── azls/         language server
├── build-tool/   project configuration (not yet a package manager)
├── DIPs/         design documents, one per language area
├── ROADMAPs/     release planning
└── examples/     sample projects
```

# License

Apache 2.0. See `LICENSE`.
