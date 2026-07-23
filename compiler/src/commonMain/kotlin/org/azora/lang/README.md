# Azora Compiler Architecture

A multi-phase, IR-based compiler for the Azora language: multi-pass semantic
analysis with compile-time function execution (CTCE), a target-agnostic typed
IR, and the active JavaScript, WebAssembly, and LLVM source backends plus an
in-memory interpreter — all driven from one optimized IR per compile.

Source lives under `compiler/src/commonMain/kotlin/org/azora/lang/`
(package `org.azora.lang`). A `wasmJs` target also exists so the compiler can
run in the browser (the playground builds it to WASM).

---

## Pipeline at a Glance

```
Source → Lexer → Parser → AST Validator
                  ↓
           Stdlib Injection (only modules you `import`, transitively)
                  ↓
           Symbol Collection → Type Resolution ⇄ CTCE (fixed point) → Alloc/Drop → Effects
                  ↓
           IR Generator → IR Optimizer  (release mode only)
                  ↓
  ┌──────┬──────┬──────┬──────────┐
  ↓      ↓      ↓      ↓          ↓
  JS    Wasm   LLVM   Interpreter (IR dump)
```

Every `Compiler.compile()` lowers the (optimized) IR to JavaScript,
WebAssembly, and LLVM IR in one pass and returns them together. Adding a target
= one new file under `backend/` plus one field on `CompilationResult.Success`.

### Phase boundaries (see `Compiler.kt`)

1. **Frontend** — Lexer → Parser → (debug instrumentation) → Stdlib injection → AST validation.
2. **Semantic** — multi-pass: symbol collection → import resolution →
   type-resolution ⇄ CTCE fixed-point loop → alloc/drop → effect checking.
3. **IR** — AST → typed IR → optimization (constant fold/propagate, DCE, unused-symbol elimination).
4. **Backend** — IR → JavaScript, WebAssembly, LLVM (+ interpreter, + IR/AST dump).

---

## Language Surface

Azora is statically typed with type inference, (deeply) immutable bindings,
generics (incl. variadic), nullable and failable types, pattern matching,
compile-time execution, a memory model, real concurrency, dependency injection,
FFI, and reactivity. The authoritative, user-facing feature list is the repo
root `README.md`; this section is a keyword/construct reference for compiler work.

### Bindings & mutability

| Keyword | Mutability | JS emit |
|---------|------------|-------------|
| `var x: Int = 5` | mutable | `let` |
| `var x = 5` | mutable, inferred | `let` |
| `let x = 5` | immutable (read-only view) | `const` |
| `fin x = 5` | deeply immutable | `const` |
| `threadlocal var x = 0` | per-thread mutable | backend runtime slot |
| `threadlocal fin y = 42` | per-thread constant | backend runtime slot |

Top-level `fin`/`var`/`let`: `fin` (immutable global) is allowed; `var`/`let`
globals are rejected (not thread-safe).

### Visibility (declaration prefixes)

`expose` (public), `confine` (private), `protect` (protected). Applied to
top-level declarations and members.

### Functions

```
func add(a: Int, b: Int): Int { return a + b }
func<T> identity(x: T): T { return x }                 // generics, call-site inference
func<...T> sprintf(fmt: String, rest: ...T) { ... }    // variadic generics (last type param)
inline func square(x: Int): Int { return x * x }        // body substituted at call sites
func f(x: Int = 0, mut m: List, ref r: Int, out o: Int) // defaults + param modifiers
create(value: 30, label: "A")                            // named arguments
f(...arr)                                                // spread array into call args
```

Parameter modifiers: `mut` (mutable param), `ref` (by-reference — mutations
propagate to caller), `out` (callee-assigned output). Ownership/reference
kinds: `ref`, `shared ref`, `weak ref` (with optional `mut`).

### Types

- **Primitives**: `Int UInt Long ULong Byte UByte Short UShort Cent UCent
  Float Real Decimal Bool Char String Unit`.
- **Compound**: fixed arrays `[T]`, immutable collections
  `List<T>`/`Set<T>`/`Map<K, V>`, mutable collections
  `mut List<T>`/`mut Set<T>`/`mut Map<K, V>`, tuples `(A, B)`,
  function types `(A) -> B`, map values `mapOf("k": v)`.
- **User-defined**: `pack` (structs), `enum`, `slot` (tagged unions), `typealias`,
  `fail` (error sets).
- **Type parameters**: generics `func<T>`, `pack<T>`; **variadic** `func<...T>`.
- **Nullable**: `T?` with `null`, `??` (coalesce), `?.` (safe access),
  `?=`/`?+=`/… null-conditional assignment family.
- **Failable**: `T!ErrSet` — a `T` or an error from a declared setOf (propagated
  via the existing exception machinery).
- **Pointer**: `T*` — a heap reference (`alloc`, `deref ptr` / `*ptr`).
- Integer/float promotion: `2 + 1.5` → `3.5` (auto-widens).

### Control flow

`if`/`else if`/`else`; `while`; `for x in a..b`, `for x in array`, `for x in flow`;
`for x by N in a..b` (step); `reverse for`; `loop { }`; `loop { } while cond`
(do-while); `for/while/loop … else { }` (else runs unless `break`); labeled loops
`@lbl for`, `break @lbl` / `continue @lbl`; `when expr { patterns -> { } else -> { } }`
pattern matching (enums, slots with destructuring, literals) with exhaustiveness
checking; `guard cond else { }`; `break`/`continue`.

### Declarations (top-level constructs)

| Construct | Purpose |
|-----------|---------|
| `pack Name { fields }` / `pack Empty` | struct; empty packs may omit `{ }` |
| `pack Tuple<...T> where (...T).length >= 2 { inline for Ty in ...T with index { mixin "$index: $Ty" } }` | variadic tuple template |
| `enum Color { Red; Green }` | enum |
| `slot Option { Some(Int); None }` | tagged union |
| `impl pack Name { methods }` / `impl Spec for Name` | pack methods in the declaring file + trait impls |
| `func Name.method(args) { ref self -> body }` | extension method outside the declaring file |
| `spec Name { signatures }` / `spec Into<T>: T { ref self } use as "to${T.typeName}"` | trait or compact callback spec |
| `node Name(params) { … }` | inheritable type (base class) |
| `leaf Name(params) : Parent(args) { repl func … }` | final subclass (single inheritance) |
| `virt func` / `repl func` / `base.method()` | virtual / override / super-call |
| `typealias T = U` | type alias |
| `fail ErrSet { V1, V2 }` | error-set declaration |
| `deco Name { fin field: Type }` | decorator/annotation type; metadata fields must be explicitly immutable |
| `impl Decorator for Type` | implements a decorator as a bodyless marker contract |
| `impl Decorator(field: value) for Type` | implements a decorator with immutable compile-time metadata |
| `impl Decorator for Type::field` / `impl Decorator for Type::*` | decorates one field / every declared pack field |
| `impl [A, B] for [Type::x, Type::y]` | applies the decorator/target cross-product |
| `deco Name bind Spec { fields }` | binds a decorator to a spec; the decorated type becomes generic argument zero |
| `deco Name for [.Pack, .Node] bind [X for .Pack, Y for .Node]` | constrains decorator applications and individual transitive bindings by target |
| `solo Name { … }` / `wrap Name { … }` / `inject Type` | DI singleton / container / resolve |
| `flow name(p): T { … yield v }` | lazy generator |
| `@Reactive func name() { }` | rendering-independent reactive owner |
| `bridge target { func sigs }` | FFI extern declarations |
| `zone Name { … }` / `friend zone std::math { … }` | named namespace (`Name::member`) / shared namespace contribution |
| `test "name" { }` / `test .All "suite"` | one test / bodyless file-level aggregate suite |

### Object-model members (inside `impl`/`node`/`solo` bodies)

`hook name { }` (lifecycle callback), `prop name: T { }` (computed property),
`ctor(params) { }` (secondary constructor), `dtor { }` (destructor), and
`flip { } flop { }` (alternating execution). Inside ordinary `impl Type { }`
blocks only `prop`, `func`, `task`, and `flow` members are accepted. Index
overloading is standalone: `impl oper[] for Type { ref self, index -> ... }`
and `impl oper[]= for Type { mut ref self, index, value -> ... }`. Extension
methods use `func Type.method(...) { ref self -> }`, and infix extension
functions use `infx Type.method(...)`.

### Variadic tuples

`Tuple` is a variadic pack template in `std.container`:

```azora
@enforceNumFields
pack Tuple<...T> where (...T).length >= 2 {
    inline for Ty in ...T with index {
        mixin "$index: $Ty"
    }
}
```

`tupleOf(elements: ...T): Tuple<...T>` creates a tuple while preserving each
element's static type. The generated fields are numeric (`tuple.0`, `tuple.1`,
...) and the `where (...T).length >= 2` constraint rejects single-element
tuples.

### Conversion specs

`std.convert` defines compact callback specs:

```azora
spec Into<T>: T { ref self } use as "to${T.typeName}"
spec From<T>: T { ref self } use as "from${T.typeName}"
```

The `: T` is the callback return type, `{ ref self }` declares the receiver, and
`use as` declares a generated member name template. It can be any literal member
name (`use as "render"`) or include type-parameter placeholders such as
`${T.typeName}`. Without parentheses in the spec header,
`impl Into<String> for List<T> { ref self -> ... }` generates property-style
`.toString`. If the spec header includes parentheses, the generated callback
requires a normal call such as `.toString()`. `impl as String for Type { ref self
-> ... }` is separate:
it is used by `value as String` casts and does not create `.toString`.

### Memory model

`alloc <expr>` (heap pointer; `alloc [a,b,c]` enables pointer arithmetic),
`deref ptr` / `*ptr` deref, `*ptr = v` store, `drop <expr>` (advisory free under GC),
`unsafe { }`, `isolated(expr)` (deep copy), `zone alloc { }` /
`friend zone alloc { }` (scoped arenas). Pointer arithmetic: `ptr + n`,
`ptr - n`, `ptr1 - ptr2`, `ptr1 == ptr2`.

### Concurrency

`flow` generators (lazy, suspend at `yield`), `task { }` / `await t`
(cooperative async with **real parallelism** on `Dispatchers.Default` — each
task gets isolated execution state), `channel()` + `.send`/`.receive`/`.close`,
`launch { }` (fire-and-forget, joined before exit).

### Error handling

`throw value`; `try { } catch { name -> body }`; `expr catch fallback`;
`try expr` (propagate a failable expression to the current failable function);
`rescue { }` (catch-and-suppress); `fail ErrSet.V` (raise an error);
`fail defer { }` (runs only on error exit); `defer { }` (LIFO cleanup).

### Reactivity

`@Reactive` enables `mem` (memoized), `rem` (saveable), `ret` (retained), and
`effect`. Effects may track reads automatically, declare one/list dependencies,
or use `effect defer` for owner-exit cleanup. Reactive callables may only be
called from another reactive scope.

### Compile-time execution (CTCE)

`inline fin`/`let`/`var` (compile-time bindings), `inline if cond { }`
(conditional compilation), `inline for x in a..b { }` (loop unrolling),
`inline { }` / `deepinline { }` (compile-time blocks), `noinline` (escape hatch),
`inline func` (call-site substitution), `inline assert` / `inline trace`.
Constant folding, propagation, and dead-code elimination run in the IR optimizer.

Decorator metadata is part of CTCE. `(reflect value).hasDeco<D>` tests direct and
transitively bound decorators on values, types, packs, functions, properties,
fields, parameters, and the other `DecoTarget` declaration categories.
`(reflect value).decoMeta<D>.field` reads a decorator's named, positional, or default `fin`
field value. Both properties are compile-time-only and must occur in an
`inline` expression:

```azora
deco Persisted for .Pack {
    fin ignoreUnknownFields: Bool = false
}

@Persisted(ignoreUnknownFields: true)
pack User

inline if (reflect User).hasDeco<Persisted> {
    inline assert (reflect User).decoMeta<Persisted>.ignoreUnknownFields
}
```

`Root`, `DecoTarget`, `HasDeco`, `DecoMetadata`, and `derive` are compiler-predefined in
`Root.az` and injected into every module without an `import` declaration.

`@derive(generator: "name", role: "role", provider: "zone",
providerModule: "module")` connects a library decorator to an installed compiler
derive generator. Optional conversion-provider fields work the same way. The
generator reads these roles and provider metadata rather than embedding library
module paths or decorator names, so libraries can relocate, rename, or define
their own participating decorators. Generated dependency imports are internal
and limited to the modules named by that metadata.

### Serialization contracts

`std.serializer` separates the lossless `SerialValue` tree from text formats.
`Serializer<T>` converts typed values to and from that tree,
`JsonSerializer<T>` owns JSON text conversion, and `AzonSerializer<T>` owns
AZON text conversion. `@Serializable` binds all three contracts;
`@JsonSerializable` and `@AzonSerializable` opt into one text format. Their
`ignoreUnknownFields` and `encodeDefaults` values are immutable decorator
metadata and are available to generated inline code through `decoMeta<D>`.
Bodyless decorator implementations may configure those fields directly; omitted
fields use the defaults declared by the decorator:

```azora
impl Serializable(
    ignoreUnknownFields: true,
    encodeDefaults: false
) for User
```

The compiler applies the same field-name, duplicate-argument, required-field,
and type validation used by `@Serializable(...)`. Value arguments are rejected
on ordinary spec implementations because only decorators define metadata.

Decorator implementations can also select pack fields. Lists are normalized to
one application for every decorator/target pair, and `Pack::*` selects only the
fields declared by that pack:

```azora
impl SerialName(value: "login") for User::name
impl [SerialName, SerialRequired] for User::name
impl SerialIgnore for [User::name, User::password]
impl [SerialName, SerialRequired] for User::*
```

Member selectors and wildcards are decorator-only and bodyless. Unknown fields,
non-pack wildcard owners, invalid decorator targets, and applications repeated
through overlapping explicit/wildcard selectors are compile errors.

The serializer derive emits checked `toSerialValue`/`fromSerialValue` methods
and the selected JSON/AZON methods before IR generation. `SerialName` controls
both encoded and decoded keys, `SerialIgnore` omits a field and restores its
declared default, and `SerialRequired` forces encoding and rejects absence.
`ignoreUnknownFields` controls unknown-key rejection; `encodeDefaults` controls
default omission except for required fields. Duplicate wire names,
ignore/required conflicts, ignored fields without defaults, duplicate input
keys, unsupported types, and numeric overflow are diagnosed.

Generated value-tree codecs currently cover scalar primitives, nullable scalar
primitives, nested serializable packs, `List<T>`/`Set<T>` with primitive
elements, and `Map<String, V>` with primitive values. The methods become normal
typed IR and therefore share behavior across the interpreter, JavaScript,
WebAssembly, and LLVM backends.

### Testing & debugging

`test "name" { }` defaults to `TestMethod.This`. `test .All "suite"` may omit
its body and groups every `This` test in that source file into one isolated
suite. `assert cond { "msg" }`, `trace { "msg" }`, plus their `inline`
(compile-time) variants are available inside tests.

### Operators

Arithmetic `+ - * / %`; comparison `== != < <= > >=`; logical `&& || !`;
bitwise `& | ^ ~ << >>`; assignment `= += -= *= /= %=`, `++`, `--`;
null-conditional `?? ?.` and `?= ?+= ?-= ?*= ?/= ?%= / ?++ ?--`;
casts `expr as Type`, `expr is Type`, negated `expr is! Type`; raw strings `"""…"""`;
string interpolation `"$name"`, `"${expr}"`.

### Keywords (by category)

Reserved words in the language (see `frontend/Token.kt`):

- **Bindings**: `var` `fin` `let` `threadlocal`
- **Functions/types**: `func` `return` `pack` `shield` `enum` `slot` `typealias` `impl` `spec` `node` `leaf` `virt` `repl` `base`
- **Control**: `if` `else` `for` `while` `loop` `in` `by` `reverse` `break` `continue` `when` `guard`
- **Errors/concurrency**: `throw` `try` `catch` `rescue` `fail` `defer` `flow` `yield` `task` `await` `launch`
- **Memory/FFI/DI**: `alloc` `drop` `unsafe` `isolated` `bridge` `solo` `wrap` `inject`
- **Reactivity/object model**: `mem` `rem` `ret` `effect` `hook` `prop` `ctor` `dtor` `flip` `flop`
- **Metaprogramming**: `inline` `deepinline` `noinline`
- **Scoping/modules**: `zone` `friend` `module` `import`
- **Modifiers/visibility**: `mut` `ref` `out` `shared` `weak` `expose` `confine` `protect`
- **Operators-as-keywords**: `oper` `infx` `as` `is` `null` `deco`
- **Testing**: `test` `assert` `trace`

---

## Phase 1 — Frontend (`frontend/`)

Transforms source text into a structured, validated AST. No name resolution
or type inference happens here.

- **Lexer** (`Lexer.kt`): tokenizes source (keywords, literals, operators,
  delimiters, interpolated strings), tracking line/column. Newlines are
  significant outside brackets (statement separators). Comments: `//` line and
  `/* */` nestable block.
- **Parser** (`Parser.kt`): recursive-descent; produces a raw, unresolved AST.
  Many modern constructs desugar to existing nodes at parse time (e.g. `is!`,
  do-while, the `?=` family, named zones, `launch`), so they need no backend
  changes.
- **Debug instrumentation** (`DebugInstrumenter.kt`): in debug builds, tags
  statements with `__dbg(line)` markers so the debugger can pause.
- **AST Validator** (`AstValidator.kt`): structural checks (duplicate names,
  missing returns, redeclarations, flow exhaustiveness, etc.).
- **AST Dumper** (`AstDumper.kt`): tree-style dump for debugging (`azora compile ast`).

**AST node types** (`Ast.kt`): a large sealed hierarchy. Categories:

- **Expressions** (`Expr`): literals (`Int`/`Real`/`String`/`Bool`/`Char`),
  `Identifier`, `Binary`, `Unary`, `Call`, `MethodCall`, `Member`, `Index`,
  `Lambda`, `Cast`, `IsCheck`, `NullCoalesce`, `SafeMember`, `Await`, `Yield`,
  `Alloc`, `Deref`, `Isolated`, `MapLit`/`ArrayLiteral`/`TupleLit`/`SetLiteral`,
  `Range`, `Spread`, `NamedArg`, `Reference`, `IfExpr`, `CatchExpr`, …
- **Statements** (`Stmt`): declarations (`VarDecl`/`FinDecl`/`LetDecl`/`RemDecl`),
  `Assignment`/`MemberAssign`/`IndexAssign`/`DerefAssign`, `Return`, `ExprStmt`,
  `If`, `For`, `While`, `Loop`, `Break`/`Continue` (labeled), `When`, `Zone`/
  `FriendZone`, `Defer`, `Throw`/`Try`, `Assert`/`Trace`, the `Inline*` family,
  `Hook`/`Flip`/`Flop`, `Effect`, …
- **Top-level** (`TopLevel`): `Func`, `Pack`, `Enum`, `Slot`, `Impl`, `Spec`,
  `Node`, `TypeAlias`, `Fail`, `Deco`, `Solo`, `Wrap`, `Bridge`, `View`,
  `Test`, `UseImport`, plus the inline-construct top-levels.
- **Types** (`TypeRef`): `Named`, `Nullable`, `Failable`, `Array`, `Map`,
  `Tuple`, `Function`, `Pointer`, `Set`, `Explicit`/`Inferred` annotations.

All nodes carry `line`, `column`, `length` for error reporting.

---

## Phase 2 — Semantic Analysis (`semantic/``)

Multiple passes. Metaprogramming (CTCE) creates ordering dependencies that
can't be resolved in one pass, so the core runs as a **fixed-point loop**
(type resolution ⇄ CTCE) until the AST stabilizes. Orchestrated by
`SemanticPipeline.kt`.

1. **Top-level CTCE** (`CtfeEvaluator.kt`) — flattens conditional declarations
   before symbol collection so `SymbolCollector` can see them.
2. **Symbol Collection** (`SymbolCollector.kt`) — registers all signatures
   (functions, packs, enums, slots, nodes, …) so forward references work.
   Built-ins (`println`, `channel`, …) are registered here.
3. **Import Resolution** (`ImportResolver.kt`) — resolves cross-module/stdlib
   references (largely handled by `StdlibInjector` + `QualifiedStdRewriter`).
4. **Type Resolution ⇄ CTCE fixed point** (`TypeResolver.kt`,
   `CtfeEvaluator.kt`) — resolve/infer types, fold compile-time constructs back
   into the AST, repeat until stable. Any `inline` node that survives is an error.
5. **Alloc/Drop Analysis** (`AllocDropAnalyzer.kt`) — liveness, use-before-init,
   unused locals (post-CTCE, since generated code may allocate).
6. **Effect Checking** (`EffectChecker.kt`) — `PURE`/`IMPURE` classification
   with fixed-point propagation across the call graph (post-CTCE).

`SymbolTable.kt` holds function/variable symbols with scoped lookup and the
enum/slot/fail registries.

---

## Phase 3 — IR Generation (`ir/`)

- **IrGenerator** (`IrGenerator.kt`): lowers the CTCE-stabilized, type-checked
  AST into typed, target-agnostic IR. Inline functions are skipped (not emitted).
  `TokenType` operators become `IrBinaryOp`/`IrUnaryOp`; every expression carries
  its resolved `IrType`; variable shadowing is resolved by name mangling
  (`x` → `__x0`) so backends need no scope-aware logic. `UpperScopeAccess` (`::`)
  resolves to a mangled `Var` reference.
- **IrOptimizer** (`IrOptimizer.kt`): constant folding → constant propagation
  → dead-code elimination → unused-symbol elimination. Runs in **release** mode
  only; debug builds feed the raw IR to backends so output mirrors the source.

**IR node types** (`IrNode.kt`): `IrType` (primitives, `Array`, `Map`, `Tuple`,
`Function`, `Pointer`, `Named`, nullable/variant metadata), `IrExpr`, `IrStmt`,
`IrFunction`, `IrTopLevel` (incl. `Extern` for `bridge`), `IrProgram`. See the
file for the full hierarchy.

---

## Phase 4 — Backend (`backend/`)

All backends are thin lowering passes from the same optimized IR.

| File | Target | Notes |
|------|--------|-------|
| `JavaScriptCodegen.kt` | JavaScript | Full. Emits plain JS and appends `main()`. |
| `WasmCodegen.kt` | WebAssembly (WAT) | Full; folded S-exprs, linear memory + host imports. |
| `LlvmCodegen.kt` | LLVM IR (`.ll`) | Partial — placeholders for closures, defer, compound types, pointers. `lli`/`clang`/`llc` ready. |
| `IrInterpreter.kt` | (in-memory) | Full direct execution — drives tests, REPL, and the playground. Concurrency runs on `Dispatchers.Default` with real parallelism. |

---

## Entry Point (`Compiler.kt`)

Orchestrates all four phases and returns every generated output at once:

```kotlin
when (val result = Compiler().compile(source, release = true)) {
    is CompilationResult.Success -> {
        result.javascript   // generated JavaScript
        result.wasm         // WebAssembly text (WAT)
        result.llvm         // LLVM IR text
        result.ast          // CTCE-stabilized AST after semantic analysis
        result.ir           // typed IR (before optimization)
        result.optimizedIr  // typed IR (after optimization)
        result.effects      // per-function effect classifications
        result.warnings     // non-fatal warnings
    }
    is CompilationResult.Failure -> result.errors
}
```

Flags: `warningsAsErrors` treats warnings as failures; `release = false` skips
optimization; `debug = true` instruments statements for the debugger.

---

## Standard Library (`stdlib/`)

The stdlib (`Internal/Std/*.az`, 35 modules) is compiled into an `AzStdlib`
index and **import-gated**: a file sees a module's names only after `use`-ing
it, and only referenced items are injected transitively (`StdlibInjector.kt`).
User declarations shadow stdlib items; programs that never touch the stdlib
compile unchanged. `QualifiedStdRewriter.kt` resolves `std::module::name`
qualified references. `Compiler.withStdlibHint` points undefined-symbol errors
at the right `use`.

---

## Design Principles

1. **Don't resolve everything in one pass.** Multiple passes with a CTCE
   stabilization loop handle metaprogramming's ordering dependencies.
2. **Separate declaration semantic from body semantic.** Signatures register
   (Pass 1) before bodies analyze — forward references just work.
3. **CTCE before type checking.** Compile-time constructs resolve first so the
   type checker sees clean code.
4. **CTCE shares the type system.** The evaluator uses the same `IrType` — no
   separate interpreter types.
5. **IR is the portability asset.** Target-agnostic typed IR; backends are thin
   lowering passes. A new target is one file.
6. **Post-CTCE analysis.** Alloc/drop and effect checks run after CTCE
   stabilizes, because generated code may introduce allocations/effects.
7. **Desugar to existing IR where possible.** Many language features lower at
   parse time to existing nodes (`is!`, do-while, `?=`, named zones, …), so they
   need zero backend work and are instantly testable end-to-end.

---

## File Map

```
compiler/src/commonMain/kotlin/org/azora/lang/
├── Compiler.kt                  Pipeline orchestrator
├── Platform.kt                  Target/platform helpers
│
├── frontend/
│   ├── Token.kt                 TokenType, NumericSuffix, Token
│   ├── Lexer.kt                 Source → tokens
│   ├── Ast.kt                   Expr, Stmt, TopLevel, FuncDecl, TypeRef, Program
│   ├── Parser.kt                Tokens → AST (recursive descent + desugaring)
│   ├── AstValidator.kt          Structural validation
│   ├── AstDumper.kt             Tree dump (`compile ast`)
│   └── DebugInstrumenter.kt     `__dbg(line)` markers for the debugger
│
├── semantic/
│   ├── SymbolTable.kt           Function/variable symbols + scoped lookup + registries
│   ├── SymbolCollector.kt       Pass 1: signatures + builtins
│   ├── ImportResolver.kt        Cross-module/stdlib resolution
│   ├── CtfeEvaluator.kt         CTCE: top-level (Pass 0) + fixed-point body folding
│   ├── TypeResolver.kt          Type resolution + inference + checking
│   ├── AllocDropAnalyzer.kt     Liveness / use-before-init / unused locals
│   ├── EffectChecker.kt         Purity classification + effect propagation
│   └── SemanticPipeline.kt      Multi-pass orchestrator (fixed-point CTCE loop)
│
├── stdlib/
│   ├── StdlibInjector.kt        Import-gated, transitive stdlib injection
│   └── QualifiedStdRewriter.kt  Resolves `std::module::name` references
│
├── ir/
│   ├── IrNode.kt                IrType, IrExpr, IrStmt, IrFunction, IrProgram
│   ├── IrGenerator.kt           AST → typed IR (skips inline functions)
│   └── IrOptimizer.kt           Constant fold → propagate → DCE → unused-symbol elim
│
└── backend/
    ├── JavaScriptCodegen.kt     IR → JavaScript
    ├── WasmCodegen.kt           IR → WebAssembly (WAT)
    ├── LlvmCodegen.kt           IR → LLVM IR text
    └── IrInterpreter.kt         IR → direct execution (tests, REPL, playground)
```

Sister modules in this repo: `app/` (the `azora` CLI) and `azls/` (the language
server + debug session, packaged as `azls.jar`).
