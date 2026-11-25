# Porting the Azora compiler from Kotlin to C++ (plan, 2026-11-25)

**Status:** approved 2026-11-25, §15 decisions taken as recommended; in progress on
branch `cpp_port`. Stages 0-4 are done: the first slice reports zero differences
(§13, results there), and so does the program handed to the first
`SemanticPipeline.analyze` for every corpus entry and recorded case (§9, stage 4).
Stage 5 (semantic passes) is next.

**Goal.** A C++ compiler that produces exactly what the Kotlin compiler produces, byte
for byte, for every input, then replaces it. The port is written in a small subset of C++
that maps one-to-one onto Azora, so that a later self-hosted compiler is a translation,
not a redesign.

**Why.** A Kotlin/Native release link of the compiler takes 30 to 35 minutes. A C++
compiler of the same size builds from scratch in minutes and rebuilds one changed file in
seconds. C++ also removes the JVM from tests and tools, and runs where clang runs (macOS,
iOS, Linux, Windows).

**What does not change.** The language, the passes, their order, their names, the CLI
contract, the output. Improvements wait until the two compilers agree everywhere.

---

## 1. Summary

1. Port file by file, in pipeline order, keeping Kotlin's names, function order and
   comments, so a reviewer can read both side by side.
2. After each stage, both compilers write the same canonical dump of what that stage
   produced. The two dumps must be identical (`cmp`) on the whole corpus and on every
   recorded test case before the next stage starts.
3. Data model: one struct per node kind (an Azora `pack`), one `std::variant` per node
   category (an Azora `variant enum`), and `switch` on the kind (an Azora `when`).
   No inheritance, no virtual functions.
4. Memory: every node of one compilation lives in that compilation's arena and is freed
   with it. Nodes point at each other with plain pointers and own nothing.
5. Build: `clang++` and `/usr/bin/make`, both from the Xcode command-line tools. Only the
   C++ standard library and macOS system calls (POSIX). No third-party code.
6. Tests: instead of rewriting 3,313 Kotlin tests by hand, the Kotlin suite is run once
   with a recorder that saves every compilation it performs (inputs and all outputs).
   Those recordings become source-in/output-out cases that the C++ binary runs.
7. First slice: lexer and parser, proven identical on `std`, every Engine package, the
   templates and every source the Kotlin test suite parses (§13).

---

## 2. What is being ported

`compiler/src/commonMain` holds 101 files and 83,386 lines (it grew from the ~67k quoted
earlier). The platform source sets add 1,652 lines, the native CLI 328 and the JVM CLI
1,159 (`Main.kt` and `Packages.kt`). AZLS is a further 6,287 lines.

| stage | Kotlin files | lines |
|---|---|---|
| 1. Lexer, parser, AST | `Token`, `Lexer`, `Ast`, `Parser`, `AzoraSyntaxVocabulary`, `SourceSymbolValidator`, `ScopeAccessRewriter`, `CallbackImplNormalizer`, `UseAsTemplate`, `CodePoints`, `Literals`, `LocalNames`, `AstDumper` | 19,148 |
| 2. AST passes | `MacroExpander`, `AstValidator`, `AstMapper`, `DebugInstrumenter`, `Suppressions`, `TraceSemantics` | 1,947 |
| 3. Library injection, packages | `StdlibInjector`, `DeclarationRenamer`, `StdSurface`, `AzStdlibLoader`, `StdlibSource`, `packages/*`, `MapCompat`, `Platform` | 5,397 |
| 4. Rewrites before analysis | `VariadicMonomorphizer`, `SerializationDeriver`, `ComparisonDeriver`, `CastDeriver`, `DisplayDeriver`, `SpecDefaults`, `ScopeQualifiedImplTargets`, `ReflectDecoExpander`, `InferredTypeArgs`, `ReturnedVariants`, `Witnesses`, `PackSpecializer`, `ImplParameterAligner`, `InlineCallables` | 5,132 |
| 5. Semantic analysis | `SemanticPipeline`, `SymbolTable`, `SymbolCollector`, `TypeResolver`, `CtfeEvaluator`, `ConstraintEvaluator`, `TypeFunctionEvaluator`, `BoundChecker`, `Bounds`, `DecoratorResolver`, `DecoratorMetadata`, `EffectChecker`, `AllocDropAnalyzer`, `Stability`, `SemanticFacts`, `SignatureAccessChecker`, `MemberTypeInstantiation`, `ComparisonDispatch`, `OptionalNarrowing`, `SlotPatterns`, `StepText` | 17,941 |
| 6. IR | `IrNode`, `IrGenerator`, `OwnershipCleanup`, `ElementReads`, `Mangling`, `IrSymbolCanonicalizer`, `IrOptimizer`, `IntegerSemantics` | 10,813 |
| 7. LLVM backend | `LlvmCodegen`, `LlvmUtf8Runtime`, `Binary128`, `WideInt`, `NativeAbi`, `FsAccess`, `OsAccess` | 9,103 |
| 8. Parts | `LlvmUnits` | 476 |
| 9. Driver, package interface, CLI | `Compiler`, `PackageInterface`, `diagnostics/*`, native `Main.kt`, JVM `Main.kt` + `Packages.kt` | 4,348 |
| 10. GPU shaders | `gpu/*` | 2,815 |
| 11. Interpreter | `IrInterpreter` | 2,451 |
| 12. WASM | `WasmCodegen`, `WasmAssembler`, `WasmHost`, `WasmUtf8Runtime` | 5,297 |

The largest single files: `Parser` 13,083, `TypeResolver` 7,914, `LlvmCodegen` 7,796,
`IrGenerator` 6,111, `WasmCodegen` 3,858, `Ast` 3,346, `StdlibInjector` 3,038.

Node categories that become variants: `Expr` (about 46 kinds), `Stmt` (41), `TopLevel`
(28), `TypeRef` (11), `IrType` (23), `IrExpr` (31), `IrStmt` (25), `IrTopLevel` (6), plus
small ones (`TypeAnnotation`, `MacroPattern`, `TypeFunctionStmt`/`Expr`,
`StringTemplatePart`, `ImportSpec.Selector`, `StringPart`, `IrTemplatePart`).

Kotlin patterns the port has to translate, counted in `commonMain`: `.copy(` 1,423;
`when (` 931; `is Expr.` 1,691; `is Stmt.` 1,040; `is TopLevel.` 775; `is IrType.` 644;
`is IrExpr.` 652; `is IrStmt.` 549; `error(` 523; insertion-ordered maps and sets 535;
hash maps and sets 225; `Regex(` 42; `open class` 1 (`AstMapper`, 9 subclasses);
interfaces 69 lines (mostly the `sealed interface` diagnostic hierarchy).

---

## 3. Rules for the port

- **No inheritance, no `virtual`.** Not for nodes, passes, diagnostics or exceptions.
- **Only the C++ standard library** and the operating system's own APIs (POSIX on macOS:
  `open`/`read`/`mmap`, `stat`, `opendir`, `posix_spawn`, `waitpid`). No third-party code,
  not even header-only.
- **Same behaviour.** The Kotlin compiler is the specification until it is retired. When
  the port finds a Kotlin bug, the fix is made in Kotlin first (asking the owner when it
  changes language semantics), then ported. The C++ never "fixes" silently.
- **No special cases to pass a comparison.** A mismatch is fixed by porting the behaviour
  that produces it, never by teaching the C++ the expected output.
- **Same names.** Kotlin `parsePrimary` stays `parsePrimary`; `TypeResolver` stays
  `TypeResolver`; files keep their names in snake case (`type_resolver.cpp`). Functions
  appear in the same order as in Kotlin. Comments come along.
- **No git.** The owner commits. Drift against the Kotlin sources is tracked by content
  hash (§12), not by history.

---

## 4. Name and layout

**Proposal** (the owner decides; alternatives in §15):

- Directory: `azora-lang/compiler-cpp/`, beside `compiler/` (Kotlin).
- Binary: `azora`, a drop-in for `azora.kexe` with the same commands, flags, exit
  statuses and output. Built at `compiler-cpp/build/release/azora` and
  `compiler-cpp/build/debug/azora`.
- C++ namespace: `azora`.

```
compiler-cpp/
  Makefile                 the only build description
  build.sh                 make -j<cores> wrapper; release | debug | asan | clean
  PORTED.txt               Kotlin file -> sha256 it was ported from -> C++ files (§12)
  src/
    base/                  arena, Span, Str, OrderedMap, text helpers, numbers,
                           unicode tables, fs, process, sha256, json (no compiler logic)
    frontend/              token.h lexer.cpp ast.h ast_dump.cpp
                           parser.h parser_decls.cpp parser_stmts.cpp parser_exprs.cpp
                           parser_types.cpp parser_macros.cpp ... (Parser.kt split by area)
                           macro_expander.cpp ast_validator.cpp ast_mapper.cpp ...
    stdlib/                stdlib_injector*.cpp declaration_renamer.cpp std_surface.cpp ...
    packages/              manifest.cpp azon.cpp resolver.cpp lockfile.cpp
    semantic/              symbol_table.cpp symbol_collector.cpp type_resolver*.cpp
                           ctfe_evaluator.cpp ... semantic_pipeline.cpp
    ir/                    ir.h ir_dump.cpp ir_generator*.cpp ir_optimizer.cpp ...
    backend/               llvm_codegen*.cpp llvm_units.cpp interpreter.cpp wasm_*.cpp
    gpu/
    diagnostics/
    driver/                compiler.cpp package_interface.cpp main.cpp (the CLI)
  oracle/                  the Kotlin side of the comparison (§8), compiled by Gradle
  tests/
    corpus.txt             source roots for the differential corpus
    cases/                 recorded test cases (§10)
  tools/
    parse-diff.sh          proves the first slice (§13)
    stage-diff.sh          the same for any later stage
    port-drift.sh          lists Kotlin files changed since they were ported (§12)
```

A big Kotlin file becomes one header and several `.cpp` parts. Each part's first lines
name the Kotlin line range it ports.

---

## 5. Build

- **Tools:** `/usr/bin/clang++` (Apple clang 21 here) and `/usr/bin/make` (GNU make 3.81).
  Both come with the Xcode command-line tools, which are already required for the
  Engine's clang builds. No CMake, Ninja or package manager.
- **Flags:** `-std=c++20 -Wall -Wextra -Werror -fno-rtti -fwrapv` plus
  `-MMD -MP` for header dependencies. Exceptions stay on (§6.6).
  - `debug`: `-O0 -g`; `asan`: debug plus `-fsanitize=address,undefined`;
  - `release`: `-O2`, then `-flto=thin` once measured.
- **Incremental:** one object per `.cpp`; `make -j10` rebuilds only what a change reaches.
- **Standard library sources:** embedded like Kotlin's `AzStdlibBundle`, by a shell step
  that turns `std/**/*.az` and `std/package.azon` into a generated `.cpp`. `AZORA_STDLIB`
  overrides the bundle, as today.
- **Version:** read from the same manifest Gradle reads (`BuildConfig.VERSION`), so both
  compilers report the same version and the package-interface key stays comparable.

Expected: a full release build of ~100k C++ lines in a few minutes on 10 cores, a one-file
change in seconds. Measured numbers go here as stages land.

---

## 6. Data model

### 6.1 Node categories

Kotlin:

```kotlin
sealed class Expr { abstract val line: Int; ... }
data class Call(val callee: String, val args: List<Expr>, override val line: Int, ...) : Expr()
when (e) { is Expr.Call -> ...; is Expr.Binary -> ... }
val e2 = e.copy(args = newArgs)
```

C++:

```cpp
struct ExprCall {                      // one struct per kind, Kotlin's fields in Kotlin's order
    Str callee;
    Span<const Expr*> args;
    int32_t line, column, length;
    Span<const TypeRef*> typeArgs;
    const Expr* receiver;              // nullable: nullptr
    mutable std::optional<Span<const TypeRef*>> inferredTypeArgs;   // Kotlin `var`, set by the resolver
};
struct Expr { std::variant<ExprIntLiteral, ExprDoubleLiteral, /* ... */, ExprCall, /* ... */> v; };
enum class ExprKind : uint8_t { IntLiteral, DoubleLiteral, /* ... */ Call, /* ... */ };  // same order
inline ExprKind kind(const Expr* e) { return ExprKind(e->v.index()); }

switch (kind(e)) {                     // Kotlin `when`
    case ExprKind::Call: { const ExprCall& c = std::get<ExprCall>(e->v); ... break; }
    ...
}
if (const ExprCall* c = std::get_if<ExprCall>(&e->v)) { ... }   // Kotlin `is` + smart cast

ExprCall c2 = std::get<ExprCall>(e->v);    // Kotlin copy(args = newArgs)
c2.args = newArgs;
const Expr* e2 = make(arena, c2);
```

Azora, later:

```azora
pack ExprCall { callee: String, args: [Expr&], line: Int, ... }
variant enum Expr { IntLiteral(ExprIntLiteral) ... Call(ExprCall) ... }
when e { .Call(c) -> ... }
```

The `ExprKind` enum and the variant list are written once, in the same order, with a
`static_assert` per kind that the index matches. A `switch` without `default` makes clang
report every kind a function forgot (`-Wswitch` under `-Werror`), which is the check
Kotlin's exhaustive `when` gives.

Each kind struct keeps exactly the Kotlin class's constructor fields, in order, including
`line`/`column`/`length`. That makes the per-kind dump function a straight list of fields,
and makes later passes port line by line. If the variant turns out wasteful (it is as
large as its largest kind), large rarely used kinds move their bulk behind one pointer;
that is a measured change, not a starting point.

`TypeRef`, `IrType`, `IrExpr`, `IrStmt`, `IrTopLevel` and the small categories follow the
same shape.

### 6.2 Everything else

| Kotlin | C++ | Azora |
|---|---|---|
| `data class` outside a sealed family (`FuncDecl`, `Param`, `PackField`, `IrFunction`) | plain struct, arena-allocated when shared | `pack` |
| `object NullLiteral : Expr()` | empty kind struct | variant without payload |
| `enum class X(val spelling: String)` | `enum class X` + `spelling(X)` switch function | `enum` (valued) |
| immutable `List<T>` in a node | `Span<T>`: pointer + count into the arena | `[T]` |
| mutable local `MutableList<T>` | `std::vector<T>` | `[T]` |
| `mutableMapOf`, `linkedMapOf`, `mapOf` (insertion order) | `OrderedMap<K,V>` (base/, vector of entries + hash index) | map |
| `HashMap`, `hashSetOf` | `std::unordered_map`/`set` only when never iterated into output; otherwise `OrderedMap` after the Kotlin side is made ordered (§6.7) | map |
| `Map` stored in a node (`FuncDecl.constDefaults`) | `Span<std::pair<K,V>>` in insertion order, linear lookup | `[(K, V)]` |
| `String` | `Str` (UTF-8 bytes in the arena) in nodes; `std::string` while building | `String` |
| `T?` for nodes | `const T*`, `nullptr` for null | `T?` |
| `T?` for values (`Int?`, `String?`) | `std::optional<T>` | `T?` |
| `Any` / `Any?` payloads (`Token.literal`, `NumericLiteral.value`) | a small `std::variant` of the types that actually occur | `variant enum` |
| `Pair`, `Triple` | named struct where it travels, `std::pair` inside one function | tuple |
| data-class `==` / `hashCode` | defaulted `operator==` for value types; generated `equal(a, b)` / `hash(a)` for node trees where Kotlin compares structurally (`Set<TopLevel>`, `Set<List<TypeRef>>`) | `derives (Equal, Hash)` |
| custom `equals` (`IrType.Named` ignores type arguments) | hand-written `equal` that does the same | impl |
| custom `toString()` (types, IR) | `toString(const IrType*)` free function, same text | `Display` |
| extension functions | free functions | `func T&.f()` |
| class with mutable state (`TypeResolver`, `Parser`) | struct with the same fields and non-virtual member functions | `pack` + `impl` |
| `object` holding functions (`MacroExpander`, `CodePoints`) | namespace of free functions | module functions |
| companion-object mutable state (`Parser` fragment macros, `IrType.aliases`, `AzStdlib` caches) | fields of one `CompileSession` passed by reference; no globals | a value passed along |
| lambdas | C++ lambdas | lambdas |
| `by lazy` | `std::optional` filled on first use | `lazy fin` |
| `StringBuilder`, string templates | `std::string` and `+=` | `String` building |
| `Regex` (42 small fixed patterns) | one hand-written matcher per pattern; no `<regex>` | string functions |
| `open class AstMapper` with 9 subclasses overriding hooks | `struct AstRewriter` holding optional hook functions + free `rewrite(...)` functions that walk the tree | a pack of function values |
| `interface Store`, `PackageHost`, `DiagnosticSink`, `LineIndex`, `SourceManager` | struct of `std::function` members, or the one concrete struct when there is only one implementation | pack of function values |
| `sealed interface AzoraDiagnostic` + 9 sub-interfaces | one struct with a `stage` enum and a `code` | `pack` + `enum` |

### 6.3 Passes

A pass stays the shape it has in Kotlin: a struct with the Kotlin class's fields and
member functions with the Kotlin method names. Kotlin's private helpers stay members, so
the C++ file reads like the Kotlin file. Kotlin functional chains
(`filter { }.map { }.firstOrNull { }`) become plain loops unless they are one line; loops
port to Azora directly.

### 6.4 Templates and macros

Allowed templates: `Span<T>`, `OrderedMap<K,V>`, `make<T>(arena, ...)` and the standard
containers. Nothing else is generic. No preprocessor macros except one
`AZORA_UNREACHABLE()` that throws (headers use `#pragma once`). No operator overloading
beyond `==`. No `auto` in function signatures.

### 6.5 Session state

Kotlin keeps some state in companion objects and caches it across compilations (the
parsed standard library, fragment macros, `IrType.aliases`). The C++ port puts all of it
in an explicit `CompileSession` (per compilation) and a `StdlibCache` (shared,
read-only after it is built). Behaviour, including when Kotlin resets each piece, is
reproduced exactly; only the storage moves.

### 6.6 Errors

Kotlin's `error()`, `require()` and `check()` throw, and 46 `catch` sites turn the
throw into a compilation failure or a skipped library. C++ does the same with one plain
struct:

```cpp
struct CompileFailure { FailureKind kind; std::string message; };   // IllegalState / IllegalArgument
[[noreturn]] void fail(std::string message);                        // Kotlin error()
```

It inherits from nothing (C++ can throw any type). Every Kotlin `catch` maps to a
`catch (const CompileFailure&)`. Where Kotlin catches *any* `Exception` and the message
of a Kotlin runtime exception reaches output (`NumberFormatException`,
`IndexOutOfBoundsException`), the C++ throws a `CompileFailure` carrying the same text.
Azora has `throw`/`try`/`catch`, so this ports as is.

### 6.7 Kotlin behaviour that must be reproduced

These decide whether outputs are byte-identical. Each gets one helper in `base/`, used
wherever the Kotlin relied on it:

- **UTF-16 positions.** Kotlin strings are UTF-16. `column`, `length` and every
  `String.length`/`substring` index count UTF-16 units. The C++ lexer decodes the source
  to UTF-16 once and scans that, exactly as Kotlin does; it stores lexemes as UTF-8.
  Elsewhere, text is UTF-8 and the few places where an index into non-ASCII text matters
  use UTF-16 helpers. Diagnostics and AZLS positions stay UTF-16, which is also what LSP
  uses.
- **Character classes.** The lexer calls `Char.isLetter()`, `isDigit()`,
  `isLetterOrDigit()`, which accept any Unicode letter or decimal digit. A generated table
  of the JVM's answers for all 65,536 UTF-16 units is committed under `base/` (data, not a
  library). The JVM is the reference because the tests run on it.
- **Number text.** `Double.toString()`/`Float.toString()` (shortest digits, Java's
  layout: `1.0`, `1.0E10`, `1.0E-5`) via `std::to_chars` plus Java's formatting rules;
  `toString(radix)`; parsing with `std::from_chars` (correctly rounded, as Java's is).
- **Integer arithmetic.** Kotlin `Int`/`Long` wrap; C++ signed overflow is undefined, so
  the build uses `-fwrapv` and CTFE uses explicit wrapping helpers anyway. Kotlin masks
  shift counts (`x shl 65 == x shl 1` for `Long`); C++ does not, so shifts go through
  helpers. `Double.toLong()` saturates and maps NaN to 0; C++ does not.
- **Hashes.** `String.hashCode()` (Java's UTF-16 polynomial) where its value reaches
  output; FNV-1a and SHA-256 as written in `LlvmUnits` and `PackageInterface`.
- **Iteration order.** Insertion-ordered maps and sets port to `OrderedMap`. Kotlin
  `HashMap`/`HashSet` iteration order depends on hash codes and table sizes, and already
  differs between the JVM and Kotlin/Native builds (the system-order difference noted in
  `INCREMENTAL_COMPILATION_2026_11_24.md`). Before a pass that iterates one into output is
  ported, the Kotlin side is switched to an ordered collection (owner's OK first, since
  output order can change), so both compilers have one defined order.
- **Sorting.** `sortedBy`/`sortedWith` are stable: `std::stable_sort`. Kotlin compares
  strings by UTF-16 unit, which orders characters above U+FFFF differently from UTF-8
  byte order; a `compareUtf16` helper is used where keys can hold such characters.
- **`toString()` leaks.** Some messages and keys contain a data class's default
  `toString()` (`Named(name=Int, args=[])`). Each one that reaches output gets an explicit
  C++ function producing the same text. The differential checks find any that are missed.

### 6.8 Self-hosting checklist

Every C++ construct used must have a direct Azora form: struct → `pack`, variant →
`variant enum`, switch → `when`, member function → `impl` method, `Span`/`vector` →
array, `OrderedMap` → map, `std::optional`/nullable pointer → `T?`, throw/catch →
`throw`/`catch`, arena → Azora allocator. Code review rejects anything without one
(inheritance, virtual, template metaprogramming, macros, operator overloading,
`reinterpret_cast` outside `base/`).

---

## 7. Memory ownership

- **One arena per compilation** (`CompileSession::arena`): a bump allocator over 1 MB
  blocks, freed in one step when the compilation ends. All AST nodes, IR nodes, spans and
  node strings live there. Nodes hold `const T*` to other nodes and own nothing, so
  sharing a subtree between an old and a new tree (what Kotlin's `copy()` does) costs
  nothing and frees nothing.
- **Nodes are trivially destructible**: their fields are numbers, enums, `Str`, `Span`,
  pointers and small variants of those. The arena never runs destructors. A
  `static_assert(std::is_trivially_destructible_v<...>)` guards every node struct.
- **The standard library cache** has its own arena that lives as long as the process (the
  CLI) or the server (AZLS). Compilations read its nodes and never write them. Where
  Kotlin mutates a node in place (`Call.inferredTypeArgs`, `contextualType`,
  `literalFactory`), the C++ does the same thing to the same node, so any sharing effect
  is reproduced; these few fields are listed in `ast.h`.
- **Pass state** (symbol tables, work lists, caches) uses ordinary standard containers
  owned by the pass struct and released by its destructor.
- **No reference counting, no garbage collector, no `shared_ptr`.**
- **Threads:** none at first. Parsing library files in parallel is possible later, but
  Kotlin parses them in order with a shared compile-time environment, so it would need
  that dependency handled first.

The Kotlin/Native CLI sets a 1 GB minimum heap. The arena's peak size is measured per
stage and reported with the timings.

---

## 8. How sameness is proven

**The oracle.** A Kotlin component under `compiler-cpp/oracle/` (compiled by Gradle as a
test-side source set of `:compiler`, run on the JVM) writes canonical dumps from the
Kotlin compiler. Its AST/IR dumper is generic: it walks any object by Java reflection and
prints every constructor property of every data class, in declaration order. That makes
it lossless without hand-written code per node, and it cannot silently skip a field that
someone adds later. The C++ side has one dump function per struct, which forces the C++
structs to hold every Kotlin field.

**The dump format** (text, one field per line so a mismatch can be read with `diff`):

```
(Expr.Call
  callee "println"
  args [
    (Expr.StringLiteral
      value "hi"
      line 3
      column 13
      length 4)]
  line 3
  ...
  receiver nil)
```

- strings quoted, with `\" \\ \n \r \t` and `\uXXXX` for other control characters and
  lone surrogates;
- `Long`/`Int` in decimal; `Double`/`Float` as their raw bits in hex, then Kotlin's text;
- `nil` for null; enums by name; `object`s by simple name;
- lists `[...]`, maps `{k v ...}` and sets `#{...}` in iteration order;
- a parse or compile failure dumps as `(Failure "message")`.

**Stage dumps.** Each stage has a dump of its result:

| stage | compared output |
|---|---|
| 1 | AST of each file, or its parse error |
| 2-4 | the program handed to `SemanticPipeline`, or the failure |
| 5 | errors and warnings in order, then the analysed program |
| 6 | IR (generic dump) before and after `IrOptimizer` |
| 7 | LLVM text |
| 8 | every part file and `link.txt` |
| 9 | CLI stdout, stderr and exit status; `interface.txt` and parts |
| 10-12 | shader output, interpreter output, WAT |

`tools/stage-diff.sh <stage>` builds both sides, dumps the corpus and the recorded cases
through that stage, compares every pair with `cmp`, and exits non-zero on the first
difference set (it lists all of them). A stage is done when it reports zero differences.

**The corpus** (`tests/corpus.txt`): `std/`, `azora-engine/packages/*/src`, the Engine
templates, every example under `azora-lang/examples`, and the sources the Kotlin test
suite feeds the compiler (recorded, §10). Generated or staged copies (`build/`,
`.azora-build/`) are excluded.

---

## 9. Port order

Each stage starts only when the previous one reports zero differences. The C++ always
runs the whole pipeline up to the stage under test; there is no mixing of Kotlin and C++
halves.

0. **`base/`**: arena, `Span`, `Str`, `OrderedMap`, UTF-8/UTF-16 helpers, Unicode
   tables, number text, wrapping arithmetic, sha256, fs and process wrappers, JSON.
1. **Lexer, parser, AST** (19k Kotlin lines). The first slice (§13).
2. **AST passes**: `MacroExpander`, `AstValidator`, `AstMapper` → `AstRewriter`,
   `DebugInstrumenter`.
3. **Injector and packages**: `AzStdlibLoader`, `StdlibInjector`, `DeclarationRenamer`,
   `StdSurface`, `packages/*`. Done when the injected program matches.
4. **Rewrites before analysis**: derivers, `VariadicMonomorphizer`, `ReflectDecoExpander`,
   `InferredTypeArgs`, `ReturnedVariants`, `Witnesses`. Done when the program given to
   `SemanticPipeline` matches, for every corpus entry and recorded case.
   **Result (2026-11-25): done.** `STAGE=frontend tools/parse-diff.sh` compiles each
   entry up to the first `SemanticPipeline.analyze` (C++ `Compiler::frontend`, Kotlin
   `Compiler.compile` stopped by the inert `FrontendProbe`): **3,862 files, 0 different**
   on `tests/corpus.txt` (2,852 programs, 1,010 Failure error lists, no crashes; 3,693
   recorded test sources) and **158 files, 0 different** on `tests/corpus-engine.txt`
   with the Engine packages as libraries (112 programs, the largest dump 51 MB). Stages
   2 and 3 were compared per file first (`STAGE=macros|validate|inject`, 0 different).
   Speed is on par with a cold JVM (61 s vs 59 s; Engine 18.5 s vs 18.1 s): the time is
   the library sources re-parsed on every compile, which both compilers do, and the C++
   parser is untuned (bounds-checked token access, UTF-8 decoding, string-keyed lookups).
5. **Semantic passes**: `SymbolTable`, `SymbolCollector`, `CtfeEvaluator`,
   `TypeResolver`, the checkers, `SemanticPipeline`. The largest stage; it is split into
   sub-steps in pipeline order with the errors list compared at each.
6. **IR**: `IrNode`, `IrGenerator`, `OwnershipCleanup`, `Mangling`, `IrOptimizer`.
7. **LLVM backend**: `LlvmCodegen` and its helpers. Done when the `.ll` text is identical
   for every case, debug and release. At this point the C++ compiler can build programs.
8. **`LlvmUnits`**: identical parts for `--parts=N`.
9. **Driver, `PackageInterface`, CLI**: `Compiler`, diagnostics rendering, the
   `azora.kexe` command set (`check`, `compile llvm` with `--parts`/`--interface`,
   `analyze`, `hover`, `definition`, `complete`, project `build`/`play`). Done when
   `tools/qualify-native-compiler.py` and `tools/qualify-native-semantic.py` pass with
   `--compiler` pointing at the C++ binary, and `azora-engine/tools/build.sh` produces
   identical parts and interface files for every template.
10. **GPU shaders** (`compile metallib|spv|dxil`), needed before Engine builds can drop
    `azora.kexe`.
11. **Interpreter** (`run`, `test`, `repl` without LLVM). See decision 5 in §15.
12. **WASM backend**: identical WAT and host glue.

**Switch-over points.**

- After 10: Studio's `toolchain-env.sh` and the Engine's `build.sh` can point at the C++
  binary. `azora.kexe` stays buildable as the fallback.
- After 12 and the AZLS move (§11): the Kotlin compiler is no longer needed by any tool,
  and the owner decides when to retire it.

---

## 10. How the tests move

The suite has 3,219 compiler tests and 94 AZLS tests. Most of them compile an inline
Kotlin string and assert on the result: 861 `compile(` calls, 300 `interpret(`, 276
`parse(`, 92 `assertRejected(`, plus WAT and LLVM execution helpers. Rewriting them by
hand would take long and would test a translation of the tests, not the tests.

**Recording.** A recorder hook in the Kotlin compiler, inert unless
`AZORA_RECORD_CASES=<dir>` is set, saves every compilation the suite performs:

- inputs: the source, the library sources, every option (`release`, `debug`, defines,
  exports, warnings-as-errors, `reportExperimental`);
- outputs: success or failure, errors, warnings, rendered diagnostics, the IR dump before
  and after optimization, LLVM text, WAT, backend errors, and the interpreter's output
  when the test ran it;
- the test (suite and name) that made it, for tracing a failure back.

Each distinct input becomes `tests/cases/<sha256>/` with `input.az`, `libraries/`,
`options.txt` and one file per output. Direct `Parser(...)` uses in tests are recorded
the same way at the lexer, as AST cases.

**Running.** `azora __case <dir> --through=<stage>` (a hidden command) runs one case up to
the current stage and compares the outputs that stage covers; `tools/run-cases.sh` runs
all of them in parallel. A case passes only when every covered output is identical.

**Why this covers the Kotlin assertions.** A Kotlin test passed with exactly these
outputs, so any C++ output identical to them passes the same assertion. Comparing whole
outputs is stricter than the original assertions, which is what a port needs.

**What recording does not cover.**

- Unit tests of internal functions that never compile a program (`WideInt`, `Binary128`,
  `Mangling`, `Azon`, `Manifest`, `CodePoints`, `LlvmUnits.split`, the GPU emitters).
  They are listed while recording and ported by hand as small C++ tests in
  `tests/unit/`, or as hidden `azora __tool` commands with text in and out.
- End-to-end execution (`lli`, clang, node). Identical LLVM text and WAT already imply
  identical behaviour; a smaller set of run cases (source, expected stdout and exit
  status) is kept to check the toolchain path itself.
- AZLS behaviour, covered by protocol cases (§11).

**After Kotlin retires.** The recorded outputs become the C++ compiler's golden files.
`tools/run-cases.sh --update` rewrites them after an intended change, and the diff is
reviewed like code. New tests are written directly as cases.

---

## 11. AZLS, the IDEA plugin, Studio, the Engine, the playground

- **IDEA plugin.** It does not link the compiler. It bundles `azls-all.jar` and runs it as
  an LSP server over stdio; its own lexer and formatter are IntelliJ code and stay Kotlin.
  Only the launch command and the bundled file change, once AZLS is native.
- **AZLS** links the Kotlin compiler as a library: `Lexer` for highlighting, `Parser` for
  outlines, `Compiler.analyze` for diagnostics, hover, definition and completion,
  `AzStdlib` and `packages` for the workspace.
  1. During the port it stays Kotlin, on the Kotlin compiler. Nothing changes.
  2. After stage 9 the C++ binary already speaks the native CLI's JSON protocol
     (`analyze`, `hover`, `definition`, `complete`, protocol 1). It gains a persistent
     `serve` mode that keeps the standard library parsed between requests. AZLS gets a
     second backend that talks to that process, and its 94 tests run against both
     backends.
  3. AZLS itself is ported to C++ as `azora lsp` (LSP over stdio, with the small JSON
     reader in `base/`). The plugin then bundles the binary instead of the jar.
- **Studio** runs `azora.kexe` through `native/tools/toolchain-env.sh`; it switches by
  path after stage 10.
- **Engine `build.sh`** uses `AZORA_COMPILER_BIN`; same.
- **Web playground** (`webMain`, Kotlin compiled for the browser with the interpreter).
  C++ to WebAssembly needs a libc for wasm, which macOS does not have and which would be
  third-party. The playground keeps the Kotlin build until a self-hosted Azora compiler
  can compile itself with Azora's own WASM backend.
- **iOS** (`iosMain`): the C++ compiler builds for iOS with the same clang.

---

## 12. Keeping up with Kotlin during the port

The Kotlin compiler keeps changing while the port runs. Without git, drift is tracked by
content:

- `PORTED.txt` records, for each ported Kotlin file, the SHA-256 of the text it was
  ported from and the C++ files that hold it.
- `tools/port-drift.sh` hashes the current Kotlin files and lists every ported file whose
  hash changed. Those changes are ported next; the differential checks show whether any
  were missed.
- Proposed working rule once a stage is ported: a change to a ported Kotlin file is
  ported to C++ in the same piece of work (decision 4 in §15).

---

## 13. The first slice: lexer and parser

**Scope.** `Token`, `Lexer`, `Ast`, `Parser` and what `Parser` calls:
`SourceSymbolValidator`, `ScopeAccessRewriter`, `CallbackImplNormalizer`,
`UseAsTemplate`, `AzoraSyntaxVocabulary`, `CodePoints`, `Literals`, `LocalNames`, and the
`TypeFunctionCall`/`NamedTypeMacroCall`/`ModuleQualifiedSymbol` helpers in `Ast.kt`.
Plus `base/` and the dump.

**The parse protocol** both sides follow, which is the one a compilation uses:

1. Reset the parser's fragment-macro table (`Parser.resetFragmentMacros`). Parse the
   standard library's files in the loader's order with one shared compile-time
   environment (lists, enums, list scopes), as `AzStdlibLoader.parse` does.
2. Parse the Engine packages as one library set, the way `StdlibInjector.create` parses
   a compilation's additional sources: every `packages/*/src` file in sorted path order
   (the native CLI's walk), sharing one copy of the environment from step 1. Fragment
   macros accumulate; two Engine files declare them (`@children`, `@applyKey`).
3. Parse every other file the way a compilation parses its entry: a fresh copy of the
   environment from step 1, and the fragment-macro table as it stood after step 2 for
   templates and examples (they compile against the Engine), or after step 1 for the
   recorded test sources.
4. Write each file's AST dump, or its failure message, to `<out>/<path>.ast`.

**Corpus.**

- `std/` (50 files, 16,547 lines);
- `azora-engine/packages/*/src` (129 files, 49,720 lines);
- `azora-engine/templates/*` (29 files);
- `azora-lang/examples`;
- the sources the Kotlin suite lexes, recorded once with the recorder hook
  (`AZORA_RECORD_SOURCES=<dir>`), deduplicated by hash. These include the inline test
  programs, intentionally malformed ones, and compiler-generated snippets. A snippet that
  only an internal parse accepts fails the ordinary parse the same way on both sides,
  which is still a valid comparison of the error message.

**Deliverables.**

- `compiler-cpp/src/base/*`, `src/frontend/*` and `azora __parse-dump <list> <out>`.
- `compiler-cpp/oracle/AstOracle.kt`: the reflective dumper and the same protocol on the
  Kotlin side, run as a Gradle test that does nothing unless its environment variable is
  set.
- The recorder hook: a few lines in `Lexer` (and later `Compiler.compile`), inert by
  default. This is the only change to Kotlin compiler code in the slice.
- `compiler-cpp/tools/parse-diff.sh`: builds the C++ binary, runs both dumps over the
  corpus, compares every file with `cmp`, prints the mismatches and the timings, and
  exits non-zero on any difference.

**Speed report.** Time to lex and parse the whole corpus, excluding dump writing:

- Kotlin on the JVM, first pass (cold) and best of five (warm);
- C++ release build, same corpus;
- the Kotlin/Native compiler has no parse-only command, and adding one costs a 35-minute
  link, so it is left out unless the owner wants it.

**Done** when `parse-diff.sh` reports zero differences on the whole corpus and the numbers
are written into this section.

**Result (2026-11-25): done.** `tools/parse-diff.sh` over the whole corpus: **3,912 files,
0 different** - std (50), Engine packages (129), templates (29), examples (11) and 3,693
distinct sources recorded from the Kotlin suite (`tools/record-sources.sh`, 3,187 tests).
659 of the files are parse failures (intentionally malformed test snippets, expression
fragments, stale examples); their messages match too. The C++ side is also clean under
AddressSanitizer and UBSan on the whole corpus.

Speed, lex + parse of the whole corpus (130k lines, 4.4 MB), excluding reading and dump
writing, Apple M1 Max:

| | time |
|---|---|
| Kotlin on the JVM, first pass (cold) | 1.18 s |
| Kotlin on the JVM, best of five (warm, JIT-compiled) | 0.22 s |
| C++ release (`-O2`), any pass | 0.27 s |

So a command-line run, which is always a cold run, parses about 4x faster in C++; a
long-lived warm JVM is about as fast as the C++ parser today. The C++ parser has had no
tuning beyond the obvious (no zero-filled arena blocks, tokens handed out in place); a
profile puts 21% in the lexer (0.065 s alone) and 29% in top-level `inline for`
expansion, which re-lexes and re-parses each iteration as Kotlin does. Further tuning is
deferred until more of the pipeline is ported, where it can be measured end to end. Thin
LTO made no difference and is off.

Build: the 31k lines of C++ so far build from scratch in 11.5 s (10 cores) and rebuild
after a one-file change in 1.3 s.

Deviations from the deliverables above: the hidden command is `azora __parse-job <job>`
(the oracle's job protocol: `reset`, `std`, `libraries`, `entries`, `lex`) rather than
`__parse-dump <list> <out>`, so one job file drives both sides; the recorder is
`AZORA_RECORD_SOURCES` in `Lexer`'s initializer.

---

## 14. Risks

- **Moving target.** The Kotlin compiler changes daily. Mitigated by §12 and by porting
  in pipeline order, so only one stage is ever out of date.
- **Hidden behaviour.** Iteration order, `toString()` text, exception messages, number
  formatting, UTF-16 indices (§6.7). The byte-level comparison is what finds them; each
  one found becomes a `base/` helper or a Kotlin fix.
- **Nondeterminism in Kotlin itself.** The JVM and Kotlin/Native builds already disagree
  in at least one place. Those spots are fixed in Kotlin first, otherwise there is no
  single expected output.
- **Scale.** About 85k lines of Kotlin become roughly 100-120k lines of C++. The
  semantic stage (18k lines, `TypeResolver` alone 7.9k) dominates; it is split into
  sub-steps with their own comparisons.
- **Memory.** Node sizes under `std::variant` are measured in the first slice; boxing
  large kinds is the fallback.
- **Two compilers to maintain** until the switch-over. Kept short by not adding features
  to the C++ port that Kotlin lacks.

---

## 15. Decisions for the owner

1. **Approve this plan.**
2. **Name and layout.** Recommended: `azora-lang/compiler-cpp/`, binary `azora`,
   namespace `azora`. Alternatives: `azora-lang/cpp/`; binary `azc`.
3. **Node representation.** Recommended: struct per kind + `std::variant` per category
   (maps to `pack` + `variant enum` + `when`). Alternative: one struct per kind that
   starts with a shared header holding a kind tag, cast by tag (maps to `pack` + kind
   field + `when`, but needs pointer casts that Azora would write with `unsafe`).
4. **Working rule during the port.** Recommended: no freeze of Kotlin work; a change to an
   already ported Kotlin file is ported in the same piece of work. Alternative: freeze the
   Kotlin compiler per stage while that stage is ported.
5. **Interpreter.** Recommended: port it (stage 11); `run`, `test` and the REPL keep
   working without clang. Alternative: drop it and make `run`/`test` compile through
   LLVM and clang.
6. **Kotlin-side changes this needs:** the recorder hooks (inert by default); the oracle
   under `compiler-cpp/oracle/`, which takes one line in `compiler/build.gradle.kts`
   adding that folder to the `desktopTest` sources; and switching output-relevant
   `HashMap`/`HashSet` iteration to ordered collections when a stage reaches them.
