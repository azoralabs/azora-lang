# Typed Collection Literals and Tuples

> **Status:** Proposal
>
> **Short name:** GTC DIP
>
> **Scope:** Native collection literals, tuple values, and destructuring.

## 1. Summary

| Delimiter | Meaning | Examples |
|---|---|---|
| `{ ... }` | Bodies and import selector groups | `func f() { ... }`, `import a.b::{x, y}` |
| `[ ... ]` | Collection literals and indexing | `[1, 2]`, `["a": 1]`, `items[i]` |
| `( ... )` | Calls, declaration/release lists, tuples and positional patterns | `f(x)`, `(x + y)`, `(1, "two")`, `fin (x, y) = pair` |

`purge (a, b)` is a dedicated release list; it releases each listed value in order.

Declaration lists such as `pack Text derives (Copy, Clone)` and
`derive (Equal, Hash) for (A, B)` use parentheses. Decorator rows may use
`@(First, Second)`. These are syntax lists, not tuple values.

Every binding declares its own initializer. Every assignment and call states its
own target. To retain one result for several later uses, bind it explicitly.

```azora
fin x = nextX()
fin y = nextY()
fin measured = measure()
self.width = measured
self.height = measured
fin (id, name) = lookup()
```

A collection literal is a runtime value whose target type selects a static
`literal` factory. A tuple has a fixed arity and possibly heterogeneous element
types. Destructuring reads the components of one tuple value.

## 2. Goals

- Native sequence and associative literals, including empty values and spreads.
- Contextual construction for standard and custom collection types.
- Tuple literals and types with the identity of `Tuple<...T>`.
- Positional binding and assignment patterns.
- Explicit ownership, evaluation order, and useful diagnostics.

## 3. Evaluation

Explicit statements execute in source order. Each initializer uses only its
binding's expected type. A tuple assignment evaluates its source once before
writing its extracted components. Collection elements evaluate left to right.

## 4. Import selector groups

Symbol selector groups use `::` followed by braces:

```azora
import a.b::{x, y}
import std.format::{Display, Debug}
import std.container::{list::*, map::{Map, HashMap}}
```

The separator communicates intent:

- `.` continues a module path;
- `::` selects a symbol or symbol group from the resolved module/scope.

The grammar is conceptually:

```text
import-selector-group := "::" "{" import-selector (separator import-selector)* [separator] "}"
separator             := "," | NEWLINE+
```

An empty import selector group is invalid:

```azora
import a.b::{} // error: an import selector group must name at least one selector
```

Nested selector groups are allowed. Their base is the path immediately before
the `::{...}`:

```azora
import a::{b::{x, y}, c::*}
```

## 5. Native collection literals with `[]`

> **Implementation profile (2026-09-15):** Non-empty sequence literals default to
> `Array`, per the user's decision. Contextual arrays propagate element types
> through global/local bindings, assignments, arguments, returns, branches, pack construction
> and nested literals; fixed nested shapes and numeric constant ranges are checked.
> Empty arrays require an element context. Native array storage preserves the
> resolved element width. The grammar accepts multiline/trailing-comma sequence
> and associative literals, including `[:]`; the old `![...]` form is removed.
> Standard `arr`, `vec`, `map` and `set` construction macros are absent.
>
> Target-owned List/Set/Map/custom literal factories described below are still
> pending. The compiler diagnoses these contextual targets rather than assigning
> array storage to a different collection type. Implicit collection conversion
> is not inferred from short type names. This profile does not qualify
> generic/spec boxing, all ownership models, arbitrary-width numeric ABI layout,
> or whole-program inference. Parsing `[:]` is not completed Map allocation.

### 5.1 Sequence literals

The sequence form is:

```azora
[element0, element1, element2]
```

Examples:

```azora
fin numbers = [1, 2, 3]
fin names: List<String> = ["Ada", "Grace"]
fin unique: Set<Int> = [1, 2, 2, 3]
fin queue: Queue<Job> = [first, second, third]
```

Commas are required between elements. Newlines are whitespace, not implicit
element separators. A trailing comma is allowed:

```azora
fin names = [
    "Ada",
    "Grace",
]
```

This rule avoids ambiguity between two adjacent expressions split by a newline.

### 5.2 Associative literals

The associative form is:

```azora
[key0: value0, key1: value1]
```

Examples:

```azora
fin ages = ["Ada": 36, "Grace": 85]
fin ages: Map<String, Int> = ["Ada": 36, "Grace": 85]
fin ordered: TreeMap<String, Int> = ["b": 2, "a": 1]
```

All top-level entries in one literal must use the same shape. Mixing keyed and
unkeyed elements is invalid:

```azora
[1, "two": 2] // error: a collection literal cannot mix elements and entries
```

### 5.3 Empty literals

The empty forms are deliberately different:

```azora
[]   // empty sequence literal
[:]  // empty associative literal
```

An empty literal normally requires an expected type:

```azora
fin names: List<String> = []
fin ages: Map<String, Int> = [:]
```

Without an expected type, the compiler cannot infer element types:

```azora
fin values = [] // error: cannot infer the element type of an empty sequence literal
fin table = [:] // error: cannot infer key and value types of an empty associative literal
```

The language does not default these to `List<Nothing>` or `Map<Nothing,
Nothing>` in version 1.

### 5.4 Default inferred types

Without an expected target type, a non-empty literal uses these defaults:

| Literal shape | Inferred public type | Standard backing implementation |
|---|---|---|
| `[e0, e1, ...]` | `Array<E>` | Native contiguous array |
| `[k0: v0, ...]` | `Map<K, V>` | `LinkedHashMap<K, V>` |

Sequence literals default to `Array` (user decision, 2026-09-10). Explicit list,
set and map contexts select the appropriate collection implementation. Code may request a
specific implementation through an annotation:

```azora
fin dense: ArrayList<Int> = [1, 2, 3]
fin hashed: HashMap<String, Int> = ["a": 1]
fin sorted: TreeMap<String, Int> = ["a": 1]
```

`Set` has no separate punctuation. Context chooses set semantics:

```azora
fin set: Set<Int> = [1, 2, 2, 3]
```

The target's literal factory decides duplicate handling. For the standard set,
duplicates collapse according to `Equal` and `Hash`/`Order`. For maps, the
standard factories use last-written-value-wins unless the target type documents
a stricter policy.

### 5.5 Contextual typing

A collection literal is initially represented as an unresolved literal shape,
not as an `ArrayList` AST constructor call. Expected type flows into it from:

- a binding annotation;
- an assignment target;
- a return type;
- a function parameter;
- a pack field;
- a cast or explicit contextual type expression;
- an enclosing literal whose own target is known.

Examples:

```azora
fin xs: Set<Int> = [1, 2, 3]
consumeQueue([first, second]) // parameter type is Queue<Job>

func defaults(): Map<String, Int> {
    return [:]
}
```

If there is no expected type, default inference happens after all element
expressions are typed.

### 5.6 Element type inference

For a sequence literal without an expected element type:

1. Resolve every element expression independently.
2. Compute their least common assignable type.
3. Apply ordinary numeric promotion where the language already permits it.
4. Apply concrete-to-spec upcasts before reporting incompatible types.
5. Reject the literal if no sound common type exists.

The compiler MUST NOT silently choose `Any` merely because the elements differ:

```azora
[1, "two"] // error unless a contextual target explicitly accepts Any
```

An explicit context may request a spec type:

```azora
fin shapes: List<Shape> = [Square(2), Circle(1)]
```

Each element is converted to `Shape` before the common-element check. This rule
also closes the current gap where collections of spec-typed values cannot be
built.

Associative literals infer keys and values independently using the same rules.

### 5.7 Literal factories belong to the target type

There is no `List<T>([1, 2, 3])` form in this proposal. Explicit construction
uses ordinary varargs:

```azora
fin xs = List<Int>(1, 2, 3)
fin concrete = ArrayList<Int>(1, 2, 3)
```

Because `List<T>` is a spec rather than a concrete pack, its ordinary vararg
constructor is itself a normal type-owned bridge factory selecting the standard
implementation. That facility is independent of literal syntax: it receives
ordinary call arguments, participates in ordinary call diagnostics, and may be
used even if this DIP is not involved. Conceptually:

```azora
spec List<T> {
    bridge ctor .(...elements: T): Self {
        return ArrayList<T>(...elements)
    }
}
```

The bracket-specific `literal` factory exists so contextual source can say
`fin xs: List<Int> = [1, 2, 3]` without inventing a constructor call or an
intermediate collection.

Contextual bracket construction is defined by a new static declaration named
`literal`. A sequence literal factory has this conceptual syntax:

```azora
spec List<T> {
    bridge literal [...elements: T]: Self {
        return ArrayList<T>(...elements)
    }
}
```

An associative literal factory receives variadic entry tuples:

```azora
spec Map<K, V> {
    bridge literal [...entries: (K, V)]: Self {
        return LinkedHashMap<K, V>(...entries)
    }
}
```

For a concrete custom type:

```azora
pack SmallVec<T> {
    // storage...
}

impl SmallVec<T> {
    literal [...elements: T]: SmallVec<T> {
        return SmallVec<T>(...elements)
    }
}

fin values: SmallVec<Int> = [1, 2, 3]
```

The exact body may use a capacity-aware private constructor or builder. The
surface protocol does not require the compiler to know those details.

Normative properties of `literal` are:

- it is static and has no receiver;
- it is selected at compile time from the expected target type;
- it is not ordinary overload resolution by the name `literal`;
- it cannot be called as `List.literal(...)` in version 1;
- a target may define at most one sequence factory per element arity pattern;
- a target may define at most one associative factory per key/value pattern;
- its result must be the containing target type or a concrete value assignable
  to that target when the containing target is a spec;
- it may be generic over the containing type's parameters;
- it participates in ordinary `where` constraints;
- failure types declared by the factory become failure requirements of the
  literal expression;
- literal construction is direct dispatch; it never requires a runtime spec
  method table.

For a spec such as `List<T>`, the body designates the standard concrete value
that is returned as that spec. For a concrete pack, the body normally returns
that pack directly.

### 5.8 No intermediate collection

The source:

```azora
fin xs: SmallVec<Int> = [a(), b(), c()]
```

lowers conceptually to:

```azora
fin xs: SmallVec<Int> = SmallVec<Int>.__literal(a(), b(), c())
```

It does not lower to:

```azora
SmallVec<Int>(List<Int>(a(), b(), c()))
```

The literal's elements become direct variadic arguments to the selected factory.
The backend may further specialize the standard factories into capacity
allocation plus ordered insertion.

### 5.9 Evaluation order

Elements and entries are evaluated exactly once, from left to right.

For a map entry, the key is evaluated before its value. The next entry begins
only after both have completed:

```azora
[key0(): value0(), key1(): value1()]
```

has the order:

1. `key0()`
2. `value0()`
3. `key1()`
4. `value1()`

The literal factory receives the resulting arguments in that order. A factory
may organize storage differently, but it may not cause an expression to be
evaluated again.

### 5.10 Ownership and borrowing

Literal elements follow ordinary argument ownership:

```azora
fin copied = [copyValue]
fin moved: List<Resource> = [take resource]
fin borrowed: List<Resource&> = [resource.&]
```

- `Copy` elements may be passed implicitly.
- A named, non-`Copy` owned value requires `take` when the factory takes it by
  value.
- A literal may contain shared or exclusive borrows only when the target
  element type permits those borrows and their inferred lifetime does not
  outlive the literal result.
- The literal factory may not retain a borrow beyond the result type's declared
  borrow origins.
- Moving one element does not move any other element.
- If evaluation of a later element fails, already-evaluated owned temporaries
  must be destroyed according to the language's normal partial-construction
  rules once deterministic destruction is implemented.

### 5.11 Spread elements

Sequence literals may contain a spread:

```azora
fin all = [0, ...middle, 100]
```

The spread expression must be iterable with an element type assignable to the
literal element type. It is consumed or borrowed according to the written
ownership mode and the iterable protocol.

Associative literals may spread another entry iterable:

```azora
fin merged: Map<String, Int> = [...defaults, "port": 8080]
```

Spread order is insertion order. Duplicate behavior remains target-defined.

Because a spread has runtime length, a factory receiving one cannot always be
lowered to a single fixed variadic call. The compiler instead uses the target's
builder form. Version 1 may reject spread literals until that builder hook is
implemented; it must not silently materialize an unrelated temporary list.

### 5.12 Arrays and fixed-size targets

An expected fixed-size array may consume a sequence literal:

```azora
fin bytes: Array<UByte, 4> = [0, 1, 2, 3]
```

When the size is a compile-time constant, arity must match exactly. The compiler
diagnoses the mismatch before code generation.

Without an expected array type, `[0, 1, 2, 3]` remains a `List<Int>`. Literal
syntax never infers a fixed-size array merely from the number of elements.

## 6. Builtin tuples with `()`

### 6.1 Tuple literals

A comma at the top level of parentheses constructs a tuple:

```azora
fin t = (1, "2", true)
```

Its inferred type is:

```azora
(Int, String, Bool)
```

Tuple elements are evaluated exactly once from left to right.

### 6.2 Tuple types

Tuple type syntax is native:

```azora
fin t: (Int, String, Bool) = (1, "2", true)

func swap(value: (Int, String)): (String, Int) {
    return (value.1, value.0)
}
```

Parenthesized function types remain unambiguous:

```azora
(Int, String) -> Bool   // function taking Int and String
((Int, String)) -> Bool // function taking one tuple argument
```

The second spelling contains an inner tuple type and an outer function-parameter
list.

### 6.3 Identity with `Tuple<...T>`

The standard library keeps `Tuple`, but it becomes a compiler bridge pack:

```azora
bridge pack Tuple<...T>
```

The following type pairs are identical, not merely convertible:

```azora
(Int, String)
Tuple<Int, String>
```

Likewise, the literal and explicit constructor create the same runtime type:

```azora
(1, "two")
Tuple<Int, String>(1, "two")
```

The compiler owns tuple layout, arity, positional field access, and lowering.
The standard library owns behavior layered on that representation, including
`Clone`, `Equal`, `Order`, `Hash`, `Display`, and conversion helpers.

`tupleOf(...)` may remain temporarily as a compatibility function, but it is
not the canonical syntax and should eventually be deprecated.

### 6.4 Tuple arity

Version 1 adopts these arity rules:

- `()` is `Unit`, preserving the existing unit value.
- `(x)` is grouping and has the type of `x`.
- `(x,)` is rejected with a diagnostic explaining that one-element tuples are
  not supported.
- `(x, y)` and larger are tuples.

This matches the current semantic model in which tuples have at least two
elements and avoids introducing `Tuple<T>` as a type distinct from `T`.

### 6.5 Tuple access

Tuple elements use zero-based positional access:

```azora
fin first = value.0
fin second = value.1
```

The index must be a compile-time integer within the tuple's arity. Dynamic
indexing uses collections, not tuples.

### 6.6 Tuple ownership

A tuple owns its owned elements. Constructing a tuple follows ordinary argument
ownership:

```azora
fin pair = (take socket, address)
```

Borrow tuples preserve an origin per element:

```azora
func names(a: User&, b: User&): (String&|a|, String&|b|) {
    return (a.name.&, b.name.&)
}
```

Moving one tuple element leaves that element spent without spending unrelated
elements, following Azora's field-move rules.

## 7. Destructuring with `()`

### 7.1 Positional binding destructuring

A binding keyword followed by a parenthesized pattern destructures one value by
position:

```azora
fin (x, y) = point
val (email, username) = user
var (left, right): (Int, Int) = pair
```

The right-hand expression is evaluated exactly once. Conceptually, the compiler
binds it to a hidden temporary and then extracts components in order.

The outer binding keyword applies to every local binding in the pattern.

### 7.2 Assignment destructuring

Without a binding keyword, a positional pattern assigns existing targets:

```azora
(x, y) = pair
(left, right) = (right, left)
(self.width, self.height) = dimensions
```

The source is fully evaluated before the first target is written. Components
are also extracted before assignment begins, so swap-like forms are safe and do
not observe earlier writes.

### 7.3 Loop destructuring

Loop headers use positional patterns:

```azora
for (key, value) in entries {
    println(key + ": " + value)
}

for ((x, y), weight) in weightedPoints {
    ...
}
```

Loop bindings are `fin` by default. If mutable per-iteration bindings are ever
needed, that should be an explicit loop-binding modifier rather than inferred
from the pattern delimiter.

### 7.4 Name-based binding destructuring

Name-based destructuring uses `local = member` entries inside parentheses:

```azora
val user = User("alice", "alice@example.com")

val (mail = email, name = username) = user
```

This binds:

- local `mail` from member `user.email`;
- local `name` from member `user.username`.

The order is explicit and need not match declaration order.

When one outer keyword is insufficient, each entry may declare its own binding
kind and the pattern begins the statement:

```azora
(val mail = email, fin name = username, var n = loginCount) = user
```

The two forms may not be mixed. Either:

- an outer keyword supplies the kind for all entries; or
- every newly declared entry states its own kind.

### 7.5 Name-based assignment destructuring

Existing variables may receive selected members:

```azora
(mail = email, name = username) = user
```

Here `mail` and `name` must already be assignable targets. As with positional
assignment, the source and selected components are evaluated before writes
begin.

### 7.6 Pattern classification

At one parenthesis level, a pattern is either positional or name-based:

```azora
fin (x, y) = value                    // positional
fin (x = width, y = height) = value  // name-based
```

Mixing the two at the same level is rejected:

```azora
fin (x, y = height) = value // error
```

Nested patterns may independently choose their mode:

```azora
fin ((x, y), label = name) = value // allowed only if the outer representation
                                   // explicitly defines these two components;
                                   // ordinary packs should prefer one mode per level
```

For version 1, the compiler SHOULD conservatively reject a mixed-mode outer
pattern unless the source's destructuring bridge describes it exactly. This
keeps diagnostics predictable.

### 7.7 Ignoring components

An underscore discards a positional component:

```azora
fin (first, _, third) = triple
```

The component is still extracted far enough to run required ownership and
destruction semantics. `_` does not suppress a move, failure, or destructor.

In a name-based pattern, omission already ignores a member, so `_ = member` is
allowed only when forcing that member's evaluation or move is meaningful. It
should otherwise receive an “omit the entry” warning.

### 7.8 Nested destructuring

Positional patterns may nest:

```azora
fin ((x, y), (width, height)) = geometry
```

Name-based patterns may bind a nested member through a nested pattern:

```azora
val ((street, city) = address, name = username) = user
```

The right side of `=` in a name-based entry is a source member selector, not an
arbitrary expression. Version 1 permits one accessible identifier. Member paths
may be added later if they can produce better diagnostics than nested patterns.

### 7.9 Destructurable sources

Tuples are always positionally destructurable.

Packs support name-based destructuring through accessible stored fields or
properties. Normal visibility rules apply; destructuring cannot bypass
`protected`, `confined`, or private access.

Positional destructuring of a non-tuple pack requires explicit opt-in:

```azora
pack User derives Destructure {
    fin username: String
    fin email: String
}
```

`derives Destructure` fixes the positional order to stored-field declaration
order and makes changing that order a source-compatibility change. Computed,
static, and hidden fields do not participate unless an explicit implementation
defines a custom order.

The initial implementation may make `Destructure` compiler-known. A later
library-facing bridge may expose the equivalent of:

```azora
bridge spec Destructure<...Elements>
```

but the bridge must preserve moves and borrows rather than materializing a
copied tuple unconditionally.

### 7.10 Arity and type checking

Positional pattern arity must exactly equal source arity. Diagnostics report
both counts:

```text
cannot destructure (Int, String, Bool) into 2 positions: source has 3 elements
```

Every binding annotation is checked against the corresponding component:

```azora
fin (id: Int, name: String) = value
```

A shared annotation on the whole pattern is permitted only when it is a tuple
type of matching arity:

```azora
fin (id, name): (Int, String) = value
```

### 7.11 Ownership during destructuring

Destructuring follows the ownership mode of the source expression:

```azora
fin (a, b) = copyTuple       // copies only if the required components are Copy
fin (a, b) = take ownedPair  // moves owned components
fin (a, b) = pair.&          // binds shared borrows where component types permit
fin (a, b) = pair.!          // binds exclusive borrows where permitted
```

Rules:

1. The source expression is evaluated exactly once.
2. A named non-`Copy` source is not implicitly consumed.
3. `take source` permits moving non-`Copy` fields/components.
4. Moving components records partial moves on the source.
5. Borrowed destructuring produces component borrows rooted in the source's
   origin.
6. No component borrow may outlive that origin.
7. Name-based extraction observes the same access and ownership rules as an
   ordinary member expression.
8. Assignment validates all target writes and source moves before performing
   any write, preventing half-applied assignments caused by a static error.
9. Runtime failure during extraction follows normal partial-initialization and
   destruction rules.

## 8. Explicit statements and destructuring

| Intent | Syntax | Evaluation |
|---|---|---|
| Create independent values | `fin a = makeA()` followed by `fin b = makeB()` | each initializer once |
| Retain one result | `fin value = make()` | once |
| Split a positional result | `fin (a, b) = makePair()` | source once |
| Assign components | `(self.a, self.b) = makePair()` | source once |
| Construct a collection | `fin xs = [a, b]` | each element once |
| Construct a tuple | `fin pair = (a, b)` | each element once |

## 9. Grammar sketch

```text
primary-expression
    := literal | identifier | call-or-member-expression
     | parenthesized-or-tuple-expression | collection-literal | lambda-expression

parenthesized-or-tuple-expression
    := "(" ")" | "(" expression ")"
     | "(" expression "," expression ("," expression)* [","] ")"

binding-statement
    := binding-kind identifier [":" type] "=" expression
     | binding-kind destructure-pattern "=" expression

import-selector-group
    := path "::" "{" import-selector (separator import-selector)* [separator] "}"
```

A brace in expression position opens a lambda. A brace after an import's `::`
opens selectors. Statements use ordinary names and assignable expressions.

## 10. Compiler integration

Keep collection shape, tuple elements, expected types, and source spans through
resolution. Resolve each collection literal through the target's `literal`
factory, with no intermediate collection. Validate tuple patterns against source
arity and ownership before lowering their positional reads and writes.

All backends must observe the same element order, one evaluation of a destructured
source, and normal cleanup for partially initialized values.

## 11. Literal migration

| Existing construction | Native form |
|---|---|
| `@arr[1, 2]` or `@vec[1, 2]` | `[1, 2]` |
| `mapOf(entry(k, v))` | `[k: v]` |
| `tupleOf(a, b)` | `(a, b)` |
| `Tuple<A, B>` | `(A, B)` |

## 12. Validation

Test empty and nested literals, contextual target selection, generic inference,
element type errors, spreads, source order, tuple identity and access, and
single-evaluation destructuring. Test import selectors independently of value
expressions. Exercise standard and custom factories on every supported backend.

## 13. Companion operator vocabulary

This section records the operator vocabulary selected alongside the delimiter
design. It prevents these spellings from acquiring unrelated meanings in
separate features and defines how they interact with ownership, borrowing,
effects, evaluation order, and the lexer.

`<|`, `<|>`, and `///`.

### 13.1 Equality, identity, and type tests

Azora distinguishes three different questions:

```azora
left == right       // do these values compare equal?
left === right      // do these handles/views identify the same object or storage?
value is User       // does this runtime value conform to type User?
```

Their negations are correspondingly different:

```azora
left != right
left !== right
value is! User
```

`==` and `!=` use value semantics and the ordinary `Equal` contract. They may
run type-defined equality logic and may report whatever effects the equality
contract permits.

`is` and `is!` test runtime type conformance. Their right operand is a type or
type pattern, not a value. They answer whether a boxed/spec value, variant, or
other dynamically typed representation can be viewed as that type. They say
nothing about whether two values have equal contents or share storage. `is!`
may lex as `BANG` followed by `IS`; it does not require a combined token.

#### 13.1.1 Identity equality with `===` and `!==`

`===` asks whether two identity-bearing values refer to the same semantic
identity. `!==` is exactly its logical negation. These operators are
compiler-defined and cannot be overloaded; user code must not redefine what
“same allocation” means.

Identity is defined by category:

| Operand category | `a === b` means |
|---|---|
| Shared borrows | both borrows have the same storage address and compatible provenance |
| Exclusive borrows | both designate the same storage; ordinary exclusivity normally prevents two independently live operands |
| `Shared<T>` / `SyncShared<T>` | both handles use the same ownership/control block |
| `Weak<T>` | both weak handles refer to the same control block, even if the object has expired |
| `Unique<T>` views | both views designate the allocation owned by the same unique handle |
| Boxed spec/object value | both boxes designate the same dynamic object identity |
| Raw pointer in `unsafe` code | equal address and provenance according to the pointer model |
| Nullable identity value | `null === null` is true; `null === nonNull` is false |

Scalar numbers, booleans, characters, ordinary unboxed tuples, and ordinary
value packs do not have identity merely because they occupy memory during one
compilation. Comparing them with `===` is a type error:

```azora
fin a = 42
fin b = 42

a == b  // true
a === b // error: Int is a value type and has no observable identity
```

Likewise, two independent values can be equal without being identical:

```azora
fin first: Shared<Image> = sharedOf(loadImage())
fin second: Shared<Image> = first.clone()
fin independent: Shared<Image> = sharedOf(first.value.clone())

first === second      // true: same control block
first === independent // false: different allocations
first == independent  // may be true if Image equality compares equal contents
```

Both operands are evaluated exactly once, left to right. Identity comparison
observes them through shared access; it never moves, clones, retains, releases,
or extends the lifetime of either operand. A non-`Copy` smart pointer therefore
does not need to be copied merely to compare its identity.

For generics, identity comparison is accepted only when the constraints prove
that `T` has identity-bearing representation. The compiler may expose this as a
compiler-known `Identity` capability, but implementing that capability cannot
override the comparison algorithm.

Identity is not equality, ordering, hashing, or synchronization. In particular:

- `===` does not imply `==`;
- identity does not supply a stable hash after an object is destroyed;
- comparing identities does not make concurrent access safe;
- raw address equality does not prove that dereferencing is valid;
- identity comparison does not keep a weak target alive.

Recommended diagnostics include:

```text
operator '===' requires identity-bearing operands; type 'Point' is an unboxed
value type. Use '==' for value equality.

cannot compare identities of unrelated types 'Shared<Image>' and
'Shared<Window>'
```

### 13.2 Ownership-aware exchange with `<>`

`<>` is a statement-level primitive that exchanges the values stored in two
assignable locations:

```azora
left <> right
self.front <> self.back
items[i] <> items[j]
```

It is conceptually similar to:

```azora
(left, right) = (right, left)
```

but gives exchange a direct ownership model and permits a backend to use an
efficient swap without constructing a tuple-shaped temporary.

The normative rules are:

1. Both operands must be assignable mutable locations of the same static type.
2. Resolve and evaluate the left location once, then the right location once,
   before moving either stored value.
3. Acquire the exclusive access required for both locations.
4. Move the left value into compiler-owned temporary storage.
5. Move the right value into the left location.
6. Move the temporary into the right location.
7. Do not copy, clone, retain, release, or run a destructor for the values merely
   because they were exchanged.

Consequently, exchange works for move-only values:

```azora
var primary: Unique<Socket> = openPrimary()
var fallback: Unique<Socket> = openFallback()

primary <> fallback // valid: owners exchange locations; neither is duplicated
```

Binding mutability still applies. A `fin`, immutable field, shared borrow, or
read-only property cannot participate as a destination. An exclusive borrow may
participate when it grants access to the complete stored location.

The compiler must handle aliasing explicitly:

- provably disjoint locations are exchanged;
- provably identical complete locations make the operation a no-op after both
  location expressions have been evaluated;
- provably partially overlapping locations are rejected;
- safe indexed/container locations whose equality is known only at runtime may
  perform one equality/disjointness check before moving values;
- raw-pointer overlap that cannot be proven safe requires `unsafe` and remains
  subject to the pointer provenance model.

Active borrows are checked as if both locations were exclusively borrowed for
the duration of the operation. A live shared/exclusive borrow that conflicts
with either location rejects the exchange. Exchanging two handles does not move
their pointees, but it does move which binding owns each handle.

Location evaluation may fail before the exchange begins—for example, a checked
index can be out of bounds. In that case no stored value is moved. After both
locations and their exclusive access have been established, the primitive
exchange itself is non-failable and indivisible with respect to language-level
destruction. It is not automatically atomic between threads; use an atomic
container operation when inter-thread atomicity is required.

`<>` is not a general expression and produces `Unit` only as a statement. It
cannot be overloaded because custom implementations could accidentally clone,
drop, or partially exchange unique owners.

#### Current implementation profile (2026-09-14)

The compiler represents exchange as dedicated AST and IR statements. The
interpreter, LLVM and WASM implementations support mutable ordinary bindings,
stored pack fields and built-in array elements of identical static types. Both
locations, including checked indices, are evaluated before either value is
written. The primitive adds no clone, retain, release or destructor calls.
Optimizer passes preserve storage identity and invalidate affected value facts.

This is a restricted implementation of the contract above. Until location loans
and address-preserving borrow lowering are available, the frontend rejects raw
dereferences, borrowed binding exchange, call-bearing locations (including
overloaded index arithmetic), lazy/reactive storage, custom accessors and known
owner/projection overlap. Index arithmetic must use built-in integer operations.
Existing mutability and conflicting-borrow checks apply to both operands.

WASM array allocation, indexing, stores and exchange now carry element widths
(including Double and Long); wide pack fields still require a typed aggregate
layout and are rejected. LLVM rejects erased generic fields whose physical and
resolved types differ. Ordinary scalar Double bindings can be exchanged. Exact-once destruction
of all move-only ownership models, general pointer provenance, reactive
notifications and arbitrary exclusive-borrow locations remain acceptance work;
the handle and aliasing tests do not certify those broader ownership facilities.

### 13.3 Typed compiler holes with `???`

`???` is a deliberate missing-expression marker used during development:

```azora
func loadUser(id: UserId): User {
    return ???
}
```

It receives a dedicated `TYPED_HOLE` token and may appear wherever an expression
is required. It does not appear in a type, declaration name, pattern, or target
position. The lexer must recognize `???` before `??` and `?`.

A typed hole participates in constraint collection so the compiler can continue
checking its surroundings. It adopts the expected type, ownership mode, failure
set, and effect requirements of its position without claiming that an actual
implementation exists. For example:

```azora
func<T, R> transform(value: T): R {
    return ???
}
```

may produce:

```text
typed hole has expected type 'R'
required ownership: return an owned R
available values:
  value: T (owned, not Copy under current constraints)
no conversion from T to R is available
```

A useful hole diagnostic SHOULD include:

- the inferred expected type;
- whether the position needs an owned value, shared borrow, or exclusive borrow;
- the required borrow origin when returning a borrow;
- allowed and required failure/effect sets;
- visible locals and whether each is available, borrowed, or already moved;
- callable candidates whose result is compatible;
- missing generic constraints that would make a candidate valid.

The hole itself performs no operation. It does not move a local, create a
borrow, acquire a resource, emit an effect, or generate cleanup. Ownership
analysis treats those events as hypothetical only so later diagnostics do not
cascade from a nonexistent move.

Every reachable or unreachable `???` is a compilation error in all production
and development builds. It never silently lowers to `null`, zero, `Unit`, an
uninitialized value, or `panic`. Programmers who want an executable stub must
write an explicit `panic("TODO")` or other failure expression.

Multiple holes should each receive a stable diagnostic identifier so IDEs can
display and request completions for them independently. Named holes and automatic
code synthesis are possible future IDE features but do not change the source
semantics.

### 13.4 Pipelines with `|>` and `<|`

Pipelines change the written order of an ordinary call; they do not introduce a
new calling, ownership, failure, or dispatch convention.

#### 13.4.1 Forward pipeline `|>`

The forward form sends the left value into the first argument position of the
right call target:

```azora
value |> transform
value |> transform(option)
```

These lower respectively to:

```azora
transform(value)
transform(value, option)
```

Pipelines associate left to right:

```azora
request
    |> validate
    |> normalize
    |> save(database.&)
```

is equivalent to:

```azora
save(normalize(validate(request)), database.&)
```

except that the typed AST should retain source spans for each pipeline stage so
errors point to the failing stage rather than to one synthesized nested call.

#### 13.4.2 Reverse application `<|`

The reverse form permits the callable to be written first while keeping a large
argument expression unparenthesized:

```azora
render <| buildPage(model)
save(database.&) <| normalize(request)
```

These mean:

```azora
render(buildPage(model))
save(normalize(request), database.&)
```

`<|` associates right to left. Mixing forward and reverse pipeline operators at
one level SHOULD require parentheses because otherwise visually plausible code
can have surprising grouping.

#### 13.4.3 Ownership and borrowing through a pipeline

Pipeline lowering preserves the exact argument expression. It never inserts
`take`, `lend`, `.&`, `.!`, or `.clone()`:

```azora
resource.&   |> inspect       // shared borrow
resource.!   |> mutate        // exclusive borrow
take resource |> consume      // explicit ownership transfer
resource.clone() |> retain    // explicit independent owner
```

If `consume` takes a non-`Copy` value by ownership, `resource |> consume` is
rejected for the same reason as `consume(resource)`. A pipeline stage may retain
a borrow only when the ordinary callable signature and borrow-origin rules allow
it. Async stages receive no special lifetime extension.

Every stage is evaluated once in written data-flow order. The callable and its
non-piped arguments follow the same evaluation order as the equivalent ordinary
call. Failures and effects propagate normally; `|>` and `<|` do not catch,
retry, roll back, or parallelize anything.

#### 13.4.4 Pipeline typing and precedence

The result type of one stage becomes the first-argument input type of the next.
Generic inference uses that input normally. Overload resolution is performed one
stage at a time and diagnostics should print the incoming pipeline type.

Version 1 does not introduce a placeholder syntax such as `_` inside a stage.
The piped value always occupies the first argument position. A lambda makes any
other placement explicit:

```azora
value |> { v -> combine(prefix, v) }
```

Pipeline precedence is lower than arithmetic, member access, calls, comparison,
and null coalescing, but higher than assignment and group operators. `|>` is
left-associative and `<|` is right-associative.

### 13.5 Alternative choice with `<|>`

`<|>` selects the first successful failable expression:

```azora
fin config = loadCachedConfig() <|> downloadConfig() <|> defaultConfig()
```

Its behavior is:

1. Evaluate the left expression exactly once.
2. If it succeeds, return its value and do not evaluate the right expression.
3. If it fails with a recoverable failure, clean up its partially initialized
   owned values and evaluate the right expression.
4. If both fail, combine/propagate their declared failure information according
   to the ordinary failable-expression model.

It does not catch panic, memory faults, cancellation that must propagate, or
failures outside the expression's declared recoverable set. Effects performed
before the left failure remain observable; `<|>` is lazy but not transactional.
APIs needing rollback must provide an explicit transaction/checkpoint scope.

Both alternatives must produce one common assignable result type. Ownership is
branch-local: only the successful branch transfers its result outward, while a
failed branch destroys anything it initialized. A named non-`Copy` value cannot
be consumed independently by both alternatives unless control-flow ownership
analysis proves that exactly one executed branch takes it:

```azora
fin output = first(take input) <|> second(take input)
```

This is valid only if failure from `first` guarantees that ownership of `input`
is restored or was never committed. If `first` can consume and then fail without
returning ownership, `second` cannot take the same value. `lend`/`return`, a
clone, or a shared owner makes the policy explicit.

`<|>` is not boolean OR (`||`) and not null coalescing (`??`). A nullable value
uses `??`; `<|>` is reserved for language-level recoverable failure/alternative
semantics. It associates left to right and has lower precedence than `|>`/`<|`
but higher precedence than assignment.

### 13.6 Documentation comments with `///`

`///` begins a documentation comment that runs to the end of the physical line:

```azora
/// Opens a file and returns its unique owner.
///
/// Fails with `NotFound` when the path does not exist.
func open(path: Path&): File
```

Consecutive `///` lines form one Markdown document block. The block attaches to
the next declaration when only whitespace, ordinary comments, and decorators
intervene. A blank undocumented source line ends the block unless the blank line
itself is written as `///`.

Documentation may attach to modules, imports intended for re-export, types,
variants, fields, constructors, functions, properties, specs, impl members,
macros, and other named declarations. A dangling documentation block at the end
of a scope is a warning.

The compiler retains normalized documentation and source spans in symbol
metadata for IDE hover, generated API documentation, reflection where enabled,
and diagnostics. Documentation comments do not enter runtime data unless a
reflection/build option explicitly requests them.

Markdown code fences are content, not Azora lexical delimiters. Documentation
tools may interpret tags or links, but the compiler should not give arbitrary
`@tag` text semantic effects. References to Azora symbols should be resolved by
the documentation tool using stored source context.

The lexer must recognize `///` before the ordinary `//` line-comment rule and
emit either a `DOC_COMMENT` trivia token or an equivalent attached trivia node.
The third slash is not part of the text. `//// text` is a documentation comment
whose content begins with `/ text`, unless a formatter chooses to warn.

Documentation comments have no ownership, borrowing, evaluation, or effect
semantics. They must not change whether code compiles except for documentation
validation configured as warnings/errors.

### 13.7 Lexical priority and token names

The lexer uses maximal munch. These spellings must be checked before any token
that is their prefix:

| Priority family, longest first | Suggested token |
|---|---|
| `===` | `IDENTICAL` |
| `!==` | `NOT_IDENTICAL` |
| `???` | `TYPED_HOLE` |
| `<|>` | `ALTERNATIVE` |
| `///` | `DOC_COMMENT` trivia |
| `<>` | `EXCHANGE` |
| `|>` | `PIPE_FORWARD` |
| `<|` | `PIPE_REVERSE` |

Important prefix relationships include:

```text
===  before == and =
!==  before != and !
???  before ?? and ?
<|>  before <|, |>, <, and |
///  before // and /
<>   before < and >
```

Whitespace does not change tokenization inside one ligature. The formatter
places one space around binary operators and preserves `///` at the beginning
of its documentation line.

Borrow-origin fences are contextual type syntax, not ligatures:
`T&|self|` tokenizes as the borrow `&`, an opening `|`, the origin name, and a
closing `|`. The lexer must not invent an `&|` token. Empty `T&||` is invalid;
therefore maximal-munch `||` remains logical OR and never denotes an empty
origin set. The formatter emits origin fences without internal edge spaces and
places nullable `?` after them: `T&|source|?`.

### 13.8 Overloading policy

These constructs are semantic syntax, not arbitrary overload names:

- `===`/`!==` use compiler-defined identity;
- `<>` performs compiler-verified storage exchange;
- `???` is a compiler hole;
- `|>`/`<|` lower to ordinary calls;
- `<|>` uses the language's recoverable-failure model;
- `///` is documentation trivia.

None may be declared with `oper` in version 1. This keeps ownership and
evaluation guarantees independent of user code. Libraries customize behavior
through the ordinary call targets, failure contracts, or identity-bearing
standard types—not by redefining punctuation.

### 13.9 Diagnostics

Recommended operator-specific diagnostics include:

```text
type 'Point' has value equality but no identity; use '==' instead of '==='

cannot exchange 'left' and 'right': right is shared-borrowed by 'view'

cannot exchange immutable binding 'left'; declare mutable storage or use a new
binding

typed hole has expected type 'File' and must produce an owned value

pipeline stage 'save' expects first argument 'Document' but incoming pipeline
value has type 'Document&'

left alternative may consume 'request' before failing, so the right alternative
cannot take it; lend, clone, or share the value explicitly

documentation comment does not attach to a declaration
```

### 13.10 Required tests

Before stabilization, tests must cover:

#### Identity

- value equality versus identity equality;
- identity of cloned shared/weak handles and independent allocations;
- borrow origins and pointer provenance;
- nullable identity operands;
- rejection for scalars, unboxed tuples, and value packs;
- generic identity constraints;
- exactly-once left-to-right operand evaluation;
- proof that comparison does not change retain/release counts.

#### Exchange

- `Copy`, non-`Copy`, unique-owner, tuple, pack, and smart-pointer storage;
- member and checked-index locations evaluated once;
- immutable targets and conflicting live borrows;
- disjoint, identical, partially overlapping, and dynamically equal locations;
- exact-once destruction and no clone/retain/release during exchange;
- failure during location evaluation before mutation;
- explicit lack of cross-thread atomicity.

#### Typed holes

- expected types from return, argument, collection, tuple, and generic context;
- owned/shared/exclusive expected modes and returned borrow origins;
- effect/failure requirements and missing generic constraints;
- moved/unavailable local reporting;
- multiple independent holes and unreachable holes;
- mandatory compilation failure and absence of generated IR;
- maximal munch against `??` and `?`.

#### Pipelines and alternatives

- forward and reverse associativity and precedence;
- calls with zero and several written arguments;
- generic inference and overload diagnostics at each stage;
- explicit `take`, `lend`, shared borrow, exclusive borrow, and clone inputs;
- failure/effect propagation without hidden catch or concurrency;
- left-success laziness and right-side non-evaluation for `<|>`;
- both-fail diagnostics and cleanup of failed alternatives;
- ownership restoration requirements when retrying with the same value;
- separation from `||` and `??`;
- maximal munch against `|`, `<`, and `>`.

#### Documentation

- single and consecutive documentation lines;
- intentional blank lines written as `///`;
- attachment through decorators and ordinary comments;
- dangling blocks and unsupported attachment targets;
- Markdown fences and symbol-reference source spans;
- metadata retention/removal configuration;
- lexical distinction from `//`, `/`, and division.
