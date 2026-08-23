# Lambda Receivers, Parameters, and Capture

> Status: proposed syntax replacement. The existing compiler implementation must
> be migrated after this design is accepted. Target: Azora 0.0.6.

This DIP defines one lambda model for callable receivers, ordinary parameters,
generic parameters, closure capture, ownership, and asynchronous execution.

The design separates three things that must never be conflated:

1. a **receiver** is supplied when the callable is invoked;
2. an ordinary **parameter** is supplied inside the call parentheses;
3. a **capture** is taken from the lexical environment when the closure is
   created.

The short form is intentionally very small:

```azora
fin scale: Int&.(Int) -> Int = { self * it }
```

`self` is the implicit receiver and `it` is the implicit sole ordinary
parameter. Neither value is captured.

## 1. Design decisions

This DIP adopts the following rules:

- Callable receiver types are written before a dot:
  `Int&.(Int) -> Int`.
- A lambda normally omits its receiver declaration and obtains it from the
  expected callable type.
- `self` names an implicit receiver.
- `it` names one implicit ordinary parameter.
- Explicit ordinary parameter names remain inside the body before `->`.
- Explicit receiver names are written before the body:
  `[&] value { ... }` or `[&] (a, b) { ... }`.
- Capture lists remain square-bracket forms immediately before the body:
  `[&]`, `[=]`, `[n]`, `[n.&]`, `[n!, k]`, `[take]`, and
  `[captured = value.&]`.
- A default may carry a hard exclusion fence: `[&, without e]`,
  `[&, without e, without f]`, and `[&, without (e, f)]`.
- Receiver modes, capture defaults, and capture overrides share one header:
  `[!, cache.&] context { ... }` or `[&, cache.!] context { ... }`.
- Receiver ownership is checked at invocation. Capture ownership is checked at
  closure creation.

The former bracketed receiver-type syntax and its semicolon partition are
removed. A lambda has at most one square-bracket ownership header. When receiver
names follow it, the leading ownership mode also governs those receivers. The
same mode is the default for captured free values unless explicit entries
override it.

## 2. Canonical surface

The canonical family of lambda forms is:

```text
[async] <generics> ownershipHeader? receiverNames? lambdaBody
```

Every prefix is optional. The smallest lambda is:

```azora
{ 42 }
```

Representative forms:

```azora
{ value -> value * 2 }
{ value: Int -> value * 2 }
{ self.render() }
{ self.scale(it) }

[&] context { context.render() }
[&] (left, right) { left.combine(right) }
[&] (a, b) { x, y -> a * x + b * y }

[n] { n }
[n.&] { n }
[n!, k] { n += k }
[take] { connection.send(request) }
[captured = value.&] { captured.render() }
[&, without e] { use(a, b, c, d) }
[&, without (e, f)] { use(a, b, c, d) }

[&, cache.!] context { item -> context.render(item, cache) }
async [take] { connection.send(request) }
```

`func` is never part of a lambda expression. Named declarations continue to
use `func` or `async func`:

```azora
func<T> T&.inspect(depth: Int): String
async func load(id: String): Product
```

## 3. Callable types

### 3.1 No receiver

An ordinary callable type starts with its parameter list:

```azora
() -> Unit
(Int) -> String
(Int, String) -> Bool
```

### 3.2 One receiver

A callable receiver type precedes the ordinary parameter list and is separated
from it by `.`:

```azora
Context&.() -> Unit          // shared receiver
Buffer!.(Byte) -> Unit       // exclusive mutable receiver
Request.(Client&) -> Reply   // owned receiver
```

The ownership suffix belongs to the receiver type:

- `T&.` borrows the receiver for reading;
- `T!.` borrows the receiver exclusively and permits legal mutation;
- `T.` consumes an owned receiver when called.

The dot is mandatory. It distinguishes the receiver from an ordinary first
parameter:

```azora
Context&.(Int) -> String     // receiver Context&, ordinary Int parameter
(Context&, Int) -> String    // two ordinary parameters, no receiver
```

### 3.3 Several receivers

Several receiver types use the type-tuple spelling before the dot:

```azora
(Logger&, Metrics&).() -> Unit
(Canvas!, Theme&, Locale&).(Node&) -> Unit
(Source&, Destination!).(Int) -> Result
```

This is a receiver-type group, not a runtime tuple value. Receiver order is
part of callable type identity.

After alias normalization and generic substitution, receiver base types must be
pairwise distinct. Capability does not make equal types distinct:

```azora
(Logger&, Metrics&).() -> Unit   // valid
(Logger&, Logger!).() -> Unit    // rejected: Logger occurs twice
```

Use a nominal role wrapper or make one value an ordinary parameter when an
operation genuinely needs two values of the same type.

## 4. Implicit `self`

When a lambda has an expected callable type with one receiver and has no
explicit receiver name, the receiver is available as `self`:

```azora
fin render: ViewContext&.() -> Unit = {
    self.draw()
}
```

The receiver type determines what `self` may do. A `T&.` receiver cannot mutate
`T`; a `T!.` receiver may perform mutation permitted by `T`; a `T.` receiver is
owned for that invocation and may be consumed.

For several unnamed receivers, `self` is a compiler-only positional receiver
view:

```azora
fin copy: (Source&, Destination!).(Int) -> Unit = {
    self.1.write(self.0.read(it))
}
```

`self.0`, `self.1`, and so on do not allocate a tuple. They address resolved
receiver slots. Positional access is useful for short generic adapters, but
explicit names are preferred when a body performs substantial work.

A lambda with no callable receiver has no synthesized `self`. An enclosing
named method's `self` remains an outer binding and is subject to the capture
rules if a nested lambda uses it.

## 5. Implicit `it`

When the expected callable type has exactly one ordinary parameter and the
lambda has no explicit `->` parameter header, that parameter is available as
`it`:

```azora
fin twice: (Int) -> Int = { it * 2 }
fin scale: Int&.(Int) -> Int = { self * it }
```

`it` is not introduced when:

- the callable has no ordinary parameters;
- it has two or more ordinary parameters;
- the lambda writes an explicit parameter header;
- no expected type or invocation constraint can establish exactly one
  parameter.

Two or more ordinary parameters must be named:

```azora
fin combine: (Int, Int) -> Int = { left, right -> left + right }
```

An explicit one-parameter header suppresses `it`:

```azora
fin twice: (Int) -> Int = { value -> value * 2 }
```

Nested lambdas resolve `it` to the nearest lambda that synthesizes it. Code
should name parameters explicitly when nested implicit parameters would make a
body difficult to read.

## 6. Explicit receiver names

Receiver names follow the lambda's single ownership header. Their types are
known from the callable expectation or written on the names.

### 6.1 One receiver

```azora
fin render: Context&.() -> Unit = [&] context {
    context.finish()
}
```

The leading header mode must agree with the callable receiver capability:

```azora
[&] context       // shared receiver
[!] buffer        // exclusive mutable receiver
[take] request    // owned receiver
```

An optional type annotation permits a lambda to establish its receiver type
without an expected callable type:

```azora
fin render = [&] context: Context { context.finish() }
```

The mode supplies the outer capability, so the annotation names the base type
without repeating `&`, `!`, or ownership punctuation. That same mode is the
default capture policy for otherwise unnamed used free values.

### 6.2 Several receivers

A uniform mode applies to every name in a parenthesized receiver group:

```azora
fin weighted: (A&, B&).(X, Y) -> R =
    [&] (a, b) { x, y -> a * x + b * y }
```

Mixed capabilities use one mode per name:

```azora
fin transfer: (Source&, Destination!).(Int) -> Unit =
    [&, !] (source, destination) { count ->
        destination.write(source.read(count))
    }
```

The following rules apply:

- one mode may be broadcast to all receiver names;
- otherwise the number of modes must equal the number of names;
- the resolved modes must match the callable receiver types positionally;
- names must be unique;
- canonical receiver base types must be pairwise distinct;
- the explicit names replace positional `self` for that lambda;
- one broadcast mode is also the capture default;
- a positional mixed-mode vector has no single capture default, so every free
  value must have an explicit capture entry.

Per-name base-type annotations are also accepted:

```azora
[&, !] (source: Source, destination: Destination) { count: Int -> ... }
```

### 6.3 A receiver slot is not closure storage

An explicitly named receiver is still supplied at invocation. It is not read
from the closure's creation scope and does not occupy capture storage. The
header's default governs the receiver's call-time capability as well as the
capture policy, but those operations happen at different times.

```azora
fin action: Context&.() -> Unit = [&] value {
    consume(value)
}
```

Here `value` does not need to exist where `action` is created. It is bound when
`action` is invoked with a contextual receiver.

## 7. Capture entries

Capture happens when the closure is created. Capture defaults and explicit
capture entries live in the same single ownership header that precedes optional
receiver names.

### 7.1 Explicit captures

| Syntax | Meaning | Requirement | Outer binding afterward |
|---|---|---|---|
| `[n]` | independent value copy | `n` implements `Copy` | usable |
| `[n.clone()]` | independent clone | `n` implements `Clone` | usable |
| `[n.&]` | shared borrow of original | valid shared borrow | usable subject to loan |
| `[n!]` / `[n.!]` | exclusive mutable borrow | exclusive access is legal | borrowed for closure lifetime |
| `[take n]` | move ownership into closure | `n` is owned here | moved and unusable |
| `[alias = source]` | copied capture under another name | source is `Copy` | usable |
| `[alias = source.&]` | shared capture under another name | valid shared borrow | usable subject to loan |
| `[alias = source!]` | mutable capture under another name | exclusive access is legal | borrowed |
| `[alias = take source]` | owned capture under another name | source is owned here | moved |

Multiple entries are comma-separated:

```azora
[n!, k] { n += k }
[snapshot = state, output = buffer!, config = settings.clone()] {
    output.write(snapshot, config)
}
```

`n!` is the compact capture spelling. `n.!` is its expression-aligned spelling;
both resolve to the same mutable capture and cannot be used together for one
source. Shared capture uses `n.&`.

### 7.2 Ownership defaults

An ownership header may contain one broadcast default:

| Default | Applied to each otherwise unnamed used free local |
|---|---|
| `[=]` | copy |
| `[&]` | shared borrow |
| `[!]` | mutable borrow |
| `[take]` | move |

Defaults are usage-driven. They select only free local bindings used by the
lambda body. They never capture globals, declarations, types, modules, or the
entire lexical scope.

When receiver names follow the header, `&`, `!`, or `take` also supplies their
receiver capability. A uniform receiver mode is broadcast over a parenthesized
receiver-name group. `=` is capture-only; receiver capability must then be
known from the expected callable type.

```azora
fin worker = async [take] {
    connection.send(request)
}
```

Only the used free locals `connection` and `request` move. An unused binding in
the surrounding scope is unaffected.

An explicit entry overrides the default for that source. The override belongs
in the same header:

```azora
[=, counter!, cache.&, socket = take connection] {
    counter += 1
    cache.read()
    socket.send()
}
```

With a named receiver, meaningful mixed policies include:

```azora
[!, cache.&] context { ... }  // context and free values mutable; cache shared
[&, cache.!] context { ... }  // context and free values shared; cache mutable
```

`[&, cache.&] context` is rejected as a redundant override: `[&]` already
selects shared borrowing for every otherwise unspecified used value and for the
receiver named `context`.

A source may be captured only once, including through aliases.

### 7.3 Capture exclusions with `without`

`without` removes a lexical binding from the set a default may capture:

```azora
fin a = loadA()
fin b = loadB()
fin c = loadC()
fin d = loadD()
fin e = sensitiveState()

fin action = [&, without e] {
    use(a, b, c, d)
}
```

The compiler captures `a`, `b`, `c`, and `d` by shared borrow because the body
uses them. It does not capture `e`. A default never captures an unused binding,
so the immediate closure layout is the same as `[&]` while `e` remains unused.
The exclusion is nevertheless meaningful: it is a persistent rule preventing a
later edit from silently capturing `e`.

The explicit and default-plus-exclusion forms express different maintenance
policies:

| Form | Policy |
|---|---|
| `[a.&, b.&, c.&, d.&]` | closed allowlist: only these four sources may be captured |
| `[&, without e]` | open default with denylist: any used free value except `e` may be captured by shared borrow |

If a later edit uses a new free binding `g`, the explicit allowlist reports a
missing capture, while `[&, without e]` captures `g` automatically. Choose the
first form for a tightly audited closure boundary and the second when only
specific values must never cross the boundary.

Repeated and grouped exclusions are equivalent:

```azora
[&, without e, without f] { ... }
[&, without (e, f)] { ... }
```

The parenthesized form is a list owned by `without`; it does not construct a
tuple. Physical newlines may separate names without commas:

```azora
[&,
    without (
        e
        f
    )
] { ... }
```

An exclusion is a hard fence, not a low-priority preference. If the lambda body
needs an excluded free binding, compilation fails:

```azora
fin invalid = [&, without e] {
    consume(e) // error: e is excluded from capture
}
```

The diagnostic proposes one of these deliberate repairs:

- remove `without e` and allow the default capture;
- pass `e` as an ordinary parameter;
- supply it as a contextual receiver when its type is a capability;
- derive a separate safe value before the lambda and capture that new binding.

An explicit capture cannot override an exclusion in the same header:

```azora
[&, e.&, without e] { ... }                 // rejected
[&, alias = e.&, without e] { ... }         // rejected by source identity
```

This makes `without` useful as an auditable ownership, security, and lifetime
boundary. Removing the fence must be a visible source edit.

`without` requires a capture default. `[e.&, without f]` is rejected because an
explicit-only header already captures nothing except what it names, making the
exclusion meaningless. Empty groups, duplicate exclusions, unknown names, and
excluding globals or declarations are also rejected.

Exclusions resolve in the closure-creation scope. They apply only to lexical
capture candidates, never to ordinary parameters or invocation receiver slots.
If an excluded name is also chosen as an explicit receiver alias, the compiler
rejects the confusing collision and asks for one of the names to be changed.

### 7.4 Capture analysis and optimization feedback

The compiler computes an exact capture report for every lambda. The report is
available to diagnostics, AZLS, compiler dumps, and optimization passes:

```text
receiver: context by shared borrow
captured: a.&, b.&, c.&, d.&
excluded: e
closure fields: 4
```

Useful feedback includes:

- an explicit capture that the body never uses: suggest removing it;
- a mutable capture used only for reads: suggest changing `value!` to
  `value.&`;
- an owned capture whose source is `Copy` and remains needed afterward: suggest
  a copy capture, but never insert it silently;
- an excluded value that becomes necessary: error at the use and relate it to
  the `without` entry;
- a default that selects no values: report an optional lint, not an error.

An unused `without e` is not warned about by default. It may be an intentional
future-proof fence, which is precisely its purpose. A stricter lint profile may
report it as informational but must not offer automatic deletion without
explaining that the policy guard would be lost.

The closure representation contains no fields for unused or excluded values.
After ownership checking, ordinary optimization may inline the closure,
scalar-replace fields, or remove the environment entirely. It must not silently
weaken `!` to `&`, replace `take` with copy, or remove an exclusion from resolved
metadata merely because one backend optimized the current body differently.

## 8. The single-header rule

A lambda has zero or one square-bracket ownership header:

```azora
[&] { readState() }                   // shared capture default, no receiver
[&] value { consume(value) }          // shared receiver and shared captures
[&] (a, b) { x, y -> ... }            // shared receivers and shared captures
[!, cache.&] context { ... }          // mutable default, shared cache override
[&, cache.!] context { ... }          // shared default, mutable cache override
[&, without secret] context { ... }   // shared default; secret may not be captured
[&, without (e, f)] context { ... }   // grouped hard exclusions
```

Receiver names, when present, follow that header. Capture overrides stay inside
the same brackets. A second ownership/capture bracket before the body is always
rejected:

```azora
[&] context [cache.&] { ... } // error: merge into one ownership header
```

The diagnostic should propose a semantically meaningful merge. It must not
blindly suggest `[&, cache.&] context`, because that override is redundant. If
the programmer intended a different capability, possible repairs are
`[!, cache.&] context` or `[&, cache.!] context`.

Parsing is determined entirely by syntax:

- `[` opens the one ownership header;
- an identifier or parenthesized name group after `]` names receivers;
- `{` opens the body;
- another `[` between receiver names and `{` is an error.

No expected type is consulted to choose the parse. Expected callable types are
used only afterward to validate or infer receiver types and capabilities.

## 9. Normative grammar

```text
lambdaExpression
    := "async"? genericParameters? ownershipHeader? receiverNames? lambdaBody

ownershipHeader
    := "[" ownershipItems? "]"

ownershipItems
    := ownershipItem (separator ownershipItem)*

ownershipItem
    := ownershipDefault
     | capture
     | captureExclusion

ownershipDefault
    := "=" | "&" | "!" | "take"

receiverNames
    := receiverName
     | "(" receiverName (separator receiverName)+ ")"

receiverName
    := identifier (":" baseType)?

capture
    := identifier
     | identifier ".&"
     | identifier "!"
     | identifier ".!"
     | identifier ".clone" "(" ")"
     | "take" identifier
     | identifier "=" captureSource

captureSource
    := identifier
     | identifier ".&"
     | identifier "!"
     | identifier ".!"
     | identifier ".clone" "(" ")"
     | "take" identifier

captureExclusion
    := "without" identifier
     | "without" "(" identifier (separator identifier)+ ")"

genericParameters
    := "<" genericParameter (separator genericParameter)* ">"

genericParameter
    := identifier | "..." identifier

lambdaBody
    := "{" explicitParameters? statements "}"

explicitParameters
    := parameter (separator parameter)* "->"

parameter
    := identifier (":" type)?
     | "..." identifier ":" type
     | identifier ":" "..." identifier

separator
    := "," | physicalNewline
```

When receiver names follow the header, its leading bare ownership entries are
interpreted as follows:

- one of `&`, `!`, or `take` broadcasts to every receiver and is also the
  capture default;
- exactly as many leading receiver modes as receiver names map positionally;
  this mixed vector supplies no capture default;
- `=` is never a receiver capability; it remains a capture-only default and
  receiver capabilities must come from the expected callable type;
- named and aliased entries are capture overrides, not receiver modes.

More than one bare ownership entry without receiver names is rejected as
conflicting capture defaults. With receiver names, a bare-mode prefix whose
length is neither one nor the receiver count is rejected.

Physical newlines may replace commas where the grammar already knows it is
inside a delimited list. A comma remains required when two entries share one
physical line.

An empty ordinary parameter list writes no arrow:

```azora
{ run() }       // correct
{ -> run() }    // rejected
```

Const generic parameters are not accepted on lambda expressions. A lambda may
have one variadic generic type pack, and a variadic ordinary parameter must be
last. Receiver lists are not variadic.

## 10. Receiver and capture composition

Receiver names and capture aliases share the lambda body's value namespace.
They must not collide.

```azora
[&, context = fallback.&] context { ... } // rejected: duplicate body name
```

Receiver base types must be unique, but capture types may repeat. Captures are
resolved by lexical name, while receivers are resolved by callable type.

The order of operations is fixed:

1. At closure creation, evaluate explicit capture sources from left to right.
2. Acquire capture borrows or move/copy/clone captured values.
3. At each invocation, evaluate receiver expressions left to right.
4. Acquire all receiver borrows as one checked set.
5. Evaluate ordinary arguments left to right.
6. Enter the body.
7. Release invocation-scoped receiver and parameter borrows on return.

Capture loans may live as long as the closure. Receiver loans normally live for
one invocation. The borrow checker checks overlap between the two sets.

## 11. Invocation

A one-receiver callable can be called through dot syntax or supplied through a
`using` context:

```azora
fin scale: Int&.(Int) -> Int = { self * it }

println(5.scale(2))

using 5 {
    println(scale(2))
}
```

Several receivers use the language's multi-receiver forms:

```azora
using (source, destination) {
    transfer(16)
}

{source, destination}.transfer(16)
```

`(source, destination).transfer(16)` calls a member on one tuple.
`[source, destination].transfer(16)` calls a member on one collection. Neither
supplies two receiver slots.

An owned `T.` receiver is consumed at the call. A `T&.` receiver is shared for
the call, and a `T!.` receiver requires exclusive access for the call. The
closure never inserts `take`, `clone()`, or a borrow-strength conversion.

## 12. Free-variable resolution

A local is free in a lambda when it is declared outside that lambda and used by
the body. The following are not captures:

- ordinary parameters;
- receiver bindings, including implicit `self`;
- implicit `it`;
- locals declared inside the lambda;
- generic parameters;
- globals and static storage;
- named functions, types, modules, and scopes.

A lambda that uses a free local must name it in the ownership header or select
it with a capture default. A missing-capture diagnostic proposes valid
spellings.

Each nested lambda has its own capture boundary:

```azora
var n = 1
fin outer = [n.&] {
    fin inner = [n.&] { n }
}
```

The inner lambda sees the outer closure's `n` as an outer local and captures it
deliberately. Capture policy is never inherited silently.

## 13. Generic and variadic lambdas

Generic parameters precede the ownership header and receiver names:

```azora
fin identity = <T> { value: T -> value }

fin transform = <T, U> [&, cache.!] context: Context { value: T ->
    context.convert<T, U>(value, cache)
}
```

Generic names are compile-time types and are not captured. Receiver-type
uniqueness is rechecked after generic substitution. Thus `(T&, U&).() -> Unit`
is a legal generic shape, but a specialization where both become the same
canonical type is rejected.

A heterogeneous variadic ordinary parameter uses a variadic type pack:

```azora
fin count = <...T> { values: ...T -> values.size }
count(1, "two", true)
```

A homogeneous variadic ordinary parameter puts `...` on its name:

```azora
fin sum = <T> { ...values: T ->
    var result = 0
    for value in values { result += value }
    result
}
```

Receivers themselves are never variadic.

## 14. Asynchronous lambdas

`async` composes with the same receiver, capture, and parameter syntax:

```azora
fin worker = async [take] {
    connection.send(request)
}

fin scheduled: Scheduler&.() -> Unit = async [&, input = take value] scheduler {
    scheduler.run(input)
}
```

Borrowed captures must satisfy normal lifetime and suspension rules. An
asynchronous closure that may outlive the current lexical operation normally
copies, clones, or takes its captures. A receiver is still supplied per
invocation and must remain valid for that invocation.

## 15. Escaping closures

A closure that escapes its creation scope must own every captured local. Copy,
clone, and move captures may escape; borrowed captures may not outlive their
sources.

```azora
func make(): () -> Int {
    fin n = 1
    return [n] { n }
}

func invalid(): () -> Int {
    var n = 1
    return [n.&] { n } // rejected: borrowed local escapes
}
```

A callable receiver type does not make the closure borrow a particular
receiver while stored. The borrow begins only when a receiver is supplied to an
invocation. Consequently a receiver-taking closure may escape even when its
future receiver will be borrowed.

## 16. Mutation, moves, and returned borrows

Assignment counts as a free-variable use for capture selection:

```azora
var count = 0
fin increment = [!] { count += 1 }
```

Member and index mutation select the root binding:

```azora
fin append = [list!] { list.add(1) }
fin replace = [table!] { table[key] = value }
```

An exclusive capture does not invent permissions. Capturing a `fin` binding as
`value!` does not make the binding reassignable, and it cannot make immutable
fields mutable.

An owned capture moves when the closure is created. An owned receiver moves
when the closure is called. These events are intentionally different:

```azora
fin consumeReceiver: Message.() -> Unit = { send(take self) }
fin consumeCaptured = [message = take pending] { send(take message) }
```

A returned borrow must name its origin in the callable return type:

```azora
fin name: User&.() -> String&|self| = { self.name.& }
```

For an explicitly named receiver, `self` in the origin fence still denotes the
receiver slot, not a lexical identifier:

```azora
fin name: User&.() -> String&|self| = [&] user { user.name.& }
```

For several receivers, use `self.0`, `self.1`, and so on in the origin fence,
even when the body gives those slots names. This keeps callable type identity
independent of local aliases.

## 17. Trailing lambdas

The same forms may be used as a call's final argument:

```azora
observe(source) [latest.!, calls.!] { value ->
    latest = value
    calls++
}

build(mode: .Fast) [&, cache.!] context {
    context.finish(cache)
}
```

Parentheses may be omitted when the lambda is the only ordinary argument.
Constructor, method, generic, scope-qualified, callable-field,
callable-variable, and arbitrary callable-expression calls use the same
trailing-lambda rule.

The parser recognizes receiver names after an ownership header only as part of
a lambda expression.
It must not reinterpret a collection expression or an ordinary call based on a
later expected type.

## 18. Lowering contract

The semantic resolver records two independent structures:

1. receiver slots: canonical type, capability, position, optional local alias;
2. capture slots: exact source binding, capture mode, optional alias, lifetime.

It also records a source-identity set of capture exclusions. Exclusions do not
become closure fields and have no runtime representation; they are retained in
resolved metadata for diagnostics, IDE explanations, and incremental rebuilds.

Capture selection is target-independent:

1. Resolve every explicit capture and exclusion in the creation scope.
2. Reject duplicates, source aliases that cross the exclusion fence, and
   exclusions without a default.
3. Collect the lambda's semantically live free-local uses.
4. Remove locals handled by explicit capture entries.
5. If any remaining used local is excluded, emit a capture-exclusion error.
6. Apply the default only to the remaining used locals.
7. Materialize closure slots in deterministic source-binding order.

Compile-time evaluation and statically resolved branch elimination may prove a
reference semantically unreachable before step 3. Such a value needs no capture.
Later backend-specific dead-code elimination must not change capture diagnostics
or ownership availability; otherwise the same source could compile differently
for LLVM and WebAssembly.

It also records whether `self` and `it` were synthesized. Defaults are expanded
during semantic analysis. No backend repeats free-variable or capture policy.

- copy and move captures store values in the closure environment;
- clone captures create independent values before storage;
- shared and mutable captures store a checked address or shared cell;
- receiver slots are invocation inputs and are not closure fields;
- aliases lower body references to their resolved slot;
- exclusions lower to no slot and perform no borrow, copy, clone, or move;
- globals remain direct global accesses;
- a move capture invalidates its source after closure creation;
- an owned receiver invalidates its call-site source after invocation;
- positional multi-receiver `self.N` lowers directly to receiver slot `N`;
- `it` lowers to ordinary parameter slot zero.

The interpreter, LLVM, WebAssembly, and later self-hosted compiler must consume
the same resolved representation.

## 19. Diagnostics

Diagnostics should name the category—receiver, parameter, or capture—and give
one concrete repair.

```text
this lambda has one receiver; use 'self' or name it with '[&] receiver'

'it' requires exactly one ordinary parameter; write '{ left, right -> ... }'

receiver group has 2 names but its positional mode vector has the wrong arity

receiver 'fallback' repeats canonical type 'Logger' already used by 'primary';
wrap both values in distinct nominal role types or make one an ordinary parameter

receiver mode '&' does not match expected mutable receiver 'Buffer!.'

'n' is a free local; capture it with '[n]', '[n.&]', '[n!]', '[take n]',
or select a default such as '[&]'

'items' cannot be captured by copy because List is not Copy;
use '[items.&]', '[items.clone()]', or '[take items]'

this returned closure borrows 'state'; clone or move it into the closure

contextual receivers cannot be variadic

'e' is used here but excluded by 'without e'; remove the exclusion, pass 'e'
as an ordinary parameter or receiver, or capture a separate safe derived value

'e' is both captured and excluded in this ownership header; an exclusion is a
hard fence and cannot be overridden by an alias

'without e' requires a capture default such as '&', '!', '=', or 'take'

lambdas do not use 'func'; write '{ ... }'
```

Source ranges should cover the offending receiver mode/name, parameter, capture
entry, default, or lambda introducer rather than the entire declaration.

## 20. Migration

The compiler should provide targeted rewrites:

| Previous form | New form |
|---|---|
| receiver type written as a bracket list | `T&.(...) -> R` or `(T&, U&).(...) -> R` |
| named receiver in a lambda header | `[&] receiver { ... }` |
| receiver and capture separated by `;` | one ownership header followed by receiver names |
| implicit one ordinary parameter named manually | `{ it ... }` where clear |
| mutable capture | preserve either `[value!]` or `[value.!]` |

Examples:

```azora
fin scale: Int&.(Int) -> Int = { self * it }

fin render: Context&.() -> Unit = [&] context {
    context.finish()
}

fin cached: Context&.(Item&) -> Output = [&, cache.!] context { item ->
    context.render(item, cache)
}
```

The migration must preserve ownership exactly. It must not replace an explicit
borrow with a copy, insert `clone()`, or turn a capture into an invocation
receiver.

## 21. Final invariants

1. Receiver, parameter, and capture are separate semantic categories.
2. `self` is synthesized only from callable receiver slots.
3. `it` is synthesized only for exactly one unnamed ordinary parameter.
4. Receiver ownership is exercised per invocation.
5. Capture ownership is exercised at closure creation.
6. Parsing never depends on an expected type.
7. A lambda has at most one ownership header; receiver names follow it.
8. Several receiver base types are pairwise distinct after substitution.
9. `without` is a hard source-identity fence and cannot be bypassed by aliasing.
10. Defaults select only semantically needed free values after exclusions.
11. No implicit clone, move, or borrow-strength conversion is inserted.
12. Every backend consumes the same resolved receiver and capture metadata.
