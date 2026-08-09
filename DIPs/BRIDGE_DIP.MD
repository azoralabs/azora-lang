# Azora `bridge` - DIP Implementation Profile

> Status: **implemented**, except where marked. `bridge` is the one keyword that
> says *"someone outside Azora provides this"* - the compiler, a C library, or a
> WebAssembly host.

A declaration normally carries its own implementation. A `bridge` declaration
does not: it states a name, a signature and nothing else, and something outside
the language supplies the rest. That "something" is named by a **target**.

```azora
bridge .C {
    func sqrt(x: Double): Double
}
```

The declaration is a promise about a symbol. Azora type-checks every call
against it and then hands the call to whichever backend is being generated.

---

## 1. Two things wear the same keyword

`bridge` is used for two related but distinct jobs, and telling them apart is
the first thing to get right.

**A bridge block is FFI.** It declares extern functions and names the target
that provides them.

```azora
bridge .C {
    func @foreignName("azora_time_unix_nanoseconds")(): Long
}
```

**A bridge modifier is "the compiler already knows this".** It marks an ordinary
declaration as one the compiler provides rather than one the standard library
writes out.

```azora
bridge pack Unit
bridge spec Equal requires PartialEqual
bridge enum Target { Compiler, C, ObjectiveC, WebAssembly }
```

The two never mean the same thing. A block says *link to this*; a modifier says
*do not expect a body, I am built in*.

---

## 2. Targets

A target names who provides the implementation. It is a member of `std::Target`,
declared in `std/core.az`:

```azora
bridge enum Target {
    Compiler,
    C,
    ObjectiveC,
    WebAssembly
}
```

| target        | meaning                                           |
|---------------|---------------------------------------------------|
| `Compiler`    | the compiler itself supplies it, on every backend |
| `C`           | a C symbol, resolved by the native linker         |
| `ObjectiveC`  | an Objective-C entry point                        |
| `WebAssembly` | a host import, taken from the `env` module        |

### 2.1 Writing a target

Four spellings, all equivalent:

```azora
bridge { … }                        // no target named: Compiler
bridge .C { … }                     // the member, leading dot
bridge Target.C { … }               // qualified in full
bridge [.C, .WebAssembly] { … }     // provided by several backends
```

A bracketed list means more than one backend supplies the symbol; the targets
are recorded comma-joined. `std/time.az` uses exactly this, because the clock
comes from the C runtime natively and from the host in the browser.

A target may also be a compile-time constant already bound to a member, which
lets a build configuration choose one:

```azora
inline fin HOST = Target.WebAssembly
bridge HOST { func now(): Long }
```

A bare `bridge C { … }` is **rejected**. An unqualified identifier is only a
target if it was bound first - otherwise the diagnostic names all three ways to
write it:

```
'C' is not a bridge target at line 3 - write '.C' for the Target member,
'Target.C' in full, or bind it first with 'inline fin C = Target.<Variant>'
```

That strictness is deliberate. A mistyped target would otherwise become a
plausible-looking symbol that fails at link time instead of at the declaration.

### 2.2 The target list is duplicated on purpose

`Parser.bridgeTargetMembers` mirrors the `Target` enum, because a `bridge` is
parsed long before the standard library is available to consult. The two must be
kept in step by hand; the mirror carries a comment saying so.

- ⬜ Derive the parser's set from `std/core.az` instead of mirroring it, or add
  a test that fails when the two drift

---

## 3. The block form

```azora
bridge .C {
    func name(params): Ret
    func @foreignName("foreign_symbol")(params): Ret
    fin @foreignName("API_VERSION"): String = "1.2.3"
}
```

Function entries are bodyless. `: Ret` may be omitted, in which case the return
type is `Unit`. A bridge may also publish initialized `fin`, `let`, and `var`
storage under an exact backend symbol.

### 3.1 Foreign names are ordinary metaprogramming

Backend symbol names are the backend's business and may not be legal Azora
identifiers. `std.core` therefore provides a normal declaration-position macro:

```azora
bridge [.C, .WebAssembly] {
    func @foreignName("clock_gettime")(): Long
    fin @foreignName("API_VERSION"): String = "1.2.3"
}
```

The macro expands to two adjacent declaration fragments: the exact backend
name followed by the local Azora name. The compiler supplies the declaration
target as the macro parameter. The standard macro selects `upperCamel` for
types and `lowerCamel` for functions and values:

```azora
// generated declaration form
func "clock_gettime" clockGettime(): Long
fin "API_VERSION" apiVersion: String = "1.2.3"
bridge pack "native_HTTP_client" NativeHttpClient
```

For a function, the bridge declares the exact extern and expansion writes an
ordinary forwarding function named `clockGettime`. For storage, Azora references
`apiVersion` while LLVM emits `@API_VERSION` and WebAssembly exports
`API_VERSION`.

The compiler does **not** recognize the name `foreignName`. It accepts any
visible declaration-position macro whose expansion contains exactly two String
fragments. The standard macro is defined in `std/core.az` as ordinary Azora
meta code:

```azora
macro @foreignName { target ->
    ($name: String) =>
        $name
        inline if target.isType {
            $name.upperCamel
        } else {
            $name.lowerCamel
        }
}
```

`target` is a macro parameter supplied when the macro decorates a declaration;
`target.isType` is true for packs, specs, enums, errors, and the other type
declaration categories. Declaration-position macros return the backend name
first and the local name second. This keeps ABI naming policy in the standard
library rather than hardcoding a special foreign-name facility into the
parser. Bridge `use as` syntax does not exist. The unrelated spec-member
`use as "template"` feature is unchanged.

### 3.2 The single-function shorthand

```azora
bridge func stringLength(value: String): Int
bridge .C func fabs(x: Double): Double
```

Exactly a one-entry block. A bare `bridge func` defaults to `Compiler`, which is
what most of the standard library's primitives are. Type parameters may be
written before or after the name and are accepted but not retained - an extern
is monomorphic.

---

## 4. The modifier form

`bridge` in front of a declaration marks it compiler-provided. What that changes
depends on the kind.

| form                   | effect                                                 |
|------------------------|--------------------------------------------------------|
| `bridge pack`          | no struct is emitted, and it **cannot be constructed** |
| `bridge func`          | bodyless; becomes an extern                            |
| `bridge impl`          | registers its members, emits no IR                     |
| `bridge spec`          | conformance does not require the members be written    |
| `bridge enum`          | accepted marker; otherwise an ordinary enum            |
| `bridge annot`         | accepted marker; otherwise an ordinary annotation      |
| `bridge typealias`     | accepted marker                                        |
| `bridge variant enum`  | accepted marker                                        |
| `bridge oper` / `prop` | a member the compiler supplies                         |

### 4.1 `bridge pack`

```azora
bridge pack Unit
bridge pack Any
bridge pack Array<T, N: Int> { fin data: T* }
```

The type exists for the type checker and has no runtime representation of its
own - the backend supplies the storage. Constructing one directly is an error:

```
compiler bridge pack 'Any' cannot be constructed directly
```

A bridge pack is also excluded from the derivations that read a type's fields:
`Clone`/`Copy` are not auto-derived for it, and neither is comparison. It has no
fields the compiler owns, so field-wise reasoning would be a guess.

### 4.2 `bridge spec`

```azora
bridge spec Equal requires PartialEqual
bridge spec Clone { func clone[self: Self&](): Self }
```

Implementing a bridge spec does not require writing its members - the compiler
supplies the default. `impl [Clone] for Point` is a complete implementation, and
`Point.clone()` resolves to the compiler's deep copy.

For a non-bridge spec the members are required, and their absence is reported at
the `impl`. The exception exists because a bridge spec's contract is with the
compiler, not with the author.

---

## 5. What each backend does with an extern

Every `bridge` signature lowers to one `IrTopLevel.Extern`. From there the three
backends diverge, and the divergence is the point: the same declaration is a
link-time symbol natively and a host import in the browser.

### 5.1 Interpreter

A table of built-in implementations, looked up by name and then by the name's
last underscore-separated segment - so `std__math__sqrt` finds `sqrt`. It covers
the C-math surface (`sin`, `sqrt`, `pow`, …) and the libm spellings (`fabs`,
`fmod`, `atan2`, `hypot`, …) so that a `bridge .C` naming the C function still
runs under the interpreter. An extern with no entry is an error at the call.

### 5.2 LLVM

- A math extern whose signature is all-`Double` and matches a libm name by arity
  becomes a `declare double @name(…)`, and the mangled Azora symbol is defined as
  a call to it. `std::sqrt` therefore needs no hand-written C shim.
- The string primitives (`stringLength`, `charAt`, `substring`, …) are emitted as
  runtime helpers, so their `declare` is skipped.
- Everything else becomes a plain `declare`, left to the linker.

### 5.3 WebAssembly

- A float operation Wasm has natively is emitted directly.
- A math function it lacks gets a software implementation the compiler writes.
- Everything else becomes `(import "env" "name" …)`, which the host must supply.

That last line is the contract the browser embedder has to meet, and it is
currently discovered by reading the generated WAT.

- ⬜ Document the required host imports, or emit a manifest of them

---

## 6. What is not specified

The blocking items for `0.1`. Each is a promise the language currently makes
without saying what it means.

- ⬜ **The C ABI mapping is unwritten.** Which Azora type corresponds to which C
  type, what `String` is across the boundary, whether a `pack` may be passed by
  value, and what the alignment and padding rules are
- ⬜ **Ownership across the boundary is undefined.** Who frees a pointer the
  callee returns, whether a borrow may be handed out, and what happens to a
  value taken by a foreign function
- ⬜ **Callbacks are not addressed.** Passing an Azora function to C requires a
  calling convention and a lifetime for the closure environment
- ⬜ **No safety story.** A `bridge` call is not `unsafe`, so a program can enter
  arbitrary foreign code without saying so. Decide whether extern calls require
  `unsafe`, and whether a target may declare itself safe
- ⬜ **Failure is unmodelled.** An extern cannot fail: there is no `?!` on a
  bridge signature and no mapping from `errno` or a host exception
- ⬜ **`ObjectiveC` has no backend.** It is a valid target that nothing consumes
- ⬜ **Variadic externs** (`printf`) are not supported and not rejected with a
  diagnostic that says so
- ⬜ **Struct layout is not guaranteed.** `bridge pack` has no `repr` control, so
  a type shared with C has no stated layout
- ⬜ The generator that would make all of this bearable is
  §7.4's `azora bindgen`: bindings derived from a header rather than
  transcribed from one

---

## 7. Rules

1. A bridge target is a `std::Target` member, written `.X`, `Target.X`, or a
   constant bound to one. A bare identifier is not a target.
2. A bridge signature is bodyless. Its return type defaults to `Unit`.
3. A declaration-position name macro must expand to one two-string
   `[backendName: localName]` mapping. No local-name policy is inferred by the
   bridge parser.
4. `bridge` on a declaration means the compiler provides it: no body, no struct,
   no required spec members, and no field-wise derivation.
5. A `bridge pack` cannot be constructed.
6. An extern is monomorphic. Type parameters parse and are discarded.
7. Bridge storage keeps its exact symbol in LLVM and is exported under that
   exact name in WebAssembly.
8. Every extern reaches every backend. A backend that cannot provide one is a
   failure at that backend, not at the declaration.

---

## 8. Related

- `COMPILER_INTRINSIC_DIP.MD` - the `Compiler` target's other half: what the
  compiler provides and under what names (currently empty)
- `TYPE_DIP.MD` - the type spellings a bridge signature may use
- `ROADMAPs/VERSION_0_1_ROADMAP.MD` §3.8 - FFI's release blockers, and the
  binding generator
