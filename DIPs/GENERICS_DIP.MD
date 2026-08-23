# Azora Generics and Variance DIP

> Status: **Proposed**
> Scope: generic declarations and applications, constraints, inference,
> defaults, const generics, variadic type packs, associated types, generic
> implementations, variance, ownership and borrowing, lowering, ABI, tooling,
> diagnostics, and migration
> Related: `TYPE_DIP.MD`, `VARIADICS_DIP.MD`,
> `OWNERSHIP_BORROWING_DIP.md`, `GTC_DIP.MD`, and `AZLS_DIP.MD`

## 0. Decision summary

Azora generics are statically checked, invariant by default, and specialized
for concrete use by the native compiler. They support three parameter kinds:

1. ordinary type parameters, such as `T`;
2. compile-time value parameters, such as `N: Int`;
3. variadic type packs, such as `...T`.

The language keeps the existing core spellings:

```azora
pack Box<T> { fin value: T }
func<T> first(value: T): T { return value }
pack Array<T, N: Int> { ... }
pack Tuple<...T> where (...T).size >= 2 { ... }
spec Arithmetic<Rhs = Self, Out = Self> { ... }
func<T> sort(values: List<T>&): List<T> where T: Order { ... }
```

Declaration-site variance is added for nominal generic types:

```azora
spec Source<out T> {
    func !.next(): T
}

spec Sink<in T> {
    func !.send(value: T)
}

pack Cell<T> {
    var value: T
}
```

- `out T` is covariant: the API produces `T` and cannot accept arbitrary `T`.
- `in T` is contravariant: the API consumes `T` and cannot expose a `T`.
- plain `T` is invariant and can flow both ways.

If `Cat` conforms to the `Animal` spec, then:

```text
Source<Cat> <: Source<Animal>
Sink<Animal> <: Sink<Cat>
Cell<Cat> and Cell<Animal> are unrelated
```

Azora does not need class inheritance for this. Pack-to-spec conformance and
spec inheritance provide the subtype relationships variance operates on.

Variance never creates copies and never bypasses ownership:

- an owned covariant upcast transfers the value unless it is `Copy`;
- a shared borrow preserves safe covariance;
- an exclusive mutable borrow is invariant;
- raw mutable pointers are invariant;
- mutable containers remain invariant;
- no diagnostic fix inserts `clone()` implicitly.

The compiler validates every variance annotation from the public API surface.
It does not infer public variance silently. Use-site projections such as
`MutableList<out Animal>` and wildcard types are not part of the initial design;
read-only specs and shared borrows provide the capability-safe view instead.

---

## 1. Current implementation profile

Azora already implements or parses a broad but uneven generic system.

### 1.1 Existing capabilities

The current frontend represents generic parameters on:

- packs;
- specs;
- functions, methods, constructors, properties, and operators;
- implementation blocks;
- ordinary enums and errors;
- payload variants and payload errors;
- type aliases;
- some bridge declarations and callbacks.

Current syntax includes:

```azora
pack Box<T> { ... }
func<T, U> map(value: T, transform: (T) -> U): U { ... }
spec Into<T> { ... }
impl Into<String> for Value { ... }
typealias Pair<T> = Tuple<T, T>
variant enum Option<T> { Some(T); None }
```

It also includes:

- explicit type arguments: `convert<String>(value)`;
- inference when the argument list is omitted;
- explicit inference holes: `convert<_, String>(value)`;
- default type arguments: `Rhs = Self`;
- const parameters: `N: Int` and enum-valued parameters;
- const defaults;
- variadic type parameters using the prefix form `<...T>`;
- `where` constraints and spec bounds;
- generic implementations;
- associated types declared with `assoc` and bound by an `impl`;
- compile-time type properties and type reflection.

### 1.2 Current defects this DIP resolves

The current AST usually stores `typeParams: List<String>` plus several side
collections. That makes the meaning of each parameter hard to recover. A single
parameter may separately appear in `constParams`, `constDefaults`,
`constEnums`, `typeDefaults`, `variadicParam`, or parser-only `staticParams`.

Further problems include:

- `T: Spec` written inside a type-parameter list can be parsed but its bound is
  not represented uniformly;
- generic spec arguments are sometimes parsed and then ignored by conformance;
- `SymbolTable.conformsTo` can ignore type arguments entirely;
- the constraint evaluator treats an unknown concrete constraint as accepted;
- some inference only sees a parameter when it is the entire parameter type,
  missing nested forms such as `List<T>`;
- `Any` can act as both recovery and compatibility escape, hiding failures;
- ordinary generics are described as erased even when an owned pack's physical
  layout depends on its arguments;
- variadic and conditional-layout generics follow a different specialization
  route from ordinary generics;
- variance is not represented;
- mutable and read-only standard-library APIs are not yet designed around
  variance and borrow-safe element access.

This DIP defines one coherent semantic model. Migration may preserve temporary
AST compatibility fields, but the type checker must eventually consume only the
new model.

---

## 2. Goals

1. Give every generic parameter an explicit kind and source span.
2. Make generic syntax consistent across declarations.
3. Check a generic body using declared bounds, not accidental instantiations.
4. Make every concrete constraint decidable or diagnostically rejected.
5. Support bidirectional inference without falling back silently to `Any`.
6. Preserve explicit partial inference through `_`.
7. Make const and variadic generics part of the same substitution system.
8. Compare generic conformance with full type arguments.
9. Add sound declaration-site covariance and contravariance.
10. Integrate variance with `take`, `lend`, borrows, mutation, and destruction.
11. Keep packs free from class inheritance requirements.
12. Produce predictable native layouts and destruction glue.
13. Support separate compilation and future self-hosting.
14. Give AZLS exact generic diagnostics, inferred arguments, and fixes.
15. Keep existing useful syntax source-compatible where it has sound semantics.

## 3. Non-goals

The baseline design does not include:

- higher-kinded parameters such as `F<_>`;
- arbitrary dependent types;
- Rust-style lifetime parameters;
- use-site wildcard/projection types;
- implicit cloning to satisfy generic ownership;
- C++-style unconstrained templates checked only after substitution;
- user-defined overlapping specialization;
- negative implementations;
- runtime reflection for every type parameter;
- generic values crossing a foreign ABI without a concrete wrapper.

These can be proposed separately if real use cases justify their complexity.

---

## 4. Unified parameter model

The frontend replaces parallel name/set/map fields with structured parameters:

```kotlin
data class GenericParameter(
    val name: String,
    val kind: GenericParameterKind,
    val variance: Variance = Variance.INVARIANT,
    val bounds: List<GenericBound> = emptyList(),
    val default: GenericArgument? = null,
    val span: SourceSpan,
)

sealed interface GenericParameterKind {
    data object Type : GenericParameterKind

    data class Const(
        val valueType: TypeRef,
    ) : GenericParameterKind

    data object TypePack : GenericParameterKind
}

enum class Variance {
    INVARIANT,
    COVARIANT,
    CONTRAVARIANT,
}

sealed interface GenericArgument {
    data class Type(val type: TypeRef) : GenericArgument
    data class Const(val value: ConstValue) : GenericArgument
    data class Types(val types: List<TypeRef>) : GenericArgument
    data object InferenceHole : GenericArgument
}
```

Declarations use `List<GenericParameter>`. Applications use
`List<GenericArgument>`. Substitution maps parameter IDs, not strings, to
arguments.

Every parameter receives a compiler identity distinct from its spelling. This
prevents a nested generic declaration from accidentally substituting an outer
parameter with the same name.

### 4.1 Parameter ordering

Parameters are written in this order:

1. required ordinary type parameters;
2. required const parameters;
3. parameters with defaults;
4. at most one type pack, which must be last.

The compiler may allow a const parameter before an ordinary type when the const
type depends on the earlier type model in the future, but no required parameter
may follow a defaulted parameter unless it is always inferable and the rule is
explicitly expanded by a later DIP.

### 4.2 Names and shadowing

Uppercase parameter names are conventional, not semantically required. A
generic member may introduce new parameters but may not shadow a visible outer
generic parameter. Reusing `T` in a nested scope is diagnosed because it makes
constraints, generated names, and hover information ambiguous.

---

## 5. Surface grammar

Conceptual grammar:

```text
GENERIC_PARAMS  ::= '<' GENERIC_PARAM (',' GENERIC_PARAM)* '>'

GENERIC_PARAM   ::= VARIANCE? IDENTIFIER TYPE_BOUND? TYPE_DEFAULT?
                  | IDENTIFIER ':' CONST_TYPE CONST_DEFAULT?
                  | VARIANCE? '...' IDENTIFIER PACK_BOUND?

VARIANCE        ::= 'out' | 'in'
TYPE_BOUND      ::= ':' TYPE
TYPE_DEFAULT    ::= '=' TYPE
CONST_DEFAULT   ::= '=' CONST_EXPR
PACK_BOUND      ::= ':' TYPE

GENERIC_ARGS    ::= '<' GENERIC_ARG (',' GENERIC_ARG)* '>'
GENERIC_ARG     ::= TYPE | CONST_EXPR | '_' | '...' TYPE_PACK_EXPR

WHERE_CLAUSE    ::= 'where' CONSTRAINT ('&&' CONSTRAINT)*
```

Examples:

```azora
pack Box<T> { ... }
pack Pair<A, B> { ... }
spec Source<out T> { ... }
spec Sink<in T> { ... }
spec Arithmetic<Rhs = Self, Out = Self> { ... }
pack Array<T, N: Int> { ... }
pack Matrix<T, R: Int, C: Int, O: MatrixOrder = .RowMajor> { ... }
pack Tuple<...T> where (...T).size >= 2 { ... }
```

`out` and `in` are recognized as variance only inside a generic declaration
parameter list. They remain their existing tokens and keep their other grammar
meanings elsewhere.

### 5.1 Meaning of `:` in a parameter

The declaration resolved to the right of `:` determines the parameter kind:

- a spec type makes an ordinary type parameter with a conformance bound;
- an allowed const value type (`Int`, `UInt`, `Bool`, `Char`, or a payload-free
  enum) makes a const parameter;
- any other concrete pack/type is rejected as a bound unless Azora later adds
  nominal pack inheritance.

Therefore these are unambiguous after name resolution:

```azora
func<T: Order> sort(value: T)       // T is a type bounded by spec Order
pack Array<T, N: Int>               // N is a compile-time Int value
pack Matrix<T, O: MatrixOrder>      // O is a compile-time enum value
```

A symbol cannot simultaneously be a spec and an allowed const value type in the
same scope. If lookup is ambiguous for another reason, the declaration receives
a name-resolution diagnostic rather than guessing its parameter kind.

### 5.2 Inline bounds and `where`

These are equivalent:

```azora
func<T: Order> compare(a: T&, b: T&): Compare { ... }

func<T> compare(a: T&, b: T&): Compare
where T: Order {
    ...
}
```

Use inline bounds for one short condition and `where` for several parameters,
generic spec arguments, associated types, or const expressions.

Multiple spec bounds use a type tuple because this is a type-list position:

```azora
func<T> index(value: T&): ULong
where T: (Equal, Hash) {
    ...
}
```

This follows the language-wide decision that type groups use parentheses, as in
`derives (Equal, Hash, Display)`. The older `[Order, Clone]` bound-list spelling
is deprecated and migrated to `(Order, Clone)`.

Independent constraints are joined only with `&&`:

```azora
func<I, T> collect(iterator: I): List<T>
where I: Iterator && I::Item == T {
    ...
}
```

A comma never means logical conjunction in a `where` clause. Commas remain
separators inside generic parameter lists, argument lists, and type tuples.

---

## 6. Where generics are allowed

### 6.1 Nominal declarations

Packs, specs, ordinary enums, payload variants, errors, payload errors, and
type aliases may declare generic parameters:

```azora
pack Box<T> {
    fin value: T
}

spec Serializer<T> {
    func &.encode(value: T&): String
}

variant enum Option<out T> {
    Some(T)
    None
}

variant error DecodeError<out T> {
    Invalid(T)
    End
}

typealias Pair<T> = (T, T)
```

Payload-free enums may retain generic parameters only when the parameter affects
implemented specs, static members, reflection, or type identity. An unused
parameter is a warning because `Tag<Int>` and `Tag<String>` otherwise become
distinct types with identical values for no obvious reason.

### 6.2 Callable declarations

Free functions, methods, constructors, properties, operators, callbacks, and
eligible bridge declarations may be generic:

```azora
func<T> identity(value: T): T { return value }

impl Box<T> {
    func<U> &.map(transform: (T&) -> U): Box<U> { ... }
}
```

Variance annotations are rejected on callable parameters:

```azora
func<out T> invalid(): T // rejected
```

A generic function is not a nominal type constructor whose applications enter
a subtype relation. Its callable value type already has automatic parameter and
return variance.

### 6.3 Generic implementations

```azora
impl<T> Equal for Box<T>
where T: Equal {
    ...
}

impl<T, U> Into<Box<U>> for Box<T>
where T: Into<U> {
    ...
}
```

Every implementation parameter must be constrained by the target type, the
implemented spec, or a decidable equality/const condition. An unconstrained
parameter is rejected because it cannot be inferred for conformance selection.

### 6.4 Bridge declarations

Generic bridge declarations are allowed only when the compiler/backend supplies
their lowering or when every exported foreign symbol is wrapped at a concrete
Azora type. An open generic type never crosses C, Wasm import/export, or another
foreign ABI directly.

---

## 7. Type application and identity

### 7.1 Application

```azora
Box<Int>
Map<String, User>
Array<Float, 16>
Option<List<User>>
```

A generic declaration used as a type must receive all required arguments after
inference/defaulting. Bare `Box` is not shorthand for `Box<Any>`.

Constructor inference may omit arguments when fields and expected type uniquely
determine them:

```azora
fin explicit = Box<Int>(1)
fin inferred = Box(1)          // Box<Int>
fin expected: Box<Int> = Box(1)
```

### 7.2 Canonical identity

Generic type identity includes:

- defining module and declaration identity;
- normalized type arguments;
- normalized const values;
- complete variadic pack contents.

Aliases are transparent:

```azora
typealias UserPair = Pair<User>
```

`UserPair` and `Pair<User>` are the same type. Qualification changes lookup, not
identity after resolution.

### 7.3 Arity and argument kind

The compiler reports separately:

- missing required argument;
- too many arguments;
- type supplied to const parameter;
- const supplied to type parameter;
- pack expansion in a non-pack position;
- unresolved inference hole;
- argument violating a bound.

It does not flatten all these into “wrong generic arguments.”

### 7.4 No wildcard type

`_` is an inference request at an application site, not a persistent wildcard or
existential type:

```azora
convert<_, String>(value)
```

Every `_` must resolve before typed IR. `Array<T, *>` and Java-style `?` generic
wildcards are not part of this design.

---

## 8. Constraints and bounds

### 8.1 Nominal spec bounds

```azora
func<T> hash(value: T&): ULong where T: Hash { ... }
func<T, U> equal(a: T&, b: U&): Bool where T: PartialEqual<U> { ... }
```

Conformance includes all generic arguments. An `impl PartialEqual<String> for
UserId` does not satisfy `PartialEqual<Int>`.

This requires replacing name-only uses of `conformsTo(typeName, specName)` with
a query over resolved types and substituted spec arguments.

### 8.2 Multiple bounds

```azora
func<T> keyed(value: T&): ULong
where T: (Equal, Hash, Display) {
    ...
}
```

The tuple means logical conjunction. It is not a runtime tuple value.

### 8.3 Associated-type constraints

Associated types prevent unnecessary visible parameters:

```azora
spec Iterator assoc Item {
    func !.next(): Option<Self::Item>
}

func<I, T> collect(iterator: I): List<T>
where I: Iterator && I::Item == T {
    ...
}
```

`Self::Item` and `I::Item` are type projections resolved from one selected
implementation. An ambiguous or missing projection is a type error.

Generic associated types are not in the baseline; `assoc Item<T>` requires a
separate proposal.

### 8.4 Const constraints

```azora
pack Matrix<T, R: Int, C: Int>
where R > 0 && C > 0 && R <= 4096 && C <= 4096 {
    ...
}
```

Supported const predicates are deliberately closed and deterministic:

- equality and inequality;
- ordered comparisons;
- integer arithmetic proven safe at compile time;
- boolean conjunction/disjunction/negation;
- membership in compile-time ranges or literal sets;
- type-pack size/index conditions;
- compiler-approved pure type properties.

An unsupported constraint is diagnosed at its declaration. A constraint may be
deferred while it mentions outer generic parameters, but once all inputs are
concrete it must evaluate to satisfied or violated. “Unknown means satisfied”
is forbidden.

### 8.5 Constraint checking model

A generic body is checked once under its declared bounds:

```azora
func<T> sum(a: T, b: T): T
where T: Arithmetic<T, T> {
    return a + b
}
```

The `+` is legal because the bound supplies it. The compiler does not wait for
`sum<Int>` to discover whether the body happens to work. This gives stable
library errors and prevents one instantiation from changing another.

At each application, the compiler substitutes arguments and proves every bound.
Diagnostics include the failed bound, the argument, the selected/missing impl,
and the declaration that required it.

The existing expression spelling `where T is Number` may be accepted during
migration, but the canonical conformance spelling is `where T: Number`.
Runtime `is` remains a type test. Restricting the generic form to the declared
constraint grammar prevents an arbitrary expression from becoming a promise the
compiler later cannot decide.

F-bounded constraints are supported:

```azora
func<T> maximum(a: T&, b: T&): T&|a, b|
where T: Order<T> {
    ...
}
```

This is a finite obligation about one parameter and is not confused with an
infinite solver cycle.

---

## 9. Type inference

### 9.1 Bidirectional inference

Inference collects constraints from:

- ordinary call arguments;
- receiver types;
- nested generic structure such as `List<T>` and `Map<K, V>`;
- expected return/assignment type;
- constructor fields;
- lambda parameter and result expectations;
- collection and tuple literals;
- explicit arguments and `_` holes;
- default type/const arguments;
- bounds and associated-type equalities.

```azora
func<T> wrap(value: T): Option<T> { ... }

fin a = wrap(1)                    // Option<Int>
fin b: Option<String> = wrap("x")
```

Inference must recurse structurally. A parameter inside `List<T>`, `T?`, a
tuple, a callable, or a borrow is not invisible merely because it is nested.

### 9.2 Inference order

For one candidate:

1. install explicit generic arguments;
2. create variables for omitted arguments and `_` holes;
3. collect receiver and value-argument constraints;
4. collect expected-result constraints;
5. solve equalities/subtyping and numeric literal constraints;
6. normalize associated types;
7. apply defaults to still-unbound trailing parameters;
8. validate bounds;
9. rank conversions and candidate specificity;
10. require every parameter needed by the signature or lowering to be resolved.

Overload selection evaluates candidates independently. A failing bound removes a
candidate but is retained as explanation if no candidate succeeds. Declaration
order is not a tie-breaker for semantic ambiguity.

### 9.3 Repeated parameters

```azora
func<T> choose(a: T, b: T): T { ... }
```

The arguments must unify to one type. Where subtyping applies, the compiler may
choose a unique least common supertype only if that choice is stable and loses
no required capability. Numeric literals use ordinary numeric promotion.

It must not silently infer `Any` merely because two types differ. If several
unrelated specs are possible, inference is ambiguous and asks for an explicit
argument or expected type.

### 9.4 Return-only parameters

```azora
func<T> empty(): List<T> { ... }
```

`empty()` requires an expected type or explicit argument:

```azora
fin a: List<Int> = empty()
fin b = empty<String>()
fin c = empty() // error: T cannot be inferred
```

### 9.5 Partial explicit arguments

```azora
fin result = convert<Int, _>(value)
```

The first argument is fixed; the second is inferred. A hole cannot reach IR,
reflection, mangling, or diagnostics as if it were a real type.

### 9.6 Ownership is not an inference conversion

Inference does not insert `take`, `lend`, a borrow, or `clone()`. It can infer a
type through an explicitly written ownership operation, but ownership intent
remains visible in source.

### 9.7 Collection and tuple context

Native collection and tuple literals contribute ordinary inference constraints:

```azora
fin cats: List<Animal> = [Cat(), Cat()]
fin pair: (Animal, Animal) = (Cat(), Dog())
fin counts: Map<String, Int> = ["a": 1, "b": 2]
```

With an expected type, each element is checked against its corresponding
generic argument. Without one, homogeneous containers infer a unique common
element type; heterogeneous tuples preserve one type per position. An empty
collection needs an expected type or explicit constructor argument because it
contains no evidence for its element parameters.

---

## 10. Default arguments

```azora
spec PartialEqual<Rhs = Self> { ... }
spec Arithmetic<Rhs = Self, Out = Self> { ... }
pack Buffer<T, N: Int = 256> { ... }
```

Rules:

- defaults are evaluated left to right;
- a default may reference `Self` and earlier parameters;
- it may not reference a later parameter;
- after the first default, later non-pack parameters also need defaults;
- explicit arguments win;
- inference runs before defaults for functions;
- defaults fill only still-unresolved slots;
- substituted defaults must satisfy all bounds;
- a type pack defaults to the empty pack only by being omitted at a variadic
  application; it has no `= (...)` baseline syntax.

Changing a public default changes source behavior and generic metadata even if
the generated ABI symbol is unchanged. Tooling should surface defaults in hover.

---

## 11. Const generics

### 11.1 Purpose

Const generics express layout, arity, algorithms, and static policy without
turning values into ad hoc type names:

```azora
pack Array<T, N: Int> { ... }
pack Matrix<T, R: Int, C: Int, O: MatrixOrder = .RowMajor> { ... }
```

`Array<Int, 4>` and `Array<Int, 8>` are distinct types.

### 11.2 Allowed const kinds

Baseline const parameters may use:

- signed and unsigned integer types;
- `Bool`;
- `Char`;
- payload-free enum variants.

Values must be compile-time evaluable, deterministic, comparable for type
identity, and serializable into generic metadata. Floating-point, strings,
pointers, owned values, and arbitrary packs are excluded initially.

### 11.3 Const expressions

Arguments may be literals, const names, enum variants, and approved pure integer
expressions:

```azora
Array<Byte, HeaderSize + PayloadSize>
Matrix<Float, N, N>
```

The compiler normalizes values, so `Array<Int, 2 + 2>` and `Array<Int, 4>` have
the same type identity.

Overflow, division by zero, forbidden CTCE capability, or nontermination is a
diagnostic at the argument/constraint.

### 11.4 Const inference

Const values can be inferred from an expected type or a value whose type embeds
them:

```azora
func<T, N: Int> size(array: Array<T, N>&): Int { return N }

fin n = size(array4) // N = 4
```

There is no persistent wildcard size. If a dynamically sized collection is
needed, it is a different nominal representation such as `List<T>` or a slice,
not `Array<T, *>`.

Accordingly, a function generic over every fixed array size writes the size:

```azora
func<T, N: Int> length(values: Array<T, N>&): Int { return N }
```

Bare `Array<T>` is permitted only at an inference-bearing construction/use site
where `N` is solved immediately; it is not a complete stored signature and does
not mean “any size.”

---

## 12. Variadic type packs

The prefix spelling remains canonical:

```azora
pack Tuple<...T> where (...T).size >= 2 { ... }
func<...T> tupleOf(...elements: ...T): Tuple<...T> { ... }
```

Rules:

- at most one type pack per declaration;
- it is last;
- it may bind zero types unless constrained;
- `(...T).size` is the canonical pack-size query;
- indexing and slicing a pack are compile-time-only operations;
- expansion order is source order;
- elementwise bound `where T: Copy` means every member of type pack `T`
  implements `Copy`;
- an empty pack vacuously satisfies an elementwise bound;
- pack constraints are enforced for every specialization;
- a pack cannot escape unresolved into runtime IR.

### 12.1 Variance on a type pack

Nominal declarations may write:

```azora
bridge pack Tuple<out ...T>
```

This means each element parameter is covariant at its corresponding position:

```text
(Cat, Dog) <: (Animal, Animal)
```

The spelling is `out ...T`: variance qualifies the parameter and `...` remains
the prefix marker attached to its name. `in ...T` is legal for a variadic
consumer type but expected to be rare.

Callable declarations still cannot declare variance even when variadic.

### 12.2 Value varargs are separate

`func<T> f(...values: T)` is a runtime homogeneous variadic parameter.
`func<...T> f(...values: ...T)` is a heterogeneous compile-time type pack whose
value arguments correspond positionally. These are not interchangeable, and
diagnostics must name which form was expected.

The detailed value-spread and receiver behavior remains governed by
`VARIADICS_DIP.MD`.

---

## 13. `Self` and associated types

### 13.1 `Self`

Inside a nominal declaration or implementation, `Self` is the complete applied
type, not only its constructor name:

```azora
pack Box<T> {
    func &.same(other: Self&): Bool
}
```

Inside `Box<Int>`, `Self` is `Box<Int>`.

Inside a spec, `Self` is the implementing type. Defaults such as `Rhs = Self`
are substituted at the conformance use/implementation site.

### 13.2 Associated types versus parameters

Use a generic parameter when the caller chooses the type and one implementor may
support several choices:

```azora
spec Into<T> { ... }
```

Use an associated type when the implementation chooses one uniquely:

```azora
spec Iterator assoc Item { ... }
```

An implementation binds every required associated type exactly once:

```azora
impl Iterator for UserRows assoc Item = User { ... }
```

Associated types participate in substitution, equality constraints, inference,
cycle detection, ABI metadata, and hover just like named arguments.

---

## 14. Subtyping foundation

Variance operates on an existing subtype relation; it does not invent pack
inheritance.

Azora subtype edges include:

- a concrete type conforming to a spec;
- a child spec inheriting a parent spec;
- `Nothing` as the bottom type where supported;
- non-null `T` to `T?`;
- alternatives to a union/variant view where defined;
- callable variance;
- generic applications according to declared variance.

Packs do not inherit storage/layout from other packs. A `Cat` pack becomes an
`Animal` value by implementing the `Animal` spec:

```azora
spec Animal {
    prop &.name: String&|self|
}

pack Cat { fin name: String }

impl Animal for Cat {
    prop &.name: String&|self| = self.name.&
}
```

The type checker must carry complete applied spec types. `List<Cat>` conforming
to `Iterable<Cat>` is not reduced to the name `Iterable`.

---

## 15. Declaration-site variance

### 15.1 Invariance

```azora
pack Cell<T> {
    var value: T
}
```

`Cell<Cat>` is not a subtype of `Cell<Animal>`. If it were, a `Dog` could be
written into storage promised to contain only cats.

Invariance is the default because it is safe for every API and preserves room
to add both producers and consumers later.

### 15.2 Covariance: `out T`

```azora
spec Source<out T> {
    func !.next(): T
}
```

If `Cat <: Animal`, then `Source<Cat> <: Source<Animal>`. Reading a cat as an
animal is safe. A covariant type cannot accept an arbitrary `T` through its
public API:

```azora
spec Invalid<out T> {
    func !.put(value: T) // rejected
}
```

The word `out` describes the direction through the public abstraction, not
whether the implementation allocates, moves, borrows, or returns by value.

### 15.3 Contravariance: `in T`

```azora
spec Sink<in T> {
    func !.send(value: T)
}
```

If `Cat <: Animal`, then `Sink<Animal> <: Sink<Cat>`. Something able to consume
every animal can be used where only cats will be sent.

A contravariant type cannot expose a `T`:

```azora
spec Invalid<in T> {
    func &.current(): T // rejected
}
```

### 15.4 Explicit, validated, not inferred

Public nominal declarations state variance explicitly. The compiler analyzes
the declaration and rejects a false promise. It may suggest that an invariant
parameter could safely become `out` or `in`, but it does not change public
subtyping silently.

Changing variance is an API metadata change. Making invariant `T` covariant or
contravariant generally widens source assignability, but still requires normal
library compatibility review.

### 15.5 Where variance is permitted

Variance annotations are permitted on parameters of:

- specs;
- packs;
- ordinary/payload enums and errors;
- transparent type aliases, if the target validates the same variance;
- compiler bridge nominal types;
- variadic nominal type packs.

They are not permitted on functions, methods, constructors, operators, impl
parameters, const parameters, or associated types.

### 15.6 Applied-type subtyping algorithm

After aliases and defaults are normalized, two applications of the same
constructor compare parameter by parameter. For `C<A>` used where `C<B>` is
required:

- invariant parameter: `A` and `B` must be the same type;
- `out` parameter: `A <: B` must hold;
- `in` parameter: `B <: A` must hold;
- const parameter: normalized values must be equal;
- `out ...T` pack: packs must have equal arity and each source element must be a
  subtype of the corresponding destination element;
- `in ...T` pack: the element comparison is reversed;
- invariant pack: every corresponding element must be identical.

Different tuple/type-pack arities are never related by variance. This algorithm
is recursive, so nested constructors compose their declared variance. It also
terminates through memoized subtype obligations and reports recursive cycles
that cannot be decided coinductively.

---

## 16. Variance validation

### 16.1 Polarity

The checker walks each externally observable type occurrence with a polarity:

- positive means produced;
- negative means consumed;
- invariant means both directions are possible.

Rules compose:

| Position | Effect |
|---|---|
| function/method return | preserve polarity |
| function/method parameter | reverse polarity |
| immutable exposed field | positive |
| mutable exposed field | invariant |
| callable parameter type | reverse again |
| callable return type | preserve |
| argument of `C<out X>` | preserve |
| argument of `C<in X>` | reverse |
| argument of invariant `C<X>` | invariant |
| shared borrow `X&` | preserve |
| exclusive mutable borrow `X!` | invariant |
| mutable raw pointer `X*` | invariant |
| nullable `X?` | preserve |
| tuple element | preserve |

Positive `T` is legal for `out T`, negative `T` for `in T`, and every occurrence
for invariant `T`.

### 16.2 Nested example

```azora
spec Stream<out T> {
    func &.forEach(action: (T&) -> Unit)
}
```

`action` is a method parameter, which reverses polarity. `T&` is a parameter of
the callable, reversing it again. Two reversals make `T` positive, so the method
is valid on a covariant stream.

```azora
spec Invalid<out T> {
    func &.acceptBuilder(build: () -> T)
}
```

Here `T` is positive inside the callback return, then reversed because the
callback is accepted by the method. Overall it is negative, so this is invalid:
the caller could supply a builder for any `Animal` to an object that internally
requires `Cat`.

### 16.3 Fields and constructors

A covariant pack may store `T` in an immutable field:

```azora
pack Box<out T> {
    fin value: T
}
```

Construction with a `T` is allowed because the concrete argument is fixed while
the object is created. After construction, no public operation may replace the
field with a wider value. A `var value: T` makes `T` invariant and is rejected
under `out` or `in`.

Private implementation details may use the parameter in either direction when
they cannot be reached through the public abstraction or change an observable
invariant. The first implementation may conservatively validate all members;
relaxing proven-private occurrences later is source-compatible.

### 16.4 Bounds and variance

Using a parameter in a bound is not itself an input/output operation. However,
the resulting inherited specs and associated-type equalities are expanded and
their public signatures are variance-checked.

### 16.5 Unsafe variance escape hatch

No `@UnsafeVariance` equivalent is included. Standard-library convenience does
not justify a general soundness escape. APIs such as `contains(T)` on covariant
collections must be generalized or placed on an invariant interface.

### 16.6 `Self`, receivers, constructors, and static members

The ordinary function receiver declarations `func &.name(...)` and
`func !.name(...)` do not by themselves consume or produce a generic
parameter; they state how the existing object is accessed. Receiver types are
stated either by a full named group or by the implicit-`self` shorthand. An
owned receiver such as `func<T> Box<T>.consume()` is an input position because
it moves the complete `Box<T>` into the call.

Other uses of `Self` are expanded during validation:

- in a pack or enum, `Self` means the complete application such as `Box<T>`;
- in a spec, non-receiver `Self` refers to the hidden implementing type and is
  conservatively invariant unless object-safety rules prove otherwise.

This prevents a covariant parameter from being hidden inside `Self`.

Constructors and static/type members are selected from a concrete type before an
instance exists; they are not callable through an upcast instance. Their input
parameters therefore do not invalidate instance covariance. Produced types and
returned generic abstractions are still checked normally. If a first-class
nominal metatype is added later, that metatype receives its own variance rules.

---

## 17. Variance with ownership and borrowing

Variance answers type substitutability. Ownership answers who may use, move,
mutate, or destroy the value. Both checks must pass.

### 17.1 Owned covariant upcast

```azora
fin cats: Source<Cat> = makeCatSource()
fin animals: Source<Animal> = take cats
```

The subtype conversion is valid and ownership transfers. `cats` becomes
unavailable. If the source is `Copy`, normal copy rules may permit the binding
without `take`; variance itself never creates the copy.

An owned covariant upcast is one-way unless a checked runtime cast later proves
the narrower type. It cannot be used to regain a mutable `Source<Cat>` alias.

### 17.2 Shared borrow

```azora
fin cats: Source<Cat> = makeCatSource()
fin animals: Source<Animal>& = cats.&
```

A shared borrow cannot replace the owned value, so it preserves the constructor's
declared covariance. The borrow retains the original owner as its origin.

### 17.3 Exclusive mutable borrow

```azora
var cats: Box<Cat> = Box(Cat())
var animals: Box<Animal>! = cats.! // rejected
```

Even if `Box<out T>` is covariant as an owned/read-only abstraction, an
exclusive mutable borrow is invariant because it can replace the whole value or
reach mutable state. Otherwise a `Box<Dog>` could be written back into storage
whose owner expects `Box<Cat>`.

Therefore:

```text
C<Cat>&  <: C<Animal>&  when C is covariant
C<Cat>!  is not a subtype of C<Animal>!
```

### 17.4 Raw pointers

`T*` is invariant because an unsafe pointer may be read and written. A future
read-only pointer type could be covariant, but it must be a distinct capability.

### 17.5 `take`

Returning or taking a produced `T` is a positive occurrence:

```azora
spec Source<out T> {
    func !.takeNext(): T
}
```

This is variance-safe: `Source<Cat>` viewed as `Source<Animal>` returns a cat,
which is an animal. Ownership analysis separately proves the source can transfer
that element.

### 17.6 `lend`

A `lend` parameter still consumes its type for the duration of the call, so it
is a negative occurrence. Returning the same ownership does not turn it into a
producer. The lend contract is checked in addition to variance.

### 17.7 Destruction

Destroying a covariant container does not consume an arbitrary externally
supplied `T`; it destroys the actual stored argument type. Generated drop glue
is specialized for that concrete type. A public method accepting `T` only to
purge it remains a negative occurrence and is forbidden for `out T`.

### 17.8 Capability derivation

Generic capability derivation is conditional:

```azora
pack Box<T> derives (Clone)
```

is semantically equivalent to a generated implementation like:

```azora
impl<T> Clone for Box<T> where T: Clone { ... }
```

`Box<User>` implements `Clone` only when `User` does. `Copy`, `Equal`, `Hash`,
`Display`, destruction behavior, thread-safety, and other field-derived
capabilities follow the same substitution rule. Variance never weakens a
capability requirement.

### 17.9 Closure and async boundaries

Substituting a covariant or contravariant type does not permit a borrow to escape
through a closure, task, flow, or `await`. Borrow-origin and suspension rules run
on the fully substituted callable type.

---

## 18. Function and callable variance

Callable types have automatic variance and need no `in`/`out` annotations:

```text
(Animal&) -> Cat
    <:
(Cat&) -> Animal
```

The first function accepts every animal, so it can accept the cat its caller
will provide; it returns a cat, which satisfies an animal result.

Rules:

- parameters and contextual receivers are contravariant;
- return types are covariant;
- exclusive/mutable parameter capabilities must remain compatible;
- failure sets are covariant by inclusion: fewer possible failures may satisfy
  a callable type permitting more;
- effect requirements are covariant by permission: a purer callable may satisfy
  a position allowing more effects, never the reverse;
- non-escaping callables may satisfy an escaping slot only if capture ownership
  and lifetime rules prove they can actually escape;
- reactive/async callable-kind conversions follow their dedicated DIPs.

Before callable subtyping, every contextual receiver list is checked for
pairwise-distinct canonical base types. This check runs after alias expansion
and generic substitution and ignores only the outer ownership/borrow
capability. Consequently `[T&, U&]()` is a valid generic callable shape with an
implicit `T != U` specialization requirement, while a specialization that
substitutes the same type for both is rejected before variance is considered.

Generic function values are instantiated before ordinary callable subtyping.
Rank-2 or first-class polymorphic values—passing “a function generic for every
T” without instantiation—are not baseline functionality.

---

## 19. Standard-library variance proposal

### 19.1 Producers

Good covariant candidates:

```azora
spec Source<out T> { ... }
spec Sequence<out T> { ... }
spec Flow<out T> { ... }
variant enum Option<out T> { Some(T); None }
bridge pack Tuple<out ...T>
```

A failable result/product can be covariant in both success and error payloads
when it only exposes them:

```azora
variant enum Result<out T, out E> {
    Ok(T)
    Err(E)
}
```

### 19.2 Consumers

```azora
spec Sink<in T> {
    func !.send(value: T)
}

spec Comparator<in T> {
    func &.compare(a: T&, b: T&): Compare
}
```

### 19.3 Read/write split

State that both reads and writes is invariant. Split views make useful variance
available:

```azora
spec ReadState<out T> {
    prop &.value: T&|self|
}

spec WriteState<in T> {
    func !.set(value: T)
}

spec State<T> : (ReadState<T>, WriteState<T>) { }
```

`State<T>` remains invariant even though its parent views each have variance.

### 19.4 Lists

The desired hierarchy is:

```azora
spec List<out T> { ... }
spec MutableList<T> : List<T> { ... }
pack ArrayList<T> { ... }
```

But the current `List<T>` surface cannot simply be marked `out`:

- `contains(element: T)` and `indexOf(element: T)` consume `T`;
- returning owned `T` from `self&` is invalid for non-`Copy` elements regardless
  of variance.

The read API should return borrows tied to `self`:

```azora
spec List<out T> {
    prop &.first: T&|self|
    prop &.last: T&|self|
    func &.get(index: Int): T&|self|
    func &.forEach(action: (T&) -> Unit)
}
```

Equality/search members can be generalized instead of using an unsafe variance
escape:

```azora
func<U> &.contains(element: U&): Bool
where T: PartialEqual<U>
```

Mutation stays on invariant `MutableList<T>`.

### 19.5 Maps and sets

Map values can be covariant in a read-only view; keys are used for lookup and
remain invariant in the straightforward API:

```azora
spec Map<K, out V> { ... }
spec MutableMap<K, V> : Map<K, V> { ... }
```

A future generalized lookup could permit key covariance, but it should not use
an unsafe annotation.

Set membership consumes a comparison key, so `Set<T>` remains invariant until
its search API is generalized like `List.contains`. Mutable sets are invariant
in every case.

### 19.6 Arrays and pointers

`Array<T, N>` is invariant because it owns mutable contiguous element slots.
A separate shared slice/read view may be covariant:

```azora
spec SliceView<out T> {
    func &.get(index: Int): T&|self|
}
```

---

## 20. Generic implementations and coherence

### 20.1 Exact conformance identity

```azora
impl Into<String> for UserId { ... }
impl Into<Int> for UserId { ... }
```

These are distinct, non-conflicting implementations. Lookup includes spec
arguments and substitutes implementation parameters.

### 20.2 Coherence rule

An implementation is legal when the current module/package owns either:

- the implemented spec; or
- the outermost target type constructor.

This orphan rule ensures two dependencies cannot add competing implementations
for the same foreign spec and foreign type.

### 20.3 Overlap

Two implementations may not apply to the same concrete substitution:

```azora
impl<T> Display for Box<T> where T: Display { ... }
impl Display for Box<Int> { ... } // overlapping: rejected initially
```

User-defined specialization priorities are excluded. Compiler-generated derives
yield to an explicit non-overlapping implementation according to derive rules,
not general specialization.

### 20.4 Variance and conformance lookup

Variance is applied after finding the nominal constructor and its exact
implementation. It must not make an implementation appear that was never
declared. For example, `Serializer<Animal>` and `Serializer<Cat>` remain distinct
unless `Serializer` itself declares variance that makes the relationship safe.

### 20.5 Recursive constraints

The solver detects cycles such as `T: A<T>` leading back to the same obligation.
Coinductive acceptance is allowed only for compiler-designated auto capabilities
whose rules define it. Ordinary cycles produce a diagnostic showing the
obligation chain.

---

## 21. Lowering and code generation

### 21.1 Semantic rule versus implementation strategy

Programs cannot observe whether the compiler monomorphizes, shares code, or uses
witness tables except through documented reflection and ABI. Semantics are
defined by substituted types and constraints.

The initial native/self-hosting strategy should be monomorphization for concrete
owned types and functions because it gives:

- exact inline field layout;
- exact alignment and size;
- direct drop/clone/copy glue;
- direct method calls where known;
- no hidden boxing for ordinary generic packs;
- predictable manual-memory behavior.

### 21.2 Required specialization

Specialization is mandatory when:

- a pack layout contains a type parameter;
- a const parameter affects layout/control flow;
- a variadic pack affects arity;
- conditional fields or generated members depend on arguments;
- a bridge/backend intrinsic requires a concrete type;
- destruction or calling convention differs by argument.

The current division where only variadic/conditional packs specialize while
ordinary generic packs erase must not remain if ordinary packs store `T` inline.

### 21.3 Optional code sharing

The compiler may share generated function bodies when all substituted types have
a uniform ABI and constrained operations are supplied through witness tables.
This is an optimization. It cannot change overload selection, reflection,
ownership, or diagnostic behavior.

### 21.4 Generic library artifacts

Separately compiled libraries store:

- generic signature and parameter metadata;
- normalized constraints and variance;
- template typed IR or equivalent self-hostable representation;
- referenced witness/spec requirements;
- source/diagnostic metadata as configured;
- compiler/language edition compatibility.

Consumers instantiate reachable combinations at link/build time.

### 21.5 Reachability and infinite expansion

Only reachable concrete instantiations generate code. The compiler detects
expanding recursion:

```text
f<T> -> f<List<T>> -> f<List<List<T>>> -> ...
```

and reports the instantiation chain. It also enforces configurable depth and
code-size limits with structured diagnostics.

### 21.6 Mangling

A specialized symbol identity includes declaration identity and canonical
arguments. Human-readable prefixes may be followed by a stable hash; raw type
spellings are not relied upon as the sole ABI because modules, long tuples, and
const expressions make that unstable.

### 21.7 Dynamic spec values

A value stored behind a spec type uses the language's existential/dynamic
representation: data pointer or inline payload plus a witness/vtable as defined
by the ABI. Variance changes which spec applications are substitutable; it does
not change the concrete stored object's identity.

---

## 22. Reflection and reification

Type parameters are always available to compile-time type functions where the
current compiler already supports constructs such as `T.typeName`. This does
not imply full runtime reification.

Baseline rules:

- `inline`/`deepinline` type reflection resolves at instantiation;
- `is T`, runtime construction by `T`, or requesting runtime type metadata is
  permitted only when specialization or an explicit type descriptor makes it
  available;
- generic metadata must not be fabricated as `Any` after erasure;
- a future `reified T` marker may request runtime metadata, but is not required
  by this DIP and must be proposed with ABI costs.

Reflection reports canonical applied types, including const and variadic
arguments and declared variance metadata.

---

## 23. Generic diagnostics

Diagnostics follow `AZLS_DIP.MD`: typed compiler variants with precise spans,
related locations, notes, and safe fixes. Proposed codes use the type family.

| Code | Meaning |
|---|---|
| `AZ-TYP-0200` | wrong generic arity |
| `AZ-TYP-0201` | wrong generic argument kind |
| `AZ-TYP-0202` | type argument cannot be inferred |
| `AZ-TYP-0203` | conflicting inference constraints |
| `AZ-TYP-0204` | ambiguous common supertype |
| `AZ-TYP-0205` | generic bound not satisfied |
| `AZ-TYP-0206` | associated type missing or ambiguous |
| `AZ-TYP-0207` | invalid/default argument dependency |
| `AZ-TYP-0208` | const argument is not compile-time evaluable |
| `AZ-TYP-0209` | const constraint failed |
| `AZ-TYP-0210` | variadic pack arity/element bound failed |
| `AZ-TYP-0211` | unresolved `_` inference hole |
| `AZ-TYP-0212` | invalid variance annotation location |
| `AZ-TYP-0213` | covariant parameter used in input position |
| `AZ-TYP-0214` | contravariant parameter used in output position |
| `AZ-TYP-0215` | variant parameter used through invariant capability |
| `AZ-TYP-0216` | exclusive borrow cannot use variance conversion |
| `AZ-TYP-0217` | overlapping generic implementation |
| `AZ-TYP-0218` | orphan implementation |
| `AZ-TYP-0219` | recursive generic obligation or expansion |
| `AZ-TYP-0220` | open generic cannot cross bridge ABI |

### 23.1 Variance diagnostic example

```azora
spec Broken<out T> {
    func !.add(value: T)
}
```

The diagnostic underlines `T` in `value: T`, relates `out T` in the declaration,
and explains the negative path:

```text
method parameter -> T
negative          -> negative
```

Suggested repairs are contextual:

- remove `out` and make the parameter invariant;
- move mutation to a separate invariant spec;
- generalize the method with its own parameter and an appropriate relation.

The compiler must not suggest `in T` if other members produce `T`.

### 23.2 Bound diagnostic example

For `sort<User>(users)` when `User` lacks `Order`, primary is the explicit
`User` argument or inferred call range, and related information points to
`where T: Order`. Candidate implementations and derives may be offered as
guidance, but generating semantic methods is not automatically machine-safe.

### 23.3 Inference fixes

AZLS can safely offer an explicit type argument when one unique solution was
found but source policy requires it. When inference is ambiguous, actions may
insert `_` placeholders or a snippet for explicit arguments; they do not pick a
candidate arbitrarily.

---

## 24. AZLS and IDE behavior

Hover on a generic declaration shows:

- parameter kinds and variance;
- defaults;
- normalized bounds;
- associated types;
- whether the declaration is specialized or bridge-provided when useful.

Hover on an application shows inferred arguments and their sources:

```text
map<T = User, U = String>
T inferred from values: List<User>&
U inferred from transform return type
```

Completion inside a `where` clause offers visible specs, earlier parameters,
associated types, and legal const properties. Signature help displays explicit,
inferred, defaulted, and packed arguments distinctly.

Go-to-definition on a parameter goes to its declaration; on an associated type
projection it can navigate to both spec declaration and selected impl binding.

Semantic tokens distinguish type parameters, const parameters, type packs, and
associated types. Rename follows parameter identity and never replaces an
unrelated shadowed spelling.

---

## 25. Safety and performance rules

### 25.1 Soundness

- No concrete failed/unknown constraint is accepted.
- No generic argument disappears from conformance identity.
- No variance conversion passes through an exclusive mutable capability.
- No generic inference inserts ownership operations.
- No open generic foreign ABI is emitted.
- No erased layout stores an unknown-sized `T` inline.
- No unresolved parameter, pack, const, projection, or hole reaches backend IR.

### 25.2 Compile-time budgets

The compiler bounds solver iterations, obligation depth, associated-type
normalization, const evaluation, pack expansion, and monomorphization count.
Exceeding a budget reports the chain and the configurable limit rather than
hanging or crashing.

### 25.3 Code-size control

Compiler reports can show the largest specialization families. Optional shared
code is allowed when ABI-equivalent. Authors are not required to trade safety
for code size by using erased `Any` values.

---

## 26. Worked designs

### 26.1 Covariant option

```azora
variant enum Option<out T> {
    Some(T)
    None
}

func animal(): Option<Animal> {
    fin cat: Option<Cat> = .Some(Cat())
    return take cat
}
```

Payload construction is legal; the produced value can widen covariantly.

### 26.2 Invariant mutable cell

```azora
pack Cell<T> {
    var value: T
}

impl<T> Cell<T> {
    func &.get(): T&|self| = self.value.&
    func !.set(value: T) { self.value = take value }
}
```

Both directions require invariance.

### 26.3 Producer/consumer pipeline

```azora
spec Source<out T> {
    func !.next(): Option<T>
}

spec Sink<in T> {
    func !.send(value: T)
}

func<T> pipe(source: Source<T>!, sink: Sink<T>!) {
    while fin item = source.next() {
        sink.send(take item)
    }
}
```

Variance lets a `Source<Cat>` feed an animal-oriented pipeline and lets a
`Sink<Animal>` stand in for `Sink<Cat>`, while `take` remains explicit.

### 26.4 Generic borrowed lookup

```azora
func<K, V> find(map: Map<K, V>&, key: K&): V&|map|?
where K: Hash {
    ...
}
```

The returned borrow is tied to `map`; generic substitution does not erase the
origin. Because `K` is consumed as a lookup key and `V` is produced, a read-only
map can be invariant in `K` and covariant in `V`.

### 26.5 Const matrix

```azora
pack Matrix<T, R: Int, C: Int, O: MatrixOrder = .RowMajor>
where T: Number &&
      R > 0 &&
      C > 0 {
    inline for r in 0..<R {
        inline for c in 0..<C {
            var m$r$c: T
        }
    }
}
```

Layout, drop glue, and methods specialize per normalized arguments.

### 26.6 Generic implementation with associated type

```azora
impl<T> Iterator for ListCursor<T> assoc Item = T {
    func !.next(): Option<T> { ... }
}

func<I, T> drain(iterator: I, sink: Sink<T>!)
where I: Iterator &&
      I::Item == T {
    ...
}
```

### 26.7 Tuple variance

```azora
fin pair: (Cat, Dog) = (Cat(), Dog())
fin animals: (Animal, Animal) = take pair
```

Tuple elements are covariant. The conversion transfers the tuple as a whole;
it does not duplicate either element.

---

## 27. Migration plan

### Phase 0: freeze syntax and semantics

- reserve `out`/`in` in generic declarations;
- adopt `(A, B)` for multiple type bounds;
- confirm `(...T).size` as pack-size spelling;
- freeze parameter ordering/default rules;
- add generic diagnostic codes.

### Phase 1: structured parameter AST

- add `GenericParameter`, kinds, arguments, variance, bounds, defaults, spans;
- migrate pack/spec/function/impl/enum/error/alias nodes;
- retain computed compatibility accessors temporarily;
- assign parameter identities and reject shadowing.

Exit: semantic passes no longer reconstruct parameter meaning from parallel
sets/maps.

### Phase 2: substitution and identity

- implement one substitution engine for types, consts, packs, `Self`, and
  associated projections;
- normalize applications/defaults;
- make conformance compare full spec arguments;
- remove name-only conformance from generic decisions.

Exit: `PartialEqual<String>` never satisfies `PartialEqual<Int>`.

### Phase 3: constraints

- preserve inline bounds;
- normalize inline and `where` forms;
- replace permissive ordinary-expression evaluation with the closed constraint
  evaluator;
- reject unsupported/unknown concrete conditions;
- add obligation chains and cycle detection.

Exit: every concrete constraint is satisfied or diagnosed.

### Phase 4: inference

- replace shallow argument matching with structural bidirectional constraints;
- implement expected-result, nested call, lambda, associated-type, const, and
  pack inference;
- remove silent `Any` fallback;
- stabilize overload ranking and explanations.

Exit: all required arguments resolve before typed IR.

### Phase 5: variance

- parse/store `out` and `in`;
- implement polarity validation;
- implement applied generic subtyping;
- make shared borrows preserve and exclusive borrows erase variance conversion;
- implement callable variance;
- add variance diagnostics and IDE data.

Exit: producer/consumer tests pass and every mutable aliasing counterexample is
rejected.

### Phase 6: stdlib API migration

- make producer types covariant where valid;
- split read/write views;
- fix collection element access to return owner-tied borrows;
- generalize equality/search inputs;
- keep mutable containers invariant;
- update tuple bridge to `out ...T`.

Exit: stdlib variance annotations validate without unsafe escape hatches.

### Phase 7: specialization and ABI

- specialize layout-dependent ordinary generics alongside const/variadic ones;
- emit template typed IR in library artifacts;
- generate concrete layout/drop/witness data;
- add canonical mangling and expansion-cycle limits;
- reject open generic bridge exports.

Exit: generic inline storage has correct native size/alignment and deterministic
destruction on every backend.

### Phase 8: tooling and hardening

- publish structured generic diagnostics through AZLS;
- add inferred-argument hover/signature help;
- add cross-module incremental invalidation for generic metadata;
- benchmark solver/specialization performance;
- delete compatibility fields and legacy paths.

---

## 28. Acceptance criteria

1. Every parameter has a structured kind, bounds, default, variance, identity,
   and span.
2. All supported declaration kinds use the same parameter/application model.
3. Generic bodies are checked from declarations and bounds.
4. Concrete unknown constraints cannot compile.
5. Generic spec arguments participate in conformance identity.
6. Nested and expected-type inference work; unresolved arguments do not become
   `Any`.
7. `_` is eliminated before typed IR.
8. Const arguments normalize and affect type identity correctly.
9. Type-pack constraints and arity are enforced.
10. `out T` and `in T` are declaration-site variance with polarity validation.
11. Shared borrows allow safe covariance; exclusive borrows and raw pointers are
    invariant.
12. Owned variance conversions obey move/Copy rules and never clone implicitly.
13. Mutable containers remain invariant.
14. Function parameters are contravariant and returns covariant.
15. Generic impl overlap/orphan rules are deterministic.
16. Layout-dependent generic packs are not unsafely erased.
17. Concrete drop/clone/copy glue respects substituted capabilities.
18. Generic library artifacts support separate compilation.
19. AZLS explains inference, failed bounds, and variance paths without parsing
    message text.
20. No wildcard, unresolved projection, parameter, pack, or const reaches a
    backend.

---

## 29. Rejected alternatives

### Make every generic covariant

Rejected. Mutation would allow values of the wrong subtype into narrower
storage.

### Infer public variance automatically

Rejected. Adding a consuming member could silently change a public subtype
relationship. Variance is API intent and must be written.

### Use-site projections first

Rejected for the baseline. `MutableList<out Animal>` adds existential/projection
complexity and interacts poorly with exclusive borrows. Read-only specs and
shared views express the capability directly.

### Treat `_` as a wildcard

Rejected. It would let unknown types survive past inference and complicate
ownership/layout. `_` is a request that must be solved.

### Accept unknown constraints

Rejected. A written guarantee that is silently ignored is worse than no
guarantee.

### Erase every generic to `Any`

Rejected. It hides type failures, cannot represent arbitrary inline layout
without boxing, weakens manual-memory predictability, and complicates drop glue.

### Monomorphize by source-text spelling

Rejected. Aliases and equivalent const expressions would produce duplicate or
incompatible types. Specialization uses canonical semantic identity.

### Add lifetime parameters to generics

Rejected. Azora uses inferred lifetimes and explicit borrow-origin contracts
such as `T&|source|`; generic lifetime variables would duplicate that model.

### Allow overlapping impl specialization immediately

Rejected. It makes library coherence, separate compilation, and overload
explanations substantially harder. A later DIP can add a constrained model.

---

## 30. Deferred extensions

Potential future work, each requiring a separate design:

- higher-kinded parameters and generic type constructors;
- generic associated types;
- first-class polymorphic function values;
- explicit runtime-reified parameters;
- read-only pointer variance;
- existential `some`/`any` types;
- carefully scoped use-site projections;
- specialization priorities;
- effect and failure-set parameters;
- richer const value kinds.

The baseline leaves syntax space for these without pretending they already
work.

---

## 31. Final rules

1. Generics are invariant unless a nominal declaration explicitly says `out`
   or `in`.
2. Bounds are promises checked both in the generic body and at application.
3. A concrete constraint is never accepted as “unknown.”
4. Inference must solve every required argument; `Any` is not failure recovery.
5. Generic conformance includes all type arguments.
6. Variance composes through type positions and stops at mutable capabilities.
7. Variance changes substitutability, not ownership.
8. `take`, `lend`, borrow, clone, and destruction remain explicit or
   compiler-proven under their own rules.
9. Layout-dependent generics receive concrete layout and drop behavior.
10. Tooling consumes typed generic facts, never reconstructed message text.
