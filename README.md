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
app/build/install/azora/bin/azora compile kotlin hello.az

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
              CTFE Evaluator (inline/deepinline)
                   ↓
              Symbol Collection → Type Resolution → Alloc/Drop → Effect Check
                   ↓
              IR Generator → IR Optimizer
                   ↓
     ┌───────────┬───────────┬───────────┐
     ↓           ↓           ↓           ↓
  Kotlin     TypeScript    LLVM IR   Interpreter
```

The IR is target-agnostic. Every backend lowers from the same optimized IR. Adding a new target means one new file.

## Implemented Features

### Types
- **Primitives**: `Int`, `UInt`, `Long`, `ULong`, `Byte`, `UByte`, `Short`, `UShort`, `Cent`, `UCent`, `Float`, `Real`, `Decimal`, `Bool`, `Char`, `String`, `Unit`
- **Compound**: arrays `[T]`, tuples `(A, B)`, function types `(A) -> B`, maps `["k": v]`
- **User-defined**: `pack` (structs), `enum`, `slot` (tagged unions), `typealias`, `fail` (error sets)
- **Type parameters**: generics (`func<T>`, `pack<T>`) with call-site inference
- **Nullable**: `T?` with `null`, `??` (coalesce), `?.` (safe access)
- **Failable**: `T!ErrSet` — a value of `T` or an error from a declared set
- **Pointer**: `T*` — a heap reference (`alloc`, `*deref`)
- **Integer/float promotion**: `2 + 1.5` → `3.5` (auto-widens)

### Bindings
- `var` (mutable), `let` (immutable), `fin` (deeply immutable)
- Type inference: `var x = 5` or `var x: Int = 5`
- Named arguments: `Point(y: 4, x: 3)`

### Functions
- Default parameters: `func f(x: Int = 0)`
- Generics with inference: `func<T> identity(x: T): T`
- Named function args: `create(value: 30, label: "A")`
- Inline functions: `inline func square(x) { ... }` — substituted at call sites
- Trailing-lambda syntax: `map(items) { x -> x * 2 }`
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

### Object-Oriented
- **Structs** (`pack`): fields, construction, field access/mutation
- **Methods** (`impl`): methods with implicit `self`, mutation by reference
- **Traits** (`spec`): trait declarations with validated implementations (`impl Trait for Type`)
- **Operator overloading**: `plus`, `minus`, `times`, `div`, `mod`, `equals` → `+`, `-`, `*`, `/`, `%`, `==`, `!=`
- **Index overloading**: `oper[]` / `oper[]=` in `impl` blocks → user types become indexable (`m[i]`, `m[i] = v`)
- **Infix functions**: `a plus b` syntax (any method callable infix); `infx Type.method(...) { }` declares an extension method usable infix
- **Named zones**: `zone Name { … }` is a namespace; members accessed as `Name::member`

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
- `*ptr` dereference, `*ptr = v` store-through
- `drop <expr>` — release (advisory under GC)
- `unsafe { … }` — opt-in block
- `isolated(expr)` — produce an independent deep copy
- `zone alloc { … }` / `friend zone alloc { … }` — scoped allocation arenas; pointers allocated inside are tracked and freed at zone exit

### Concurrency
- **Generators**: `flow name(params): Elem { … yield v }` — a flow is a LAZY producer; its body runs incrementally, suspending at each `yield` until consumed (`for x in flow()`). Infinite flows work; breaking early only runs the body as far as consumed
- **Tasks**: `task { … }` / `await t` — async with **real parallelism** (runs on `Dispatchers.Default`, a multi-threaded pool); each task gets isolated execution state, so concurrent tasks never race
- **Channels**: `channel()` with `.send(v)` / `.receive()` / `.close()` for task-to-task communication
- **Launch**: `launch { … }` — fire-and-forget task (joined before the program exits)

### Decorators
- `deco Name { fields }` declares an annotation type
- `@Name`, `@Name(args)`, `@target:Name` applied to declarations (parsed and stored)

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
- `bridge <target> { func sigs }` — declares extern functions for cross-target interop (C, JVM, JS, …)
- Interpreter resolves common C-math (`sin`, `cos`, `sqrt`, `pow`, …) to `kotlin.math`
- Codegens emit real extern declarations: Kotlin `external fun`, TypeScript `declare function`, LLVM `declare`

### Backends
- **Kotlin/JVM**: full code generation to Kotlin source
- **TypeScript**: full code generation
- **LLVM IR**: text output (partial — placeholders for closures, compound types)
- **Interpreter**: in-memory execution (used by tests, REPL, playground)

### CLI
- `azora run <file.az>` — compile and run
- `azora check <file.az>` — type-check
- `azora compile <kotlin|typescript|llvm|ir> <file.az>` — output generated code
- `azora repl` — interactive REPL
- Multi-file projects: `.az` files in sibling directories auto-discovered and merged

## Testing

```bash
./gradlew :compiler:desktopTest
```

492 tests covering all features. Tests verify runtime correctness through the IR interpreter.

## Missing Features (Roadmap)

### Language
- **Multi-statement lambda codegen** — best-effort in Kotlin/TS

### Systems (large effort)
- **Pointer arithmetic**
- **UI / reactivity**: `view`/`rem`/`effect`
- **Inheritance**: `node`/`leaf`/`base`
- **Variadic generics**: `pack Tuple<T...>`

### Known Limitations
- Generics use type erasure (field types are `Any` at runtime)
- LLVM backend uses placeholders for closures, defer, compound types, pointers, and concurrency
- `use` is parsed as metadata (files merged by CLI, no semantic name resolution)
- Concurrency features (`flow`/`task`/`await`/`channel`/`launch`) execute with real parallelism in the interpreter (Dispatchers.Default); Kotlin/TS/LLVM backends stub them

## Project Structure

```
azora-lang/
├── compiler/          IR-based compiler (commonMain + wasmJs)
├── app/              CLI entry point (azora run/check/compile/repl)
├── build-config/     Version constants
├── build-logic/       Gradle convention plugins
├── Internal/Std/     Standard library source (.az files, not yet compilable)
├── Internal/Testing/ Integration tests (.az files)
└── examples/         Example projects
```

## Websites

- **Book** (`azora-lang-book-website`): 25+ chapter language tutorial
- **Playground** (`azora-lang-code-website`): in-browser code execution via WASM
- **Docs** (`azora-lang-docs-website`): stdlib API reference

## License

Apache 2.0
