# Azora Compiler Intrinsics - DIP Implementation Profile

> Status: **implemented**, except where marked. This is the `Compiler` bridge
> target's other half: what the compiler itself provides, under which names, and
> what a backend must do about each.

`BRIDGE_DIP.MD` describes how a declaration says *"someone else provides this"*.
When that someone is `Target.Compiler`, this document says who, what and where.

An intrinsic is a name the compiler knows the meaning of. It is not looked up in
any module and cannot be shadowed by writing a function with the same name -
which is why the internal ones are spelled with a leading `__`, a prefix no
user-written name should use.

---

## 1. Three ways the compiler provides something

They differ in what the author writes and in where the meaning lives.

| kind                   | written as                      | meaning lives in            |
|------------------------|---------------------------------|-----------------------------|
| **Declared intrinsic** | `bridge func` in `std`          | each backend, keyed by name |
| **Internal intrinsic** | never written; the parser emits | each backend, keyed by name |
| **Generated code**     | a decorator or bodyless `impl`  | a deriver, as ordinary AST  |

The third is the one to reach for. A deriver writes Azora that the author could
have written, so every backend sees ordinary checked code and no backend has to
learn anything. An intrinsic is what is left when that is impossible - when the
operation has no Azora spelling at all.

---

## 2. Declared intrinsics

Written in the standard library as a bodyless `bridge func`, so they carry a
signature, documentation and a stability decorator like any other API:

```azora
/** Number of characters in `value`. */
@Experimental(since: "0.1")
bridge func stringLength(value: String): Int
```

The author calls `stringLength(s)`. The compiler recognises the name and
supplies the body per backend.

### 2.1 The string and character primitives

`stringLength` `charAt` `ord` `chr` `isDigit` `isAlpha` `substring`
`startsWith` `endsWith` `contains` `indexOf` `toUpper` `toLower` `trim`
`replace` `split` `toChars` `fromChars`

These exist as intrinsics because `String` has no Azora-level representation to
write them against. Under LLVM they are emitted as runtime helpers rather than
declared - `LlvmCodegen.stringIntrinsics` is the list that suppresses the
`declare`.

### 2.2 The math primitives

`sin` `cos` `tan` `asin` `acos` `atan` `sqrt` `cbrt` `hypot` `exp` `log`
`powr` `fill`

Each backend answers differently, and the differences are the reason the
intrinsic exists rather than an Azora implementation:

| backend     | how                                                                        |
|-------------|----------------------------------------------------------------------------|
| interpreter | a table of host implementations, in `IrInterpreter.externImpls`            |
| LLVM        | `declare double @sqrt(double)`, with the mangled Azora symbol calling it   |
| Wasm        | native where the instruction set has it, software implementation otherwise |

The LLVM path matters: `scope std { bridge func sqrt(…) }` mangles to
`std__math__sqrt`, a symbol nothing provides. Rather than ship a hand-written C
shim, the compiler declares the libm function and *defines* the mangled name as
a call to it. Nothing has to be linked beyond libm.

Matching is by the name's last underscore-separated segment, its arity, and an
all-`Double` signature. An unrelated user extern called `log` with a different
signature keeps its own linkage. `powr` is Azora's real-exponent power and maps
to libm's `pow`.

The interpreter's table also carries the **libm spellings** - `fabs`, `fmod`,
`fmin`, `fmax`, `atan2`, `hypot`, `log2` - so a `bridge .C` naming the C
function rather than an Azora alias still runs under the interpreter. Without
them such code could only be tested by a native build.

### 2.3 Reflection

```azora
bridge func<T> reflect(includeInherited: Bool = false): Reflected<T>
```

`reflect<T>` is a compile-time handle, not a runtime value. The parser
rewrites it and its members into internal intrinsics (§3.4).

---

## 3. Internal intrinsics

Never written by an author. The parser or IR generator emits them while
desugaring a syntactic form, and each backend implements them by name. They are
the ones with the `__` prefix.

### 3.1 Memory

| name            | from                | meaning                              |
|-----------------|---------------------|--------------------------------------|
| `__alloc`       | `alloc expr`        | heap-allocate, yielding `T*`         |
| `__allocBuffer` | `alloc T(count)`    | a zero-initialised buffer of `count` |
| `__purge`       | `purge expr`        | release                              |
| `__deref`       | `ptr.*`             | read through a pointer               |
| `__derefAssign` | `ptr.* = v`         | write through a pointer              |
| `__ptrAdd`      | `ptr + n`           | offset                               |
| `__ptrSub`      | `ptr - n`           | offset backwards                     |
| `__ptrDiff`     | `p1 - p2`           | distance                             |
| `__isolated`    | `v.clone()`, `Copy` | an independent deep copy             |

`__isolated` is the one shared by two surfaces: `.clone()` lowers to it when the
type's `Clone` conformance has no written member, and implicit `Copy`
duplication uses the same call.

### 3.2 Control and values

| name              | from                    | meaning                         |
|-------------------|-------------------------|---------------------------------|
| `__nullCoalesce`  | `a ?? b`                | first non-null                  |
| `__safeMember`    | `a?.b`                  | member read that tolerates null |
| `__isCheck`       | `x is T`                | runtime type test               |
| `__dynCast`       | `x as? T`               | checked cast, `T?`              |
| `__panic`         | `panic "msg"`           | unrecoverable abort             |
| `__null`          | internal                | the null sentinel               |
| `__tag`           | internal                | a variant's discriminant        |
| `__self`          | a generated constructor | the value under construction    |
| `__displayString` | `DisplayDeriver`        | the generated render member     |

### 3.3 Concurrency and services

| name       | from          | meaning                                  |
|------------|---------------|------------------------------------------|
| `__launch` | `launch { }`  | fire-and-forget task, joined before exit |
| `__delay`  | `delay ms`    | suspend the current task                 |
| `__inject` | `inject Type` | resolve a DI singleton                   |
| `channel`  | `channel()`   | a buffered channel                       |
| `async`    | `async { }`   | spawn the thunk as a task                |

`channel` and `async` are the two that are **not** `__`-prefixed, because they
are called by those names in source. `channel` is a stated exception: it is
meant to live in `std.concurrency` and is blocked there on `Mutex` and `Queue`,
which the standard library does not yet define.

- ⬜ Move `channel` into `std.concurrency` once `Mutex` exists (§3.3 of the
  roadmap)

### 3.4 Compile-time

| name         | from                     |
|--------------|--------------------------|
| `__reflect`  | `reflect<T>`        |
| `__hasAnnot`  | `reflect<T>.hasAnnot<D>`  |
| `__annotMeta` | `reflect<T>.annotMeta<D>` |
| `__withAnnot` | `reflect<*>.withAnnot<D>` |

These never reach a backend. They are consumed by CTCE and the reflection
expander, and a program that still contains one after compile-time evaluation is
a compiler bug rather than a runtime call.

### 3.5 Debug

`__dbg(line)` is emitted by `DebugInstrumenter` in debug builds only, and is what
the debugger's stepping and breakpoints are attached to.

---

## 4. Generated code, not intrinsics

The preferred mechanism. A decorator or a bodyless `impl` asks the compiler to
*write Azora*, which is then checked and lowered like anything else.

| deriver                | trigger                     | writes                                                     |
|------------------------|-----------------------------|------------------------------------------------------------|
| `SerializationDeriver` | `@Serializable`, `@Azon…`   | `toSerialValue` / `fromSerialValue`, `toAzon` / `fromAzon` |
| `ComparisonDeriver`    | `impl [Equal, Order] for T` | `oper==`, `oper<=>`, `hash`                                |
| `CastDeriver`          | a cast spec implementation  | renames `_cast` to `operas@Target`                         |
| `DisplayDeriver`       | a `Display` implementation  | `prop __displayString`                                     |

They run in that order in `Compiler.compile`, each on the previous one's output.

### 4.1 `@Derive` - how a library opts in

A generator is selected by metadata on the decorator, not by the compiler
knowing a library's module paths or decorator names:

```azora
@Derive(
    generator: "serializer",
    role: "all",
    provider: "std",
    providerModule: "std.serializer",
    deferGeneric: true
)
annot @Serializable for .Pack bind [Serializer, AzonSerializer] { … }
```

`generator` selects an installed generator; `role` says how this decorator
participates in it; the `provider` fields say where the generated code should
import its helpers from. This is what makes the derive machinery reachable from
outside `std` - a third-party format declares its own decorator and names the
same generator.

- ⬜ The generator set is closed: `"serializer"` is the only one. Whether a
  library may install a generator, and what that would mean for compile times
  and trust, is undecided

---

## 5. Compiler-provided types

`bridge pack` and friends, declared in `std/core.az`, carrying no
implementation:

| declaration                                                     | why the compiler owns it              |
|-----------------------------------------------------------------|---------------------------------------|
| `bridge pack Unit`                                              | the empty type                        |
| `bridge pack Any`                                               | the erased top type                   |
| `bridge pack Self`                                              | the receiver's type in a signature    |
| `bridge pack Type`                                              | a type as a compile-time value        |
| `bridge pack Array<T, N>`                                       | storage supplied by each backend      |
| `bridge enum Target`                                            | the bridge targets themselves         |
| `bridge enum DecoTarget`                                        | what a decorator may be applied to    |
| `bridge enum TestMethod`                                        | `This` / `All` test aggregation       |
| `bridge enum LogLevel`                                          | `inline trace` levels                 |
| `bridge annot @Stable` / `Experimental` / `Since` / `Deprecated` | stability metadata the compiler reads |
| `bridge annot @EnforceNumFields`                                 | a checked field-count constraint      |
| `bridge annot @Derive`                                           | §4.1                                  |

None can be constructed directly, and none is auto-derived over: a bridge pack
has no fields the compiler owns, so field-wise reasoning would be a guess.

---

## 6. What is not specified

- ⬜ **No intrinsic registry.** The set is whatever the three backends happen to
  handle. Nothing enumerates it, nothing checks the backends agree, and adding
  one to the interpreter alone produces a program that runs interpreted and
  fails to link natively. This is the same shape as the backend-parity gate
- ⬜ **`__` is a convention, not a rule.** Nothing rejects a user-written
  `__alloc`, and what happens if one is declared is undefined
- ⬜ **Behaviour is not specified anywhere.** `substring`'s bounds, `split`'s
  treatment of an empty separator, `round`'s tie-breaking, `chr` on a value
  outside the code-point range - each is whatever the backend does, and the
  three do not obviously agree
- ⬜ **Failure is unmodelled.** An intrinsic cannot fail: `charAt` out of range
  and `chr` of an invalid value have no stated result
- ⬜ **The math surface is partial.** `LIBM_INTRINSICS` covers 25 functions,
  `std.math` declares fewer, and the Wasm software implementations cover fewer
  again. Which is the contract is unstated
- ⬜ **No conformance tests.** An intrinsic's three implementations are never
  compared against each other

---

## 7. Rules

1. An intrinsic is recognised by name. It is not resolved through a module and
   cannot be shadowed.
2. A declared intrinsic is a `bridge func` targeting `Compiler`, carrying a full
   signature and documentation.
3. An internal intrinsic is `__`-prefixed and never appears in source.
4. Prefer generation to an intrinsic. A deriver writes ordinary Azora, so every
   backend sees checked code and learns nothing new.
5. Every backend must implement every intrinsic it can be handed. One that
   cannot is a failure of that backend, not of the call.
6. A compile-time intrinsic must not survive CTCE.

---

## 8. Related

- `BRIDGE_DIP.MD` - the declaration form, targets, and the other three
- `CTCE_DIP.MD` - what compile-time evaluation does with §3.4
- `ANNOTATIONS_DIP.MD` - `@Derive` and decorator binding
- `ROADMAPs/VERSION_0_1_ROADMAP.MD` §6.1 - the backend-parity gate these fall
  under
