# Azora Reflection - DIP Implementation Profile

> Status: **implemented and compile-time only.** There is no runtime
> reflection and none is planned. One of the five declared members - and the
> whole `ReflectedField` type beside it - is unreachable (§5).

Reflection in Azora answers questions at compile time and leaves nothing
behind. `std::reflect<T>` is not a value: it is a handle the evaluator resolves
and deletes, so a program that reflects heavily emits exactly the same code as
one that wrote the answers out by hand.

```azora
annot System

@System func movement() { … }
@System func physics()  { … }

func main() {
    inline for S in std::reflect<*>.withAnnot<System> {
        S()                    // becomes: movement(); physics()
    }
}
```

The point is that a library can find what a program declared **without the
program registering anything**.

---

## 1. `reflect` is compile-time only

```azora
fin r = std::reflect<P>()
// reflect is compile-time-only and must be followed by .hasAnnot<D> or .annotMeta<D>
```

There is no `Reflected<T>` value at run time, no type object, and no way to ask
a question of a value the compiler cannot see. Reflection is a metaprogramming
facility, not an introspection API.

That is a deliberate limit and the reason the feature costs nothing: every query
folds during compile-time evaluation, and an intrinsic that survives it is a
compiler bug rather than a runtime call.

- ⬜ The diagnostic names only `.hasAnnot` and `.annotMeta`, but `.withAnnot`,
  `.declName` and `.enclosingRealm` are equally valid. The message should list
  what is actually allowed

---

## 2. Asking about one type

### 2.1 `hasAnnot<D>` - does it carry this decorator?

```azora
inline assert std::reflect<P>.hasAnnot<Marked> { "P is marked" }
inline assert !std::reflect<Q>.hasAnnot<Marked> { "Q is not" }
```

Folds to a boolean.

### 2.2 `annotMeta<D>` - what did it write on the decorator?

```azora
annot Tagged { fin label: String = "none" }

@Tagged(label: "alpha")
pack P { … }

inline fin l = std::reflect<P>.annotMeta<Tagged>.label     // "alpha"
```

Reads back the argument that declaration passed, or the decorator's default
where it passed none. This is what keeps a decorator the **single place** the
information is written: the library reads it back rather than asking the author
to repeat it in a registration call.

### 2.3 `enclosingRealm`

```azora
inline fin r = std::reflect<P>.enclosingRealm
```

The realm a declaration sits in, or null.

---

## 3. Asking about the program

```azora
inline for S in std::reflect<*>.withAnnot<System> { … }
```

`reflect<*>` is a **whole-program** handle, and `withAnnot<D>` enumerates every
declaration carrying `D` - packs, enums and free functions alike. The loop
unrolls once per declaration with the loop variable bound to its name.

Inside the body, `std::reflect<S>` resolves against the bound declaration:

```azora
inline for S in std::reflect<*>.withAnnot<System> {
    std::println(std::reflect<S>.declName)      // "movement", then "physics"
}
```

`.declName`, `.hasAnnot<D>` and `.annotMeta<D>` are what a bound declaration
answers. Two separate loops enumerate in the same order, so an index chosen in
one identifies the same declaration in the other - a property the whole scheme
rests on, and one the tests pin.

The pass attaches no meaning to any decorator. It knows nothing about any
library, and runs after injection, so declarations decorated in any module are
visible.

### 3.1 It unrolls into statements, not declarations

The loop works **inside a function body**. At top level it does not:

```azora
inline for S in std::reflect<*>.withAnnot<System> {
    func run$S() { S() }        // Unknown compile-time list 'std'
}
```

So reflection can generate *calls* but not *declarations*. A library that wants
a function per decorated type cannot write one.

- ⬜ Decide whether top-level unrolling over `withAnnot` should work. `inline for`
  itself does unroll at top level over a type list; it is the `reflect<*>` list
  that is not recognised there

---

## 4. What it produces

Ordinary static code. `S()` over two decorated functions becomes
`movement(); physics()` - two direct calls, no function values, no table, no
indirection. It lowers to every backend without any of them knowing reflection
exists.

That is why this is worth having over a runtime registry: the registry costs a
list, an indirect call and an initialisation order problem, and this costs
nothing at all.

---

## 5. Declared and unreachable

`std/reflection.az` declares five members on `Reflected<T>`. One cannot be
used, and it is the one a reader is most likely to reach for.

| member           | state                              |
|------------------|------------------------------------|
| `hasAnnot<D>`     | ✅ works                            |
| `annotMeta<D>`    | ✅ works                            |
| `withAnnot<D>`    | ✅ works on `reflect<*>`, in a body |
| `enclosingRealm` | ✅ accepted                         |
| `fields`         | ⬜ **unreachable**                  |

```azora
inline for f in std::reflect<P>.fields { … }
// inline for requires a literal range, got Member

std::println(std::reflect<p>.fields.length)
// reflect is compile-time-only and must be followed by .hasAnnot<D> or .annotMeta<D>
```

`ReflectedField` and its `name` / `value` props are declared alongside it and are
equally unreachable, since nothing can produce one.

- ⬜ **`fields` and `ReflectedField` are declared and unusable.** Either
  implement iteration over them or delete the declarations - a standard library
  that declares a member no program can reach is the same defect as one that
  names a type it does not define
- ⬜ `reflect<T>(includeInherited: Bool = false)` takes a parameter that nothing
  reads, left from the removed inheritance model

---

## 6. What is not specified

- ⬜ **No declaration generation** (§3.1)
- ⬜ **No enumeration except by decorator.** There is no "every pack", no "every
  implementer of this spec", no filter by realm or module. `withAnnot<D>` is the
  only query, so discovering anything requires it be decorated first
- ⬜ **No method or signature reflection.** A declaration's members, parameter
  types and return type are not askable
- ⬜ **`annotMeta` reads one decorator.** A declaration carrying several is asked
  about each by name; nothing enumerates them
- ⬜ **Order is declaration order**, which the tests rely on, but nothing states
  it as a guarantee - and across modules it is injection order
- ⬜ **No interaction with generics.** `reflect<Vec<Int>>` versus `reflect<Vec>`
  is unspecified

---

## 7. Rules

1. `reflect` is compile-time only. A query that reaches run time is an error.
2. `std::reflect<T>` asks about one declaration; `std::reflect<*>` about the
   program.
3. `withAnnot<D>` enumerates every declaration carrying `D`, in declaration
   order, and is the only enumeration there is.
4. `inline for` over `withAnnot<D>` unrolls in a function body, binding the loop
   variable to each declaration's name.
5. Inside such a loop, `reflect<S>` answers `.declName`, `.hasAnnot<D>` and
   `.annotMeta<D>` for the bound declaration.
6. Every query folds away; the emitted code is what the author could have
   written by hand.

---

## 8. Related

- `CTCE_DIP.MD` - the evaluator these queries fold in
- `ANNOTATIONS_DIP.MD` - `annot`, and the metadata `annotMeta` reads back
- `COMPILER_INTRINSIC_DIP.MD` §3.4 - `__reflect`, `__hasAnnot`, `__annotMeta`,
  `__withAnnot`
- `ROADMAPs/VERSION_0_1_ROADMAP.MD` §2.6


// condition effects
effect mustClose == true { // we have to add effect cond, when this cond first became true, then it executes
azora::engine::exit()
}

// keys and multiple keys
remember var expanded = false by item.id
remember var scroll = 0 by page.id
remember var state = State() by user.id
remember var state = State() by [user.id, page.id]