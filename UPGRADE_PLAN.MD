We can have scope in global scope, like scope vhs, it will act just like realm did!!!

I think you can probably drop realm if your module/import system already gives you all of this.
Your proposal already covers the main reasons namespaces exist:

module main

import std.container.list.*

func main() {
var x: List<Int> = ...
var y: std.container.list::List<Int> = ...
}

You have both convenient imported access and fully qualified access. Then aliases give you namespace-like grouping:

import std.container.list.* as std
import std.container.map.* as std

func main() {
var x: List<Int> = ...
var y: Map<String, Int> = ...
}

And this is especially nice:

import std.container.[list.*, map.*] as std

Now std effectively becomes a local namespace view assembled by imports.
The key design question is what realm gives you that modules cannot. If its purpose was only:

realm graphics {
...
}

for grouping symbols, then modules already solve that structurally:

graphics
├── color
├── image
├── texture
└── shader

with:

import graphics.[color.*, image.*] as gfx

var c: gfx::Color
var i: gfx::Image

I actually prefer this because there is only one hierarchy to understand: modules.
One detail I would explicitly support is alias merging, as your example implies:

import std.container.list.* as std
import std.container.map.* as std

These should merge into one alias scope rather than conflict:

List
Map

But collisions should be diagnosed:

import foo.vector.* as math
import bar.vector.* as math

If both expose Vector, then:

math::Vector

should be ambiguous unless explicitly renamed.
You could support:

import foo.vector.Vector.* as FooVector
import bar.vector.Vector.* as BarVector

or whatever selective-import syntax you settle on.
There is also a very elegant consequence: an alias doesn't need to represent one module. It can represent an import namespace.

import std.container.[list.*, map.*, set.*] as collections
import std.math.[vec.*, mat.*, quat.*] as math

Usage:

var users: collections::List<User>
var ids: collections::Set<Int>

var position: math::Vec3
var rotation: math::Quat

That is arguably more flexible than a declared realm, because the consumer chooses how the API is grouped.

import std.serializer.[Serializable]
import std.serializer.Serializable
// use this to import only one symbol from that file or some symbols
import std.x.* without Y
import std.x.* without [Y, Z]
import std.x.[A, f.* without [G, H], u.P] // we use without keyword to remove some symbols from import
// without only available on full file import using *
Make sure you cover all cases
And remove "protected" keyword and its implementation.
Remove "exposed import", i do not want reexport anymore
add "ambient" keyword, replacement for "exposed confined"
add "resident" keyword - read MODULES_DIP.MD updated and update with last changes

One more rule must be, in a public module like "module a" we can have "exposed func f1()" or other exposed types, what this means? when we "import a" it is ok to write like this, f1 will automatically be imported, no need to import it explicitly or use .*

* allow underscores in names, maybe user wants to name it f_1() or something, also update azls and azora idea plugin to allow them
* fix in compiler, azls and azora idea plugin, func and prop that returns a Type must start mandatory with big letter
* await(handle) - wrong syntax ;; await handle - correct syntax - fix in compiler
 
* factory Alloc { when we want only static access , no self, no fields - no constructor available 
- add using keyword, currently no use of it, fully remove use keyword
- we also use ftom now on . for static access, and we will keep :: only for import as and scope access and associated types access ans serial field access
- auto registration of compose components (om azora-engine)
- on impl non static methods mandatory self context declaration, remove impl Name:: and all static methods are now inside normal impl blocks but without self context declaration
- I updated compose macro and applyKey macros, make sure they work as they are
* var x: Array<T> = .() * count - it must also understand the context even if static call
* remove fill function from array from compiler and arrayOf, we will use .() * 10 - it will fill 10 elements with 0 or .(5) * 10, it will fill 10 elements with value 5
* we will always have alloc T*() instead of alloc T(), because we need to allocate a pointer or alloc T^() and when we write fin x: T* = alloc .() it will infer to alloc T*()
* pack ArrayList<T> derives Clone binds ::data by capacity { - "binds ::data" wil bind the data field to the constructor, when we have this what the compiler should do, it must allow us to allocate through data, ofc without using alloc keyword, because alloc keyword will allocate multiple lists. ::data must be mandatory a pointer, and the result will be .() * 8 will allocate 8 positions inside data ;; by capacity means that when we say *() * 8 - it will allocate 8 elements in pointer and capacity will equal to 8
* no more impl oper reverse.. - we will keep only oper.. which will mean both normal and reverse
* no more "impl Number for Ty::" ;; impl Number for Ty { ... } and factory spec Number {  prop rank: Int } that is how we know we have type-side implementation  Make sure a macro like this works even if we do not use it, just add a test for this case: macro @applyKey => { // this should be automatically deepinline because we are in a macro
*     fin key = reflect<Scope>.find("key")
*
*     return when {
*         key == null => ""
*         key.type != String => assert { "`key` must be String" }
*         else => """
*             if key == "" {
*                 positionalKey(anchor.parent, ${Self.typename.lowercase})
*             } else {
*                 key
*             }
*         """
*     }
* }

* self.offset = 0
* self.allocCount = 0
* self.peakUsage = 0
  can be written as self.[offset, allocCount, peakUsage] = 0 // 0 will go to all of them
  or self.[a, b, c] ‎ =  [1, "2", true] - just a nice sugar and ofc, if each element on newline, comma can be avoided
- bug "self.offset = offset - size" self must be required everywhere like this "self.offset = self.offset - size"
- make sure x++ and x-- works
- no more impl ctor and impl dtor please, they must be inside impl block
* in {
*     assert capacity > 0 { "StackAllocator capacity must be positive" }
* } scope { self! -> // this old receiver should not be allowed anymore
* (*freeStack)[i] - wrong syntax, for pointer deref we must use freeStack.*[i]
* var freeStack: Int* = alloc Int[0] - is wrong syntax, we must use var freeStack: Int* = alloc Int() // or alloc .() // or alloc .() * 3 or alloc Int() * 4
* spec Allocator must be bridge spec Allocator because we want this " var p = alloc Point(1.0, 2.0) in arena" which is a compiler feature, but only works if connecting with bridge ofc
* - func count<T>(arr: Array<T>, target: T): Int where T: Order and func count<T: Order>(arr: Array<T>, target: T): Int - both avaliable, just like in rust
* only one impl per file please, maximum one impl

// STEPS - TODOS

## Ground rules for this upgrade

**R1 - Additive first, removals last.** The stdlib is embedded in the compiler
(`:compiler:generateAzStdlib` bakes `std/*.az` into `AzStdlib.kt`) and injected into
*every* compile. Any parser change that rejects an old form breaks the whole test suite
until `std/` is migrated. So every step below is tagged:

- `[ADD]` - new syntax/behaviour, nothing breaks; land immediately.
- `[DROP]` - removes an old form. Implement the diagnostic behind
  `CompilerOptions.legacySyntax` (default `true` during the upgrade). The removal is
  *written* in its own step, but only *enforced* when Phase 9 flips the flag.
- `[FIX]` - behaviour change with no syntax surface; land immediately.

**R2 - stdlib and stdlib-dependent tests are frozen** until Phase 9. Known old-form uses
that Phase 9 must rewrite (already surveyed):
`impl ctor/dtor` -> `std/container/map.az:256,262,492,498`, `std/container/map.az:701`,
`std/container/queue.az:39`; `impl X::` -> `std/reflection.az:234`;
`await(t)` -> `std/concurrency/async.az:84-85`; `arrayOf` -> 12 sites;
`realm` -> 0 sites in `std/` (good), 16 test files, `azls/`, `README.md`, 12 DIPs.

**R3 - One PR/commit per step.** Each step lists *Touch* (files), *Do*, and *Accept*
(the test that proves it). Add the test in the same commit.

**R4 - Three surfaces per syntax change.** compiler -> `azls/` -> `/Users/milkybit/azora/azora-idea-plugin`.
The plugin's `AzoraLanguageFacts` keyword sets mirror
`frontend/AzoraSyntaxVocabulary.kt`; keep them in sync in the same step, never later.

**R5 - Docs are part of the step.** `DIPs/*.MD` + `README.md` update with the code, and
per [[Azora docs layering]] describe what the language *is*, never what it was.

---

## Baseline (measured 2026-08-18, commit 40c51ea)

**`./gradlew :compiler:desktopTest` is 1608 failed / 1734 on a clean tree.** Not a
regression - `std/` is already written ahead of the compiler, and **one** parse error
takes the whole suite down:

```
Failed to parse standard library source allocator/allocator.az
(bundled with the compiler): Expected '{' after ctor, got 'scope' at line 124
```

That is `std/allocator/allocator.az:121-126` - a `ctor[self: Self!](capacity: Int)` with
an `in { … } scope { … }` body and no `self! ->` arrow, i.e. exactly **S5.5**. Parsing
aborts at the first error in a file, so there are almost certainly more behind it; each
one surfaces as its predecessor is fixed.

**Consequences for the plan:**

- **R1 is half wrong.** Old forms are not what breaks the suite - *missing new forms* are.
  The `legacySyntax` flag still earns its keep for the `[DROP]` steps, but the stdlib is
  not "frozen behind" the compiler, it is ahead of it.
- **"full suite green" is not an available signal yet.** Until S5.5 lands, every step's
  *Accept* has to be its own `--tests '*ThatTest*'` run, plus "failure count did not rise
  above 1608". Record the count in each commit message.
> ## ✅ THE STDLIB PARSES (2026-08-18)
>
> **1319 failed / 1911, down from 1608 / 1734. 289 tests newly passing, zero
> regressions.** Every `.az` file in `std/` now gets through the parser, so the queue
> below is closed and the *shape* of the work changes: what remains is **semantic and
> codegen**, not syntax.
>
> **The remaining 1319, by cause:**
>
> | count | cause |
> |------:|-------|
> | ~1692 refs | `undefined function 'println'` (and `print`, `arrayOf`, `listOf`, `writeText`, …) |
> | 75 | `macro 'std__arr' is not defined` (also `std__vec`, `std__map`, `std__set`) |
> | 76 | `Function 'ArrayList_ctor' already defined` |
> | ~23 | assorted individual failures |
>
> ### RESOLVED: there is no `std` qualifier (2026-08-18) - **1608 -> 221 failures**
>
> The qualifier is gone from the language. `std/` and the tests were swept; the
> compiler side turned out to be **eleven dead spellings** left behind by it.
>
> `println` used to be declared in a realm, so it reached IR as `__std_println` and
> every backend matched that literal. It is now a plain `println` in `module std.io`,
> which canonicalizes to `println` - so the literals matched nothing and every program
> that printed failed at **run time** with `Undefined function: println`. That single
> class of bug was **~987 tests**.
>
> **Fixed by deleting spellings, not by adding new ones:**
> - `symbolDenotes(symbol, local)` now accepts all three forms - bare `println`,
>   frontend `a__println`, IR `__a_println` - so a pass asks for a declaration by its
>   *local* name, never by a spelling that depends on where the library declares it.
> - `ir/Intrinsics` names the compiler-provided declarations in one place
>   (`print`, `println`, `toString`, `Array_fill`, `concurrency_cancel`).
> - 11 backend sites (`IrInterpreter`, `LlvmCodegen`, `WasmCodegen`) plus 6
>   frontend/semantic sites (`IrGenerator`, `TypeResolver`, `CtfeEvaluator`,
>   `DisplayDeriver`, `ReflectDecoExpander`) now go through it.
> - Three of the literals named declarations the library no longer has **at all**
>   (`convert__toString`, `concurrency__async`, `concurrency__channel`) - removed as dead.
> - `SerializationDeriver`'s last literal, `"std.container.list"`, became a
>   `listModule:` argument on `@Derive`, beside the `providerModule`/`conversionModule`
>   it already reads from the library. Grepping the compiler for a qualified library
>   spelling now returns **nothing**.
>
> **Scope mangling is `__a_b`, verified end to end** (`ScopeSymbolManglingTest`):
> `scope a { func b() }` -> `__a_b`, `scope a { fin C }` -> `__a_C`,
> `scope x { scope y { func z } }` -> `__x_y_z`, and no IR symbol carries a doubled
> separator. The frontend spells it `a__b` *before* `IrSymbolCanonicalizer` runs - that
> is the internal spelling `ScopeNamespaceTest` asserts, and both layers are now
> labelled so neither reads as the wrong convention.
>
> **Then ctor overloads (2026-08-18): 221 -> 208.** `impl ArrayList<T>` declares three
> `ctor`s and all three mangled to `ArrayList_ctor`. The cause was mine: S6 appended the
> repetition as an ordinary trailing `count` parameter, which made
> `ctor[…]() * count` indistinguishable from `ctor[…](initial: Int)` - same arity, same
> symbol. Fixed by making the repetition part of the ctor's *identity*
> (`FuncDecl.isRepeated`), and keying both the emitted symbol and the factory on
> `(arity, repeated)` through one shared rule in `ir/Mangling` (`ctorSymbol`,
> `ctorFactorySymbol`). **A type with one ctor keeps the plain `Type_ctor`**, so nothing
> moved for the common case. Pinned by `CtorOverloadTest`.
>
> That revealed the second error hiding in the same message: `std/container/list.az`
> both `derives Clone` **and** hand-writes `impl Clone for ArrayList`. The hand-written
> one is the right one - an `ArrayList` owns a `T*` buffer, so a derived field-copy would
> duplicate the pointer rather than the elements, which its own doc comment says. Dropped
> the `derives`.
>
> **The remaining 208** are ordinary semantic work with no single systemic cause:
> undefined library functions (62), `no member on Array<Any>` (10), `compiler bridge pack
> cannot be constructed` (8), and a long tail - plus a handful of tests whose *subject*
> the `std` sweep removed (they assert a qualifier survives parsing, or that an
> unqualified name fails) and which now need rewriting against the new truth.
> `StdlibInjector.register` (line ~924) maps a declared `std__println` *down* to
> `println`, because declarations used to live in `realm std`. They no longer do -
> `std/io/io.az` declares a plain `println` - so the mapping has nothing to fire on,
> while ~1700 references in tests/examples/README still say `println`. Either:
>   - **(a)** register the reverse too (`println` in a `std.*` module also answers to
>     `std__println`), making `` mean *the library root* rather than a realm. One
>     small change in `register`; keeps every existing reference working.
>   - **(b)** drop `` everywhere, per "we do not have `` anymore". Correct by
>     the new model, but it is the Phase 9 test migration - ~1700 sites.
>
> The same question decides the macro failures: `@arr` mangles to `std__arr`.

- **The stdlib is a queue of missing features, one error deep at a time.** Fixing the
  gate reveals the next gate; work the queue until the suite comes back, taking each step
  out of phase order as the stdlib names it. **This queue is now closed.** Progress:
  - `allocator/allocator.az:124` `Expected '{' after ctor, got 'scope'` -> **S5.5, done**
  - `allocator/allocator.az:165` `Unexpected token '++'` -> **S7.3, done**
  - `allocator/allocator.az:223` `Expected member name after '.', got '['` -> **S7.1, done**
  - `allocator/allocator.az:306` `Expected '{' after dtor, got '('` -> **fixed in `std/`**,
    not in the compiler: a `dtor` takes no parameter list, so `dtor[self: Self!] {` is the
    spelling and the `()` was dropped
  - `allocator/allocator.az:546` `got 'factory' at top level` -> **fixed in `std/`**:
    `solo pack Alloc` + `impl Alloc { … }`, no new declaration form (see S5.6)
  - **`allocator/allocator.az` now parses end to end.** The queue moves on:
  - `concurrency/async.az:114` `got 'scope' at top level` -> **S3.1, done**
  - `config.az:19` `Expected '{', 'realm', or 'if' after 'deepinline'` -> **S3.1, done**
    (the `deepinline scope { … }` block form)
  - `container/core.az:24` `Expected '{' after 'macro @arr', got '['` -> **S8.0, done**
    (braceless single arm, then `::` through a module path, then sigil arms - three
    gates on the same file)
  - `container/deque.az:189` `'hasAnnot' requires an explicit reflect<receiver>`
    -> **S3.1b, done**. Not a parse error, and not a stdlib bug: removing realms removes
    `realm std`, so a `` qualifier names nothing and the bare spelling is right.
  - `container/list.az:163` `Expected '{' after ctor, got '*'` -> **S6.1/6.3/6.4, parsed**
  - `algorithm/algorithm.az:60` `Unexpected token '+'` -> same step (`.() * a.size + b.size`)
  - `container/list.az:172` `Unexpected token 'inline'` -> **S8.1b, done**
  - **`container/list.az` now parses end to end.**
  - `container/map.az:44` `Expected name after '.', got '['` -> **S1.2, done**
  - `core.az:85` `Expected '{' after 'variant enum Option', got '<'` -> **S1.9, done**
  - `core.az:91` `Expected 'prop', 'func', … in impl block` -> **fixed in `std/`**: the
    type computation moved out of the `impl` to top level, where it already belonged
  - `core.az:90` `'deepinline prop Nullable' takes no value parameters` -> **fixed in
    `std/`**: the compiler's rule stands - arguments make it a `deepinline func`
  - `core.az:91` `Expected '{' after 'when' in a type computation` -> **S1.10, done**
  - **`core.az` now parses end to end.**
  - `io/io.az:29` `got 'factory' at top level` -> **fixed in `std/`** as
    `scope fmt { fin newline = "\n" … }` - exactly the `fin`-only scope of S3.1
  - `primitive.az:135` `got 'factory' at top level` -> **S5.7, done as `direct spec`**
  - `primitive.az:147` `Expected 'prop', 'func', … in impl block` -> **S5.1 (bindings)**
  - `primitive.az:170` `Decorator implementation lists require 'for Target'` ->
    **impl target lists**
  - `serializer.az:762` `Expected test name string, got 'scope'` -> **`test scope`**
  - `serializer.az:802` `Expected 'for' after derived specs` -> **`derive` with values**
  - `serializer.az:840` `Expected ']' after implementation targets` -> **newline-separated
    targets**. This one closed the queue.
- **Re-measure after every landed step** and diff the failing-test *names*, not just the
  count - a count can hold steady while one test swaps for another. `scratchpad/collect.py`
  dumps the set from `compiler/build/test-results/desktopTest/*.xml`.
- **Watch for tests passing by accident** against the red baseline: `EnumWhenTest`
  matched `"C"` against `(SCOPE)` in a stdlib parse error. Expect a few more of these to
  surface as failures while the queue is worked; check the assertion before believing one.

---

## Phase 1 - Imports: the new grammar (all `[ADD]`)

Everything here is parser + `TopLevel.UseImport` + `ImportResolver`/`StdlibInjector`.
Today's parser (`Parser.kt:7371 parseUse`) supports only `import a.b`, `import a.b.*`,
and brace groups `import a.{x, y}` (`parseImportGroup`, `Parser.kt:7413`). The import
payload is `List<Pair<String, String?>>` - too weak for aliases, filters and nesting.

### S1.1 `[ADD]` Replace the import payload with a real AST node - **DONE 2026-08-18**
- **Touched** `frontend/Ast.kt` (new top-level `ImportSpec` before `sealed class TopLevel`;
  `TopLevel.UseImport` now holds `specs`), `frontend/Parser.kt` (`parseUse`,
  `parseImportGroup`), `semantic/SerializationDeriver.kt:151`
- **Did** `ImportSpec(path, selector, without, alias, line, column)` with
  `Selector = All | Path | Group(members)`. **No `Symbol` case** - the existing
  `import std.math.abs` and a future `import std.serializer.[Serializable]` are the same
  thing (a dotted path that is either a module or a symbol), and only the module graph
  can tell them apart, so that choice stays in the semantic passes. A `Group` member
  carries its own fully-joined path, which is what makes S1.2's nesting free.
  `UseImport.imports` is now a derived `get()` over `specs.flatMap { flatten() }`, so all
  ~10 read sites in `StdlibInjector`/`CtfeEvaluator`/`AstDumper` were untouched;
  `UseImport.of(pairs, …)` serves the one synthesised-import call site.
  `without`/`alias` are carried but unparsed until S1.3/S1.4.
- **Accepted** new `frontend/ImportSpecTest.kt`, 11 tests, all pass. Suite failure count
  unchanged at 1608 (see Baseline) - 1734 -> 1745 tests, zero regressions.

### S1.2 `[ADD]` Bracket groups + nesting + selective symbols - **DONE 2026-08-18**
- **Touched** `frontend/Parser.kt`: `parseUse` reduced to a clause loop, with the work in
  a recursive `parseImportSpec(prefix)` and `parseImportGroup(base, start)`.
- **Did** `import std.container.[array.*, list.*]`,
  `import std.serializer.[Serializable]`, `import std.x.[A, f.*, u.P]`, and nesting to
  any depth (`import a.[b.[c, d.*], e.*]`). Members are separated by a comma **or** a
  line break. Several clauses per statement still work, including after a group.
- **The design that made this small:** a group member is parsed as a *clause in its own
  right*, by the same function. That is what lets groups nest and mix selectors without a
  second grammar for the inside of one, and it is why S1.1 gave a member its own
  fully-joined path. The brace spelling goes through the same parser, so `{…}` silently
  gained nesting too - it is dropped in S9.3 regardless.
- **`import a.b.[c]` and `import a.b.c` flatten identically** - a one-member group only
  says the same thing with room for a second name later. There is still no `Symbol`
  selector; which of the two a dotted path means is the module graph's business (S1.1).
- **Rejected, each with a test:** an empty group, an unclosed group, and `::` in an
  import - the last now names the bracket form in its message.
- **Accepted** new `frontend/ImportGrammarTest.kt`, 16 tests; `ImportSpecTest`'s 11 still
  pass unchanged. **Suite** 1608 / 1876, no regressions, and
  `TupleVariadicTest.tupleTestsAzFileParses` newly passing.

### S1.3 `[ADD]` `without` filters
- **Touch** `Parser.kt`, `AstValidator.kt`
- **Do** `import std.x.* without Y` and `import std.x.* without [Y, Z]`.
  `without` is already a reserved token (`TokenType.WITHOUT`). Nested use is legal:
  `import std.x.[A, f.* without [G, H], u.P]`.
- **Do** Validator error when `without` follows a non-`*` selector:
  *"`without` filters a wildcard import; `import a.b.C without D` names a single symbol"*.
- **Accept** `ImportGrammarTest` positive + negative cases; a `without`-ed symbol is not
  in scope and referencing it reports *undefined*, not *private*.

### S1.4 `[ADD]` `as <alias>` and alias namespaces
- **Touch** `Parser.kt`, `semantic/SymbolTable.kt`, `semantic/ImportResolver.kt`
- **Do** Parse a trailing `as Name` on any import spec. An alias is **not** a module
  reference - it is a *view* assembled by imports. Model it as
  `SymbolTable.aliasNamespaces: Map<String, MutableMap<String, SymbolRef>>`.
- **Do** **Alias merging** is the default: repeated
  `import std.container.list.* as std` / `import std.container.map.* as std` union into
  one `std` view. Merging is per-symbol; two imports contributing the *same* symbol name
  from different modules do **not** error at import time.
- **Accept** `ImportAliasTest`: `List` and `Map` both resolve from two merged
  imports; `import std.container.[list.*, map.*, set.*] as collections` gives
  `collections::List` / `collections::Set`.

### S1.5 `[ADD]` Alias collision diagnostics
- **Touch** `semantic/ImportResolver.kt`, `semantic/TypeResolver.kt`
- **Do** Ambiguity is reported **at use**, not at import: `math::Vector` where both
  `foo.vector` and `bar.vector` contributed `Vector` errors with both origins and the
  suggested fix (split the alias, or import the symbol selectively under its own alias).
  Importing both and never using `Vector` is legal.
- **Accept** `ImportAliasTest`: two-`Vector` program compiles until `math::Vector` is
  written; error text names `foo.vector.Vector` and `bar.vector.Vector`.

### S1.6 `[ADD]` `::` resolution for alias views
- **Touch** `Parser.kt` (qualified-name parsing), `TypeResolver.kt`
- **Do** `alias::Name` resolves through `aliasNamespaces` in both type position
  (`var x: List<Int>`) and expression position. Also keep working: fully qualified
  `std.container.list::List<Int>` (module path `.`-separated, symbol after `::`).
- **Accept** `ImportAliasTest` covers both spellings against the same symbol.

### S1.7 `[ADD]` `exposed` members are auto-imported by a plain `import`
- **Touch** `semantic/ImportResolver.kt`, `stdlib/StdlibInjector.kt`
- **Do** In `module a`, an `exposed func f1()` (or any exposed type) enters scope on a
  bare `import a` - no `.*`, no selective import. Non-exposed members still need
  `import a.*` or `import a.f2`.
- **Accept** `ModulesTest`: bare `import a` sees `f1` and reports `f2` undefined.

---

## Phase 2 - Module visibility axis

### S2.1 `[ADD]` `ambient` and `resident` module keywords
- **Touch** `frontend/AzoraSyntaxVocabulary.kt`, `Token.kt` (`AMBIENT`, `RESIDENT`),
  `Parser.kt` (`ModuleVisibility`), `Ast.kt:1712` (`enum ModuleVisibility`)
- **Do** Extend `ModuleVisibility` to `PUBLIC | CONFINE | EXPOSED | AMBIENT | RESIDENT`
  with the semantics in `DIPs/MODULES_DIP.MD:24-30`:
  - `ambient module` = package-only + auto-imported in package (**the replacement for
    `exposed confined`**).
  - `resident module` = public + auto-imported in package.
  Member defaults follow the module (overridable by `_`-private / `confined` / `ambient`).
- **Accept** `ModulesTest`: a 4-file fixture asserting each axis's auto-import and
  reach; `exposed confined module` now errors with *"use `ambient`"*.

### S2.2 `[ADD]` Enforce the `exposed`/`ambient`/`resident` axis
- **Touch** `semantic/ImportResolver.kt`, `Compiler.kt:214` (private-name gate)
- **Do** MODULES_DIP line 42 records that only `confined` is enforced today. Implement
  the folder comparison for all five and delete that caveat from the DIP.
- **Accept** cross-package import of a `confined`/`ambient` module errors; same-package
  import succeeds without an explicit `import`.

### S2.3 `[DROP]` Remove `protected`
- **Touch** `AzoraSyntaxVocabulary.kt:78`, `Token.kt:172`, `Ast.kt:1691,1695,1704`,
  `Parser.kt:858,866-868,1798,1827-1830,7661`, `TypeResolver.kt:742`
- **Do** Delete `Visibility.Reach.PROTECT` and `TokenType.PROTECT`. `protected` becomes a
  plain identifier. Legacy diagnostic: *"`protected` was removed; use `confined` (package)
  or a leading `_` (module-private)"*.
- **Accept** `VisibilityTest`: `protected func f()` errors; `var protected = 1` parses.

### S2.4 `[DROP]` Remove `exposed import` (re-export)
- **Touch** `Parser.kt:1022`, `Ast.kt` (`UseImport.exported`),
  `StdlibInjector.kt:240,1015-1016,1044-1148` (`exportedImportsByModule`)
- **Do** Delete the `exported` flag and its whole transitive-seeding path in
  `StdlibInjector`. **Risk:** this is the mechanism that makes stdlib umbrella modules
  (`std/core.az` etc.) pull their children in. Before deleting, confirm what breaks by
  running `:compiler:desktopTest` with the flag forced off; the fix is to make umbrella
  modules `resident`/`ambient` (Phase 2.1) rather than re-exporting.
- **Accept** `StdlibInjectionTest` green with the flag off; `exposed import x` errors.

### S2.5 `[FIX]` Rewrite `DIPs/MODULES_DIP.MD`
- **Do** Full grammar for Phase 1 + 2: alias views, merging, collisions, `without`,
  nesting, selective symbols, the five module kinds, `exposed`-member auto-import.
  Delete the "only `confined` is enforced" note. No `realm` anywhere.

---

## Phase 3 - Kill `realm`, promote `scope`

`realm` has 629 references across 30 compiler files - this is the single biggest step and
must come *after* Phase 1, because module aliases are its replacement.

### S3.1 `[ADD]` `scope <name> { ... }` at global scope - **DONE 2026-08-18**
- **Touched** `Parser.kt` only: `namespaceKeywordAhead()` (replaces
  `isRealmNamespaceAhead`'s body and reports *which* keyword opened the namespace),
  `parseRealmNamespace`, `parseRealmBody`, `isRealmBlockAhead`, `parseRealmBlock`.
- **Did** Both readings, told apart by whether a name follows the keyword - so neither
  needs a word of its own:
  - `scope Name { … }` / `scope A::B { … }` - a namespace. Members mangle to
    `Name__member` and are reached `Name::member`. Nests, and the same name may be
    opened as often as it likes.
  - `scope { … }` / `scope "label" { … }` / `deepinline scope { … }` - a block. Members
    keep their top-level names; `inline`/`deepinline` keep the CTFE semantics
    `inline realm` had (this is what `std/config.az` is written with).
  Routed onto the existing realm machinery, so zero backend work.
- **A scope declares only `fin` bindings** (decided 2026-08-18). `var`/`val`/`let` are
  rejected, and the message points at `solo pack`, which is where a binding that can be
  rebound or mutated belongs. `realm` is deliberately *not* held to the rule: it keeps
  taking what it always took so that removing it stays a separate, reviewable change.
- **Accepted** new `frontend/ScopeNamespaceTest.kt`, 18 tests, all pass.
- **Suite** 1607 failed / 1810 - **below the 1608 baseline**, zero regressions, and
  `MacroTest.macroSigilPrecedesRealm` newly passing.
- **Next stdlib blocker:** `config.az:19` -> **`container/core.az:24`,
  `Expected '{' after 'macro @arr', got '['`** - a macro declared with a bracketed
  pattern. No plan step owns it.

### S3.1b `[FIX]` `reflect<T>` is written plain - **DONE 2026-08-18**
Fallout of removing realms, found by the stdlib: `reflect` lived in `realm std`, so
`isReflectName` only accepted a bare `reflect` **inside a namespace**
(`typeFunctionNamespacePrefix.isNotEmpty()`) and demanded `reflect` everywhere
else. With `realm std` gone the qualifier names nothing.
- **Touched** `Parser.kt` - `isReflectName` accepts `reflect` unconditionally (the
  `__reflect` suffix stays while `` is retired), and the two diagnostics no longer
  teach a `` spelling.
- **Unchanged:** the *receiver* is still required. `X.hasAnnot<D>` is still an error -
  an annotation is asked of a reflection handle, not of the value. Only a name followed
  by a reflect target (`<Type>`, `<Type::member>`, `<*>`) is the handle, so an ordinary
  function named `reflect` still works.
- **Accepted** new `frontend/BareReflectTest.kt`, 7 tests. One existing test
  (`DecoratorReflectionTest.reflectionRequiresKeywordAndDeclarationMemberSyntax`) asserted
  the old message text and was updated - message only, behaviour identical.
- **Suite** 1609 / 1832, only the known `EnumWhenTest` accidental pass.

### S3.2 `[DROP]` Remove the `realm` keyword
- **Touch** `AzoraSyntaxVocabulary.kt:33`, `Token.kt:89`, `Parser.kt:199,219,370,395,
  1204,1621,1633,1643,1799,1836,5859`
- **Do** Delete `TokenType.REALM`. Legacy diagnostic: *"`realm X` was removed; use
  `scope X` for a namespace, or a module + `import ... as X` for a namespace view"*.
- **Accept** `realm X { }` errors; `func realm()` parses.

### S3.3 `[FIX]` Rename the realm machinery to scope
- **Touch** `frontend/IntraRealmRewriter.kt` -> `IntraScopeRewriter.kt`,
  `semantic/RealmQualifiedImplTargets.kt` -> `ScopeQualifiedImplTargets.kt`,
  `Ast.kt:1725 RealmMeta` -> `ScopeMeta`, `realmPrefix`/`localRealm`/`realmTypeNamespaces`/
  `testRealmMembers`/`realms` fields, `Mangling.kt`, `IrSymbolCanonicalizer.kt`
- **Do** Pure rename, no behaviour change. Reflection surface `(reflect X).realm` becomes
  `.scope` (`DIPs/REFLECTION_DIP.MD`). **Watch the APFS case-insensitivity trap** when
  renaming files ([[azora case-insensitive FS]]): `git mv` in two steps.
- **Accept** full suite green; `grep -ri realm compiler/src` returns 0.

### S3.4 `[FIX]` `realm test` -> `scope test`
- **Touch** `Ast.kt:2862 testRealmMembers`, `TestRealmTest.kt`
- **Do** The test-visibility rule keeps working under the new spelling.

### S3.5 `[FIX]` Purge `realm` from docs and tooling
- **Touch** 12 `DIPs/*.MD`, `README.md`, `ROADMAPs/VERSION_0_1_ROADMAP.MD`,
  `azls/src/main/kotlin/org/azora/azls/{AzoraLanguageServer,Models,AzHighlighter}.kt`,
  `azls/src/main/azora/AzoraLanguageServer.az`, plugin `AzoraLanguageFacts`
- **Accept** `RealSourcesSmokeTest` in the plugin repo stays at zero lex errors.

---

## Phase 4 - The `.` / `::` split

### S4.1 `[ADD]` `.` for static access
- **Touch** `Parser.kt` (postfix `.`), `TypeResolver.kt` (member resolution)
- **Do** `Type.staticMember` resolves statics. Resolution order on a `.`: value member of
  an expression first, then static member of a type name.
- **Accept** `StaticAccessTest`: `Color.red`, `Alloc.create()` resolve.

### S4.2 `[DROP]` Restrict `::` to its four remaining jobs
- **Touch** `Parser.kt` (32 `DOUBLE_COLON` sites), `TypeResolver.kt`
- **Do** `::` is legal **only** for: (a) an `import ... as` alias view, (b) scope access,
  (c) associated types (`T::Item`), (d) serial field access (`::data` in `binds`).
  Everything else - notably `Type::staticMember` - errors with *"use `.` for static
  access"*.
- **Accept** `StaticAccessTest` negative cases; the four legal forms still parse.

---

## Phase 5 - impl / ctor / dtor / spec restructure

### S5.1 `[ADD]` Statics live in the normal `impl` block
- **Touch** `Parser.kt:3087 isStaticImplAhead` / `parseImpl`, `SymbolCollector.kt`
- **Do** Inside a plain `impl T { }`, a member **without** a receiver declaration
  (`func f()` / `prop p: T`) is static; a member **with** one (`func f[self: Self&]()`) is
  an instance member. This is already the stdlib's spelling
  (`std/allocator/allocator.az:132,156,222`) - make the receiver-less case mean *static*
  rather than *implicit self*.
- **Accept** `ImplStaticTest`: `impl Arena { func make(): Arena {...} }` is callable as
  `Arena.make()` and is *not* callable on an instance.

### S5.2 `[DROP]` Mandatory self-context declaration on instance methods
- **Touch** `AstValidator.kt`, `Parser.kt`
- **Do** An `impl` member that references `self` without declaring a receiver errors:
  *"declare the receiver: `func f[self: Self&]()`"*.
- **Accept** `ImplStaticTest` negative case.

### S5.3 `[DROP]` Remove `impl Name:: { }`
- **Touch** `Parser.kt:756 isStaticImplAhead`, `3083-3107`
- **Do** Delete the static-impl block form and its `mangleTopLevel` path; S5.1 replaces it.
  Legacy diagnostic points at the plain-`impl` spelling.
- **Accept** `impl ReflectedParam:: { }` errors (fixture, not the stdlib file).

### S5.4 `[DROP]` Remove `impl ctor` / `impl dtor` top-level forms
- **Touch** `Parser.kt:3110-3194`
- **Do** `ctor` and `dtor` are declared **inside** `impl T { }` only - the spelling
  `std/allocator/allocator.az:121` already uses (`ctor[self: Self!](capacity: Int)`).
  Delete both top-level branches.
- **Accept** `ObjectModelTest`: in-impl `ctor`/`dtor` work; `impl ctor() for T {}` errors.

### S5.5 `[DROP]` Remove the in-brace `scope { self! -> }` receiver - **DONE 2026-08-18**
- **Touched** `Parser.kt` - two new helpers next to `isSelfReceiverHeaderAhead`:
  `skipScopeBodyIntroducer()` (consumes a `scope` that immediately precedes a `{`) and
  `rejectInBraceReceiver(declaration, signature)`. Applied to the impl-body `ctor`
  (which had **no** `scope` handling at all - the actual stdlib blocker), the impl-body
  `dtor`, and the impl-body `prop` and `parseFunc`, whose two hand-rolled copies of the
  skip collapsed into the helper.
- **Did not** touch `impl as T for Type` - that form is already an error
  (`Parser.kt:3200`), so the plan's "last surviving in-brace receiver" note was reading a
  stale comment above live code. `impl Trait for Type { self& -> }` was likewise already
  rejected (`Parser.kt:3335`).
- **Deviation:** the legacy top-level `impl ctor/dtor … for T` forms had a *mandatory*
  `self! ->`. Rather than error there (which S5.4 will do by deleting the form whole), the
  arrow became **optional** - so both spellings parse until the form goes. Half-changing a
  form that is about to be deleted buys nothing.
- **Accepted** new `frontend/ContractScopeBodyTest.kt`, 9 tests, all pass. Parser-level,
  not compile-level, because the bundled stdlib still does not parse.
- **Suite** 1609 failed / 1754. The one delta against baseline,
  `EnumWhenTest.enumWhenNonExhaustiveErrors`, is **not a regression**: it asserts
  `"non-exhaustive" in errors || "C" in errors`, and it had been passing on the `C` inside
  `(SCOPE)` in the old stdlib parse error. It goes green when the stdlib parses.
- **Next stdlib blocker moved on:** `allocator/allocator.az:124` -> **`:165`,
  `Unexpected token '++'`** (`self.allocCount++`), which is **S7.3**.

### S5.6 `[DROPPED]` `factory` types - superseded by `solo pack`
**There is no static-only namespace type.** The need this step existed for -
`Alloc.arena(…)`, `Alloc.pool(…)` as a grouped set of constructors - is met by the
singleton that already existed:

```azora
solo pack Alloc

impl Alloc {
    func arena[self: Self&](size: Int): Arena { … }
}
```

`solo pack Name` is a pack there is one of: one instance, ready when the program starts.
Everything else about it reads as a pack does - fields in the body, members in
`impl Name { … }` - so `solo` is a word about *how many*, not about *what*. Adding a
second declaration form for "a type with no instances" would have been a third way to
group functions alongside modules and scopes.

**What landed instead** (`Parser.kt`, `parseSolo`):
- **A stateless singleton needs no body.** `solo pack Alloc` on its own line: with no
  fields and members in an `impl`, there is nothing left for braces to hold.
- `solo` still requires `pack`, and still refuses methods in its body, pointing at
  `impl Name { … }`. Both unchanged.

**Also decided here:** an anonymous `impl { … }` binding to the type above it was built
and then **reverted** - the spelling is `impl Alloc { … }`. It is a real idea (a file
holds one inherent impl per type, sitting under that type), but nothing needs it yet and
it interacts with S5.9 in ways no one has decided.

**Accepted** `frontend/SoloAndGraphLifetimeTest.kt`, 7 tests, all pass.

### S5.6b `[ADD]` `scoped` DI lifetime - **DONE 2026-08-18**
- **Touched** `Token.kt` (`SCOPED`), `AzoraSyntaxVocabulary.kt` (`"scoped"`),
  `Ast.kt` (`ProviderLifetime.SCOPE` -> `SCOPED`, spelling `"scoped"`), `Parser.kt` (graph
  entry lifetimes).
- **Do** A graph's per-scope lifetime is `scoped`, not `scope`:
  ```azora
  graph AppGraph {
      solo    Repository(inject Db)        // this graph owns one
      factory LoginViewModel(inject Api)   // a new owned value per resolution
      scoped  ProfileViewModel(inject Api) // one per active scope
  }
  ```
  `scope` declares a namespace (S3.1); a lifetime borrowing the same word would put two
  unrelated meanings on it. Writing `scope` in a graph entry now errors and names `scoped`.
- **`factory` keeps its DI meaning** and is not otherwise a declaration keyword.

### S5.7 `[ADD]` `direct spec` for type-side implementations - **PARSED 2026-08-18**
- **The keyword is `direct`, not `factory`** (decided 2026-08-18). `factory` keeps only
  its DI-provider meaning. `direct spec Number { prop rank: Int }` marks a spec whose
  members belong to the *type*: `Int::rank` answers for every `Int` at once, so there is
  nothing for a value to carry and nothing to dispatch on. The answer is reached
  directly, without a value to reach it through.
- **Touched** `Token.kt` (`DIRECT`), `AzoraSyntaxVocabulary.kt`, `Ast.kt`
  (`TopLevel.Spec.isDirect`), `Parser.kt` (top-level dispatch + `parseSpec`).
- **Accepted** `frontend/ImplAndDeriveFormsTest.kt`.
- **NOT DONE - semantics.** `isDirect` is recorded and nothing reads it yet:
  `SpecDefaults`, `TypeResolver` and the `LlvmCodegen`/`IrInterpreter` dispatch still
  treat it as an ordinary spec. Still to do: `Ty::rank` and generic `T::rank` under
  `where T: Number` resolving to the type-side member, and rejecting an instance
  receiver in a `direct spec` impl.

### S5.7b `[ADD]` Forms that finished the stdlib's parse - **DONE 2026-08-18**
Four small parser additions, each replacing something the retired `::` static block used
to say, or relaxing a list to separate its elements as every other list does:
- **A binding declared beside its type.** `impl Byte { inline fin minValue: Int = -128 }`
  - no receiver, so it belongs to the type; it leaves mangled as `Byte__minValue`, which
  is what `impl Byte:: { … }` produced. All four mutabilities accepted. (This is the
  binding half of **S5.1**.)
- **`impl [Byte, UByte] { … }`** - one body, given to each type named. A bracket list
  *before* `for` still names decorators; one standing on its own names targets. The body
  is re-read per target, so a grouped impl is the single impl it stands for, N times.
- **`test scope { … }`** replaces `realm test { … }` (both parse until S3.2).
- **`derive Serializable(ignoreUnknownFields: true) for T`** - a derive may carry the
  values the decorator takes when applied by name, since the two say the same thing.
- **Bracketed impl/derive target lists separate on a newline**, comma optional.
- **Accepted** `frontend/ImplAndDeriveFormsTest.kt`, 15 tests.

### S5.8 `[DROP]` Remove `impl Spec for Ty::`
- **Touch** `Parser.kt` impl-target parsing
- **Do** Legacy diagnostic naming `factory spec` as the replacement.

### S5.9 `[ADD]` At most one **inherent** `impl X { }` per `pack X { }` per file
- **Touch** `AstValidator.kt`
- **Do** The rule is scoped to *inherent* impls: for each type `X` declared in a file,
  at most one bare `impl X { }`. Not counted, and unlimited:
  - `impl Spec for X { }` - spec implementations, any number.
  - `derives [...]` and every compiler-synthesised impl (`CastDeriver`,
    `ComparisonDeriver`, `DisplayDeriver`, `SerializationDeriver`).
  - inherent impls for *different* types in the same file.
  Error on the second `TopLevel.Impl` with an empty `spec` target and the same type name,
  pointing at the first: *"`X` already has an inherent impl at line N; merge the members"*.
- **Accept** `ImplPerFileTest`: two `impl X {}` errors; `impl X {}` + three
  `impl S for X {}` + `derives [Clone, Display]` compiles; `impl X {}` + `impl Y {}` compiles.
- **Note** measure the stdlib cost before committing:
  `grep -cE '^impl [A-Z][A-Za-z0-9_<>, ]*\{' std/**/*.az`

### S5.10 `[DROP]` `oper..` covers both directions
- **Touch** `Parser.kt:518-519,597,789-791`, `OperatorFamilyTest`
- **Do** Delete the `reverse..` operator name. A single `oper..` implementation serves
  forward and reverse iteration; the reverse direction is derived.
- **Accept** `MultiOperImplTest`: one `oper..` drives `for x in a..b` and
  `reverse for x in a..b`.

---

## Phase 6 - Memory, allocation, pointers

> **Measured 2026-08-18, before starting.** A probe of every form in this phase found
> most of what the plan says to *add* already parses: `.() * n` and `.(5) * n` in value
> position, `alloc .() * n`, `alloc .()`, `p.*[i]`, and the `alloc T[n]` this phase
> wanted to remove. What was actually missing was smaller and different from the plan:
> the `ctor … * count` **declaration**, `alloc T*()`, and a **latent bug** the plan did
> not know about (below). Several steps turn out to be moot:
> - **S6.7 (`binds ::data by capacity`) is used nowhere in `std/`** - `ArrayList` writes
>   `self._data = alloc .() * count` explicitly. Nothing needs it; do not build it until
>   something does.
> - **S6.2 (remove `alloc T[N]`) must wait**: ~50 uses across `std/`.
> - **S6.6 (remove `(*p)`) is free but pointless**: zero uses in `std/`; `p.*[i]` already
>   works and is what the 4 uses are written with.
> - **S6.8**: `bridge spec Allocator` already parses (`std/allocator/allocator.az:69`).

### S6.1/S6.3/S6.4 `[ADD]` Repeated construction - **PARSER DONE 2026-08-18**
- **`ctor[self: Self!](…) * count { … }`** - the declaration `.(…) * n` selects. The
  repetition is appended to the parameter list as `count: Int`, and **the name is fixed**:
  writing `* n` is an error naming `count`. A repetition of none builds nothing, so an
  `assert count >= 1` precondition leads the body, ahead of any the author wrote.
- **A latent bug, now fixed.** `var x: Array<Int> = .() * 3` parsed as *two statements* -
  `var x = Array()` followed by `ExprStmt(Deref(3))`, because `parseInitializer` returned
  as soon as it had built the `.()` call and left the `*` to open the next statement,
  where prefix `*` is a dereference. It "parsed", and silently built one element instead
  of three. ~30 sites across `std/` were affected.
- **The count is everything after the `*`.** `.() * a.size + b.size`
  (`std/algorithm/algorithm.az:60`) asks for as many elements as the two together. The
  form builds a value rather than multiplying one, so it does not take multiplication's
  precedence.
- **`alloc T*(…)` / `alloc T^(…)`** - the sigil on the type, where it sits when the type
  is written out. The older `alloc* T(…)` / `alloc^ T(…)` still parse.
- **Accepted** new `frontend/RepeatConstructionTest.kt`, 16 tests. **Suite** 1609 / 1848.
- **NOT DONE - semantics.** All of the above is *parsing*. `.(…) * n` produces
  `Expr.Binary(<construct>, STAR, count)`, which no later pass reads as a repetition -
  TypeResolver and IrGenerator will treat it as multiplication. Selecting the repeated
  ctor at a call site is likewise unwired. **`Expr.AllocBuffer(typeName, count)` already
  exists and is plumbed through every pass and all four backends** (it is what
  `alloc T[n]` builds), so it is the obvious lowering target for the pointer form; the
  value form (`Array<T>`) needs its own answer. Do this before believing any of it runs.

### S6.1 `[ADD]` `alloc T*()` / `alloc T^()` and inference
- **Touch** `Parser.kt:9466` (`parseAlloc`), `TypeResolver.kt`, `semantic/AllocDropAnalyzer.kt`
- **Do** `alloc` always allocates *through* a pointer type: `alloc T*()` (raw) and
  `alloc T^()` (owning). `fin x: T* = alloc .()` infers `alloc T*()` from the binding.
- **Accept** `AllocTest`: all three spellings produce the same IR.

### S6.2 `[DROP]` `alloc T[N]` is not allocation syntax
- **Touch** `Parser.kt` (`parseAlloc`)
- **Do** `var freeStack: Int* = alloc Int[0]` errors, naming `alloc Int()` /
  `alloc .() * N` as the replacement.

### S6.3 `[ADD]` `.() * count` repetition
- **Touch** `Parser.kt` (postfix `*` on a ctor call), `TypeResolver.kt`, `IrGenerator.kt`
- **Do** `.() * 10` = 10 default-initialised elements; `.(5) * 10` = 10 elements of `5`.
  Works for `alloc .() * 3` (pointer) and `var x: Array<T> = .() * count` (value).
- **Accept** `ArrayTest` + `AllocTest`, on all four backends.

### S6.4 `[FIX]` `.()` infers its type in static-call context
- **Touch** `TypeResolver.kt` (`InferredTypeArgs.kt`)
- **Do** `var x: Array<T> = .() * count` must resolve `.()` against the *declared* type
  even though the call is static. Same rule as S6.1's `alloc .()`.
- **Accept** `ConstGenericArrayTest` case with an explicit element type.

### S6.5 `[DROP]` Remove `fill` and `arrayOf`
- **Touch** `IrGenerator.kt` / builtin tables, `stdlib` array zone (`Array::fill`),
  `ArrayStdlibTest`
- **Do** `.() * N` replaces both. Note `arr![...]` (the always-on macro) desugars to
  `arrayOf` today ([[azora always-on macro modules]]) - retarget the macro before
  deleting the function, or `arr!` breaks.
- **Accept** `ArrayTest`; `arr![1,2,3]` still compiles.

### S6.6 `[DROP]` Pointer deref is `p.*`, never `(*p)`
- **Touch** `Parser.kt:9491-9494` (prefix `*` / `deref`), `9612` (postfix `.*`)
- **Do** Delete the prefix form. `(*freeStack)[i]` errors; `freeStack.*[i]` is the
  spelling. `.^` (`DerefMut`, `Parser.kt:559`) is unchanged.
- **Accept** `PointerTest` positive/negative.

### S6.7 `[ADD]` `binds ::data by capacity`
- **Touch** `Parser.kt:1166,4249` (`matchContextual("binds")`), `SymbolCollector.kt`,
  `TypeResolver.kt`, `IrGenerator.kt`
- **Do** `pack ArrayList<T> derives Clone binds ::data by capacity { ... }`:
  - `::data` must name a **pointer** field of the pack (validate).
  - Inside the pack's own impl, `.() * 8` allocates 8 slots **into `data`** without the
    `alloc` keyword (bare `alloc` would allocate a new list, which is the bug this fixes).
  - `by capacity` names the field that is set to the element count on such an allocation.
- **Accept** `BindsFieldTest`: `.() * 8` sets `data` to an 8-slot buffer and `capacity` to
  8; a non-pointer `binds` target errors.

### S6.8 `[ADD]` `bridge spec` gates `alloc ... in <allocator>`
- **Touch** `Parser.kt:964` (`bridge spec` already parses), `TypeResolver.kt`,
  `AllocDropAnalyzer.kt`
- **Do** `var p = alloc Point(1.0, 2.0) in arena` is a compiler feature that requires
  `arena`'s type to implement a spec declared `bridge spec` (as
  `std/allocator/allocator.az:69` already writes it). A non-bridge spec errors with the
  reason.
- **Accept** `AllocatorTest`: `in arena` compiles against `bridge spec Allocator`, errors
  against a plain `spec`.

---

## Phase 7 - Statements, sugar, naming

### S7.1 `[ADD]` Grouped field assignment `self.[a, b, c] = ...` - **DONE 2026-08-18**
- **Touched** `Parser.kt` only - no `Ast.kt`, no `IrGenerator.kt`, no backend.
- **Did** Both forms, desugared at parse time to ordinary `Stmt.MemberAssign`es:
  - `self.[offset, allocCount] = 0` - one value to each member.
  - `self.[a, b, c] = [1, "2", true]` - positional, arity-checked.
  Commas separate names on a line and are unnecessary across lines, in the group and in
  the value list alike. Any receiver works (`config.[w, h]`, `self.window.[w, h]`).
- **How** A new arm in the postfix loop catches `.` followed by `[`, reads the names, and
  hands the receiver back through two parser fields (`allowMemberGroup` gates where a
  group is legal, `pendingMemberGroup` carries the names). `parseExprStmt` sees the
  pending names and builds the assignments. **No `Expr` node was added** - a grouped
  target is never a value, and a new `Expr` variant would have forced every exhaustive
  `when` over `Expr` in the compiler to grow a case for something no later pass can see.
- **The fan-out form binds a temporary first** (`fin __groupN = <value>`): the value is
  *written* once so it must be *evaluated* once, whatever it lands in - `self.[a, b] =
  next()` must call `next()` once. That temporary is why the result is a `Stmt.Scope`
  (`shared = false`, the documented compiler-built kind): two grouped assignments in one
  body must not collide on it. The positional form binds nothing, having nothing to share.
- **Rejected, each with a test:** arity mismatch (names the counts), a member named twice,
  an empty group, a group used as a value (`fin x = self.[a, b]`), a group with no `=`.
- **Accepted** new `frontend/GroupedAssignTest.kt`, 17 tests, all pass.
- **Suite** 1609 failed / 1785. The single delta is `EnumWhenTest` again - see the
  accidental-pass note in Baseline; its `"C"` has nothing to match in the new message.
- **Next stdlib blocker moved on:** `allocator/allocator.az:223` -> **`:306`,
  `Expected '{' after dtor, got '('`** - `dtor[self: Self!]()`, a dtor written with an
  empty parameter list. The impl-body `dtor` parser reads a receiver and then demands the
  body brace. Small, and adjacent to the S5.5 code.

### S7.2 `[FIX]` `self.` is mandatory on both sides
- **Touch** `TypeResolver.kt` (identifier resolution inside methods)
- **Do** Bug: `self.offset = offset - size` currently resolves the bare `offset` to the
  field. A bare name inside a method must resolve to params/locals only; an unqualified
  field read errors *"write `self.offset`"*.
- **Accept** `SelfQualificationTest`; **expect stdlib fallout in Phase 9**.

### S7.3 `[FIX]` `x++` / `x--` - **DONE 2026-08-18**
- **Touched** `Parser.kt` `parseExprStmt` only. No IR, semantic or backend change.
- **Was** `++`/`--` were handled inside the `else ->` fallthrough and gated on
  `expr is Expr.Identifier`. Every other target fell through to `Stmt.ExprStmt`, whose
  `consumeNewline()` then choked on the `++` - which is why `self.allocCount++` read as
  *"Unexpected token '++'"* rather than as an unsupported target.
- **Did** Lifted `PLUS_PLUS`/`MINUS_MINUS` to their own arm of the same `when` that
  already handles `+=` and `?++`, and routed them through `buildAssignment`. That one
  call is the whole fix: it already covered all four assignable targets, so a local, a
  field, an element and a pointee now all take an increment, matching the set `+=` and
  `?++` always took.
- **Deliberately not done:** `++` does **not** pass `compoundOp`, so it keeps lowering to
  build-and-assign (`oper+`) exactly as it did for locals. Passing it would route `x++`
  to `oper+=` for types declaring one - defensible, but a dispatch change that belongs in
  its own step with its own test, not smuggled into a target-set fix.
- **Deliberately not done:** no expression form. `a[i++]` needs a value-producing side
  effect, i.e. an IR node none of the four backends have; it stays an error. The plan's
  "and in expression position" was scope this step does not carry, and the stdlib uses
  only the statement form (9 sites, all `self.field++`/`--`).
- **Accepted** new `frontend/IncrementTest.kt`, 14 tests, all pass.
- **Suite** 1608 failed / 1768 - **zero regressions, failing set now byte-identical to
  the original baseline** (`EnumWhenTest` went back to passing, still accidentally: its
  `"C"` now matches `(L_BRACKET)` in the new stdlib error).
- **Next stdlib blocker moved on:** `allocator/allocator.az:165` -> **`:223`,
  `Expected member name after '.', got '['`** (`self.[offset, allocCount] = 0`), which is
  **S7.1**.

### S7.4 `[DROP]` `await handle`, not `await(handle)`
- **Touch** `Parser.kt:9511`
- **Do** `await` takes an expression without parentheses. `await(t1)` errors (the parens
  read as a call on the awaited value). `await (a + b)` stays legal as a parenthesised
  expression - the diagnostic must only fire on the immediate-call shape.
- **Accept** `ConcurrencyTest`.

### S7.5 `[ADD]` Underscores anywhere in names
- **Touch** `Lexer.kt:180,500` (already allows `_`), `Compiler.kt:214`,
  `IntraScopeRewriter.kt:119`, `StdlibInjector.kt:1494-1501`, `ir/Mangling.kt`
- **Do** `func f_1()` must work. The lexer is fine; the risk is **mangling collisions** -
  IR symbols are `__` once then single `_` ([[Azora symbol mangling rule]]), and
  `StdlibInjector.memberSegmentOf` splits on `__`. Audit every split/join and add a
  collision assertion in `Mangling`.
- **Also** `azls/` completion/rename tokenisation, and plugin `AzoraLexer` identifier rule.
- **Accept** `SourceSymbolNameTest`: `f_1`, `_priv_f`, `a__b` as a user name round-trip
  through mangling without colliding.

### S7.6 `[ADD]` Type-returning `func`/`prop` must be capitalised
- **Touch** `AstValidator.kt`, `semantic/TypeFunctionEvaluator.kt`
- **Do** Any `func`/`prop` whose return type is `Type` (i.e. a type function /
  `deepinline prop`) must start with an uppercase letter. Error otherwise, naming the
  capitalised form. Mirrors the existing annotation rule (`Parser.kt:1096,1162`).
- **Also** azls diagnostic + plugin annotator (semantic, not lexical - per the plugin's
  rule 2, this belongs in `AzoraSemanticModel`/annotator).
- **Accept** `TypeFunctionTest`: `deepinline prop promote<T,U>: Type` errors,
  `Promote<T,U>` compiles.

### S7.7 `[ADD]` `where T: Order` and `<T: Order>` are interchangeable
- **Touch** `Parser.kt:6140 parseWhereClause`, `ConstraintEvaluator.kt`
- **Do** Both `func count<T>(arr: Array<T>, target: T): Int where T: Order` and
  `func count<T: Order>(arr: Array<T>, target: T): Int` are accepted, and combining them
  unions the bounds (Rust semantics). Verify `where T is X` vs `where T: X` spelling and
  document the canonical one.
- **Accept** `GenericsTest`: both declarations produce identical constraint sets.

### S7.8 `[ADD]` `using`, `[DROP]` `use`
- **Touch** `AzoraSyntaxVocabulary.kt:96` (drop `use`, add `using` -> `TokenType.USING`),
  `Token.kt:118`, `Parser.kt:4900,4949,5044` (`use as` in spec members),
  `frontend/UseAsTemplate.kt`, `MacroExpander.kt`
- **Do** Add `using` as a **reserved keyword with no standalone grammar yet** - reserved
  now so introducing its meaning later breaks nothing (the same treatment
  `TokenType.DERIVE` already gets, `Token.kt:155`). Its only legal position today is
  inside **macro declarations**, exactly like `with` / `without` / `by`:
  - infix macro name: `macro $a @using $b => …` (`parseMacroName`, `Parser.kt:4633`,
    already accepts keyword lexemes after `@` - verify `using` flows through and add the case)
  - type-macro arm separator: `meta type { $Base using $Filter => … }`
  Outside a macro declaration, `using` errors: *"`using` is reserved"*.
- **Do** Remove `use` entirely, including the `use as` spec-member alias. Its last user is
  the compact one-line spec form ([[Azora spec member alias]]) - replace that with the
  `inline prop to${T.typeName}: T = into<T>` spelling **first**, or the compact form breaks.
- **Accept** `MacroTest`: `macro $a @using $b => …` declares and expands; `meta type` arm
  with `using` matches. `KeywordReservationTest`: bare `using x` errors, `use as` errors.

---

### S1.10 `[ADD]` `when <scrutinee>` in a type computation - **DONE 2026-08-18**
Not in the original plan; the stdlib named it. A type computation's `when` accepted
only the conditionless `when { cond => Type }`; `std/core.az:90` writes a scrutinee:

```azora
deepinline func Nullable<T>(b: Bool): Type {
    return when b {
        true  => Option<T?>
        false => Option<T>
    }
}
```

- **Touched** `Parser.kt` only - `parseTypeFunctionExpr`, plus `booleanArmLiteral()` and
  `typeFunctionArmCondition()`.
- **The evaluator learns nothing new.** Each arm becomes a `TypeFunctionCondition` that
  already existed: a `true`/`false` arm is the `valueFlags` test the conditionless form
  builds for `b =>` / `!b =>`, and any other arm is a comparison against the scrutinee.
  `TypeFunctionEvaluator` was not touched.
- **`true` and `false` together leave nothing over**, so a `Bool` scrutinee needs no
  `else`. One arm alone still does, and a type scrutinee always does.
- **`deepinline prop` still takes no value parameters** (decided 2026-08-18): a
  parameterless type computation is a `prop`, one with arguments is a `deepinline func`.
  The rule stands and `std/core.az` says `func`.
- **Accepted** new `frontend/TypeComputationWhenTest.kt`, 10 tests. **Suite** 1608 / 1896.

### S1.9 `[ADD]` Generic case-carrying declarations - **DONE 2026-08-18**
Not in the original plan; the stdlib named it. All four declarations that carry cases
now take type parameters, after the name they parametrize as everywhere else:

```azora
enum Tag<T> { … }
variant enum Option<T> { Some(T); None }        // std/core.az:85
error IoError<T> { … }
variant error ParseError<T> { Bad(T) }
```

- **Touched** `Ast.kt` (`typeParams` on `TopLevel.Enum`, `Slot`, `Fail`) and the three
  parse sites, each reusing the existing `parseTypeParams()`.
- **Unchanged:** a payload is still what separates the two enum forms and the two error
  forms - `enum Tag<T> { First(T) }` still errors and still names `variant enum`.
  Parameters do not make a plain `enum`/`error` able to carry data.
- **Accepted** new `frontend/GenericEnumDeclTest.kt`, 10 tests. **Suite** 1608 / 1886.
- **Parse only.** What the parameters mean to name resolution is the semantic passes'
  business; generics are erased before IR either way.

## Phase 8 - Macros

### S8.1b `[FIX]` One argument list, everywhere - **DONE 2026-08-18**
Not in the original plan; the stdlib named it. An argument list was parsed by **four
separate loops** - the named call, `parseInitializer`'s `.(…)`, `parseInferredMember`'s
`.(…)`, and the `alloc T*(…)` list - and only the named-call one had learned about
`inline for`. So `alloc .( inline for e in args { e } )` (`std/container/list.az:170`)
was a syntax error for no reason a reader could see, and the other two silently lacked
spread as well.
- **Touched** `Parser.kt`: one `parseCallArgumentList()` + `parseInlineForArgument()`,
  used by all four sites. Net removal of ~60 lines of duplication.
- **Every argument list now takes** `inline for x in xs { expr }` (one argument per
  iteration), `...arr` (spread), `name: value` (named), and optional commas across lines.
  `Expr.InlineForArgs` already existed and is plumbed through every pass and backend
  (`CtfeEvaluator.spliceInlineForArgs` expands it), so this is parser-only.
- The named-call list is the one that grows a trailing lambda after the `)`; sharing the
  parser must not cost it that, and a test says so.
- **Accepted** new `frontend/CallArgumentFormsTest.kt`, 12 tests. **Suite** 1608 / 1860,
  zero regressions, and the failing set is again identical to the baseline.

### S8.0 `[ADD]` Macro declaration forms - **DONE 2026-08-18**
Not in the original plan; the stdlib named it. Three changes in `Parser.kt`:

- **A single arm needs no braces.** The braces hold *alternatives*, so a macro with
  one has nothing for them to do:
  ```azora
  macro @arr[...$items] => std.container.array::Array(...$items)
  macro @arr { [...$items] => std.container.array::Array(...$items) }
  ```
  A `{` after the name always opens the arm list, never a brace-delimited pattern -
  that arm is written inside the list like any other.
- **A sigil in front of an arm says which spelling takes it.** `@vec` and `@vec!`
  remain different macros (the sigil is part of the name); writing their arms in one
  block only saves declaring the name twice. Arms are bucketed by sigil and each
  bucket leaves as its own `TopLevel.Meta`, the extras via `pendingTopLevels`. All five
  sigils (`! ? & * ^`) work, and separate declarations still do.
- **A template may reach through a module path.** `std.container.array::Array` in
  expression position: the dotted path picks the module, and the symbol is declared
  under its own name inside it, so the access resolves to `Array`. A *single* name
  before `::` is still scope access and still mangles (`println` ->
  `std__println`). **Limitation:** the module path is checked for shape, then dropped -
  two modules exporting the same name are not yet told apart. That is S1.5's job.

Also fixed: `parseMacroArm` ended an arm at a newline or `}` only, so the braceless
form failed at end of file. EOF is a terminator too.

**Accepted** new `frontend/MacroFormTest.kt`, 15 tests, all pass. **Suite** 1607 / 1825.

### S8.1 `[FIX]` `@applyKey`-shaped macro compiles when unused
- **Touch** `frontend/MacroExpander.kt`, `semantic/ReflectDecoExpander.kt`,
  `semantic/CtfeEvaluator.kt`
- **Do** Add the exact macro from lines 123-137 of this file as a test fixture. It must
  *compile and typecheck* even with no invocation: `reflect<Scope>.find("key")`,
  `key.type != String`, `assert { }` as an expression, a `when` returning a raw
  string block, and `${Self.typename.lowercase}` splicing. A macro body is implicitly
  `deepinline`.
- **Accept** `MacroTest.applyKeyShapeCompilesUnused`.

### S8.2 `[FIX]` `compose` / `applyKey` in azora-engine
- **Repo** `/Users/milkybit/azora/azora-engine`
- **Do** After S8.1 lands, compile the engine's updated `compose` and `applyKey` macros
  as-written and fix the *compiler*, not the macros.
- **Accept** engine test suite.

---

## Phase 9 - Flip the switch (the only phase that touches `std/`)

### S9.1 Migrate `std/` to the new syntax
- `impl ctor/dtor` -> in-impl `ctor`/`dtor`: `std/container/map.az:256,262,492,498,701`,
  `std/container/queue.az:39`
- `impl ReflectedParam::` -> plain `impl` statics: `std/reflection.az:234`
- `await(t1)` -> `await t1`: `std/concurrency/async.az:84-85`
- `arrayOf` (12 sites) -> `.() * N`
- bare field reads -> `self.` qualified (S7.2 fallout - expect the widest diff)
- one-`impl`-per-file splits (S5.9 fallout - expect new files)
- `use as` in doc comments: `std/core.az:249,257,284`
- Regenerate: `./gradlew generateAzStdlib --rerun-tasks`

### S9.2 Migrate tests and examples
- 16 test files referencing `realm`, `examples/`, `README.md` snippets
  (`ReadmeSnippetTest`), `WebsiteExamplesTest`, `PlaygroundExamplesTest`

### S9.3 Flip `legacySyntax` to `false`, delete every legacy branch
- Remove the brace import group `{...}` in favour of `[...]`
- Delete all `[DROP]` compatibility code paths and their flag checks
- **Accept** `./gradlew :compiler:desktopTest` fully green with no legacy path reachable

### S9.4 Tooling and docs final pass
- `azls/`, `/Users/milkybit/azora/azora-idea-plugin` (bump `LANGUAGE_VERSION`, run
  `RealSourcesSmokeTest` against the migrated `std/`), `DIPs/*.MD`, `README.md`,
  the book/playground/docs/landing repos ([[azora-lang docs ecosystem]])

---

## Phase 10 - azora-engine (last, after the language is settled)

### S10.1 `[ADD]` Auto-registration of compose components
- **Repo** `/Users/milkybit/azora/azora-engine` - **not** azora-lang
- **Do** Components used in a `compose` block register themselves without an explicit
  registration call. Scope against `azora-engine/packages/azora-ecs/src/ecs.az`. If it
  turns out to need a compiler-side "all types implementing spec S" reflection query,
  that is a new azora-lang step opened at that point - do not pre-build it.
- **Accept** engine ECS test.

---

## Sequencing summary

```
Phase 1 (imports) ─┬─> Phase 2 (module axis) ─┬─> Phase 3 (kill realm)
                   │                          │
                   └──────────────────────────┴─> Phase 4 (. vs ::)
Phase 5 (impl)  ───┬─> independent of 1-4, but S5.7 needs Phase 4's `.` statics
Phase 6 (memory) ──┤   S6.7 needs Phase 4 (`::data`)
Phase 7 (sugar) ───┤   independent
Phase 8 (macros) ──┘   S8.1 independent; S8.2 needs 5+6+7
                       Phase 9 needs everything
                       Phase 10 (engine) needs Phase 9
```

**Highest risk, do early to learn:** S2.4 (`exposed import` removal may be load-bearing
for stdlib injection), S5.9 (one inherent impl per pack - measure the stdlib cost first),
S7.2 (mandatory `self.` - widest stdlib diff), S7.5 (underscores vs. mangling).

**All open questions are now settled** (2026-08-18): `using` is reserved-only, legal in
macro declarations alongside `with`/`without`/`by` (S7.8); the one-impl rule covers only
inherent `impl X { }` for `pack X { }`, with spec impls and derives unlimited (S5.9);
compose auto-registration is engine work, deferred to Phase 10 (S10.1).
