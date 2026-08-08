# Azora Lambdas, Contextual Receivers, and Capture - DIP

> Status: lambdas and contextual receivers are **implemented** in `0.0.5`, and
> so is §5 - phase 1 of §7 has landed. Capture is still **unspecified**, and the
> backends disagree about it (§3). The capture-list syntax proposed in §4 targets
> `0.0.6` and is blocked on §9.

Azora has closures, and they work. What it does not have is an answer to the
question every closure raises:

> when a lambda names a variable from outside itself, what exactly does it get?

Today the answer depends on which backend you run. This DIP writes down the
forms that already exist, measures the disagreement, and proposes a rule that
follows from the ownership model rather than being invented beside it.

---

## 1. The forms that exist

Three spellings, all implemented:

| form     | example                             | parameters                                               |
|----------|-------------------------------------|----------------------------------------------------------|
| bare     | `{ consume(xs) }`, `{ it * 2 }`     | none, or the single one the expected type gives, as `it` |
| explicit | `{ x: Int -> x * 2 }`, `{ n -> … }` | as written                                               |
| receiver | `[self: Vec2&] { self.x + self.y }` | contextual, bound by name                                |
| both     | `[v: Int] { f: Int -> v * f }`      | contextual in brackets, ordinary after the arrow          |

§4 adds one more entry to the receiver form's brackets rather than a fifth
spelling: `[logger: Logger&, message.&] { … }`.

**The arrow goes with the parameters.** A lambda that takes none does not write
one: `{ consume(xs) }`, never `{ -> consume(xs) }`. There is no separate
"explicitly empty" spelling because there is nothing to be explicit about - an
absent parameter list is already unambiguous, and a second way to write it would
be the same thing said twice. §5.1 is what stands between that rule and the
implementation.

A lambda never writes `func`. `func` introduces a *declaration*, and a lambda
declares nothing - `[value: Int] { … }` is the whole form. The one qualifier a
lambda takes is `async`, which is how it becomes an asynchronous lambda or a
generator.

A trailing lambda is the call's final argument. Parentheses remain around the
ordinary arguments and are omitted when the lambda is the only argument:

```azora
fin subscription = Subscription(true) { disposeResource() }
fin greeting = run { "hello" }
runner.observe { value -> trace { value } }
fin transformed = transform(seed: 4) { value -> value * 2 }
fin indirect = (callbackHost) { "called through a value" }
fin sequence = std::sequence<Int> [s: std::SequenceScope<Int>!] { std::yield(1) }
fin named = build [context: BuildContext&] { context.finish() }
fin configured = build(mode: .Fast) [context: BuildContext&] { context.finish() }
```

The `{` reopens statement context, so newlines inside a trailing lambda still
separate statements instead of being swallowed by an earlier argument list.
The rule is uniform for free and generic functions, realm-qualified calls,
constructors, methods, callable fields and variables, and arbitrary expressions
whose type is callable. The expected final parameter supplies omitted lambda
parameter and contextual-receiver types in every one of those forms. A receiver
lambda may therefore trail a call as `[name: Type&] { ... }`, with or without
ordinary arguments in parentheses.

The last expression statement of a lambda body becomes its `return`. That is a
parser rewrite, not an inference: `DO_NOT_INFER_RETURN_TYPE.MD` governs
*declarations*, and a lambda declares nothing.

---

## 2. Contextual receivers

A contextual receiver is a parameter the *caller's scope* supplies rather than
the call site. It is written in brackets, before the ordinary parameters:

```azora
fin add: [Int, Int] -> Int = { x, y -> x + y }
// or fin add = { x: Int, y: Int -> x + y }

func main() {
    with [2, 3] {
        std::println(add())     // 5 - both receivers come from `with`
    }
    std::println([2, 3].add())  // 5 - both receivers come from []
}
```

`with value { … }` and `with [a, b] { … }` open the scope that supplies them.
Ordinary and contextual parameters compose, and a receiver can also be written
at the call rather than around it:

```azora
fin scale: [Int](Int) -> Int = [value] { factor -> value * factor }

with 5 {
    std::println(scale(2))      // 10 - `value` comes from `with`, `factor` is 2
}
std::println(scale(2, 7))       // this is wrong syntax, should not work
std::println(2.scale(7))        // 14 - `value` is 2, `factor` is 7
```

The two halves of the type line up with the two halves of the lambda: the
bracketed `[Int]` is `value`, written in the lambda's brackets, and the
parenthesised `(Int)` is `factor`, written after the arrow. Neither needs its
type repeated - the declared type supplies both, which is why `[value]` and
`factor` are written bare.

A contextual receiver is **not** an argument. There are exactly two ways to
supply one - a `with` block, or the receiver call: `2.scale(7)` for a single
receiver, `[2, 3].add()` for several, the latter being the same list `with`
takes, written at the call instead of around it. Passing one positionally is
refused, because a call's arguments are its parameters and a receiver is not
one:

```
'scale' expects 1 argument(s), got 2 - its 1 contextual receiver(s) are
supplied by 'with' or by a receiver call, not as arguments
```

All of this is implemented. A bare bracket entry is ambiguous by construction -
`[value]` is a copy capture on its own (§4.2) and a receiver when the expected
type declares one and the lambda named none - so the resolver and the IR
generator ask one shared helper which entries are which, rather than deciding
separately and disagreeing.

- ⬜ **A bare entry cannot be a copy capture when the expected type declares a
  receiver.** `[retries]` against `[Ctx] -> R` binds `retries` as the receiver.
  Writing the mode (`[retries.&]`, `[retries.clone()]`) is unambiguous, and so
  is naming the receiver (`[ctx: Ctx]`); only the two bare forms contend

The receiver form binds the context by name, which is what makes an extension
written as a value read like one written as a method:

```azora
pack Vec2 {
    fin x = 0
    fin y = 0
}

func apply(v: Vec2&, f: [Vec2&] -> Int): Int {
    var acc = 0
    with v { acc = f() } // or acc = v.f()
    return acc
}

fin add = [self: Vec2&]{ self.x + self.y }
std::println(apply(Vec2(2, 3), add))   // 5
```

### Inherited receivers

A lambda passed to something that declares receivers need not name them  - 
`std::sequence { std::yield(1) }` names none, yet the closure still has to carry
one. The frontend synthesizes a binding, and `lambdaReceiverName(line, column,
index)` derives its name from the lambda's own source position so that the
frontend and the IR generator agree without threading a counter between passes.

This works and should stay. It is recorded here because it is the one piece of
the design that is invisible in source and therefore easy to break.

---

## 3. The gap: capture is undefined

Nothing in the language reference says what a captured variable is. The
implementations answered independently, and they answered differently.

**The interpreter** builds `Closure(params, body, st.scopes.toList())` - it
keeps the scope chain, so a capture is a *reference* to the original binding.

**LLVM and Wasm** collected the free variables, heap-allocated an environment
struct at closure creation, and copied each captured value into it - so a
capture was a *snapshot*. LLVM no longer does this for a referenced capture; Wasm
still does (§7 phase 3).

Two programs make the difference visible. Both compile cleanly. Both are
measured, not predicted:

```azora
// A - the closure writes to a captured variable
func main() {
    var n = 1
    fin bump = { x: Int -> n = n + x; return n }
    std::println(bump(4))
    std::println(n)
}
```

```azora
// B - the outer scope writes after the closure is made
func main() {
    var n = 1
    fin show = { x: Int -> n }
    n = 99
    std::println(show(0))
}
```

| program | interpreter | LLVM       | Wasm    |
|---------|-------------|------------|---------|
| A       | `5`, `5`    | ✅ `5`, `5` | ⬜ `5`, `1` |
| B       | `99`        | ✅ `99`     | ⬜ `1`   |

**LLVM now agrees**, and `LlvmCaptureParityTest` pins it under both the debug and
the optimized lowering. Two changes were needed, and only the first is the one
this section predicted:

1. A referenced capture stores the binding's **address** in the closure
   environment rather than a copy of its value, and the body binds the name to
   that address - so there is no second copy to fall out of step.
2. Constant propagation had to stop treating a referenced capture as a known
   constant. A closure may write the original whenever it is called, and that is
   not a write the pass can see; without this the optimized build still printed
   `5, 1` while the debug build printed `5, 5`.

The interpreter needed a change too, in the opposite direction: it kept the
whole scope chain, so *every* capture referenced and `[retries]` was not
independent of `retries`. A value capture is now snapshotted into a scope of its
own when the closure is created.

Program A is the one that hurts. The closure's own write is visible to the
closure and invisible to everyone else - the value is updated in a copy that
nothing will ever read again. No diagnostic, no warning, and the interpreter
that most tests run against gives the other answer.

This is not a bug in either backend. It is the absence of a rule, and both
picked a reasonable one.

### Why the ownership model does not already cover it

`OWNERSHIP_BORROWING_DIP.md` §16 says a closure captures by borrow, copy, clone
or ownership transfer, and two of those are enforced today: a `take` inside a
closure moves the outer binding, and an ownership transfer inside the body is
checked exactly as it would be outside one. So this compiles as expected  - 

```azora
var xs = std::listOf<String>()
fin f: () -> Int = { consume(xs) }
// line 2: cannot pass 'xs' by ownership - 'List' is not Copy;
//         transfer ownership with 'take xs', or create an independent value
//         with 'xs.clone()'
```

- because the *use inside the body* is checked. What is unchecked is the
**capture itself**. Reading `xs` inside a closure asks nothing of `xs` and
declares nothing about the mode, and it is exactly that unstated mode that the
backends disagree about.

§16's four modes are the right vocabulary. They have simply never been applied
to the act of capturing.

---

## 4. Proposal: captures are written, in the brackets the lambda already has

The mode a capture uses should be stated, not deduced from how the body happens
to read. A closure's relationship to the scope around it is part of its
interface - the thing a reader needs before reading the body, and the thing the
compiler needs before deciding whether the closure may escape.

Azora already has a bracket list on a lambda for contextual receivers. The
captures go in the same brackets:

```
[ context parameters… , captures… ]
```

No introducer separates them, because each entry already says which it is: an
entry of the form `name: Type` declares a contextual parameter, and anything
else captures. Nothing has to be ordered and nothing has to be announced - a
`:` is the whole distinction.

### 4.1 The two halves of the bracket

```azora
// nothing from outside
let callback = [] {
    trace "Hello"
}

// a contextual receiver, as today
let callback = [logger: Logger&] {
    logger.info("Hello")
}

// a capture
var message: String = String("Hello")
let callback = [message.&] {
    trace message
}

// both
let callback = [
    logger: Logger&,
    message.&
] {
    logger.info(message)
}
```

The two halves read differently because they *are* different, and each uses the
sigil form that already matches its role:

| entry             | is                      | `&` is                                              |
|-------------------|-------------------------|-----------------------------------------------------|
| `logger: Logger&` | a parameter declaration | part of the type                                    |
| `message.&`       | a borrow of a value     | the call-site borrow operator, as in `inspect(h.&)` |

Because each entry classifies itself, the two kinds may be interleaved. Writing
the context parameters first is a convention worth keeping - they are the
closure's interface, the captures its implementation - but it is not a rule the
compiler needs.

What the compiler does need is the lookahead: `[` … `]` immediately followed by
`{` opens a lambda. Without it, `[message.&]` in expression position starts an
array literal. Nothing valid parses that way today - a block is not a postfix
operator on an array - so the rule takes no existing syntax with it, but it must
be written down rather than assumed. §7 says where.

### 4.2 The capture forms

| written         | mode             | asks of the value | leaves the binding                 |
|-----------------|------------------|-------------------|------------------------------------|
| `value.&`       | shared borrow    | nothing           | readable                           |
| `value.!`       | exclusive borrow | nothing           | unusable while the closure lives   |
| `value`         | copy             | `Copy`            | untouched                          |
| `value.clone()` | clone            | `Clone`           | untouched                          |
| `take value`    | move             | nothing           | moved-from, as §10 already reports |

Each is the spelling that operation already has: `take` is a prefix keyword and
`clone` is a method, exactly as they are anywhere else.

```azora
// shared borrow - the closure does not own `message` and cannot outlive it
var message: String = String("Hello")
let callback = [message.&] { trace message }

// exclusive borrow
var count: Int = 0
let increment = [count.!] { count += 1 }

// copy - requires Copy, and the original is independent afterwards
var retries: Int = 3
let callback = [retries] { trace retries }
retries = 5
callback()          // 3

// clone - taken when the closure is created
var message: String = String("Hello")
let callback = [message.clone()] { trace message }
message.append(" World")
callback()          // Hello
trace message       // Hello World

// move
var socket: Socket = Socket.open(8080)
let worker = [take socket] { socket.listen() }
socket.close()
// line 3: use of taken value 'socket' - its ownership transferred at line 2
```

The move case needs no new diagnostic: `take socket` is a `take`, so the
existing use-after-take machinery reports it.

### 4.3 Defaults

Writing every capture is right for a closure that escapes and tedious for one
that does not, so three defaults cover the common cases - one per mode:

```azora
// copy everything the body references - Copy only, never a silent clone
let callback = [=] {
    trace retries
    trace timeout
}

// shared reference to everything the body references
let callback = [&] {
    trace message
    trace user.name
}

// shared mutable reference - the closure may write what it reads
let increment = [!] {
    count += 1
}
```

`[=]` must never quietly deepen into a clone:

```
line 3: 'message' cannot be captured by copy - 'String' is not Copy; write one
        of '[message.&]', '[message.clone()]', or '[take message]'
```

A default takes overrides, listed after it:

```azora
let callback = [
    &,
    retries,
    config.clone(),
    counter.!
] {
    …
}
```

Shared borrow by default; `retries` copied, `config` cloned, `counter` borrowed
exclusively.

`&` and `=` are written bare here rather than as `.&` and `.=` because there is
no value to their left - the `.` in `message.&` belongs to `message`.

### 4.4 Aliases

A capture may be given its own name, which is what makes a cloned or moved
capture readable:

```azora
let callback = [ownedMessage = message.clone()] { trace ownedMessage }
let worker   = [ownedSocket = take socket]     { ownedSocket.listen() }
let callback = [currentUser = user.&]          { trace currentUser.name }
```

The alias is the name inside the closure; the outer binding keeps its own. This
also gives the one way to capture two things that would otherwise collide.

### 4.5 A lambda with no brackets captures nothing

Every closure in the language today is written without a bracket list, so the
rule for that form decides whether this proposal is a migration or an addition.
The rule:

> A lambda with no capture list has **no access to the enclosing scope**. It
> sees its own parameters, its contextual receivers, and globals - nothing else.

So capture is never implicit. `[=]`, `[&]` and `[!]` are the three ways to ask
for it, and writing none asks for none:

```azora
var total = 0

fin bad = { total = total + 1 }
// line 3: 'total' is not in scope - a lambda with no capture list cannot reach
//         the scope around it; write '[total.!]', or '[!]' to capture what the
//         body reads

fin good = [total.!] { total = total + 1 }
```

This is the stricter of the two possible rules and it is deliberate. A closure's
relationship to the scope around it is the thing §3 shows nobody agreed on, and
a default - any default - decides it silently for every closure that never
thought about it. Making the terse form capture *nothing* means the question is
answered where it is asked, and the answer is visible in the first line.

It settles §3's divergence by removing the programs that exposed it: neither A
nor B compiles as written, because neither says what `n` is to the closure. With
`[n.!]` both are references to the original, so **A prints `5`, `5`** and **B
prints `99`** - the interpreter's answers. The native backends must then capture
the address rather than the value, which is the one real implementation cost in
this DIP.

The cost of the rule is migration: every existing closure that reads an outer
binding needs a bracket. That is the price of the property, and §6 is the count.

### 4.6 Escape

A borrow may not outlive its owner. A closure that outlives the scope it was
made in therefore may not hold one:

> An escaping closure must own every non-`Copy` value it captures: `take`,
> `.clone()`, or `Copy`.

A closure **escapes** when it is returned, stored in a pack field or a
collection, assigned to a binding declared outside its creation scope, or passed
to a parameter whose type is `escaping`.

```azora
func make(): () -> Int {
    var n = 5
    return [n] { n }               // fine: Int is Copy
}

func makeBad(): () -> Int {
    var xs = std::listOf<String>()
    return [xs.&] { consume(xs) }
    // line 3: closure escapes 'makeBad' while borrowing 'xs', which does not
    //         outlive it; write '[xs.clone()]' or '[take xs]'
}
```

With the list written, the diagnostic can point at the entry that is wrong
rather than at a name buried in the body - which is most of why the list is
worth having.

### 4.7 `escaping`

Most higher-order functions call their callable and are done with it - `map`,
`filter`, `forEach`, `withLock`. Requiring every one of them to own its captures
would make the common case the expensive one, so:

> A callable type is non-escaping by default. `escaping` marks the exception.

```azora
/** Calls `action` twice and returns. Nothing outlives the call. */
func twice(action: (Int) -> Unit) { … }

/** Keeps `handler` for later, so its captures must be owned. */
func onEvent(handler: escaping (Event) -> Unit) { … }
```

`escaping` is a **contextual keyword**, joining `module`, `union`, `async`,
`where` and `without`. It is an ordinary word that existing code may already use
as a name, and it only means anything directly before a callable type - so
nothing has to be renamed to adopt it.

It belongs to the *type*, not to the parameter, which is what lets it appear
wherever a callable is stored rather than only where one is passed:

```azora
pack Button {
    var onClick: escaping (Event) -> Unit
}

fin handlers: [escaping () -> Unit] = …
```

That matters because a pack field is one of the four ways §4.6 says a closure
escapes. A parameter-level marker could not describe the field that receives it,
and the check would have to guess at the boundary it is supposed to be drawing.

A non-escaping callable may be called and passed on to other non-escaping
positions, and nothing else. That is the whole check, and it is local - no
whole-program analysis, no annotation burden on the common case, and the
compiler can say which of the two rules was tripped.

### 4.8 Async closures

The same bracket, with `async` in front:

```azora
var socket: Socket = Socket.open(8080)
var config: Config = Config.production()

let worker = async [
    scheduler: Scheduler&,
    take socket,
    config.clone()
] {
    scheduler.trackStart()
    delay 100
    await socket.connect(config.&)
}
```

`scheduler` is a context dependency, `socket` is owned by the closure, `config`
is independently cloned - all visible before the body.

A borrowed capture stays legal when the borrow provably ends before the
suspension, which is §15 of the ownership DIP applied to a closure rather than a
function:

```azora
let worker = async [data.&] {
    trace data
    delay 100
    trace data
    // line 4: 'data' is borrowed across a suspension point - the caller's value
    //         may not outlive it; write '[data.clone()]' or
    //         '[take data]', or end the borrow before the suspension
}
```

This form needs one decision the current grammar does not make. An async lambda
is written `async { … }` today, and `async [ … ] { … }`.
Since `async` is a contextual keyword, `async [` could also be an index into a
binding named `async` - decidable by the same lookahead `isReceiverLambdaAhead`
already uses (scan to the matching `]`, require a `{`), but it must be decided
rather than assumed.

### 4.9 Why this shape

Three rules across three documents that all say the same thing, which is the
point:

| a borrow may not survive…       | rule                                    |
|---------------------------------|-----------------------------------------|
| a suspension                    | §15 of the ownership DIP - implemented  |
| a thread boundary               | §6 of `THREAD_SAFETY_DIP.MD` - proposed |
| the scope that made the closure | §4.6 here - proposed                    |

Same failure, same three fixes offered in the diagnostic (`take`, `.clone()`, or
shorten the lifetime), so a programmer learns it once. The capture list is what
gives all three somewhere to point.

It also gives `THREAD_SAFETY_DIP.MD` §6 the spelling it was missing:

```azora
fin h = std::spawn([take socket] { serve(socket) })
```

---

## 5. Defects to fix regardless

Found while measuring §3, and worth fixing whether or not §4 lands.

### 5.1 A bare lambda is assumed to take `it` - ✅ fixed

The parser gave every `{ body }` an implicit `it` parameter, whether or not the
body mentioned one. So the zero-parameter spelling from §1 - the one the rule
says to write - did not typecheck:

```azora
var n = 0
fin inc = { n = n + 1 }
inc()
// line 3: 'inc' expects 1 parameter(s) and 0 contextual receiver(s),
//         got 0 argument(s)
```

The workarounds today are `{ n = n + 1 }` and annotating
`fin inc: () -> Unit`. Neither should be necessary, and the diagnostic reports
the arity rather than the cause.

**The rule**, now implemented. `it` is not a property of the braces; it is the
name a lambda gives its parameter when it has exactly one and did not name it.
So a bare lambda takes:

- the parameters of the expected callable type, if there is one - `{ it * 2 }`
  against `(Int) -> Int` has one, named `it`;
- otherwise, the one named `it` if the body mentions `it`;
- otherwise, none.

Whether the body reads `it` is decided by the parser, which is the only pass
that knows its own lambda nesting - a nested `{ it * 2 }` marks the nested
lambda and not the one around it, so `xs.forEach { ys.map { it * 2 } }` still
gives the outer lambda no parameter. Where an expected callable type supplies
parameters and the lambda wrote none, the resolver and the IR generator put them
back, which is what keeps the closure's arity equal to what the call site
passes.

`{ -> body }` then has no job left, and by the same reasoning that rejects
`requires [Base]` for a single requirement, it should be rejected rather than
quietly accepted as a synonym:

```
line 2: write '{ n = n + 1 }' - a lambda with no parameters writes no '->'
```

### 5.2 A callable type cannot be a generic argument - ✅ fixed

```azora
var fs = std::listOf<() -> Int>()
// Unexpected token ')'
```

A list of callables is the natural way to hold subscribers, handlers or a work
queue, and it could not be spelled. The type *annotation* position always
accepted it; what did not was a **call-site** type argument: the lookahead that
decides whether a `<` opens a type-argument list or is a less-than allowed
neither parentheses nor `->`, so `listOf<() -> Int>()` fell back to comparison.
It now tracks parenthesis depth, so a `>` only closes the list once the callable
type it sits in has closed.

### 5.3 The closure environment's lifetime is the allocator's problem

`emitClosure` allocates the environment through `emitHeapAlloc`, which calls
`__azora_alloc`. Nothing frees it, so every closure creation is a small
permanent allocation. There is no longer a scoped arena to fall back on: that
subsystem was unreachable and has been deleted, so this is the only case.

§4.2's escape analysis is what makes the fix possible: a non-escaping closure's
environment can live on the stack, and only an escaping one needs the heap.

---

## 6. What this changes for existing code

Nearly nothing, which is the intent.

- Closures that only **read** their captures: unaffected in observable
  behaviour, since a shared borrow and a snapshot read the same value when
  nobody writes. (Program B changes answer on the native backends, but a program
  that depends on B's outcome is depending on an unspecified rule.)
- Closures that **write** their captures: the write starts being visible on
  native backends. Today it is silently discarded, so any program affected by
  this is already broken there.
- Closures passed to `map`/`filter`/`forEach`: unaffected - those parameters are
  non-escaping by §4.7's default, and a bracket-less lambda is `[&]` by
  §4.5.
- Closures **returned or stored**: these need a capture list, and a non-`Copy`
  capture in one needs `.clone()` or `take`. This is the only place existing
  code must be edited, and the diagnostic names the entry to write.

---

## 7. Implementation plan

| phase | work                                                                                                                                                      | touches                                                    |
|-------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------|
| 1     | ✅ **done** - §5.1 (a bare lambda takes no `it` unless one is called for; reject `{ -> … }`) and §5.2 (callable type as generic argument)                  | `Parser`, `TypeResolver`, `IrGenerator`                    |
| 2     | ✅ **done** - the bracket list: `name: Type` as a context parameter and everything else as a capture, the five capture forms, aliases, and the `=` / `&` / `!` defaults | `Parser`, `Ast` |
| 2b    | ✅ **done** - captures are bound, §4.5's barrier is enforced, and each mode is checked: `Copy` for a copy, `Clone` for a clone, `take` recorded as a move. Modes are not yet carried into the IR | `TypeResolver`, `IrGenerator` |
| 3     | 🟡 **partial** - LLVM captures borrows by address and program A agrees there, debug and optimized; Wasm still snapshots, because a Wasm local has no address and the binding would have to be promoted to linear memory first | `LlvmCodegen`, `IrOptimizer`, `IrInterpreter` |
| 4     | ✅ **done** - the `escaping` contextual keyword in type position, on parameters and on fields alike | `Parser`, `AzoraSyntaxVocabulary`, `Ast`, `IrNode`, `TypeResolver` |
| 5     | ✅ **done** - a borrow capture is refused in a closure that is returned, passed to an `escaping` parameter, or stored in an `escaping` field, with the three-fix diagnostic | `TypeResolver` |
| 6     | stack-allocate non-escaping environments                                                                                                                  | `LlvmCodegen`, `WasmCodegen`                               |

Phase 2 needs the lookahead §4.1 describes. `isReceiverLambdaAhead` currently
requires an identifier and a `:` immediately after the `[`, so it recognises
neither `[]` nor a capture-only list like `[message.&]`. Both become additional
entry conditions on the same scan-to-the-matching-`]`-then-`{` test it already
performs - which is also what keeps a capture list from being read as an array
literal.

Phase 3 is the one with real cost and the one that removes the divergence, so it
should land before anything depends on the new rule. Phase 1 is a straight bug
fix and is worth doing regardless; phase 2 can land before 3, since a capture
list that is parsed and checked but still lowered the old way is strictly more
information than there is today.

---

## 8. Not proposed

- **Escape analysis beyond §4.6's four cases.** A whole-program analysis would
  accept more programs and explain fewer of its refusals.
- **Inferring the capture mode from the body.** An earlier draft of §4 proposed
  reading the mode off how the body used each name - read means borrow, assign
  means exclusive borrow, and so on - on the grounds that a capture list could
  not be added without colliding with `[self: Vec2&]{ … }`. There is no
  collision: a context parameter is `name: Type` and a capture is not, so the
  two never contend for the same shape. Inference was the weaker design anyway  - 
  it put a closure's most important property in the last place a reader looks,
  and left a diagnostic with nothing to point at but a name buried in a body.
- **An introducer keyword before the captures.** `[use message.&]` was
  considered and dropped: the `:` already separates the two kinds, so the word
  marks a boundary that is visible without it.
- **Changing contextual receivers.** They work, and §2 is a record, not a
  proposal.
- **`it` for multi-parameter lambdas.** One implicit name is a convenience; two
  is a puzzle.

---

## 9. Open questions

1. **Should a `with` value be capturable by an escaping closure?** A contextual
   receiver is supplied by a scope, so it has that scope's lifetime, and §4.6
   would say no. But `with v { acc = f() }` in §2 makes receivers feel ambient,
   and the restriction may read as arbitrary at the point it fires.
2. **Is non-escaping the right default for callable types?** It is right for the
   standard library as it stands. A framework built on stored callbacks would
   write `escaping` on nearly every one, and would rather the default went the
   other way.
3. **Does an exclusive-borrow capture make the outer binding unusable for the
   closure's whole lifetime, or only while it runs?** The former is what §12's
   exclusivity rule already says and is trivially checkable. The latter accepts
   `fin inc = [n.!] { n = n + 1 }; std::println(n); inc()`, which people
   will expect to work.
4. **Should the `Copy` capture be a copy at creation or at call?** §4.2 says at
   creation - `retries = 5` after the fact does not reach the closure - which
   matches what both native backends do today. At call would match what a borrow
   reads. They differ exactly when the outer binding is written in between:
   program B again, with `Copy` instead of a borrow.
5. **Does `async [ … ] { … }` replace `async { … }` or join it?** §4.8
   shows the bracket form is decidable, but two spellings for one thing is the
   sort of thing this language usually rejects - cf. §5.1 on `{ -> … }`.
6. **May a capture list appear on the bare and explicit forms?** `[n.!] { n
   += 1 }` reads fine, and `[n.!] { x: Int -> n += x }` is the same idea with
   parameters. Nothing in the grammar objects; it just has to be decided, since
   today a `[` before `{` means a receiver lambda.
