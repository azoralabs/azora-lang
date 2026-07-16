# Azora Language

A statically-typed, multi-target programming language with a clean IR-based compiler pipeline.

## Quick Start

```bash
# Build
./gradlew :app:installDist

# Run a program
app/build/install/azora/bin/azora run hello.az

# Type-check
app/build/install/azora/bin/azora check hello.az

# Generate code
app/build/install/azora/bin/azora compile js hello.az

# REPL
app/build/install/azora/bin/azora repl
```

## Hello World

```
func main() {
    println("Hello, Azora!")
}
```

## Architecture

```
Source → Lexer → Parser → AST Validator
                   ↓
              Stdlib Injection (only the modules you `use`)
                   ↓
              Symbol Collection → Type Resolution ⇄ CTFE → Alloc/Drop → Effect Check
                   ↓
              IR Generator → IR Optimizer
                   ↓
  ┌──────┬──────┬──────┬────────────┐
  ↓      ↓      ↓      ↓            ↓
  JS    Wasm   LLVM   Interpreter  (IR dump)
```

The IR is target-agnostic. **Every** compile lowers the optimized IR to the active
codegen targets in one pass — `Compiler.compile()` returns JavaScript, WebAssembly,
and LLVM IR together.
Adding a new target means one new file under `backend/`.

## Implemented Features

### Types
- **Primitives**: `Int`, `UInt`, `Long`, `ULong`, `Byte`, `UByte`, `Short`, `UShort`, `Cent`, `UCent`, `Float`, `Real`, `Decimal`, `Bool`, `Char`, `String`, `Unit`
- **Compound**: fixed arrays `[T]`, immutable collections `List<T>`/`Set<T>`/`Map<K, V>`, mutable collections `mut List<T>`/`mut Set<T>`/`mut Map<K, V>`, tuples `(A, B)`, function types `(A) -> B`, map values `mapOf("k": v)`
- **User-defined**: `pack` (structs), `enum`, `slot` (tagged unions), `typealias`, `fail` (error sets)
- **Type parameters**: generics (`func<T>`, `pack<T>`) with call-site inference
- **Variadic generics**: `func<T...> name(first: Int, rest: T...)` — the last type param can be variadic; `rest: T...` collects remaining call args into an array
- **Spread operator**: `f(arr...)` — splat an array's elements as individual call arguments
- **Nullable**: `T?` with `null`, `??` (coalesce), `?.` (safe access)
- **Failable**: `T!ErrSet` — a value of `T` or an error from a declared set
- **Pointer**: `T*` — a heap reference (`alloc`, `deref ptr` / `*ptr`)
- **Integer/float promotion**: `2 + 1.5` → `3.5` (auto-widens)

### Bindings
- `var` (mutable), `let` (immutable), `fin` (deeply immutable)
- Type inference: `var x = 5` or `var x: Int = 5`
- Named arguments: `Point(y: 4, x: 3)`

### Functions
- Default parameters: `func f(x: Int = 0)`
- Parameter modifiers: `mut name: T` (mutable parameter), `ref name: T` (by-reference — mutations propagate to caller), `out name: T` (output — callee assigns, caller receives)
- Generics with inference: `func<T> identity(x: T): T`
- Named function args: `create(value: 30, label: "A")`
- Inline functions: `inline func square(x) { ... }` — substituted at call sites
- Trailing-lambda syntax: `mapOf(items) { x -> x * 2 }`
- Implicit `it` in single-param lambdas: `{ it + 1 }` (type inferred from context)

### Control Flow
- `if` / `else if` / `else`
- `while`, `for x in a..b`, `for x in array`, `loop`
- `for x by N in a..b` (step), `reverse for x in a..b` (descending)
- `loop { } while cond` (do-while), `for/while/loop … else { }` (else runs unless `break`)
- Labeled loops: `@lbl for …`, `break @lbl` / `continue @lbl`
- `break`, `continue`
- `when expr { patterns -> { body } else -> { body } }` — pattern matching on enums, slots (with destructuring), and literals
- Exhaustiveness checking on slot and enum types

### Inheritance
- `node Name(params) { fields; methods }` — an inheritable type (ctor params are stored as fields)
- `leaf Name(params) : Parent(args) { repl func overrides }` — a final subclass with single inheritance
- `repl func` — marks a method that overrides the parent's method
- `virt func` — marks a method as virtual (dynamic dispatch; default in `node`)
- `base.method(args)` — calls the parent node's implementation (like `super` in other languages)
- Dynamic dispatch: a parent-typed variable calls the runtime type's method (virtual dispatch)
- Inherited methods that call `self.method()` dispatch dynamically to the child's override
- `isCompatible` walks the parent chain for implicit upcasts
- `base` is a reserved keyword (cannot be used as a variable name)

### Object Model
- `hook name { body }` — lifecycle callbacks (run after main, in declaration order)
- `prop name: T { body }` — computed properties (inside `impl`, `node`, `solo` bodies; accessed as `obj.name`)
- `ctor(params) { body }` — secondary constructors (inside `node`/`solo` bodies)
- `dtor { body }` — destructors (inside `node` bodies; called by `drop`)
- `flip { body } flop { body }` — alternating execution: runs the flip body on the first encounter, flop on the next, flip again, etc. (typically used inside loops)

### Object-Oriented
- **Structs** (`pack`): fields, construction, field access/mutation; empty packs can omit the body (`pack Marker`)
- **Methods** (`impl pack Type`): methods with implicit `self`, mutation by reference in the pack's declaring file
- **Extensions** (`func Type.method(...) { ref self -> ... }`): external methods; `shield pack` forces extension receivers to be read-only
- **Traits** (`spec`): trait declarations with validated implementations (`impl Trait for Type`)
- **Conversion specs**: compact callback specs such as `spec Into<T>: T { ref self } use as "to${T.typeName}"`; `use as` is a literal member-name template, and `impl Into<String> for Type { ref self -> ... }` adds `.toString`, while `impl as String` is cast-only (`value as String`)
- **Variadic tuples**: `pack Tuple<T...> where (...T).length >= 2 { inline for Ty in ...T with index { mixin "$index: $Ty" } }`; `tupleOf(elements: ...T): Tuple<...T>` preserves each element's static type
- **Operator overloading**: `plus`, `minus`, `times`, `div`, `mod`, `equals` → `+`, `-`, `*`, `/`, `%`, `==`, `!=`
- **Index overloading**: standalone `impl oper[] for Type { ref self, index -> ... }` and `impl oper[]= for Type { mut ref self, index, value -> ... }` make user types indexable (`m[i]`, `m[i] = v`)
- **Infix functions**: `a plus b` syntax (any method callable infix); `infx Type.method(...) { }` declares an extension method usable infix
- **Named zones**: `zone Name { … }` is a namespace; members accessed as `Name::member`; shared namespace contributions can use `friend zone std::math { … }`

### Error Handling
- `throw value` — raises any value
- `try { } catch { name -> body }` — catches with optional binding
- `expr catch fallback` — catch expression
- `guard condition else { body }` — early exit
- **Error sets**: `fail ErrSet { V1, V2 }` declares a set of error variants
- **Failable types**: `T!ErrSet` — a function returning `T` or an error from `ErrSet`; `fail ErrSet.V` raises one (enforced: a `T!E` function's failures must belong to `E`)
- `fail defer { body }` — defer that runs only when the function exits via an error
- `rescue { body }` — catch-and-suppress: runs on error and swallows it (the function continues normally)

### Memory Model
- `alloc <expr>` — heap-allocate a value, returning a `T*` pointer
- `alloc [10, 20, 30]` — allocate a buffer and return a pointer to the first element (enables pointer arithmetic)
- `*ptr` dereference, `*ptr = v` store-through
- `drop <expr>` — release (advisory under GC)
- `unsafe { … }` — opt-in block
- `isolated(expr)` — produce an independent deep copy
- `zone alloc { … }` / `friend zone alloc { … }` — scoped allocation arenas; pointers allocated inside are tracked and freed at zone exit
- Pointer arithmetic: `ptr + n`, `ptr - n` (offset), `ptr1 - ptr2` (distance), `ptr1 == ptr2` (equality)

### Concurrency
- **Generators**: `flow name(params): Elem { … yield v }` — a flow is a LAZY producer; its body runs incrementally, suspending at each `yield` until consumed (`for x in flow()`). Infinite flows work; breaking early only runs the body as far as consumed
- **Tasks**: `task { … }` / `await t` — async with **real parallelism** (runs on `Dispatchers.Default`, a multi-threaded pool); each task gets isolated execution state, so concurrent tasks never race
- **Channels**: `channel()` with `.send(v)` / `.receive()` / `.close()` for task-to-task communication
- **Launch**: `launch { … }` — fire-and-forget task (joined before the program exits)

### Decorators
- `deco Name { fields }` declares an annotation type
- `@Name`, `@Name(args)`, `@target:Name` applied to declarations (parsed and stored)
- Accessor names such as `get` and `set` are normal identifiers; property-style callbacks are declared with compact `spec` syntax and `use as`.

### Functional
- Lambdas with closures: `{ x: Int -> body }`
- Higher-order functions: `func apply(f: (Int) -> Int, x: Int): Int`
- Closures capture enclosing scope

### Data
- **Enums**: `enum Color { Red; Green; Blue }` — variants as named values
- **Slots (tagged unions)**: `slot Option { Some(Int); None }` — variants with payloads + destructuring in `when`
- **Tuples**: `(1, "hello")` with positional access `.0`, `.1`

### Operators
- Arithmetic: `+ - * / %`
- Comparison: `== != < <= > >=`
- Logical: `&& || !`
- Bitwise: `& | ^ ~ << >>`
- Assignment: `= += -= *= /= %=`, `++`, `--`
- Null-conditional: `??`, `?.`, and null-conditional compound assignment `?= ?+= ?-= ?*= ?/= ?%=` / `?++ ?--`
- Casts: `expr as Type`, `expr is Type`, negated `expr is! Type`
- Compound assignment on fields/indices
- Raw strings: `"""…"""` (literal, multi-line)

### Strings & Arrays
- String interpolation: `"hello $name"`, `"result: ${expr}"`
- Raw strings: `"""…"""` (literal, multi-line, no escapes)
- String methods: `toUpperCase()`, `toLowerCase()`, `contains()`, `startsWith()`, `endsWith()`, `trim()`, `replace()`, `split()`, `indexOf()`
- Array methods: `add()`, `insert()`, `remove()`, `contains()`, `indexOf()`, `.length`, `.isEmpty`, `.isNotEmpty`
- Map literals `["k": v]`, access `m[key]`, mutation `m[key] = v`
- For-in array iteration: `for item in items { }`

### Metaprogramming (CTCE)
- `inline fin`, `inline let`, `inline var` — compile-time bindings
- `inline if condition { }` — conditional compilation
- `inline for x in a..b { … }` — compile-time loop unrolling
- `inline { }` / `deepinline { }` — compile-time blocks
- `noinline` — escape hatch back to runtime
- `inline func` — body substitution at call sites
- `inline assert` / `inline trace` — compile-time assertions and traces
- Constant folding, constant propagation, dead-code elimination

### Resource Management
- `defer { body }` — runs at function exit (LIFO, even through return/throw)

### Dependency Injection
- `solo Name { fields; methods }` — declares a singleton struct with one shared instance (lazily created)
- `wrap Name { solo Type(args); … }` — a DI container that wires singletons with construction args
- `inject Type` — resolves the singleton instance (same object every time; thread-safe under parallelism)
- Methods and fields accessible via chaining: `inject Config.get()`

### FFI (Foreign Function Interface)
- `bridge <target> { func sigs }` — declares extern functions for active backend interop
- Interpreter resolves common C-math (`sin`, `cos`, `sqrt`, `pow`, …) to `kotlin.math`
- Codegens emit backend extern surfaces: JavaScript host comments/import expectations and LLVM `declare`

### Reactivity
- `mem x: T = init` — remembered reactive declaration
- `rem x: T = init` — saveable/serializable remembered reactive declaration
- `ret x: T = init` — retained reactive declaration
- `effect { body }` — reactive side-effect block (runs once immediately; matches the old interpreter's semantics)
- `view Name(params) { body }` — a reactive UI component declaration (lowered like a function; callable from code)

### Backends (targets)
Every compile produces the active codegen outputs from the same optimized IR:

| Target | Output | Status |
|--------|--------|--------|
| **JavaScript** | JavaScript source | Full |
| **WebAssembly** | WAT (folded S-exprs, linear memory + host imports) | Full |
| **LLVM IR** | `.ll` text (`lli`/`clang`/`llc` ready) | Partial — placeholders for closures, defer, compound types, pointers |
| **Interpreter** | In-memory execution | Full — used by tests, REPL, and the playground |

Use `azora compile <target> <file.az>` to emit any of them. Target IDs:
`js` `wasm` `llvm` `ir` `ast`.

### Standard Library
A growing standard library lives in `Internal/Std/` (35 modules: `math`, `string`,
`container`, `algorithm`, `concurrency`, `parallelism`, `result`, `traits`, `io`,
`os`, `random`, `gfx`, `functional`, `allocator`, `ui`, …). It is **import-gated**:
a file sees a module's names only after importing it, and only the items actually
referenced are injected (transitively) — so a program that never touches the stdlib
compiles exactly as before. User declarations always shadow stdlib items.

```
import std.math              // unqualified: abs(x), plus math::abs(x)
import std.{math, string}    // grouped
import std.*                 // wildcard
import std.math.abs          // selective
std::math::abs(x)         // fully qualified — no import needed
```

Decorator implementations may target pack fields individually, as a list, or
with a pack-field wildcard. Decorator and target lists form a cross-product:

```azora
impl SerialName(value: "login") for User::name
impl [SerialName, SerialRequired] for [User::name, User::password]
impl SerialIgnore for User::*
```

Serialization decorators generate typed value-tree, JSON, and AZON methods.
Field names, ignored/required fields, unknown-field handling, and default
encoding are resolved at compile time and lowered through the shared IR for all
backends.

### Tooling
- **`azls`** — language server (`azls.jar`): error-tolerant syntax highlighting,
  full diagnostics, completion (keywords/builtins/user symbols/in-scope locals),
  hover signatures, and document symbols. Loaded reflectively (JSON in/out) by
  Azora Studio, with an optional `prelude` for cross-file intelligence.
- **Debugger** — `DebugInstrumenter` tags statements with `__dbg(line)` markers
  (debug builds only); `AzoraDebugSession` drives step/breakpoint execution.
- **Azora Studio** — the IDE (separate `azora-studio` repo) hosting `azls`.

### CLI
- `azora run <file.az>` — compile and run
- `azora check <file.az>` — type-check
- `azora compile <js|wasm|llvm|ir|ast> <file.az>` — output generated code
- `azora repl` — interactive REPL
- Multi-file projects: `.az` files in sibling directories auto-discovered and merged

## Testing

```bash
./gradlew :compiler:desktopTest
```

534 tests covering all features. Tests verify runtime correctness through the IR interpreter.

## Missing Features (Roadmap)

### Language
- **Multi-statement lambda codegen** — best-effort in JavaScript/WASM/LLVM

### Systems (large effort)
- **Full reactivity** — `mem`/`rem`/`ret`/`effect` currently run once; automatic dependency tracking and re-runs are future work
- **UI rendering** — `view` is parsed and callable; no DOM/Compose rendering backend yet

### Known Limitations
- Generics use type erasure (field types are `Any` at runtime)
- LLVM backend uses placeholders for closures, defer, and compound types
- Concurrency features (`flow`/`task`/`await`/`channel`/`launch`) execute with real parallelism in the interpreter (`Dispatchers.Default`) and lower through the active codegen targets where supported

## Project Structure

```
azora-lang/
├── compiler/          IR-based compiler (commonMain + wasmJs) + stdlib injector
├── app/               CLI entry point (azora run/check/compile/repl)
├── azls/              Language server + debug session (azls.jar)
├── build-config/      Version constants
├── build-logic/       Gradle convention plugins
├── Internal/Std/      Standard library source (.az files → compiled into AzStdlib)
├── Internal/Testing/  Integration tests (.az files)
└── examples/          Example projects (demo-website, demo-multiplatform)
```

## Websites

- **Book** (`azora-lang-book-website`): 25+ chapter language tutorial
- **Playground** (`azora-lang-code-website`): in-browser code execution via WASM
- **Docs** (`azora-lang-docs-website`): stdlib API reference

## License

Apache 2.0
