# Lambda Context and Capture

> Status: implemented for the interpreter, LLVM, and WebAssembly. JavaScript
> consumes the same resolved Azora IR capture metadata. Target: Azora 0.0.6.

This DIP defines one lambda expression model for contextual receivers, ordinary
parameters, generic parameters, capture, ownership, and asynchronous execution.
Its main design rule is simple:

> The semicolon in a lambda header separates context from capture.

The compiler never guesses whether a bare name is a receiver or a captured
binding. Expected types may fill omitted types, but may not change syntax.

## 1. Canonical form

```text
[async] <generics>[receivers; captures] { parameters -> body }
```

Every prefix is optional. The smallest lambda is therefore:

```azora
{ 42 }
```

Examples:

```azora
{ value -> value * 2 }
{ value: Int -> value * 2 }
[context] { context.render() }
[context: RenderContext&] { context.render() }
[; offset] { value -> value + offset }
[context; offset.&] { value -> context.scale(value + offset) }
<T> { value: T -> value }
<...T>[context; take] { values: ...T -> context.consume(values) }
async [; take] { connection.send(request) }
```

`func` is never part of a lambda expression. It introduces a named declaration.

```azora
fin valid = { 1 }
fin worker = async [; take] { work() }

fin invalid = func { 1 }
fin invalidWorker = async func { work() }
```

## 2. Grammar

The normative surface grammar is:

```text
lambdaExpression
    := "async"? genericParameters? lambdaHeader? lambdaBody

lambdaHeader
    := "[" receiverList? (";" captureList?)? "]"

receiverList
    := receiver ("," receiver)*

receiver
    := identifier (":" type)?

captureList
    := captureItem ("," captureItem)*

captureItem
    := captureDefault
     | capture

captureDefault
    := "=" | "&" | "!" | "take"

capture
    := identifier
     | identifier ".&"
     | identifier ".!"
     | identifier ".clone" "(" ")"
     | "take" identifier
     | identifier "=" captureSource

captureSource
    := identifier
     | identifier ".&"
     | identifier ".!"
     | identifier ".clone" "(" ")"
     | "take" identifier

genericParameters
    := "<" genericParameter ("," genericParameter)* ">"

genericParameter
    := identifier | "..." identifier

lambdaBody
    := "{" lambdaParameters? statements "}"

lambdaParameters
    := parameter ("," parameter)* "->"

parameter
    := identifier (":" type)?
     | "..." identifier ":" type
     | identifier ":" "..." identifier
```

Const generic parameters are not accepted on lambda expressions. A lambda may
have one variadic generic pack, and a variadic ordinary parameter must be last.
Contextual receivers are deliberately not variadic yet.

An empty ordinary parameter list writes no arrow:

```azora
{ run() }       // correct
{ -> run() }    // error
```

## 3. The semicolon boundary

Everything before `;` is a contextual receiver. Everything after `;` is a
capture entry or capture default.

```azora
[value] { value.render() }       // receiver named value
[; value] { consume(value) }     // copy capture named value
[value;] { value.render() }      // receiver, explicitly empty capture section
[; value.!] { value.update() }   // mutable capture, no receivers
```

This rule is syntax-only. It does not depend on:

- an expected callable type;
- overload selection;
- generic inference;
- a later specialization;
- which backend is selected.

Old mixed lists such as `[context: Ctx&, value.!]` are rejected with a migration
diagnostic. They must become `[context: Ctx&; value.!]`.

## 4. Contextual receivers

A contextual receiver is supplied by `with` or by receiver-call syntax. It is
not an ordinary positional argument.

Callable types always include ordinary-parameter parentheses. The receiver
list is optional when empty; `()` is never optional:

```azora
[Context]() -> String       // context, no ordinary parameters
[Context](Int) -> String    // context plus one ordinary parameter
() -> String                // no context and no ordinary parameters
[Int, Int] -> Int           // error: missing ()
```

```azora
fin scale: [Int](Int) -> Int = [value] { factor -> value * factor }

with 5 {
    std::println(scale(2))
}

std::println(5.scale(2))
```

Receiver types may be written or inferred from the expected callable type:

```azora
fin explicit = [context: Context&] { context.finish() }
fin inferred: [Context&]() -> Unit = [context] { context.finish() }
```

Several receivers preserve their declared order. Names must be unique. A name
cannot be both a receiver and a capture alias in the same lambda.

```azora
[car: Car&, world: World&; settings.&] { car.update(world, settings) }
```

When an API expects contextual receivers, a trailing bare lambda may inherit
them without naming them. The inherited values are available to nested calls
that require the same context.

## 5. Explicit capture modes

Capture happens when the closure is created, not when it is first called.

| Syntax | Meaning | Requirement | Outer binding afterward |
|---|---|---|---|
| `[; value]` | independent value copy | `value` implements `Copy` | usable |
| `[; value.clone()]` | independent cloned value | `value` implements `Clone` | usable |
| `[; value.&]` | shared borrow of original | valid shared borrow | usable, read-only through closure |
| `[; value.!]` | exclusive mutable borrow | outer access permits requested write | borrowed for closure lifetime |
| `[; take value]` | move ownership into closure | value is owned here | moved and unusable outside |

The dotted `.&` and `.!` spellings are the same ownership spellings used at
ordinary expression sites. Forms such as `value!` are not capture syntax.

Copy, clone, and move captures create closure-owned bindings. Shared and mutable
captures refer to the original storage. Consequently:

```azora
var n = 1
fin copy = [; n] { n }
fin read = [; n.&] { n }
fin add = [; n.!] { amount -> n = n + amount }

n = 5
copy()       // 1
read()       // 5
add(2)
read()       // 7
```

An exclusive capture does not invent permissions. Capturing a `fin` binding as
`value.!` does not make its name rebindable, and capturing an immutable value
does not make member or indexed mutation legal.

## 6. Capture defaults

A capture section may contain one default:

| Default | Applied to each otherwise unnamed used free variable |
|---|---|
| `[; =]` | copy |
| `[; &]` | shared borrow |
| `[; !]` | mutable borrow |
| `[; take]` | move |

Defaults are usage-driven. They select only free local bindings actually used
by the lambda body. They never capture globals, declarations, type parameters,
members reached through an already captured value, or the entire lexical scope.

```azora
fin used = openConnection()
fin untouched = openConnection()

fin worker = async [; take] {
    used.send(request)
}

// used moved; untouched did not.
```

This is the meaning of `[; take]`: move every used free local into the closure.
It is not and will never be "move the whole scope".

An explicit capture overrides the default for that source binding:

```azora
[; =, counter.!, cache.&, socket = take connection] {
    counter = counter + 1
    cache.read()
    socket.send()
}
```

A source may be captured once. Two aliases of one source are a duplicate, not
two independent captures.

## 7. Aliases

Aliases prevent collisions and make ownership intent local to the closure:

```azora
[;
    snapshot = state,
    sharedState = stateRef.&,
    output = buffer.!,
    ownedSocket = take socket,
    ownedConfig = config.clone()
] {
    output.write(snapshot)
    ownedSocket.configure(ownedConfig)
}
```

The name left of `=` exists only inside the closure. The source name right of
`=` is resolved in the creation scope. Alias names and source names must each be
unambiguous within the capture section.

## 8. Free-variable resolution

A local is free in a lambda when its declaration is outside that lambda's own
scope and it is referenced by the body. The following are not captures:

- ordinary parameters;
- contextual receivers;
- locals declared inside the lambda;
- generic parameters;
- globals and static storage;
- named functions, types, modules, and zones.

A lambda with no matching explicit capture and no default cannot use an outer
local. The diagnostic proposes valid capture spellings.

Each nested lambda has its own boundary:

```azora
var n = 1
fin outer = [; n.&] {
    fin inner = [; n.&] { n }
}
```

The inner lambda does not inherit the outer lambda's capture policy. It sees the
outer closure's binding as an outer local and must capture it deliberately.

Lexical upper-scope access (`::name`) cannot bypass a lambda boundary. Use a
capture alias when a local name is shadowed:

```azora
var value = 1
fin readOuter = [; captured = value.&] { captured }
```

## 9. Generic lambdas

Generic parameters precede the receiver/capture header:

```azora
fin identity = <T> { value: T -> value }
fin transform = <T, U>[context: Context&; cache.&] { value: T ->
    context.convert<T, U>(value, cache)
}
```

Generic names are compile-time types and are not captured. Types can be inferred
from a callable expectation or invocation in the same way as named generic
functions. They are erased or specialized before backend closure layout is
chosen, so capture layout never depends on an unresolved generic name.

### Variadic generic packs

`<...T>` declares a variadic type pack. A heterogeneous variadic ordinary
parameter uses that pack on the type side:

```azora
fin count = <...T> { values: ...T -> values.size }
count(1, "two", true)
```

A homogeneous variadic ordinary parameter puts `...` on the name:

```azora
fin sum = <T> { ...values: T ->
    var result = 0
    for value in values { result += value }
    result
}
```

These may be combined with receivers and capture:

```azora
fin dispatch = <...T>[bus: Bus!; logger.&, resources = take pending] {
    events: ...T ->
    bus.publish(events)
    logger.record(events.size)
}
```

Only ordinary parameters may be variadic. `[...contexts: Context]` is rejected.

## 10. Async lambdas

`async` composes with the same generic, receiver, capture, and parameter syntax:

```azora
fin worker = async [; take] {
    connection.send(request)
}

fin genericWorker = async <T>[scheduler: Scheduler&; input = take value] {
    transform: (T) -> T -> scheduler.run { transform(input) }
}
```

No `func` keyword appears. The result is a task handle governed by structured
concurrency. Borrowed captures must satisfy ordinary lifetime and suspension
rules; owning `take` is the normal choice when work may outlive its creation
statement or move to another thread.

## 11. Escaping closures

A closure that escapes its creation scope must own its captured locals. It may
use copy, clone, or move capture. Shared and mutable borrow captures are rejected
when the closure is returned, stored in an escaping position, or passed to an
`escaping` callable parameter.

```azora
func make(): () -> Int {
    var n = 1
    return [; n] { n }          // valid when Int is Copy
}

func invalid(): () -> Int {
    var n = 1
    return [; n.&] { n }        // error: borrowed local escapes
}
```

An unused borrow default captures nothing. Once a free binding is selected by
that default, the ordinary escaping rule applies to that exact binding.

## 12. Mutation and shadowing

Assignment is a use for capture analysis. A default mutable capture therefore
selects an outer binding even when the body only writes it:

```azora
var count = 0
fin increment = [; !] { count = count + 1 }
```

Member and index writes select the root binding as well:

```azora
fin append = [; list.!] { list.add(1) }
fin replace = [; table.!] { table[key] = value }
```

Locals declared inside the lambda shadow capture names from their declaration
point according to normal lexical rules. Duplicate receiver names, duplicate
capture aliases, duplicate sources, and receiver/capture name collisions are
compile errors.

## 13. Trailing lambdas

The same forms may be used as a call's final argument:

```azora
observe(source) [; latest.!, calls.!] { value ->
    latest = value
    calls += 1
}

build(mode: .Fast) [context: BuildContext&; cache.&] {
    context.finish(cache)
}
```

Parentheses may be omitted when the lambda is the only argument. Constructor,
method, generic, zone-qualified, callable-field, callable-variable, and arbitrary
callable-expression calls all use the same trailing-lambda rule.

## 14. Lowering contract

The semantic resolver records the exact source binding and mode for every
capture. Defaults are expanded there. No backend repeats free-variable policy.

- copy/move captures store the value in the closure environment;
- clone captures create an independent value before storing it;
- shared/mutable captures store an address or shared cell;
- aliases lower body references to the captured source slot;
- globals remain direct global accesses;
- move capture marks the outer binding unavailable after closure creation.

The interpreter overlays value snapshots on the captured scope chain and deep
copies clone captures. LLVM stores pointers for borrowed captures, including
spawned task contexts. WebAssembly boxes borrowed locals into shared linear-
memory cells so outer code and every closure observe one value. Optimizers must
invalidate facts for exact by-reference captures.

## 15. Diagnostics

Diagnostics should state the ownership error and one concrete repair:

```text
'n' is not in scope; capture it with '[; n.&]', '[; n.!]', '[; n]',
or choose a default such as '[; &]'

capture 'n' must follow ';'; write '[; n.!]'

'items' cannot be captured by copy because List is not Copy;
use '[; items.&]', '[; items.clone()]', or '[; take items]'

this returned closure borrows 'state'; clone or move it into the closure

contextual receivers cannot be variadic

lambdas do not use 'func'; write '{ ... }'
```

Source ranges should cover the offending receiver, capture item, default, or
lambda introducer rather than the complete enclosing declaration.

## 16. Complete examples

### Receiver, override, and ordinary parameter

```azora
var attempts = 0
fin base = 10

fin calculate = <T>[metrics: Metrics!; =, attempts.!] { value: T ->
    attempts += 1
    metrics.record(attempts)
    base + value
}
```

`metrics` is a contextual receiver, `base` is copied by the default, `attempts`
is an explicit mutable override, and `value` is an ordinary generic parameter.

### Safe asynchronous ownership transfer

```azora
fin connection = openConnection()
fin request = makeRequest()
fin diagnostics = logger

fin worker = async [; take, diagnostics.&] {
    diagnostics.trace("sending")
    connection.send(request)
}

await worker
```

Only `connection` and `request`, the used bindings covered by `take`, move into
the task. `diagnostics` is explicitly borrowed and must remain valid until the
structured task completes.

### Heterogeneous variadic dispatch

```azora
fin emit = <...T>[sink: EventSink!; sequence.!] { events: ...T ->
    for event in events {
        sink.emit(sequence, event)
        sequence += 1
    }
}
```

The type pack describes the heterogeneous ordinary arguments. It does not make
the receiver variadic and does not alter capture behavior.
