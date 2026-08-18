# Azora Casts and Conversions - DIP

> Status: **partly implemented** in `0.0.5`. §2 describes what the compiler does
> today; §4 onward is proposed.
> Companion to `OPERATOR_OVERLOADING_DIP.MD` (which owns the operator specs).
> Supersedes `TYPE_DIP.MD` §9.

Azora has five ways to turn a value of one type into a value of another. That is
not four too many - they answer genuinely different questions - but until now
nothing wrote down which question each answers, and two of them were the same
mechanism spelled twice.

The rule this document exists to state:

> A **cast** is about representation. A **conversion** is about meaning.
> A cast never takes ownership and never allocates; a conversion may do both.

---

## 1. The five spellings

| spelling        | kind       | may allocate | may consume | fails how         |
|-----------------|------------|--------------|-------------|-------------------|
| `value as T`    | cast       | no           | no          | compile error     |
| `value as? T`   | cast       | no           | no          | `null` at runtime |
| `value as* T`   | cast       | no           | no          | compile error     |
| `value.into<T>` | conversion | yes          | yes         | it does not       |
| `T::from(v)`    | conversion | yes          | yes         | it does not       |

The first three are operators, governed by the `Cast` / `CheckedCast` /
`BitCast` specs (§4). The last two are `std.convert`'s `Into` and `From`.

### 1.1 `as` - static conversion

Representational and total. A numeric widening, an enum to its name, a pack to a
layout-compatible one. A compile error when the compiler cannot see how the two
representations relate.

```azora
fin n: Long = small as Long
fin s: String = Weekday.Monday as String
```

### 1.2 `as?` - checked conversion

The same question asked of a value whose dynamic type is not known statically.
Yields `T?`: the value when it really is a `T`, `null` when it is not.

```azora
fin maybe: Circle? = shape as? Circle
```

`as?` is **not** `as` with a null check bolted on. A type may be convertible one
way and not the other, which is why §4 keeps them separate specs.

### 1.3 `as*` - bit reinterpretation

The bits, read as another type. `T.sizeOf == U.sizeOf` is required and checked
at the lowering site. This is the one that can be wrong in ways the type system
cannot catch, and it reads like it.

```azora
fin bits: ULong = value as* ULong
```

### 1.4 `.into<T>` - semantic conversion

Ownership-aware and free to allocate. This is where a *domain* conversion goes:
`Celsius` into `Fahrenheit`, `Path` into `String`, a builder into the thing it
builds. It may consume the receiver.

Declared by implementing `Into<T>` (`std/convert/convert.az`):

```azora
spec Into<T> {
    prop into<T>[self: Self&]: T
    use into<T> as "to${T.typeName}"
}

impl Into<String> for Label {
    prop into[self: Self&]: String { return "Label(" + self.value + ")" }
}

label.toString      // the `use as` alias
```

The member is a `prop`, so no parentheses. `use into<T> as "to${T.typeName}"`
gives it the call-site name callers already write - `.toString` for
`Into<String>`, `.toInt` for `Into<Int>` - while the member keeps its own
canonical name.

### 1.5 `T::from(v)` - canonical construction

The direction `Into` cannot express. `From` *builds* a value rather than
consuming one, so there is no `self` and its member is a **static**:

```azora
spec From<T> {
    func from(value: T): Self
}

impl From<String> for Username:: {
    func from(value: return String): Self {
        return Username(value: take value)
    }
}

fin username: Username = Username::from(lend text)
```

`From` carries no `use as`: `Type::from` is already the name callers write, so
there is no alias to generate. The `::` on the impl target is the same `::` the
use site writes.

---

## 2. What the compiler does today

Accurate as of `0.0.5`, because the rest of this document is a delta against it.

- **The three operators exist and dispatch.** `Expr.Cast` carries a `CastKind`
  (`STATIC`, `DYNAMIC`, `REINTERPRET`; `Parser.kt:7305`). Lowering asks the
  operand's type for `operas` / `operas?` / `operas*` (`IrGenerator.kt:1321`),
  and **each form asks only its own member**, so the three never stand in for
  one another. A `DYNAMIC` cast with no overload falls back to `__dynCast`,
  the runtime type test.
- **`as?` widens the result** to `T?` in the resolver (`TypeResolver.kt:2039`),
  and again at lowering when the operator did not say.
- **`cast` / `dyncast` / `bitcast` are gone.** Three `bridge func`s that
  duplicated the operators - the file's own header called them sugar for
  `as` / `as?` / `as*` - with no callers anywhere in the tree. `std/cast.az` and
  `rewriteCastIntrinsic` were deleted in `0.0.5`. `TYPE_DIP.MD:120` still says
  the operators desugar to them; they do not.
- **`Into` and `From` are as §1.4 and §1.5 describe.** Both were rewritten in
  `0.0.5`: `Into` to a generic `prop` with its alias on a `use … as` line,
  `From` to a receiver-less (static) `func`.

---

## 3. The defect: two declaration forms for one operator

`TYPE_DIP.MD:133` documents the operator's target as a parameter of the
*operator*:

```azora
impl Celsius {
    oper as<U> [self: Self&] = Fahrenheit(self.degrees * 1.8 + 32.0)
}
```

while `Parser.kt:2725` implements a second, unrelated form:

```azora
impl as Fahrenheit for Celsius { self& -> … }
```

Both are alive. Neither is checkable: in the first the body must be correct for
*every* `U` while naming exactly one of them, and `oper as<U>` can never be a
bound; the second is a bespoke declaration with a bespoke receiver syntax that
resembles nothing else in the language - it is the last surviving use of the
in-brace receiver the bracket redesign removed.

---

## 4. Proposal: the three cast specs

Per `OPERATOR_OVERLOADING_DIP.MD` §12.1's rule - *group when only the symbol
differs, split when the promise differs*:

```azora
/** Converts to `To`. Total: it cannot fail. */
@Since("0.1")
spec Cast<To> {
    prop cast<To>[self: Self&]: To
}

/** Converts to `To` when the value permits it, and answers null when not. */
@Since("0.1")
spec CheckedCast<To> {
    prop cast<To>[self: Self&]: To?
}

/** Reinterprets the bits as a `To`. Sizes must match; checked at lowering. */
@Since("0.1")
spec BitCast<To> {
    prop cast<To>[self: Self&]: To
}
```

Three specs because there are three promises: `as` is total, `as?` can fail,
`as*` is bit-level with a size requirement. `CheckedCast` does **not**
`require Cast` - a conversion that can fail is precisely the one with no
infallible form - and `BitCast` requires neither.

```azora
impl Cast<Fahrenheit> for Celsius {
    prop cast[self: Self&]: Fahrenheit {
        return Fahrenheit(self.degrees * 1.8 + 32.0)
    }
}

c as Fahrenheit      // resolves
c as* Fahrenheit     // error: Celsius has no BitCast<Fahrenheit>
```

The target moves from the operator to the **impl**, which is what makes the body
checkable and what makes `where T: Cast<String>` expressible.

### 4.1 `FullCast`

```azora
@Since("0.1")
typealias FullCast<To> = [Cast<To>, CheckedCast<To>, BitCast<To>]
```

`where T: FullCast<ULong>` is the bound for all three. An alias, not a fourth
spec: "all three casts" is a convenient bound, not a claim about a type.

### 4.2 `impl as T for Type` is retired

Replaced by `impl Cast<T> for Type`, for the same reason `{ self& -> }` went
from spec impls: a receiver is declared where every other member declares one.

---

## 5. Casts and conversions do not fall back to one another

Deliberately, and this is what keeps five spellings from collapsing into an
unpredictable four.

- `value as T` **never** tries `Into<T>`. If it did, an innocent-looking cast
  could allocate or consume its operand.
- `value.into<T>` **never** tries `Cast<T>`. A conversion is opted into; a type
  that merely has a compatible representation has not agreed to be convertible.
- `T::from(v)` and `v.into<T>` are **not** derived from each other. Deriving
  a blanket `Into` from `From`; Azora does not, because the two differ in
  ownership - `from` constructs, `into` may consume - and a derived direction
  would silently pick one.

A type that wants both writes both. They are two lines.

---

## 6. Diagnostics

```
line 12: 'Celsius' has no 'Cast<Fahrenheit>' - cannot write 'c as Fahrenheit'
  add 'impl Cast<Fahrenheit> for Celsius { prop cast[self: Self&]: Fahrenheit { … } }'
  or, if the conversion allocates or consumes, implement 'Into' and write 'c.into<Fahrenheit>'

line 20: 'as*' requires equal sizes - Celsius is 8 bytes, Flag is 1
  a bit reinterpretation cannot change a value's width

line 27: 'impl as Fahrenheit for Celsius' was removed
  write 'impl Cast<Fahrenheit> for Celsius { prop cast[self: Self&]: Fahrenheit { … } }'

line 33: 'Username' has 'From<String>' but no 'Into<String>'
  the two directions are separate; 'Username::from(s)' is what this type declares
```

---

## 7. Implementation plan

1. **`Cast` / `CheckedCast` / `BitCast` in `std/convert`**, plus `FullCast`.
   Blocked on `where T: Spec<Args>` bounds, which are not implemented yet.
2. **Dispatch through the spec impls.** `IrGenerator.kt:1321` already looks up
   `operas` / `operas?` / `operas*`; point it at the spec members instead and
   the fallback behaviour is unchanged.
3. **Retire `impl as T for Type`** (`Parser.kt:2725`) with the §6 message. One
   occurrence in the tree, in a test.
4. **Retire `oper as<U>`** from `parseOperatorName`.
5. **Correct `TYPE_DIP.MD` §9**, which still documents the deleted intrinsics.

---

## 8. Not proposed

- **A fallible semantic conversion.** `Into` and `From` are total. `TryInto`
  needs the error model to settle first - `?!` and `Result` are two different
  answers today.
- **Blanket `Into` from `From`.** §5.
- **Implicit conversions of any kind.** All five spellings are written at the
  site the conversion happens.
- **`as` between unrelated packs by field order.** Structural casting is a
  footgun; a conversion says what it means and costs one line.
