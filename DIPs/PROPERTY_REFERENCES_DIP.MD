# Azora First-Class Property References

> Status: proposed. Target: Azora 0.1. No parser, semantic, standard-library,
> IR, or backend support described here is implemented yet.

This DIP introduces values that refer to an existing field or computed property.
It does not introduce property lambdas. A reference is created explicitly with
`bind`, read explicitly with `read`, and, where legal, written explicitly with
`write`.

```azora
fin declaration = bind User::name
fin bound = bind user::name

std::println(declaration.read(user))
std::println(bound.read())
```

The design keeps three operations separate:

```azora
user.name                         // read the value now
std::reflect<User::name>          // compile-time declaration metadata
bind User::name                   // unbound runtime property reference
bind user::name                   // runtime property reference bound to user
```

The distinction is deliberate. Reflection answers static questions and is
erased. Binding creates a typed runtime capability. Ordinary member access
continues to read the value directly.

---

## 1. Goals

Property references provide a type-safe foundation for APIs that need to name a
member without reading it immediately:

- serializers and schema mappings;
- validation and form models;
- editor inspectors;
- engine and UI bindings;
- generic copying, projection, sorting, and indexing;
- dependency configuration;
- reactive adapters that observe a separately supplied state owner.

The design must preserve Azora's existing guarantees:

1. Every referenced member is resolved at compile time.
2. There is no lookup by string and no general runtime reflection.
3. Visibility is checked where the reference is created.
4. Bound references obey ownership, borrowing, and lifetime rules.
5. Mutation cannot be obtained from a `fin` or computed read-only member.
6. LLVM and WebAssembly require no garbage collector or hidden allocation.
7. Calling through a reference preserves contracts, failure types, asynchronous
   behavior, and dynamic spec dispatch.

## 2. Non-goals

This DIP does not add:

- property lambdas;
- callable types without `()`;
- runtime member lookup by name;
- runtime enumeration of fields;
- Java-style reflection objects;
- references to functions, operators, constructors, destructors, or indexers;
- references that own an arbitrary temporary receiver;
- implicit observation or reactivity;
- automatic serialization of references;
- mutable computed-property setters.

Callable types remain unchanged:

```azora
() -> String                // function
[Context]() -> String       // function with a contextual receiver
[Context] -> String         // still invalid
```

A property reference is not invoked by omitting `()`. It is a distinct typed
value with `read` and, when supported, `write` operations.

## 3. Terminology

This DIP uses **property reference** as the public umbrella term for references
to both storage fields and computed `prop` declarations.

- A **field reference** names a `var` or `fin` declared by a `pack`.
- A **computed-property reference** names a `prop` declared in an `impl`.
- An **unbound reference** contains the member identity but no owner value.
- A **bound reference** contains the member identity and a borrow of one owner.
- A **read reference** permits reading.
- A **mutable reference** additionally permits writing to a mutable field.

The compiler retains the actual member kind. Tooling and diagnostics may say
"field" or "computed property" where that distinction matters.

## 4. Surface syntax

### 4.1 Unbound references

An unbound reference starts from a type:

```azora
fin name = bind User::name
fin retries = bind Config::retries
fin empty = bind Collection::isEmpty
```

It receives an owner when used:

```azora
std::println(name.read(user))
retries.write(config.!, 5)
std::println(empty.read(items))
```

Creating an unbound reference does not borrow an instance. It may therefore be
stored globally, copied, and reused with many owners of the same type.

### 4.2 Bound read references

A bound reference starts from a stable owner expression:

```azora
fin name = bind user::name
fin retries = bind config::retries

std::println(name.read())
std::println(retries.read())
```

The default is a shared, read-only binding even when the referenced field is a
`var`. The explicit equivalent is `.&`:

```azora
fin a = bind user::name
fin b = bind user::name.&
```

Both borrow `user` for reading and produce the same type.

### 4.3 Bound mutable references

The `.!` suffix requests exclusive write access:

```azora
var user = User("Mira")
fin name = bind user::name.!

name.write("Azora")
std::println(name.read())
```

This is legal only when:

- the owner is available for exclusive borrowing;
- the target is a mutable `var` field;
- visibility at the binding site permits mutation;
- the owner storage is stable for the reference's lifetime.

The suffix belongs to the complete bound member path. It does not change the
declared mutability of the member.

### 4.4 Grammar

```text
propertyReferenceExpression
    := "bind" propertyReferenceTarget boundAccess?

propertyReferenceTarget
    := typeReference "::" memberName
     | stablePlaceExpression "::" memberName

boundAccess
    := ".&"
     | ".!"

memberName
    := identifier
```

`boundAccess` is accepted only for a value-bound target. An unbound reference
does not borrow an owner, so `bind User::name.&` and `bind User::name.!` are
errors.

The parser records a syntactic member path. Semantic resolution decides whether
the left side denotes a type or a value; capitalization is never used as a
language rule.

`bind` already appears in decorator and dependency definitions. In expression
position it begins a property-reference expression and does not alter those
declaration forms.

## 5. Normal access is unchanged

`::` inside `bind` names the declaration. Ordinary runtime member access remains
`.`:

```azora
user.name                   // String
bind user::name             // std::BoundPropertyRef<User, String>
bind User::name             // std::PropertyRef<User, String> or mutable counterpart
```

Neither of the following becomes legal:

```azora
User::name                  // not a runtime value by itself
user::name                  // not ordinary member access by itself
```

This avoids ambiguity with realm access and existing declaration paths such as:

```azora
impl SerialName for User::name
```

The leading `bind` is what asks for a runtime reference value.

## 6. Core types

The feature must not rely on undeclared compiler names. The following
compiler-provided bridge types must be declared in `std/core.az`, where `Unit`,
`Any`, `Self`, and `Type` are already declared:

```azora
use realm std {
    bridge pack PropertyRef<Owner, Value>
    bridge pack MutablePropertyRef<Owner, Value>
    bridge pack BoundPropertyRef<Owner, Value>
    bridge pack BoundMutablePropertyRef<Owner, Value>
    bridge pack AsyncPropertyRef<Owner, Value>
    bridge pack BoundAsyncPropertyRef<Owner, Value>
}
```

Their operations must also have declared signatures. The exact library layout
may use inherent bridge methods or specs, but its observable API is normative:

| type | operation | result |
|---|---|---|
| `std::PropertyRef<O, V>` | `read(owner: O&)` | `V` |
| `std::MutablePropertyRef<O, V>` | `read(owner: O&)` | `V` |
| `std::MutablePropertyRef<O, V>` | `write(owner: O!, value: V)` | `Unit` |
| `std::BoundPropertyRef<O, V>` | `read()` | `V` |
| `std::BoundMutablePropertyRef<O, V>` | `read()` | `V` |
| `std::BoundMutablePropertyRef<O, V>` | `write(value: V)` | `Unit` |
| `std::AsyncPropertyRef<O, V>` | `async read(owner: O&)` | `V` |
| `std::BoundAsyncPropertyRef<O, V>` | `async read()` | `V` |

These names denote compiler-provided representations through their core
declaration identity. Backends must not discover the feature by hardcoded
standard-library module paths.

`std.reactive.Read<T>` and `std.reactive.Write<T>` are observable-state specs.
Property references do not implement or replace them: reading a member does not
subscribe to anything, expose a version, or emit changes.

## 7. Type inference

The owner and value types are inferred from the resolved declaration:

```azora
pack User {
    fin id: Long
    var name: String
}

impl User {
    prop displayName: String = self.name
}

fin id = bind User::id
// std::PropertyRef<User, Long>

fin name = bind User::name
// std::MutablePropertyRef<User, String>

fin displayName = bind User::displayName
// std::PropertyRef<User, String>
```

A `var` produces an unbound mutable reference because write access is checked
when `write` receives its exclusive owner. A bound reference defaults to the
read-only type and requires `.!` to produce the mutable type.

Explicit annotation is allowed:

```azora
fin name: std::MutablePropertyRef<User, String> = bind User::name
fin bound: std::BoundPropertyRef<User, String> = bind user::name
```

There is an implicit capability-narrowing conversion:

```text
std::MutablePropertyRef<O, V>      -> std::PropertyRef<O, V>
std::BoundMutablePropertyRef<O, V> -> std::BoundPropertyRef<O, V>
```

No conversion adds write capability.

## 8. Reading

Reading through a reference has the same observable behavior as direct access:

```azora
property.read(owner) == owner.property
bound.read()          == owner.property
```

For a field, the operation reads storage. For a computed property, it executes
the property's implementation. The operation preserves:

- the declared result type;
- `in` and `out` contracts;
- failure sets;
- spec dispatch;
- decorators that affect generated property behavior;
- ownership of the returned value.

Reading does not automatically copy a non-`Copy` value. The same move-or-borrow
rules that apply to direct property access apply through the reference.

## 9. Writing

Only a mutable field can be written through a property reference:

```azora
pack Settings {
    var retries: Int
    fin endpoint: String
}

fin retries = bind Settings::retries
retries.write(settings.!, 4)

fin endpoint = bind Settings::endpoint
endpoint.write(settings.!, "local") // error: endpoint is fin
```

Computed properties remain read-only because Azora has no property setter
declaration. A similarly named method does not implicitly become a setter.

Writing through a reference performs the same checks as direct assignment:

- the value must be assignable to the field type;
- ownership transfer and `take` rules apply;
- an active shared borrow prevents writing;
- another exclusive borrow prevents writing;
- pack invariants and applicable contracts are preserved;
- field reach is enforced.

A mutable reference is a capability to request a write. It is not permission to
violate the owner's type rules.

## 10. Ownership and lifetimes

### 10.1 Unbound references

An unbound reference contains no owner and has no owner lifetime. It is a
statically typed member descriptor and may be:

- stored in a global;
- copied;
- passed between functions;
- used with any compatible owner;
- sent between threads when its type arguments satisfy the required thread
  safety constraints.

Its `read` and `write` calls borrow the owner only for the call.

### 10.2 Bound shared references

`bind owner::member` and `bind owner::member.&` hold a shared borrow of the
owner. They cannot outlive it:

```azora
func invalid(): std::BoundPropertyRef<User, String> {
    fin user = User("temporary")
    return bind user::name
    // error: the bound property reference outlives user
}
```

Moving or purging the owner while the bound reference is live is rejected.
Mutation that conflicts with its shared borrow is rejected according to the
ordinary non-lexical lifetime rules.

### 10.3 Bound mutable references

`bind owner::member.!` holds an exclusive borrow. While it is live:

- the owner cannot be read or written through another conflicting path;
- no second mutable reference to overlapping storage may be created;
- the reference may read and write its target field;
- the reference cannot be copied or cloned.

Disjoint-field analysis may eventually permit simultaneous mutable references
to separate fields, but v1 conservatively treats them as borrowing the complete
owner.

### 10.4 Stable owners

A bound reference requires stable storage. These are valid when their resulting
place remains stable:

```azora
bind local::field
bind self::field
bind object.member::field
```

These are rejected in v1:

```azora
bind makeUser()::name          // temporary owner
bind users[index]::name       // collection relocation may invalidate it
bind (if ready user else alt)::name
```

Smart-pointer-backed and pinned places may be admitted when their pointer DIP
defines the required stability guarantee. This DIP does not invent an owning
bound-reference form.

## 11. Visibility and encapsulation

Visibility is checked at the `bind` expression, not deferred until `read` or
`write`:

| declaration | read binding | mutable binding |
|---|---|---|
| exposed `fin` field | allowed | impossible |
| exposed `var` field | allowed | allowed with exclusive access |
| `confine` field outside its file | rejected | rejected |
| field of an `opaque pack` outside privileged code | rejected | rejected |
| exposed computed `prop` | allowed | impossible |

Creating a reference in privileged code and returning it intentionally exports
that capability, just as returning a closure that reads or writes the field
would. This is permitted only when the return type itself is visible. There is
no later visibility recheck because the receiving code possesses an explicitly
exported typed capability.

Extensions receive no additional privilege. A property reference cannot be used
to circumvent the existing rule that external extensions cannot mutate packs
whose fields do not expose mutation.

## 12. Computed properties and specs

An inherent computed property can be referenced:

```azora
impl User {
    prop displayName: String = self.name
}

fin display = bind User::displayName
std::println(display.read(user))
```

The reference calls the same implementation as `user.displayName`; it does not
capture or duplicate the property's body.

A property required by a spec preserves normal witness dispatch when the owner
type is generic:

```azora
func render<T>(value: T&) where T is PrettyPrint {
    fin pretty = bind T::pretty
    std::println(pretty.read(value))
}
```

`bind PrettyPrint::pretty` on an existential spec value is deferred until the
dynamic-dispatch representation can express its owner type safely. V1 requires
a concrete owner or a constrained generic owner.

## 13. Asynchronous properties

A named asynchronous computed property produces a distinct asynchronous
reference:

```azora
fin profile = bind User::remoteProfile
fin value = await profile.read(user)

fin bound = bind user::remoteProfile
fin other = await bound.read()
```

It does not produce a lambda and it is not called by bare access. Its `read`
operation creates the same task and follows the same cancellation, failure,
capture, and structured-concurrency rules as direct access to the asynchronous
property.

Mutable asynchronous property references do not exist because fields are
synchronous storage and computed properties have no setters.

## 14. Compile-time properties

An `inline` or `deepinline` property has no runtime accessor. It cannot produce
a runtime property reference:

```azora
fin p = bind TypeInfo::name // error when name is compile-time-only
```

Compile-time declaration information is already provided by:

```azora
std::reflect<TypeInfo::name>
```

An `inline fin` may hold an unbound reference only when every use is expanded
into ordinary runtime access before IR emission. A bound reference is always a
runtime borrow and cannot exist solely as reflection metadata.

## 15. Reflection remains separate

Azora already has compile-time-only `std::reflect`. This DIP does not replace or
extend it into runtime reflection.

```azora
inline fin metadata = std::reflect<User::name>
inline assert metadata.hasDeco<SerialName> { "name must have SerialName" }

fin property = bind User::name
std::println(property.read(user))
```

| operation | phase | contains owner | reads values | emitted at runtime |
|---|---|---|---|---|
| `std::reflect<User::name>` | compile time | no | no | no |
| `bind User::name` | runtime-capable | no | through `read(owner)` | when retained |
| `bind user::name` | runtime | yes, borrowed | through `read()` | yes |
| `user.name` | runtime | immediate receiver | immediately | direct access |

There is no conversion from `Reflected<T>` to a property reference and no
`bind std::reflect<...>` form. A property reference must name its declaration
statically in source or be received as an already typed value.

## 16. Generics

Generic owner and value types are preserved:

```azora
pack Box<T> {
    var value: T
}

func property<T>(): std::MutablePropertyRef<Box<T>, T> {
    return bind Box<T>::value
}
```

A concrete specialization produces a concrete reference:

```azora
fin intValue = bind Box<Int>::value
// std::MutablePropertyRef<Box<Int>, Int>
```

An unspecialized generic member cannot escape a scope in which its type
parameters are unknown. Generic constraints are validated at the binding site,
and conditional fields may be referenced only in specializations where that
field exists.

Variadic generic packs preserve their specialized member layout. A generated
numeric tuple field may become referenceable in a future extension to
`memberName`; v1 accepts identifier member names only.

## 17. Nullability and optional owners

Binding requires a non-null stable owner. Safe-navigation does not implicitly
create an optional bound reference:

```azora
bind possibleUser?::name // invalid
```

The program must branch or unwrap explicitly:

```azora
when possibleUser {
    is User -> {
        fin name = bind possibleUser::name
        std::println(name.read())
    }
    else -> { }
}
```

The value type itself may be nullable without restriction:

```azora
pack User { var nickname: String? }
fin nickname = bind User::nickname
```

## 18. Identity, equality, and serialization

Property references are process-local capabilities, not schema identifiers.
V1 defines no `oper==`, ordering, or hash operation for them. It also does not
derive `Serializable`.

Code that needs a stable schema name uses compile-time reflection and emits an
ordinary string or enum of its own. This avoids making source member names part
of a runtime ABI and avoids pretending that a bound owner has portable identity.

## 19. Reactivity

Reading through a property reference is not reactive:

```azora
fin name = bind User::name
name.read(user) // one read, no observer
```

`std.reactive` may provide adapters that combine an owner, a property reference,
and an actual reactive source:

```azora
fin observed = std::observe(userState, bind User::name)
```

Such an adapter belongs in the library. The compiler does not attach observers,
versions, invalidation, or effects to `bind`.

## 20. Lowering model

The semantic resolver records:

- canonical owner type;
- specialized value type;
- member declaration identity;
- field or computed-property kind;
- read, write, or async capability;
- bound or unbound form;
- bound-owner borrow mode and lifetime;
- visibility decision;
- dynamic spec witness information where applicable.

The IR must use dedicated nodes rather than encoding references as strings or
ordinary lambdas:

```text
PropertyRef(
    ownerType,
    valueType,
    memberId,
    kind,
    capability
)

BoundPropertyRef(
    property,
    ownerPlace,
    borrowMode
)

PropertyRead(reference, owner?)
PropertyWrite(reference, owner?, value)
```

Dedicated IR preserves visibility decisions and ownership information and
prevents a backend from accidentally implementing computed properties as field
offsets.

### 20.1 Interpreter

The interpreter stores a resolved member identity. A field reference reads or
writes the corresponding pack slot; a computed-property reference calls the
resolved implementation. A bound reference additionally stores the interpreter
cell representing its borrowed owner.

### 20.2 LLVM

A statically known unbound field reference lowers to a constant descriptor and
normally disappears after inlining. A retained reference contains the minimum
required representation:

- field offset or generated read/write thunk;
- computed-property function or witness pointer;
- no owner pointer for an unbound reference;
- one borrowed owner pointer for a bound reference.

No heap allocation or garbage collection is required. Bound references carry
LLVM lifetime information derived from Azora's borrow analysis. Mutable bound
references are non-copyable and keep a unique owner pointer.

### 20.3 WebAssembly

WebAssembly uses a field offset or table index plus, for a bound reference, the
owner address. A relocating owner must already be represented by stable storage
or binding is rejected. Reads and writes preserve the same linear-memory cell
used by direct member access.

The interpreter, LLVM, and WebAssembly must agree on behavior. Backend
convenience may change the physical representation but not capability,
ownership, dispatch, or diagnostics.

## 21. Optimization

The common cases should have zero abstraction overhead:

```azora
fin name = bind User::name
std::println(name.read(user))
```

When `name` does not escape, this becomes the same IR as:

```azora
std::println(user.name)
```

Likewise, a bound reference used locally may become a direct owner/member
operation. Optimization is permitted only after ownership and visibility have
been checked. It may not remove a computed property's contracts, failure
transport, async behavior, or dynamic dispatch.

## 22. Diagnostics

Diagnostics must name the member, the requested capability, and a concrete
repair. Examples:

```text
cannot bind 'User::missing': User has no field or property named 'missing'

cannot mutably bind 'User::id': 'id' is a fin field; use 'bind user::id'

cannot bind 'Secret::token': 'token' is confined to its declaring file

bound property reference to 'user.name' outlives 'user'

cannot bind temporary owner returned by 'makeUser'; store it in a stable binding first

computed property 'User.displayName' is read-only

compile-time property 'TypeInfo.name' has no runtime reference; use std::reflect<TypeInfo::name>

'bind User::name.!' is invalid: '.!' is only for a reference bound to an owner value
```

Source ranges should cover the complete `bind` expression for lifetime and
stability errors, and the member or access suffix for resolution and capability
errors.

## 23. Tooling

AZLS and IDE integrations must treat the member after `::` as a declaration
reference:

- semantic coloring follows the referenced field or property kind;
- go-to-definition opens the declaration;
- hover shows owner type, value type, mutability, reach, and documentation;
- completion after `bind Type::` shows bindable fields and properties;
- completion after `bind value::` uses the value's resolved type;
- `.read` and `.write` completion follows the inferred reference type;
- rename updates the binding site because it is a semantic reference, not text;
- an unavailable mutable capability is either omitted or shown with its reason.

`std::reflect<User::name>` retains its existing compile-time hover and metadata
behavior. Tooling must not present `bind` as runtime reflection.

## 24. Required tests

Implementation is incomplete until all of these groups pass under the
interpreter, LLVM, and WebAssembly where runtime behavior applies.

### Parsing and resolution

- unbound field, computed-property, and async-property references;
- bound default, `.&`, and `.!` forms;
- generic owners and conditional fields;
- missing and ambiguous members;
- rejection of bare `Type::member` as a value;
- rejection of `[Context] -> T` as a callable type;
- declaration uses of `bind` remain unchanged.

### Type behavior

- inferred and explicitly annotated reference types;
- mutable-to-read capability narrowing;
- no read-to-mutable conversion;
- exact nullable value types;
- spec property dispatch under a constrained generic;
- async result and failure preservation.

### Ownership

- shared bound reads;
- exclusive bound writes;
- conflicting owner access while a bound reference is live;
- owner move and purge rejection;
- bound-reference escape rejection;
- temporary and relocating-place rejection;
- unbound references remain freely reusable;
- mutable bound references are not copyable or cloneable.

### Visibility

- exposed `fin` and `var` fields;
- privileged same-file mutation allowed;
- confined and opaque fields rejected externally;
- computed-property reach;
- external extension privilege is unchanged.

### Runtime and lowering

- field read/write parity with direct access;
- computed property executes once per `read`;
- contracts and failures are preserved;
- bound references observe later legal writes;
- unbound references operate on several owners;
- retained references survive ordinary function boundaries;
- optimized and unoptimized LLVM output agree;
- WebAssembly uses the original owner cell;
- no LLVM allocation for local unbound or bound references.

### Reflection separation

- `std::reflect<User::name>` folds away;
- `bind User::name` remains only when retained at runtime;
- no conversion between `Reflected<T>` and property-reference types;
- compile-time-only properties direct users to `std::reflect`.

## 25. Implementation order

1. Declare and document the bridge reference types in `std/core.az`.
2. Parse `bind Type::member` and `bind value::member[.&|.!]` into dedicated AST.
3. Resolve member identity, type, capability, reach, and owner stability.
4. Add bound-reference borrow and escape checks.
5. Add dedicated IR nodes and optimization-independent semantics.
6. Implement interpreter behavior.
7. Implement LLVM and WebAssembly representations without allocation.
8. Add backend parity and no-allocation tests.
9. Add AZLS semantic tokens, completion, hover, rename, and navigation.

No backend should ship the feature until the common semantic suite and its
backend parity tests pass.

## 26. Normative rules

1. `bind` names an existing field or computed property; it never creates one.
2. Property references are not lambdas and are not callable types.
3. `std::reflect<Declaration>` is compile-time metadata; `bind` is a typed
   runtime-capable reference.
4. An unbound reference receives its owner explicitly on `read` or `write`.
5. A bound reference borrows one stable owner and cannot outlive it.
6. Bound references are read-only by default; `.!` requests exclusive mutation.
7. Only mutable fields can produce write capability.
8. Visibility is checked where `bind` is written.
9. Direct and referenced access have identical type, contract, failure, async,
   dispatch, and ownership behavior.
10. No form performs lookup by runtime string.
11. No implementation requires garbage collection or hidden heap allocation.
12. Compiler-provided reference types and operations must be declared in core;
    they may not exist only as hardcoded backend names.

## 27. Related DIPs

- `PACK_IMPL_DIP.MD` - fields, computed properties, receivers, and visibility
- `OWNERSHIP_BORROWING_DIP.md` - shared/exclusive borrows and escape rules
- `REFLECTION_DIP.MD` - compile-time-only `std::reflect`
- `COMPILER_INTRINSIC_DIP.MD` - declared compiler-provided bridge types
- `DYNAMIC_DISPATCH_DIP.MD` - spec property witness dispatch
- `CONTRACTS_ASSERTION_DIP.MD` - contracts preserved through referenced access
- `LAMBDA_CONTEXT_CAPTURE_DIP.MD` - callable values, deliberately separate
- `REACTIVE_DIP.md` - observable state adapters built above this feature
