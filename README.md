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
- **Compound**: arrays `[T]`, tuples `(A, B)`, function types `(A) -> B`, maps `["k": v]`, sets `![1, 2, 3]`
- **User-defined**: `pack` (structs), `enum`, `slot` (tagged unions), `typealias`
- **Type parameters**: generics (`func<T>`, `pack<T>`) with call-site inference
- **Nullable**: `T?` with `null`, `??` (coalesce), `?.` (safe access)
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
- Implicit `it` in single-param lambdas: `{ it + 1 }`

### Control Flow
- `if` / `else if` / `else`
- `while`, `for x in a..b`, `for x in array`, `loop`
- `break`, `continue`
- `when expr { patterns -> { body } else -> { body } }` — pattern matching on enums, slots (with destructuring), and literals
- Exhaustiveness checking on slot types

### Object-Oriented
- **Structs** (`pack`): fields, construction, field access/mutation
- **Methods** (`impl`): methods with implicit `self`, mutation by reference
- **Traits** (`spec`): trait declarations with validated implementations (`impl Trait for Type`)
- **Operator overloading**: `plus`, `minus`, `times`, `div`, `mod`, `equals` → `+`, `-`, `*`, `/`, `%`, `==`, `!=`
- **Infix functions**: `a plus b` syntax (any method callable infix)

### Error Handling
- `throw value` — raises any value
- `try { } catch { name -> body }` — catches with optional binding
- `expr catch fallback` — catch expression
- `guard condition else { body }` — early exit

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
- Null-safe: `?? ?.`
- Casts: `expr as Type`, `expr is Type`
- Compound assignment on fields/indices

### Strings & Arrays
- String interpolation: `"hello $name"`, `"result: ${expr}"`
- String methods: `toUpperCase()`, `toLowerCase()`, `contains()`, `startsWith()`, `endsWith()`, `trim()`, `replace()`, `split()`, `indexOf()`
- Array methods: `add()`, `insert()`, `remove()`, `contains()`, `indexOf()`, `.length`, `.isEmpty`, `.isNotEmpty`
- For-in array iteration: `for item in items { }`

### Metaprogramming (CTCE)
- `inline fin`, `inline let`, `inline var` — compile-time bindings
- `inline if condition { }` — conditional compilation
- `inline { }` / `deepinline { }` — compile-time blocks
- `noinline` — escape hatch back to runtime
- `inline func` — body substitution at call sites
- Constant folding, constant propagation, dead-code elimination

### Resource Management
- `defer { body }` — runs at function exit (LIFO, even through return/throw)

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

329 tests covering all features. Tests verify runtime correctness through the IR interpreter.

## Missing Features (Roadmap)

### Language
- **`fail`-set error unions** (`T!E`) — typed error handling with `?` propagation
- **Exhaustiveness checking for enums** (currently only slots)
- **`it` type inference** — implicit `it` is `Any`; should infer from context
- **Multi-statement lambda codegen** — best-effort in Kotlin/TS

### Systems (large effort)
- **Pointers / memory**: `alloc`, `*deref`, `drop`, `region`, `unsafe`
- **Concurrency**: `task`/`suspend`, `async`/`await`, `flow`/`yield`, `channel`
- **Decorators**: `deco`, `@Name(args)`
- **FFI**: `bridge .C { func sin(x): Real }`
- **Dependency injection**: `solo`/`wrap`/`inject`
- **UI / reactivity**: `view`/`rem`/`effect`
- **Inheritance**: `node`/`leaf`/`base`
- **Variadic generics**: `pack Tuple<T...>`

### Known Limitations
- Generics use type erasure (field types are `Any` at runtime)
- LLVM backend uses placeholders for closures, defer, compound types
- `use` is parsed as metadata (files merged by CLI, no semantic name resolution)
- No map/set runtime methods (literals parse, lowered to arrays)

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
