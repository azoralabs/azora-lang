# Azora 0.1 - Road to Production

> The complete list of what has to be true before `0.1` ships, and what is
> already true today.
>
> | | |
> |---|---|
> | ✅ | done - implemented **and** covered by a test |
> | 🟨 | partial - implemented, but not production ready |
> | ⬜ | not started |
>
> A design document is not a ✅. Anything implemented but untested, or
> implemented but not ready to depend on, is 🟨.

---

## 0. What "production ready" means

`0.1` is the first release someone outside the project can build something real
on and expect it to keep working. Four promises, and every section serves one:

1. **It compiles what it accepts.** No construct type-checks and then produces
   wrong output; no backend disagrees with another about what a program means.
2. **It fails clearly.** Every rejection names the problem and the fix.
3. **It does not move under you.** A `0.1` program still compiles on `0.1.x`.
4. **It can be worked in.** Editor, debugger, package manager, docs.

### Exit gates

Hard blockers. `0.1` does not ship with any of these open.

- ⬜ **Zero failing tests** - 75 fail today (§9.1)
- ⬜ **No known miscompiles** - §1.1 is empty
- ⬜ **Backend parity** - every construct behaves identically on every backend,
  or is a compile error on the ones that cannot express it (§6)
- ✅ **Every shipped feature has a written DIP**
- ⬜ **A published stability policy** (§11)
- ⬜ **A package manager**, with `new` → `build` → `test` working (§7.4)
- ⬜ **Self-hosting decided** - executed or explicitly deferred (§12.1)

### Where the project stands

|                  |                                     |
|------------------|-------------------------------------|
| Tests            | 1519 total, **75 failing**          |
| Standard library | 48 `.az` modules                    |
| DIPs             | 34, all written                     |
| Backends         | interpreter ✅, LLVM 🟨, Wasm 🟨    |

---

## 1. Correctness

### 1.1 Known miscompiles and unsound acceptances

Each lets a program be silently wrong. This list must be empty to ship.

- ⬜ **Derivation is not checked against field capabilities.**
  `pack Sample { var value: Double }` with `impl [Order] for Sample` is
  *accepted*, claiming a total order over a field that has only a partial one.
  `sort` then takes it and NaN sorts as if ordered.
- ⬜ **`hash` disagrees between backends.** LLVM hashes anything behind a
  pointer - including `String` - by address; the interpreter hashes by content.
  A `String`-keyed `HashMap` behaves differently per target.
- ⬜ **`Out` conflict rule unimplemented.** Two impls of one operator family
  differing only in `Out` both match; one silently wins.
- ⬜ **`<T: Spec>` in a type-parameter list is parsed and discarded.** A bound
  written there is a lie.
- ⬜ **A spec member's declared return type is not adopted by its impl.**
  `spec Order` declares `oper<=> …: Compare`, but an implementation that omits
  `: Compare` compiles and then fails at run time with
  `no method 'isLess' on String` - the inferred type stays the enum's runtime
  representation and the `<` rewrite calls `isLess` on it. Writing the return
  type is the workaround; adopting the spec's is the fix.
- ⬜ Audit for the same shape elsewhere - anything parsed and dropped rather
  than enforced.

### 1.2 Type system

- ✅ Type inference for bindings, call-site generic inference
- ✅ Integer/float promotion (`2 + 1.5` → `3.5`)
- ✅ Nullable types `T?`, `??`, `?.`, null-conditional compound assignment
- ✅ Exhaustiveness checking on enums and variants in `when`
- ✅ Variadic generics and the spread operator
- ✅ Const generics, including enum-valued and defaulted parameters
- ✅ Type-parameter defaults (`<Rhs = Self>`)
- 🟨 `where` constraints - evaluated, but four cases are unimplemented:
  element-wise conformance for variadic packs, membership in array/set
  literals, unary operators in constraint position, calls in constraints
- ⬜ **Generic erasure is the biggest open question.** Field types are `Any` at
  runtime and operators dispatch on the runtime type as a patch. Decide:
  monomorphisation, reified generics, or documented erasure with dictionaries
- ⬜ Variance rules stated, checked, documented
- ⬜ Integer overflow semantics defined and identical across backends
- ⬜ No path where `T?` is silently treated as `T`

### 1.3 Memory and ownership

The ownership model is the language's main claim; its DIP is the largest (1494
lines).

- ✅ `alloc` / `ptr.*` / `ptr.^` / store-through / `purge`
- ✅ Pointer arithmetic - offset, distance, equality
- ✅ `unsafe { }` blocks, `expr.clone()` deep copy. The `isolated(expr)`
  keyword is removed: it was `.clone()` without the `Clone` contract. `Clone`
  is granted by declaration - auto-derived field-wise for a `pack`, written out
  for the containers - and `impl Clone for Array` now covers the aggregate
  builtins, which had no way to be copied once `isolated` went
- ✅ `defer` (LIFO, through return and throw), `error defer`
- ✅ `take` / `lend` / return parameters
- ✅ Borrow forms `&` / `!` including at call sites
- 🟨 Borrow checking - needs verification for closures, generators, `defer`,
  contracts, `async` and pattern bindings
- ⬜ Use-after-move: test matrix per binding kind (`var`/`val`/`fin`/`let`)
- ⬜ Destructor ordering specified and identical on all backends
- ⬜ Custom allocators implemented
- ⬜ `unsafe` boundaries documented and enforced
- ⬜ Leak checking in the interpreter for test runs

---

## 2. Language - core

### 2.1 Types and declarations

- ✅ Primitives: `Int` `UInt` `Long` `ULong` `Byte` `UByte` `Short` `UShort`
  `Cent` `UCent` `Float` `Double` `Decimal` `Bool` `Char` `String` `Unit`
- ✅ `pack` (structs), empty packs without a body
- ✅ `enum`, `variant enum`, `variant error`, `error` sets
- ✅ `unsafe union` - C-style overlapping storage
- ✅ `typealias`
- ✅ Tuples with positional access, variadic tuples
- ✅ Fixed arrays, `List` / `Set` / `Map` and their mutable forms
- ✅ Function types, nullable, failable `T ?! E`, pointer `T*`, `T^`

### 2.2 Bindings, functions, control flow

- ✅ `var` / `let` / `val` / `fin` with their mutability axes
- ✅ Named arguments, default parameters
- ✅ Borrow sigils on parameters and receivers: `&` shared, `!` exclusive
- ✅ `inline func`, trailing lambdas, implicit `it`
- ✅ `if` / `while` / `for` / `loop`, do-while (`loop { } while cond`),
  loop-else, and `loop <iterable> { }` over `reset`/`hasNext`
- ✅ `for x in a..b by N`, `reverse for`
- ✅ Labeled loops with `break @lbl` / `continue @lbl`
- ✅ `when` with destructuring

### 2.3 Types with behaviour

- ✅ `impl` methods with a bracketed receiver
- ✅ Extensions declared beside a type they merely use
- ✅ `spec` (traits) with validated impls
- ✅ Computed properties (`prop`), secondary constructors (`ctor`), `dtor`
- ✅ Static members via `impl Type:: { }` and `impl Spec for Type:: { }`
- 🟨 Dynamic dispatch on spec-typed values (fat pointer + type-id switch); no
  keyword, using a spec as a type is what selects it. Bindings, parameters,
  returns and fields work; see `DYNAMIC_DISPATCH_DIP.MD`
- ⬜ **A collection of spec-typed values cannot be built** - `@arr[Square(2),
  Circle(1)]` is rejected because the elements are not upcast to the annotated
  element type before the shared-type check. Every "a list of things sharing a
  capability" program is unwritable, which is most of what dispatch is for
- ⬜ **WebAssembly has no spec dispatch at all** - zero occurrences in
  `WasmCodegen`; a spec-typed program compiles and does something else (§6.1)
- ⬜ The dispatcher's default branch returns a default value: a plausible wrong
  answer where it should trap
- ⬜ Boxes are never freed - every upcast leaks
- ⬜ No object-safety rule: nothing rejects using a spec with a static method,
  or one returning `Self`, as a type
- ✅ Infix calls, declared as macros (`macro $a @to $b`)
- ✅ Named scopes and `::` namespacing
- ✅ Modules, imports, scopes, import gating
- ⬜ **`use scopeName` at a use site**, the equivalent of C++'s
  `using namespace std`: bring a scope's members into scope unqualified for the
  rest of the file or block, so `println(x)` works where `println(x)` is
  written today. is written today.
  - ⬜ Scoping: file-level, block-level, or both
  - ⬜ Ambiguity rules when two opened scopes export the same name; a
    collision should be an error at the use, naming both candidates, not a
    silent winner
  - ⬜ Precedence against local declarations, imports and the prelude
  - ⬜ Interaction with import gating: opening a scope must not import a module
    that was never asked for

### 2.4 Operators

- ✅ Arithmetic, comparison, logical, bitwise, assignment, `++`/`--`
- ✅ `<=>`, with `<` `<=` `>` `>=` rewritten from it and `!=` from `==`
- ✅ `Compare` / `PartialCompare` and their members
- ✅ `PartialEqual` / `Equal` / `PartialOrder` / `Order` / `Hash`
- ✅ Derivation from a bodyless `impl [Equal, Order, Hash]`
- ✅ `==` without a conformance is a compile error
- ✅ Operator families: `Arithmetic` `Neg` `Bitwise` `Logical` `Indexable`
  `Deref` / `DerefMut`, each implementable in part
- ✅ A spec declaring only operators registers its conformance, so another spec
  can `require` it. The guard for `impl oper== by Map for HashMap` - where the
  name is an operand type, not a spec - was skipping every operator impl,
  including real conformances. An operator impl for a spec the type already
  conforms to now refines that conformance rather than duplicating it
- ✅ `Deref` / `DerefMut` split: `.*` reads through a value, `.^` writes through
  it, and `DerefMut requires Deref`. The compiler already named the operators
  separately; only the std declaration lumped them together
- ✅ Unary dispatch, including `oper!`
- ✅ In-place compound assignment (`oper+=` and family)
- ✅ `oper` members on a `spec`; the impl reads its return type from the spec
- ✅ `where T: Spec<Args>` and `where K: [A, B]` bounds
- ✅ Index and slice overloading
- ⬜ Member-selecting bounds - `where T: Arithmetic[+, /]`
- ⬜ `Iterator` spec - **blocked on `Range<T>`** (§2.7)
- ⬜ Retire `oper as<U>`
- ⬜ Close the free-operator form: every operator inside its spec's `impl`
- ⬜ Delete the legacy named-method operator path (`plus`, `minus`, `times`,
  `div`, `mod`, `equals`) - a second, undocumented way to overload arithmetic

### 2.5 Conversion and display

- ✅ `as` / `as?` / `as*` with `Cast` / `CheckedCast` / `BitCast`, target on
  the impl
- ✅ `Into` (generic `prop` with a `use as` alias) and `From` (static)
- ✅ `Display` + `Formatter`, with `"${v}"` routed through it
- ✅ String interpolation, raw strings
- ⬜ `FullCast` and spec-list `typealias`
- ⬜ Number and date formatting
- ⬜ **`Display` allocates a `Formatter` per interpolated value** - `"${a} ${b}"`
  builds two buffers; the top-level site should share one

### 2.6 Metaprogramming

Compile-time execution runs to a fixed point with type resolution, before IR
generation. Every form the parser accepts:

- ✅ Bindings: `inline fin`, `inline let`, `inline var`, `inline val`, and
  `inline <name> = expr` (re-assignment of a compile-time binding)
- ✅ Blocks: `inline { }`, `inline scope { }`, `inline scope { }` (top level)
- ✅ `inline if` / `else if` / `else`, at statement and top level
- ✅ `inline for` - unrolls at statement level, at top level, and **inside an
  `impl` body**, where each iteration generates a member
- ✅ `inline for` over a compile-time type list, a bracket list, or several
  lists in parallel; `with index` binds the position
- ✅ `inline func` - body substituted at the call site
- ✅ `inline assert`, `inline trace`, `inline panic`
- ✅ `inline "…"` string splice, including into a signature fragment
- ✅ Inline field forms in a `pack` body (conditional and repeated fields)
- ✅ `deepinline { }`, `deepinline scope { }` (top level) / `deepinline scope { }`
  (statement), `deepinline if`, `deepinline prop`, and `= deepinline { }` as a
  function body. That is the complete list: the two dispatch tables in
  `Parser.parseTopLevelDeepInline` and `parseDeepInlineStmt` accept `{`,
  `scope`/`scope` and `if`, and reject everything else
- ✅ `noinline` - escape back to runtime
- ✅ Constant folding, constant propagation, dead-code elimination
- ✅ Prefix macros: `macro @name { }` arms, invoked `@arr[1, 2, 3]`, `@vec[]`
- ⬜ `@map["k": v]` - `MacroPattern.MapEntryCapture` parses and stores its
  capture names, but the invocation side was staged and never built: there is
  no `Expr.MapEntryArg`, so `key: value` arguments do not parse. `@map` and
  `@map!` are declared in `std` and unusable; two failing tests track it
- ✅ Infix macros: `macro $a @to $b => …`, invoked `a @to b`; bodyless
  `macro $a @op $b` registers the name and calls the free function `op(a, b)`
- ✅ Macro name sigils `! ? & * ^` are part of the name, so `@vec` and `@vec!`
  are two macros; any word may be a name, including keywords (`@with`, `@to`)
- ✅ Name splicing (`oper$op`), compile-time type lists (`Numbers`, `Integers`,
  `FloatingPoints`, `SignedIntegers`, …)
- ✅ Reflection handles and `inline for … in reflect<*>.withAnnot<D>`, which
  unrolls to direct calls - no function values, no registry, nothing at runtime
- ✅ `REFLECTION_DIP.MD` written
- ⬜ **`reflect<T>.fields` and `ReflectedField` are declared and unreachable.**
  `inline for f in reflect<P>.fields` is rejected, and nothing can produce a
  `ReflectedField`. Implement iteration or delete the declarations
- ⬜ `withAnnot` unrolls into statements, not declarations: at top level it fails
  with `Unknown compile-time list`, so a library cannot generate a function per
  decorated type
- ⬜ `withAnnot<D>` is the **only** enumeration - no "every pack", no "every
  implementer of a spec", no filter by scope. Discovery requires a decorator
- ⬜ No method or signature reflection: a declaration's members, parameter types
  and return type are not askable
- ⬜ `reflect<T>(includeInherited: Bool = false)` takes a parameter nothing
  reads, left from the removed inheritance model
- ⬜ Enumeration order is declaration order, which the tests rely on and nothing
  states as a guarantee
- ⬜ **`deepinline func`, `deepinline for`, `deepinline loop`,
  `deepinline while` and `deepinline when` do not exist.** `inline` and
  `deepinline` are not the same set: `inline` also takes `func`, `for`,
  `assert`, `trace` and the binding forms. Decide whether `deepinline` should
  mirror `inline` wherever the deep variant means something - `deepinline for`
  and `deepinline func` are the two with an obvious reading - or whether the
  narrow set is the intended design and the asymmetry should be documented
- ⬜ **`inline while`, `inline loop` and `inline when` do not exist.** Decide
  whether compile-time iteration stays `for`-only and compile-time branching
  stays `if`-only, or whether the other three forms are added
- ⬜ Macro hygiene rules written down and tested
- ⬜ Recursion and termination limits for compile-time evaluation specified

### 2.7 Language decisions still open

- ⬜ **`Range<T>` as a real type** - unblocks `Iterator`, user-defined ranges,
  generalised slicing
- ⬜ **Associated types** - `Out` is a type parameter with a conflict rule today
- ⬜ **Error model unification** - `?!` and `Result` are two answers to one
  question; pick one and say why
- ⬜ **Fallible conversion** (`TryInto`) - needs the error model settled
- ⬜ Operator precedence table published and frozen
- ⬜ Reserved-word list frozen
- ⬜ Trailing-closure / named-argument interaction rules

---

## 3. Language - systems features

### 3.1 Error handling

- ✅ `throw`, `try`/`catch`, `expr catch fallback`, `rescue`
- ✅ Error sets, failable types `T ?! E`, enforced membership
- ✅ `error defer`
- ✅ `return .Variant` shorthand
- ⬜ Stack traces on error paths
- ⬜ Error messages from the runtime as good as the compiler's

### 3.2 Contracts

- ✅ `in { }` / `out { }` / `scope { }` clauses with `assert`. At most one `in`
  and one `out` per function; several conditions go inside the one clause
- ✅ `it` binding in `out`
- ✅ `CONTRACTS_ASSERTION_DIP.MD` written
- ⬜ Contract behaviour in release builds **specified**. They are checked today -
  `IrOptimizer` never eliminates an assert, and `--release` does not drop one -
  but that has never been stated as a decision rather than an optimiser detail
- ⬜ An `inline assert` whose condition does not fold silently becomes a runtime
  check; decide whether that should be an error
- ⬜ Contract inheritance: a spec cannot state a contract its implementers honour
- ⬜ Wasm discards a failed assertion's message (`unreachable`)

### 3.3 Concurrency

- 🟨 Streams as library types, so a producer is an ordinary `func`. But
  `sequence` runs its producer immediately into an `ArrayList`, and `Flow<T>` is
  that same eager list - not asynchronous, no suspension points. The names
  promise laziness and asynchrony, and the implementation is a list builder
- ✅ `async { }` / `await`, real parallelism on a thread pool
- ⬜ **`launch { }` does not parse** - `undefined variable 'launch'`. The
  `__launch` intrinsic behind it is implemented in the interpreter and LLVM but
  nothing produces it. Give it syntax or delete it; a discarded `async { }`
  handle already covers the intent
- ✅ `channel()` with send/receive/close (a compiler builtin)
- ✅ `delay`
- ⬜ **`Mutex` and `Atomic` do not exist**, so `std.parallelism.sync` and
  `std.parallelism.channel` **do not compile when used**: `SpinLock`,
  `Guard`, `CountDownLatch` and `Channel` all fail with
  `undefined function 'Mutex'` / `'Atomic'`. Importing the module is fine, which
  is what hides it
- ⬜ `Send` / `Sync` capabilities
- ⬜ Real OS threads, `JoinHandle`, scoped threads
- ⬜ `Channel<T>` moved out of the compiler into std
- ⬜ `std/parallelism/thread.az` is three functions and none is about threads:
  `sleep` is `delay` renamed, `spawnAsync` spawns then sleeps a caller-supplied
  duration, and `joinAll(count)` **joins nothing** - it sleeps for `count` ms.
  Delete the module or implement it
- ⬜ **Two unrelated channels**: the working compiler builtin `channel()` and
  the library `Channel<T>` that does not compile and whose `receive`
  asserts rather than waiting. Decide which survives
- ⬜ A memory model - ordering, visibility, and what a data race *is* in Azora
  terms. Every atomic operation's meaning depends on it
- ✅ Fix `module std.pareallelism.sync` (typo - unimportable under its own path)
- ⬜ Cancellation and structured concurrency
- ⬜ Data-race freedom claim made testable
- ⬜ Concurrency story for Wasm and LLVM
- ✅ `CONCURRENCY_DIP.MD` and `PARALLELISM_DIP.MD` written

### 3.4 Reactivity

- ✅ `react func`, and reactive state as **a lifetime plus a binding**:
  `preserve var settings = …`, `retain val session = …`, `remember fin id = …`.
  The lifetimes form a ladder - `remember` survives reactive reruns, `retain`
  survives owner recreation in the same process, `preserve` is serialized and
  restored across process restarts. Renamed from `mem` / `rem` / `ret`, which
  said nothing about which outlived which
- ✅ A reactive lifetime cannot be combined with `threadlocal` - both answer
  where a value lives, and rejected in both orders
- ✅ Reactivity is contagious:
  a reactive function may only be called from another one, as `async` colours
  its call graph
- ✅ `effect { }`, explicit dependencies, `effect defer`
- ✅ `REACTIVE_DIP.MD` written
- ✅ Effects are first-class Azora IR nodes. The interpreter tracks runtime cell
  reads and subscriptions; LLVM and Wasm lower compiler-visible dependencies
- ✅ Owner-backed cells preserve reactive values across repeated calls, with
  coroutine-safe exactly-once initialization in the interpreter
- ✅ `lazy fin` and `lazy let` provide first-read initialization; inside a
  reactive owner they are cached derived values invalidated by reactive inputs
- ✅ LLVM and Wasm executable tests cover effects, lazy values, and remembered
  state; LLVM also covers reactive async owner storage
- ✅ `std.reactive` defines `Read`, `Write`, `State`, `Derived`, subscriptions,
  snapshots, restoration, and composition without a rendering dependency
- ⬜ Native effects still observe compiler-visible assignments rather than
  arbitrary writes hidden behind aliases, pointers, or calls
- ⬜ Native reactive owner storage is not yet atomic, and durable `preserve`
  storage still requires a host adapter

### 3.5 Dependency injection

- 🟨 `solo pack`, `graph`, `inject` - resolution works, **interpreter only**
- ✅ `DEPENDENCY_INJECTION_DIP.MD` written. A `graph` is a **graph**; every entry
  states its provider lifetime first (`solo` per graph, `factory` per
  resolution, `scope` per active scope); `bind` binds a definition to the specs
  it satisfies; `include` composes graphs
- ✅ Syntax parses: lifetimes, `bind`, `include`, and lazy bindings around
  injection (`lazy fin service = inject Service`)
- ✅ Keywords added: `lazy`, `factory`, `include` (and `derive`, reserved ahead
  of use). `solo` became a modifier on `pack`, so a `solo pack` follows every
  pack rule: fields only, members in an `impl` block, receiver `self`
- ⬜ **`__inject` has no lowering in LLVM or Wasm.** A program using DI compiles
  for those targets and then does not resolve; nothing warns (§6.1)
- ⬜ **Lifetimes are parsed and ignored** - `factory` and `scope` behave as
  `solo`, so a view model is a singleton and a request object outlives its
  request
- ⬜ **`bind` is parsed and ignored** - without it `inject` names a concrete
  type and no test double can replace a real service
- ⬜ **`include` is parsed and ignored**; `replace` has no keyword; a `graph`'s
  name is still recorded and never read
- ⬜ `func main() with AppGraph` - static graph selection, so an unsatisfiable
  injection is a compile error rather than a runtime surprise
- ⬜ Replace the string-keyed `typeName → instance` map with compiler-generated
  `DefinitionId` symbols, so missing and ambiguous definitions are diagnosed at
  compile time and scope-scoped types stop colliding
- ⬜ One concurrent construction attempt and one successful instance; today's
  race duplicates construction
- ⬜ Graph-owned values destroyed in reverse dependency order; `solo`/`scope`
  return a shared ref natively, `factory` an owned value
- ⬜ `Many<T>` in `std.di` for multibinding, and `inject T` erroring on
  ambiguity instead of colliding
- ⬜ `reflect<Graph>.implementations<T>` - compile-time discovery, separate
  from runtime resolution
- ⬜ `lazy` defers nothing yet; both forms lower to the same eager `__inject`
- ⬜ Injection cycles undetected
- ⬜ Graph validation: every injection satisfiable, no cycles, no ambiguity
- ⬜ **`solo` method bodies are never type-checked.** `TypeResolver` has no
  `TopLevel.Solo` case at all, so a body referring to an undefined name reaches
  the interpreter and fails there with a cast error rather than a diagnostic

### 3.6 Annotations

- ✅ `annot`, `@Name(args)`, targeted application, annotation impls on fields
- ✅ Field wildcards and cross-product target lists
- ✅ Serialization derives (value tree, AZON) resolved at compile time
- ⬜ `ANNOTATIONS_DIP.MD` covers syntax; runtime semantics need an audit

### 3.7 Dynamic execution (`std.dynamic`)

Running Azora *from* Azora: a compiled program embeds the interpreter and runs
`.az` source at runtime. `DIPs/DYNAMIC_DIP.MD` sketches it.

```azora
import std.dynamic

fin result: Any = try runScript("script.az", entry: "h1")
when result {
    is Int -> println(result)     // smart-cast
    else -> println("Unknown return type")
}
```

- ⬜ `std.dynamic` with `runScript(path, entry:)` returning `Any`
- ⬜ `ScriptError` and the failure modes: missing file, parse error, missing
  entry point, a runtime error inside the script
- ⬜ Smart-casting the `Any` result through `is` in a `when`
- ⬜ **A compiler switch for embedding the interpreter.** A program that never
  imports `std.dynamic` must not carry it, so this is opt-in and the flag is
  what decides whether the interpreter is linked in
- ⬜ Passing arguments to the entry point, and getting typed values back
- ⬜ The sandbox question: what a script may reach - filesystem, network, the
  host program's memory
- ⬜ Interaction with ownership: a value crossing the boundary is owned by whom
- ⬜ `DIPs/DYNAMIC_DIP.MD` filled out from the sketch into a real DIP

### 3.8 FFI

- ✅ `bridge <target> { }` extern declarations
- ✅ Interpreter resolves C-math intrinsics
- ⬜ **FFI scope decided and specified** - C interop claimed, needs verifying
- ⬜ Callbacks across the boundary, struct layout guarantees, ownership rules
- ✅ `BRIDGE_DIP.MD` and `COMPILER_INTRINSIC_DIP.MD` written

#### Automatic binding generator for C headers

Hand-writing `bridge` blocks for a real C library does not scale: a header of
any size is hundreds of declarations, and every upstream release invalidates
them silently. A generator reads the header and emits the `bridge` block, so
the bindings are derived from the header rather than transcribed from it.

- ⬜ A tool, `azora bindgen <header.h>`, emitting an Azora module of `bridge`
  declarations
- ⬜ Header parsing: functions, `struct` / `union` / `enum`, `typedef`,
  function pointers, opaque handles, and `const` qualifiers
- ⬜ C type to Azora type mapping table, written down and tested: the fixed
  width integers, `size_t` / `ptrdiff_t`, `char*` versus `const char*` versus
  a byte pointer, arrays decaying to pointers, and by-value struct passing
- ⬜ Preprocessor handling: which `#define` constants become `fin`, which are
  skipped, and how include paths and macro definitions are configured
- ⬜ Struct layout must agree with the platform ABI, checked by generating a C
  program that asserts every `offsetof` and `sizeof` against the Azora side
- ⬜ Name mapping: C's flat namespace and library prefixes to Azora scopes and
  names, with an override file for the cases a rule cannot get right
- ⬜ Bitfields, anonymous members, variadic functions, and `va_list`: each
  either supported or rejected with a diagnostic naming the declaration
- ⬜ Regeneration is reproducible and diffable, so a header upgrade shows as a
  reviewable change rather than a rewrite
- ⬜ Generated bindings for one real library, compiled and executed as a test,
  so the generator is checked against something it did not choose

---

## 4. Standard library

### 4.1 State

Everything here exists and works. None of it is ready to depend on: the APIs
are unstable, coverage is uneven, and §4.4 lists what is missing outright.

- 🟨 47 modules covering math, string, char, container, algorithm, functional,
  memory, convert, io, time, random, reactive, reflection, serializer,
  concurrency, parallelism, allocator, traits
- 🟨 Import-gated injection - only referenced items are pulled in, transitively
- 🟨 Container hierarchy: `List` `Map` `Set` `Deque` `Queue` `Stack` `Array`,
  hash/linked/tree variants
- 🟨 Smart pointers: `Unique` `Shared` `Weak` `SyncShared`
- 🟨 `Clone` / `Copy` with field-wise derivation

### 4.2 Quality bar for 0.1

- ⬜ Every public item has a doc comment with an example
- ⬜ Every module has tests; coverage target agreed and met
- ⬜ Naming consistency pass across all 47 modules
- ⬜ `@Since` accurate on every public item
- ⬜ Stability markers audited - what ships stable is a deliberate list
- ⬜ Iteration protocol unified and documented
- ⬜ Capacity/growth semantics documented

### 4.3 Distribution: the standard library is a file tree, not a compiler blob

Today `std/` is embedded into the compiler as generated Kotlin (`AzStdlib`) and
injected from memory. That has to change before `0.1`.

The standard library should be **read from the filesystem where the language is
installed**, the way every other toolchain does it. In the browser the same
files ship alongside the compiler and are read from there.

- ✅ `std/` installed as `.az` source next to the compiler (`install.sh` copies
  it to `$INSTALL_DIR/std`), found by a documented resolution order:
  `AzStdlib.overrideRoot` → `AZORA_STDLIB` → project-local `std/` (walking up to
- 
  a workspace marker) → `$AZORA_HOME/std` → install prefix → bundled
- ✅ The compiler reads it from disk instead of from an embedded blob.
  `AzStdlibBundle` is now generated as *data only*; resolution, the handshake,
  parsing and caching are hand-written in `AzStdlibLoader.kt`
- ✅ Browser and playground: the same `.az` files ship inside the artifact and
  are read through the same reader as a disk tree - the bundle is the virtual
  filesystem, not a second code path (`stdlibDiskRoots()` is simply empty there)
- ✅ A version handshake: a root must carry `STDLIB_VERSION`, checked against
  `BuildConfig.VERSION`. A root the user *named* and that lacks the marker is
  rejected; a merely *discovered* directory without one is skipped, so a stray
  `~/std` cannot break a working install
- ✅ Users can point at a different standard library without rebuilding the
  compiler - `AZORA_STDLIB=/path/to/std azora run main.az`
- ✅ Parse/inject caching: the resolved tree and its parsed programs are cached
  and reused, and invalidated when the root changes

This is what makes the standard library **replaceable and inspectable** rather
than a compiler implementation detail: a user can read the source of what they
are calling, and a fix does not need a compiler release.

### 4.4 Gaps

- ✅ **`sort` is insertion sort - O(n²).** Now a stable bottom-up merge sort,
  O(n log n), with insertion sort for short runs. `sortDescending` shares the
  engine via a direction flag. Stability is asserted in `SortStdlibTest`
- ✅ `sortBy`'s key function is typed `(T) -> T` and cannot express a key of
  another type. Now `sortBy<T, K>(arr, key: (T) -> K) where K: Order` - stable,
  O(n log n), keys computed once per element rather than once per comparison
- ⬜ `std.io` - streams, stdin/stdout/stderr, readers and writers (currently
  minimal)
- ✅ `std.filesystem` - files, directories, paths, metadata. `Path` as a value
  with a pure algebra (`join`, `parent`, `fileName`, `stem`, `extension`,
  `isAbsolute`, `normalized`, `withExtension`); `readText`/`readBytes`,
  `writeText` (atomic) / `appendText`, `listDirectory` (absolute, sorted),
  `createDirectory`/`createDirectories`, `remove`/`removeAll`, `copyFile`,
  `rename`, `fileInfo`, `exists`/`isFile`/`isDirectory`, `canonical`,
  `temporaryDirectory`/`createTemporaryDirectory`. Every disk operation is
  failable with `FileError` - a missing file and an empty one are different
  answers, which is what `engine.io`'s `Blob` could not express. Designed in
  `DIPs/FILESYSTEM_DIP.MD`; `oper /` awaits that DIP's §10.1, and the LLVM
  backend is step 8
- ✅ `std.os` - process, environment. `envVar`/`hasEnvVar`/`setEnvVar`/`envOr`,
  `currentDirectory`/`changeDirectory`, `processId`, and `runCommand` returning
  a `ProcessResult`. Compiler bridges rather than C declarations, because
  `azora run` executes through the interpreter, which has no FFI - a `bridge .C`
  API would compile and then fail to run. Web and iOS implement the same surface
  by refusing clearly rather than returning a wrong answer
- ⬜ `std.http` - decide in or out
- ⬜ `std.time` - thin; audit
- ⬜ `std.testing` - assertions, fixtures, parametrised tests
- ⬜ `std.azon` - user-facing API over the serializer
- ⬜ Regex - decide in or out

---

### 4.5 Serialization: one built-in format

The standard library ships **AZON only**. JSON comes out, and anyone who wants
it - or CBOR, or YAML, or a wire format of their own - implements `Serializer`
for it. A format in the standard library is a format the language has to
maintain forever; a spec anyone can implement is not.

The surface removed:

- ✅ `std/serializer.az`: the `JsonSerializer<T>` spec, the `JsonSerializable`
  annotation, `toJson` / `fromJson`, and `_JsonMetadataFixture`
- ✅ `SerializationFormat` deleted outright rather than reduced to one case.
  The parameter was threaded through `encodeSerialValue`, `decodeSerialValue`,
  `_encodeSerialValueAtDepth` and `_SerialReader` and **never read** by either
  the encoder or the decoder, so JSON and AZON were already byte-identical.
  Both entry points now take `(value, options)`
- ✅ `SerializationDeriver.kt`: the `json` role, `FormatSet`, and the format
  cross-product. `appendFormatMethods` collapses to `appendTextMethods`
- ✅ `@Serializable`'s `bind [Serializer, JsonSerializer, AzonSerializer]`
  collapses to `bind [Serializer, AzonSerializer]`
- ✅ Tests: `SerializationDeriverTest`, `AnnotratorConformanceTest`,
  `StdlibInjectionTest`
- ✅ `compiler/…/README.md`, `README.md`, `ANNOTATIONS_DIP.MD`

Two latent bugs surfaced while removing it and were fixed in passing:

- ✅ `SerializationError.MissingField` was committed as `MissingFiel` + newline
  + `d,`, which parsed as two members, while `serialField` returned
  `.MissingField`
- ✅ `"json rejects azon separators"` and `"azon rejects json separators"`
  contradicted each other and a third test: one demanded AZON reject `:`, the
  other demanded AZON use `:`. Both passed only because the format argument
  was inert. AZON uses `:`, and one test now checks that `=` is rejected

And what has to be true after it, or the removal just loses a feature:

- ✅ `Serializer<T>` is genuinely implementable from outside the standard
  library. It was **not**, when this was first written: a user program could
  not name the pieces it needs, because a type declared in a scope keeps its
  bare name while `Scope::Type` arrives mangled, and six separate positions
  never mapped it back. Each is fixed and covered by
  `ScopeQualifiedAccessTest`:
  - ✅ `?!` took a single identifier, so no function outside `std` could
    declare `?! SerializationError` - which every `Serializer<T>` member
    must. `Parser.parseErrorSetName`
  - ✅ `impl Serializer<T> for MyCodec` did not parse; the name after
    `impl` was a bare identifier. `Parser.parseImplHeadName`
  - ✅ `SerialValue.Text(x)` did not resolve, in expression or pattern
    position. `lookupEnum` / `lookupSlot` / `lookupFail` now take the
    scope-qualified form that `lookupStruct` already accepted, and
    `SymbolTable.canonicalTypeName` gives the resulting type its declared
    identity
  - ✅ `SerialField(…)` construction failed: the injector recorded the
    qualifier and the mangled name as referenced, never the bare tail that is
    the declaration, so the pack was never injected
  - ✅ `ArrayList<SerialField>` was a parse error; `isGenericCallAhead`
    stopped at `::`
  - ✅ `impl Spec for Scope::Type` named a *field*, because `Type::member` is
    the decorator's field target and the two share a spelling. A member target
    must be bodyless, so a body settles it in the parser; a bodyless one is
    settled by `ScopeQualifiedImplTargets`, once every declaration is present,
    on what the names denote - `A::B` is a type when `B` is a declared type and
    `A` has no member `B`, so a field named after a type still reads as a field
- ✅ A worked example of a third-party format, tested: `ThirdPartyFormatTest`
  builds `KV`, a line-oriented `key=value` format sharing no grammar with
  AZON, and round trips a compiler-generated codec through it. A second test
  implements `Serializer<T>` entirely by hand for a pack carrying no decorator
- ✅ The decorators work for it exactly as for AZON: `@SerialName` renames,
  `@SerialIgnore` omits and restores the declared default on read, and
  `ignoreUnknownFields` behaves the same, because they shape the value tree
  rather than the text format

None of these were serializer-specific. `Compare.Less` was equally
unspellable, so the fixes reach every scope-scoped type in the language.

---

## 5. Diagnostics

- ✅ Errors that name the problem *and* the fix (the comparison and cast
  diagnostics are the standard to hold)
- ✅ Line numbers on diagnostics
- ✅ Scope/import hints for unknown names
- ⬜ Source spans everywhere - column, length, caret
- ⬜ Multi-line snippets with the offending region underlined
- ⬜ Error codes (`AZ0001`…) with a documented index
- ⬜ Warnings separated from errors; `--warnings-as-errors`
- ⬜ **No internal exception ever reaches the user** - parser
  `IllegalStateException` is currently reachable
- ⬜ "Did you mean…" suggestions
- ⬜ Stale hint text audited - e.g. *"add 'use std.traits'"* when `use` is not
  valid at top level
- ⬜ A test per diagnostic, asserting the text

---

## 6. Backends

### 6.1 Parity - a release gate

- ⬜ A conformance suite run against every backend asserting identical
  observable behaviour
- ⬜ Any construct a backend cannot express is a **compile error for that
  target**, never silently degraded output
- ⬜ The known silent divergences, each of which compiles everywhere and means
  something different per target:
  - ⬜ `inject` - `__inject` has no lowering in LLVM or Wasm (§3.5)
  - ⬜ `await` under Wasm lowers to its operand; tasks run inline (§3.3)
  - ⬜ `delay` under Wasm degrades - no host clock (§6.4)
  - ⬜ A failed `assert` under Wasm loses its message (§3.2)
  - ⬜ `hash` for pointer-backed values disagrees between LLVM and the
    interpreter (§1.1)

### 6.2 Interpreter

- ✅ Executes the full language; drives tests, REPL and playground
- ✅ Real parallelism for tasks
- ⬜ Performance adequate for large test suites
- ⬜ Leak/ownership checking mode

### 6.3 LLVM

- ✅ Emits `.ll` runnable under `lli`
- ✅ Structs, arrays, methods, operators, casts
- ✅ Scoped-arena subsystem deleted. It was left behind by the `scope alloc`
  removal and was **unreachable**: `arenaStack` was cleared, read and restored
  but never pushed to, and `stmtUsesAllocScope` had no branch that could return
  `true`, so `programUsesArena` was permanently `false`. Around 300 lines went,
  including the `%azora.arena` type, the `@__azora_current_arena` thread-local,
  `__azora_arena_begin` / `_alloc` / `_free_all` / `_detach_current`, and the
  branching `__azora_alloc`. The interpreter's matching `regionAllocations`
  deque, also never pushed to, went with it
- 🟨 Placeholders remain for **closures, `defer`, compound types, pointers**
- ⬜ `hash` for pointer-backed values (§1.1)
- ⬜ Unsupported global statements emit a comment
  (`; unsupported global statement`) - must be an error
- ⬜ Debug info (DWARF)
- ⬜ Optimisation pipeline and `--release`
- ⬜ Native linking - produce an executable, not just `.ll`

### 6.4 WebAssembly

Several constructs degrade silently; each is a potential miscompile.

- ✅ Emits WAT with linear memory and host imports
- ✅ Value-returning `when`/`if` (fixed - was rejected by the validator)
- ⬜ `ForEach` is a **no-op**
- ⬜ Unsupported methods "degrade to the receiver"
- ⬜ `VariantLit` / `SlotPattern` emit `(i32.const 0)`
- ⬜ `delay` degrades - no host clock
- ⬜ Strings and memory management beyond the MVP
- ⬜ Host interop story - imports and exports across the Wasm boundary
- ⬜ `wasm-opt` integration and a size budget

---

## 7. Tooling

### 7.1 CLI

- ✅ `azora run` / `check` / `compile` / `repl` / `version` / `help`
- ✅ Targets `wasm`/`wat`, `llvm`/`ll`, `ir`, `ast`
- ✅ `--debug` / `--release` / `--test` flags
- ✅ Multi-file projects auto-discovered
- ⬜ `azora test` - first-class runner with reporting
- ⬜ `azora fmt` - **no formatter exists**
- ⬜ `azora doc` - **documentation generator: nothing exists, not even
  partially.** There is no `doc` command and no doc-comment pipeline: the lexer
  discards `/** … */` entirely, so the `@param` / `@return` / `@generic` tags
  written throughout `std` are read by people and by nothing else. Today's
  documentation is hand-written Markdown - this roadmap, the DIPs, the READMEs -
  kept true by review rather than generation. What a generator needs first:
  - ⬜ The lexer keeps doc comments and attaches them to the declaration that
    follows, instead of dropping them
  - ⬜ A tag vocabulary that is specified and checked: `@param` naming a
    parameter that exists, `@return` on something that returns, `@generic` on a
    declared type parameter
  - ⬜ Output format decided, and whether it renders the scope/module tree
  - ⬜ `azls` hover reads the same comments, so the editor and the generated
    docs cannot disagree
- ⬜ `azora new` - project scaffolding
- ⬜ Consistent exit codes; `--json` diagnostics for tooling
- ⬜ Diagnostics output format is `--json`; it should be AZON, like every other
  structured format the project emits

### 7.2 Language server

- 🟨 Error-tolerant highlighting, diagnostics, completion, hover, document
  symbols - **16 of 42 tests fail**
- ✅ Reflective loading for Azora Studio, optional prelude
- ✅ `AZLS_DIP.MD` written
- ⬜ **The server points at `../Internal/Std`**, a stdlib layout that no longer
  exists. Five completion tests report an empty list because the index behind
  them is empty; point it at `std/`
- ⬜ **Hover reads `///` and `//`, not `/** */`.** `std/math.az` has 65 `/** */`
  blocks and zero `///` lines, so every doc comment the standard library carries
  is invisible in the editor
- ⬜ The server still assumes the `use` import model the language replaced with
  `import`, which is why five debug-session tests time out
- ⬜ The wire format is JSON; every other structured format in the project is
  AZON
- ⬜ Nothing checks that the highlighter and the compiler's lexer tokenize the
  same source the same way
- ⬜ **Not an LSP server** - a reflective jar with a bespoke interface, so every
  editor that is not Azora Studio needs an adapter. Decide whether to speak LSP
- ⬜ Go-to-definition across files (it finds declarations in the current file
  and reports an external one by name only)
- ⬜ Find references
- ⬜ Rename
- ⬜ Signature help
- ⬜ Inlay hints
- ⬜ Workspace indexing and cross-file intelligence
- ⬜ Incremental parsing
- ⬜ Code actions wired to §6 diagnostics
- ⬜ Semantic tokens
- ⬜ Formatter integration
- ⬜ LSP conformance tested against a real client
- ⬜ `AZLS_DIP.MD` is empty

### 7.3 Debugger

- ✅ Line markers in debug builds; step/breakpoint execution
- ⬜ Variable inspection
- ⬜ Call stack
- ⬜ Conditional breakpoints
- ⬜ Native debugging via DWARF
- ⬜ DAP conformance

### 7.4 Package manager - the largest missing piece

`build-tool` is four files: `Main`, `AzoraConfig`, `TomlParser`,
`HtmlGenerator`. None of it is dependency management.

- ⬜ **Drop TOML: the manifest is AZON.** `TomlParser` and `AzoraConfig` read
  `azora.toml`, which is the one place in the project that uses a foreign
  configuration format - JSON came out of the serializer for the same reason,
  and a language that ships AZON should not ask its users to learn TOML to
  configure it. The example projects are converted (`azora.azon`); the build
  tool still has to be
- ⬜ Manifest format specified
- ⬜ Dependency resolution with a lockfile
- ⬜ Semantic versioning policy
- ⬜ Registry: hosting, publishing, authentication, yanking
- ⬜ Path and git dependencies
- ⬜ Build profiles and feature flags
- ⬜ Reproducible builds
- ⬜ Vendoring / offline mode
- ⬜ Workspaces
- ⬜ Interaction between package sources and stdlib injection

### 7.5 Editors

- ✅ Azora Studio hosts `azls`
- ✅ IntelliJ plugin (`org.azora.lang.idea`)
- ⬜ Both track `0.1`
- ⬜ VS Code extension
- ⬜ tree-sitter / TextMate grammar for third-party editors

---

## 8. Performance and the edit-run loop

Nothing here is measured today.

- ⬜ Benchmark suite: compile time and runtime
- ⬜ Published baselines tracked per commit, with regression alerts
- ⬜ Compile-time target for a 10k-line project
- ⬜ Runtime within a stated factor of C on a defined set
- ⬜ Binary size budget
- ⬜ Parallel compilation
- ⬜ `IrOptimizer`'s effect measured

---

### 8.1 Incremental compilation

Architectural rather than an optimisation bolted on late.

- ⬜ A compilation unit smaller than the whole program, with a stated boundary
  (module? file? declaration?)
- ⬜ A dependency graph precise enough to know what a change invalidates,
  including through `inline`, macros and CTCE - a compile-time function's
  result depends on its inputs, so the graph has to model that
- ⬜ On-disk cache of parsed, resolved and lowered artefacts, keyed by content
- ⬜ Cache invalidation on compiler version, standard-library version and flags
- ⬜ Correctness guarantee: an incremental build produces byte-identical output
  to a clean one, tested
- ⬜ Measured: the edit-one-function case is a fraction of a clean build

### 8.2 Hot reload

- ⬜ Swap a function body in a running program without restarting it
- ⬜ The rules, stated: what may change (a body) and what may not (a type's
  layout, a signature, a spec's members) without a restart
- ⬜ State preservation across a reload, and how it interacts with the
  reactivity model (`remember` / `retain` / `preserve` already distinguish what
  survives)
- ⬜ Which backends support it: the interpreter first, then a decision for
  LLVM and Wasm
- ⬜ Editor and CLI integration: a watch mode that reloads rather than restarts
- ⬜ Failure mode when a change cannot be hot-swapped - a clear message and a
  clean restart, never a half-updated program

---

## 9. Testing and CI

### 9.1 The 75 failing tests - a release gate

Every one fixed, or deleted with a stated reason.

- ⬜ `TypeRefTest` (11)
- ⬜ `LlvmAggregateExecTest` (8)
- ⬜ `StdlibInjectionTest` (6)
- ⬜ `ArrayStdlibTest` (5)
- ⬜ `WebsiteExamplesTest` (4)
- ⬜ `ArrayTest`, `CollectionCtorTest`, `Feature003SyntaxTest`,
  `Tier1PolishTest` (3 each)
- ⬜ `ArrayStdlibExecTest`, `ConstGenericArrayTest`, `ContainerStdlibTest`,
  `LlvmRegressionExecTest`, `MacroTest`, `MetaprogrammingTest`,
  `StdlibMethodsTest` (2 each)
- ⬜ 15 single failures across `CodegenGolden`, `ImplMethod`, `LlvmStdlibExec`,
  `Loop`, `Modules`, `Pack`, `PlaygroundExamples`, `StabilityAnnotrator`,
  `TestAssertTrace`, `Tier4Concurrency`, `TimeStdlib`, `Traits`, `Tuple`,
  `VariadicLambda`, `SerializationDeriver`

### 9.2 Infrastructure

- ✅ 1527 tests covering language, stdlib and codegen
- ✅ Execution tests through the interpreter, LLVM (`lli`) and Wasm (`wat2wasm`)
- ✅ Golden-file codegen tests
- ✅ `ReadmeSnippetTest` compiles every `azora` block in `README.md`, so the
  documentation cannot drift from the language without failing the build
- ✅ `TESTING_TRACING_DIP.MD` written: `test` blocks, `.This` / `.All`,
  `azora test`, `trace` and `panic`
- ⬜ **`assert` reports its message, never the operand values.** `assert a == b`
  cannot say what `a` and `b` were, which is the largest gap in using the suite
- ⬜ `azora test` has no `--wasm`, so the third backend is not covered this way
  - part of why its degradations went unnoticed
- ⬜ Tests compile with `release = false`, so a release-only miscompile cannot
  be caught by the suite at all
- ⬜ Tests share one interpreter and one global scope in declaration order, so
  they are not isolated from each other
- ⬜ No test filtering, setup/teardown, parametrised tests, timing, skip markers
  or machine-readable output
- ⬜ A failing `test .All` reports the first assertion message and nothing about
  which child failed
- ⬜ **`trace` cannot be disabled** - a release build prints every trace, with
  no level threshold and no sink
- ⬜ CI on every push across all backends and platforms, using `--strict` (a
  compile error only fails the run under that flag)
- ⬜ Coverage measured against an agreed target
- ⬜ Parser fuzzing - no input crashes the compiler
- ⬜ Property-based tests for the ownership checker
- ⬜ A conformance suite a third-party implementation could run
- ⬜ Performance regression tests
- ⬜ Nightly builds

---

## 10. Documentation

### 10.1 Specification

- ✅ **All 34 DIPs are written.** Every shipped feature now has one
- ⬜ Stale DIPs corrected:
  - ⬜ `TYPE_DIP.MD` §9 documents deleted intrinsics and `oper as<U>`
  - ⬜ `FUNCTIONS_DIP.MD` and `PACK_IMPL_DIP.MD` document
    `impl oper[] for X { self&, index -> }`, a form that now errors
  - ⬜ `SYNTAX.MD` shows `where T: oper<` and operator-list typealiases, both
    rejected
- ⬜ A single normative language reference assembled from the DIPs
- ⬜ Grammar published (EBNF) and the parser verified against it
- ⬜ Memory model written down
- ⬜ Loose docs folded in or archived: `LAMBDA.MD`,
  `Scope.md`, `RealmEnum.md`, `StdDynamic.MD`, `UNSAFE.MD`

### 10.2 Learning material

- ✅ Book, playground, docs and landing repos exist
- ⬜ All of them track `0.1`
- ⬜ Tutorial: install → first program → first package
- ⬜ Cookbook covering every major feature
- ⬜ Migration guide to `0.1`
- ⬜ Generated stdlib API docs

---

## 11. Stability

- ⬜ Stability policy published - what `0.1` guarantees and for how long
- ⬜ Deprecation process defined (warn → error → remove)
- ⬜ `@Since` / `@Experimental` semantics documented and enforced
- ⬜ The public surface of `0.1` explicitly enumerated; the rest is internal
- ⬜ Feature-gating for experimental features
- ⬜ A compatibility suite `0.1.x` must keep passing
- ⬜ Release process: versioning, changelog, signed artefacts, announcement

---

## 12. Strategic decisions

Settle these early; each reshapes the schedule.

- ⬜ **12.1 Self-hosting.** `experimental_self_hosted/` exists. Is the `0.1`
  compiler Kotlin or Azora? Multi-month either way; deferring is legitimate but
  must be a decision
- ⬜ **12.2 Generics: erasure or monomorphisation** - affects performance, FFI,
  reflection, ABI
- ✅ **12.3 Primary backend** - LLVM for native, Wasm for web
- ⬜ **12.4 ABI stability** - needed to distribute binaries; in or out
- ⬜ **12.5 Target platforms** - which OS/arch triples are supported and tested
- ✅ **12.6 Inheritance** - `dyn` spec dispatch is the whole story: composition
  plus capabilities, dispatched through a spec-typed value. There is no
  implementation inheritance and none is planned

---

## 13. Sequencing

**Stop the bleeding**
§1.1 miscompiles (derivation validation first - the only one that makes a
correct-looking program wrong today) · §9.1 the 75 failures · §12.1–12.3
decisions

**Foundations**
§1.2 type-system soundness and the generics decision · §1.3 ownership
completeness · §6.1 parity suite · §6.4 Wasm silent degradations

**Missing systems**
§3.3 `Mutex`, `Atomic`, real threads · §7.4 package manager

**Surface**
§4 stdlib completeness and docs · §5 diagnostics · §7.1–7.3 CLI, `azls`,
debugger

**Hardening**
§8 performance baselines · §9.2 CI, fuzzing, conformance · §10.1–10.2
reference and tutorial · §11 stability policy · release-candidate cycle

---

## 14. Explicitly out of scope

Recorded so they do not creep in.

- Incremental/parallel compilation beyond the §8 targets
- A GUI framework or game engine in the standard library
- Full C++ interop (C only)
- Distributed or actor concurrency beyond threads and tasks
- A JIT
- Formal verification of the ownership model
