# Azora Ownership, Borrowing, Copying, Cloning, and Moving - DIP Implementation Profile

> Status: partly implemented in Azora `0.0.5`.

## Implementation status

| Section | State |
|---------|-------|
| §1 capability specs (`Clone`, `Copy requires Clone`) | **done** - specs in `std/traits/traits.az`, with implementations generated in `std/traits/core.az`. There is no third capability for moving: every value can be given away, so `take` asks nothing of its operand |
| §2–§6, §24 the four binding modes | **done** |
| §7 binding mutability vs ownership capability | **done** |
| §8 implicit copying | **done** - a named non-`Copy` value passed by value is rejected |
| §9 `.clone()` | **done** - a `bridge spec Clone` member; the compiler supplies a field-wise clone, and a written `impl` overrides it |
| §10 `take` | **done** - move recorded, use-after-take reported, including across control flow: a move inside a loop is rejected (the next iteration would use it), and a move every branch makes outlives the branch |
| §11 moving a field out | **done** - a field is spent on its own (`take a.db` leaves the rest of `a` usable and only `a.db` unreadable), and moving one out asks the same access a write does, so a shared borrow of the owner is rejected |
| §12 borrows, including the exclusivity rules | **done** - a mutable borrow is exclusive for its lifetime, the owner cannot be read or moved while one is live, shared borrows coexist, and a borrow cannot give the value away: `take`/`lend` on a borrowed parameter, receiver or binding is rejected |
| §13 borrow origins `T&[a]` | **done** - an origin must name a borrowed parameter or the receiver, and a `return` rooted in a binding must be one of them. Lifetimes themselves stay inferred |
| §13 lending (`a: return T` / `lend a`) | **done** - ownership goes to the callee and comes back, so a non-`Copy` value can be passed in more than once; `lend` and `return` are required to agree |
| §20 capabilities as generic constraints | **done** |
| §21 derivation rules | **done** for packs, enums and tagged unions |
| §22 diagnostics | **done** for the take / implicit-copy / rebind / mutate cases |
| §17 optional `take` | **done** - `take opt.require()` and the `opt.take()` shorthand yield the value and leave the optional `null`; taking out of an empty optional is caught |
| §18 smart pointers | **done** - `Unique`, `Shared`, `SyncShared` and `Weak` carry the capabilities that match how each one owns |
| §15 async ownership | **done** - a borrowed parameter still read after an `await` or `delay` is rejected, with the three fixes named; a borrow that ends before the suspension is an ordinary borrow |
| §16 closure captures | **done** for what a body shows - borrow and clone captures are accepted, and a `take` inside a closure moves the outer binding, in an `async` closure as much as a plain one. Which closures *escape* is not analysed, so an escaping capture is not yet required to be explicit |
| §14 destruction | **not implemented** |
| §19 non-movable types | **removed** - every value is movable |

Enforced today: which values may be duplicated, which may be given away, which
may not be used after being given away, **borrow exclusivity** - a mutable
borrow is the only live path to its owner for as long as it lasts - and that a
borrow does not outlive the call it was made for, whether it is named as the
origin of a returned borrow or held across a suspension.

A borrow's lifetime is its holder's scope: `let m: User! = user.!` lasts until
`m` goes out of scope, while `rename(user.!)` ends with the statement. That is
enough for §12's rules without a region analysis. Across a function boundary
(§13) and across a suspension (§15) the check is on the signature rather than on
a region: an origin must name something the call borrows, and a borrow still
read after an `await` is rejected outright.

Three places where the implementation deliberately differs from the text below:

- **`String` is `Copy`.** §8's list of primitives leaves it out, but Azora
  strings are immutable values rather than owned buffers, so treating them as
  copyable is sound and is what every existing program assumes.
- **`Unit` has no capabilities.** It carries nothing, so there is no value to
  move, clone or copy - and a `clone` taking one would need a `void` parameter.
- **`clone` is compiler-supplied.** `Clone` is a `bridge spec`, so a conforming
  type gets a field-wise clone for free and only writes an `impl` when its
  duplication needs saying - that one then wins.
- **The capabilities are named `Clone` and `Copy`.** There is no third one for
  moving: every value can be given away with `take`.

An `impl` of a spec member may omit its return type and take the one the spec
declared. That is what makes `func clone[self: Self&]()` in §21 legal without
contradicting "return types are never inferred" (`DO_NOT_INFER_RETURN_TYPE.MD`):
the type *is* declared, once, on the contract.

Azora uses ownership and borrowing to provide deterministic destruction and memory safety without exposing Rust-style lifetime parameters in ordinary code.

The model is built around:

- four binding/value mutability modes: `var`, `let`, `val`, and `fin`;
- implicit copying for `Copy` types;
- explicit duplication with `.clone()`;
- explicit ownership transfer with `take`;
- shared borrows with `T&`;
- mutable borrows with `T!`;
- compiler-inferred lifetimes;
- optional borrow-origin annotations such as `T&[source]` or `T&[...sources]`;
- ownership-safe asynchronous functions.

---

## 1. Core capability specifications

```azora
bridge spec Clone {
    func clone[self: Self&](): Self
}

bridge spec Copy requires Clone
```

There are two capabilities, not three. **Every value can be given away**, so
`take` asks nothing of its operand and there is no capability to ask for.

### `Copy`

A `Copy` value is duplicated implicitly when used by value.

```azora
var x: Int = 10
var y: Int = x
```

Both `x` and `y` remain valid.

Implicit duplication has to be cheap and has to be safe, so `Copy` **requires**
`Clone`. It does not grant it: `requires` is a precondition checked at the
`impl`, so a type states every capability it has and none is inferred from a
sibling.

```azora
pack Vec2 derives [Clone, Copy]  // both, in one declaration
```

### `Clone`

A `Clone` value may be duplicated explicitly with `.clone()`.

```azora
var original: String = "Azora"
var duplicate: String = original.clone()
```

Both values remain valid and own independent state.

`Clone` is a `bridge spec`: the compiler supplies a field-wise `clone` for any
conforming type, so a plain data type states the capability and writes nothing.
A type whose duplication needs saying writes its own `clone`, and that one wins:

```azora
impl Clone for UserProfile {
    func clone[self: Self&]() {          // the result type comes from the spec
        return UserProfile(
            name: self.name.clone(),
            tags: self.tags.clone()
        )
    }
}
```

An `impl` of a spec member may omit its return type and take the one the spec
declared - the type is still *declared*, once, on the contract, so this does not
contradict `DO_NOT_INFER_RETURN_TYPE.MD`.

---

## 2. The four declaration modes

Azora separates two independent properties:

1. **Binding mutability** - may the name be rebound to another value?
2. **Value mutability** - may the value be mutated through this owner?

| Keyword | Binding | Value | Reassignment | Value mutation |
|---|---|---|---:|---:|
| `var` | Mutable | Mutable | Yes | Yes |
| `let` | Immutable | Mutable | No | Yes |
| `val` | Mutable | Immutable | Yes | No |
| `fin` | Immutable | Immutable | No | No |

The complete matrix is:

| | Mutable value | Immutable value |
|---|---|---|
| Mutable binding | `var` | `val` |
| Immutable binding | `let` | `fin` |

---

## 3. `var`: mutable binding, mutable value

```azora
var user: User = User("Ana")

user.name = "Maria"       // Allowed
user = User("Gabriel")    // Allowed
```

`var` is the least restrictive declaration.

A mutable borrow may be created when the borrow checker allows it:

```azora
func rename(user: User!) {
    user.name = "Updated"
}

var user: User = User("Ana")
rename(user.!)
```

---

## 4. `let`: immutable binding, mutable value

```azora
let user: User = User("Ana")

user.name = "Maria"       // Allowed
user = User("Gabriel")    // Error: `user` cannot be rebound
```

`let` fixes the owner name but does not freeze the owned value.

This is useful when an object should remain the same logical object while its internal state changes:

```azora
let connection: Connection = Connection.open()

connection.send("hello")  // Allowed
connection.close()        // Allowed

connection = Connection.open() // Error
```

A mutable borrow may be created:

```azora
modify(user.!)
```

The binding itself remains immutable.

---

## 5. `val`: mutable binding, immutable value

```azora
val config: Config = Config.production()

config.enableDebug()          // Error: current value is immutable
config = Config.development() // Allowed
```

`val` is useful for replaceable snapshots, configurations, states, or handles whose current value must not be mutated in place.

```azora
val state: AppState = AppState.loading()

state = AppState.ready(data)  // Allowed
state.items.add(item)         // Error
```

A shared borrow is allowed:

```azora
inspect(config.&)
```

A mutable borrow is not allowed:

```azora
modify(config.!) // Error
```

---

## 6. `fin`: immutable binding, immutable value

```azora
fin settings: Settings = Settings.default()

settings.theme = .Dark       // Error
settings = Settings.other()  // Error
```

`fin` is the strongest declaration mode.

It is useful for values that should remain fixed for the rest of their ownership lifetime:

```azora
fin appName: String = "Azora"
fin origin: Vec3 = Vec3(0.0, 0.0, 0.0)
```

Only shared borrows may be created:

```azora
inspect(settings.&) // Allowed
modify(settings.!)  // Error
```

---

## 7. Binding mutability is separate from ownership capability

The declaration keyword does not decide whether a value can be copied, cloned, or moved.

The type capabilities decide that.

| Declaration | Copy | Clone | Take |
|---|---:|---:|---:|
| `var` | If `Copy` | If `Clone` | Always |
| `let` | If `Copy` | If `Clone` | Always |
| `val` | If `Copy` | If `Clone` | Always |
| `fin` | If `Copy` | If `Clone` | Always |

For example, an immutable binding may still transfer ownership:

```azora
let file: File = File.open("data.txt")
process(take file)

file.read() // Error: value was taken
```

Moving is an ownership operation, not value mutation.

Because `let` cannot be rebound, the binding remains permanently unusable after the move.

```azora
let file: File = File.open("data.txt")
process(take file)

file = File.open("other.txt") // Error: `let` cannot be rebound
```

A `var` or `val` binding may receive another value after its previous value was taken:

```azora
var file: File = File.open("first.txt")
process(take file)

file = File.open("second.txt") // Allowed
```

```azora
val config: Config = Config.production()
start(take config)

config = Config.development() // Allowed
```

---

## 8. Implicit copying

Azora has no `copy` keyword.

Copying happens automatically when a value implements `Copy`.

```azora
var a: Int = 42
var b: Int = a

a += 1

trace a // 43
trace b // 42
```

Passing a `Copy` value by value also copies it:

```azora
func printNumber(value: Int) {
    trace value
}

var number: Int = 42
printNumber(number)

trace number // Still valid
```

Returning a `Copy` local copies it:

```azora
func answer(): Int {
    let value: Int = 42
    return value
}
```

### Typical `Copy` types

Likely examples include:

```text
Bool
Char
Byte
Short
Int
Long
UByte
UShort
UInt
ULong
Float
Double
Decimal
small value packs containing only Copy fields
```

A user-defined type may implement or derive `Copy`:

```azora
pack Vec2 derives Copy {
    var x: Float
    var y: Float
}
```

The compiler should derive `Copy` only when:

- every field is `Copy`;
- bytewise or fieldwise copying cannot duplicate exclusive resource ownership;
- the type does not require a stable address;
- the type has no incompatible destruction behavior.

---

## 9. Explicit cloning

`clone` creates another independently owned value.

```azora
var text: String = "Hello"
var other: String = text.clone()

text.append("!")

trace text  // Hello!
trace other // Hello
```

### Collections

```azora
var original: List<Int> = listOf(1, 2, 3)
var duplicate: List<Int> = original.clone()

original.add(4)

trace original  // [1, 2, 3, 4]
trace duplicate // [1, 2, 3]
```

### Function parameters

Use `clone` when both caller and callee require independent ownership:

```azora
func cache(config: Config) {
    // Owns its independent Config
}

var config: Config = Config.production()
cache(config.clone())

config.enableDebug() // Original remains available
```

### Clone is explicit because it may be expensive

A clone may:

- allocate memory;
- duplicate an object graph;
- increment reference counts;
- copy buffers;
- duplicate user-defined state.

That cost should remain visible in source code.

---

## 10. Explicit ownership transfer with `take`

`take` transfers ownership without cloning.

```azora
var file: File = File.open("data.txt")
var owned: File = take file

file.read() // Error: value was taken
```

`take` requires nothing of its operand: every value can be given away.

No copy or duplicate resource is created.

### Assignment

```azora
var source: String = "Azora"
var destination: String = take source

trace destination // Azora
trace source      // Error
```

### Function arguments

```azora
func process(file: File) {
    // Owns file
}

var file: File = File.open("data.txt")
process(take file)

file.read() // Error
```

Without `take`, passing a non-`Copy` value by ownership is rejected:

```azora
process(file)
// Error: File is not Copy.
// Use `take file` to transfer ownership.
```

When cloning is supported, the diagnostic may also suggest:

```text
Use `file.clone()` to create an independent value.
```

### Moving into fields

```azora
pack App {
    var database: Database
}

func createApp(database: Database): App {
    return App(
        database: take database
    )
}
```

After assigning with `take`, the parameter no longer owns the database.

### Moving into an existing mutable field

```azora
func replaceDatabase[self: App!](database: Database) {
    self.database = take database
}
```

The previous field value is destroyed before or during replacement according to Azora's assignment rules.

### Moving out of fields

Moving a field out of an owner should require exclusive access to the owner:

```azora
func detachDatabase[self: App!](): Database {
    return take self.database
}
```

After the move, the field must enter a compiler-defined empty, uninitialized, or replacement-required state.

A safer alternative is to require the field to be optional:

```azora
pack App {
    var database: Database?
}

func detachDatabase[self: App!](): Database {
    return take self.database.require()
}
```

The compiler may set the optional field to `null` after the take.

### Moving values from collections

A collection API may return owned values directly:

```azora
var jobs: Queue<Job> = Queue()
var job: Job = jobs.pop()
```

If an API exposes a mutable element reference, ownership may be transferred explicitly:

```azora
var job: Job = take jobs[index]
```

This must also remove or replace the collection element so that the collection never retains an invalid value.

---

## 11. Returning ownership

### Returning a fresh temporary

A newly constructed temporary already has no other owner, so `take` is unnecessary:

```azora
func openLog(): File {
    return File.open("log.txt")
}
```

### Returning an owned local

For a non-`Copy` named local, use `take` to make the transfer explicit:

```azora
func openConfiguredLog(): File {
    var file: File = File.open("log.txt")
    configure(file.!)

    return take file
}
```

After the return, the caller owns the file.

### Returning a clone

```azora
func duplicateConfig(config: Config&): Config {
    return config.clone()
}
```

The caller receives a new independent value while the borrowed source remains valid.

### Returning a copyable value

```azora
func originX(): Float {
    let x: Float = 0.0
    return x
}
```

The value is copied automatically.

### Conditional ownership returns

```azora
func select(primary: File, fallback: File, usePrimary: Bool): File {
    return if usePrimary {
        take primary
    } else {
        take fallback
    }
}
```

Only the selected value is moved.

The unselected parameter remains owned by the current function and is destroyed when the function exits.

---

## 12. Borrowing

Azora supports two safe borrowed-reference forms.

```azora
T& // Shared, read-only borrow
T! // Exclusive, mutable borrow
```

Borrows never own the referenced value.

### Shared borrow

```azora
func inspect(user: User&) {
    trace user.name
}

var user: User = User("Ana")
inspect(user.&)

trace user.name // Still valid
```

Multiple shared borrows may coexist:

```azora
let a: User& = user.&
let b: User& = user.&
```

### Mutable borrow

```azora
func rename(user: User!) {
    user.name = "Maria"
}

var user: User = User("Ana")
rename(user.!)
```

A mutable borrow must be exclusive for its active lifetime.

### Borrowing rules

While a mutable borrow is active:

- no other mutable borrow may exist;
- no shared borrow may be used;
- the owner may not be moved;
- the owner may not be destroyed.

While shared borrows are active:

- additional shared borrows may exist;
- no mutable borrow may be created;
- the owner may not be moved or destroyed if the borrow would outlive the operation.

---

## 13. Borrow origins and inferred lifetimes

Azora internally tracks lifetimes but avoids Rust-style lifetime names such as `'a`.

Most borrow relationships are inferred automatically.

When a public API must state the origin of a returned borrow, the return type may name its source:

```azora


```

`String&[a]` means that the returned borrow originates from `a`.

### Lending: ownership that comes back

`take` spends a value. Sometimes a function needs to *own* its argument while it
runs - to write through it freely, to hand it on, to hold it in a structure it
builds - without the caller losing it for good. Writing that with `take` costs
the caller the value; writing it with a borrow costs the callee the ownership.

A parameter marked `return` says the ownership comes home:

```azora
func add(x: return Int, y: return Int): Int {
    return x + y
}

func main() {
    var x = 4
    var y = 8
    var sum = add(lend x, lend y)
    trace x   // 4 - ownership came back, so `x` is usable again
}
```

`lend` at the call and `return` at the declaration are one contract seen from
its two ends, and the compiler requires both. A `lend` to a parameter that does
not give ownership back is a move written as a loan; a `return` parameter fed by
anything else never received ownership to return. Each is reported against the
other:

```
line 4: cannot lend to parameter 'a' of 'f' - it does not give ownership back;
        declare it 'a: return …' to lend to it, or write 'take' to give the
        value away

line 4: parameter 'a' of 'f' gives ownership back, so its argument is lent;
        write 'lend' before it
```

What this buys is the case `take` cannot express - a value that is not `Copy`
going into a function more than once:

```azora
var handle = Handle(listOf<String>())

fin a = inspect(lend handle)
fin b = inspect(lend handle)   // fine: the first call gave it back
```

Any number of parameters may be marked, and each is independent - a function may
take three values on loan and return all three.

A borrow may not be marked `return`. A borrow already leaves the caller owning
the value for the whole call, so there is no ownership to give back, and the
marker would describe something that never happened:

```
line 1: 'a' is a borrow, so 'f' never takes ownership of it and has none to
        give back; drop the 'return', or drop the borrow to take ownership
```

`lend` is a contextual keyword: it means the ownership operation only when a
name follows it, so `var lend = 5` keeps working.

### Only an owner may give a value away

`take` and `lend` both hand a value to someone else, so both ask the same thing
of their operand: that it is the operand's to give. A borrow is not.

```azora
func relay(h: Handle&): Int {
    return sink(take h)
    // line 2: cannot take 'h' - 'h' is borrowed, so this function does not own
    //         it; take the owner instead, or declare 'h' by ownership so there
    //         is something to give away
}
```

The same applies to an exclusive borrow, to a borrowed receiver (`take self` in
a `[self: Self&]` method), and to a binding that holds a borrow - that last one
names the owner it is standing in for:

```
line 6: cannot take 'b' - 'b' is a borrow of 'h', which owns nothing
```

Without this, a borrow could be spent: the callee gives the caller's value away
for good, and the owner is never told. It is the check that makes `&` mean what
§12 says it means, and the reason a borrow may not be marked `return` - both
come down to a borrow having no ownership to pass on.

A `return` parameter *is* owned while the call runs, so it may be given away
like any other owned value; the loan is what the callee hands back at the end,
not a restriction on what it may do meanwhile.

The rule has a mirror: a parameter that *borrows* never takes ownership, so
handing it some costs the caller the value and buys the callee nothing.

```azora
func look(v: Handle&): Int { return 1 }

fin a = look(take h)
// line 3: cannot take 'h' to parameter 'v' of 'look' - the parameter borrows,
//         so it never takes ownership and the value would be given away for
//         nothing; write 'h.&' to borrow it for the call
```

Between the two, every ownership operation now has to reach something that can
receive it: `take` and `lend` need an owner to come from, and a parameter that
can take ownership to go to.

### Method receiver origin

```azora
func value[self: Self&](): Int&[self] {
    return self.value.&
}
```

### A borrow that may originate from multiple inputs

```azora
func choose(
    a: String&,
    b: String&,
    chooseA: Bool
): String&[a, b] {
    return if chooseA { a } else { b }
}
```

The returned borrow is valid only while every possible source required by the API contract remains valid.

### Returning multiple independent borrows

```azora
func pair(
    a: String&,
    b: String&
): Tuple(String&[a], String&[b]) {
    return tupleOf(a, b)
}
```

Each tuple element keeps its own borrow origin.

### Slices and views

```azora
func slice(
    text: String&,
    start: Int,
    end: Int
): StringView&[text] {
    ...
}
```

The returned view may not outlive `text`.

---

## 14. Destruction and scope exit

Owned values are destroyed automatically when their ownership scope ends.

```azora
func readFile() {
    var file: File = File.open("data.txt")
    // use file
} // file is destroyed here
```

This is deterministic RAII-style destruction.

### Early return

```azora
func read(enabled: Bool) {
    var file: File = File.open("data.txt")

    if !enabled {
        return
    }
}
```

`file` is destroyed on every control-flow path that leaves its scope.

### `defer`

```azora
func process() {
    var file: File = File.open("data.txt")
    defer trace "Leaving process"

    // ...
}
```

`defer` actions run at scope exit according to Azora's defined ordering.

---

## 15. Async ownership

An `async func` may suspend and resume later.

```azora
async func fetch(): Response {
    await networkRequest()
}
```

Suspension changes the lifetime requirements because local state may need to live inside the generated async state machine.

### Moving resources into async functions

```azora
async func serve(socket: Socket) {
    await socket.listen()
}

var socket: Socket = Socket.open(8080)
serve(take socket)

socket.close() // Error: socket was taken
```

The async operation owns the socket for as long as needed.

### Passing copyable values

```azora
async func retry(count: Int) {
    delay 1000
    trace count
}

var attempts: Int = 3
retry(attempts)

attempts += 1 // Still valid
```

`count` receives an implicit copy.

### Cloning for async ownership

```azora
async func process(config: Config) {
    await run(config.&)
}

var config: Config = Config.production()
process(config.clone())

config.enableDebug() // Original remains available
```

### Borrowing in async functions

```azora
async func inspect(data: Data&) {
    trace data
}
```

A borrow that does not cross suspension may be accepted normally.

```azora
async func inspect(data: Data&) {
    trace data
    delay 100
    trace data
}
```

Here the borrow crosses an `await`.

The compiler must prove that `data` remains alive and unmoved for the entire suspended lifetime.

When this cannot be proven, the compiler should reject the code and suggest:

- transfer ownership with `take`;
- create an independent value with `clone`;
- shorten the borrow so it ends before `await`.

### Borrow ending before `await`

```azora
async func process(data: Data&) {
    scope {
        let name: String&[data] = data.name.&
        trace name
    }

    delay 100
}
```

The borrow ends before suspension.

### Async return ownership

```azora
async func download(): Buffer {
    var buffer: Buffer = await network.download()
    validate(buffer.&)

    return take buffer
}
```

The caller receives ownership after the async operation completes.

### Async return borrow

Returning a borrow from an async function should normally be disallowed unless Azora can prove that the borrow source outlives the future and the returned reference.

Owned return values are safer and should be preferred:

```azora
async func readName(user: User&): String {
    return user.name.clone()
}
```

---

## 16. Closures and captures

A closure may capture a value by borrow, copy, clone, or ownership transfer.

### Shared borrow capture

```azora
let printName = {
    trace user.name
}
```

The compiler may infer a shared borrow when the closure does not escape the borrow lifetime.

### Mutable borrow capture

```azora
let increment = {
    counter += 1
}
```

The compiler may infer an exclusive borrow of `counter`.

### Copy capture

```azora
var count: Int = 5

let callback = {
    trace count
}
```

A `Copy` value may be copied into an escaping closure.

### Clone capture

```azora
var message: String = "Hello"

let callback = {
    let owned: String = message.clone()
    trace owned
}
```

A future dedicated capture syntax could make this more concise, but `clone` remains the semantic operation.

### Move capture with `take`

```azora
var socket: Socket = Socket.open(8080)

let worker = {
    let owned: Socket = take socket
    // closure owns socket
}
```

For an escaping or asynchronous closure, ownership transfer should be explicit.

After capture, the outer binding no longer owns the value.

---

## 17. Optional values and `take`

Moving from an optional should leave a valid empty state.

```azora
var file: File? = File.open("data.txt")

let owned: File = take file.require()
```

Afterward:

```azora
file == null // true
```

This rule prevents an optional from containing an invalid moved-from value.

A standard library helper may provide the same behavior:

```azora
let owned: File = file.take()
```

The keyword form remains the primitive ownership operation.

---

## 18. Smart pointers

### `Unique<T>`

`Unique<T>` is move-only by default.

```azora
var image: Unique<Image> = uniqueOf(Image())
renderer.upload(take image)

trace image // Error
```

It should not be `Copy`.

It may or may not be `Clone`, depending on whether cloning duplicates the pointed-to value:

```azora
var duplicate: Unique<Image> = image.clone()
```

### `Shared<T>`

`Shared<T>` may be `Clone` by incrementing a reference count.

It may also be `Copy` only if Azora intentionally considers reference-count increments cheap and implicit. A safer design is:

```azora
var second: Shared<Image> = first.clone()
```

This keeps reference-count changes explicit.

### `Atomic<T>`

An atomic shared owner should follow the same principle as `Shared<T>`, with thread-safe reference counting.

### `Weak<T>`

Cloning a weak reference duplicates the non-owning handle, not the object.

---

## 19. Non-movable types

**Removed.** Every value in Azora can be given away, so there is no capability to
withhold and nothing for a type to opt out of.

A type that genuinely requires a stable address - pinned async state,
self-referential data, an object registered with a native API by pointer - should
be held behind a handle that owns it, rather than being made unmovable itself.

---

## 20. Generic constraints

Capabilities may be used as generic constraints.

```azora
func transfer<T>(value: T): T {
    return take value          // no constraint: every value can be given away
}
```

```azora
func duplicate<T>(value: T&): T
where T: Clone {
    return value.clone()
}
```

```azora
func repeat<T>(value: T, count: Int): List<T>
where T: Copy {
    var result: List<T> = listOf()

    for _ in 0..<count {
        result.add(value)
    }

    return take result
}
```

For type varargs:

```azora
deepinline prop AllCopy<...T>: Bool
where T: Copy {
    return true
}
```

Here `T: Copy` means every type in the type varargs satisfies `Copy`.

---

## 21. Derivation rules

A pack, enum or tagged union that says nothing about its capabilities gets the
ones its contents allow, computed to a fixed point so a nested type is judged
before whatever holds it. An explicit `impl` always wins.

A field typed by the pack's *own* type parameter never withholds a capability:
`pack Store<T> { var value: T }` says nothing until it is instantiated. A
`union` derives nothing - it reinterprets its storage, so nothing about it can be
copied field-wise.

### `Copy`

Automatically derive only when:

- every field is `Copy`;
- implicit duplication is cheap enough for the language's policy;
- copying cannot duplicate exclusive ownership;
- the type has no incompatible destructor;
- the type does not depend on object identity.

### `Clone`

Automatically derive when:

- every field is `Clone`;
- fieldwise cloning produces an independent valid value;
- custom invariants do not require a manual implementation.

`Clone` is the permissive one and `Copy` the strict one, and the difference is
the point of the model. A field that is a list or a buffer does not block
`Clone` - a deep copy duplicates the whole value. It does block `Copy`, because
`Copy` makes duplication *implicit*, so a pack holding a collection is left out
and handing it over asks for `take` or `.clone()` in writing.

Example:

```azora
pack UserProfile {
    var name: String
    var tags: List<String>
}

impl Clone for UserProfile {
    func clone[self: Self&]() {
        return UserProfile(
            name: self.name.clone(),
            tags: self.tags.clone()
        )
    }
}
```

---

## 22. Diagnostics

Clear diagnostics are essential. These are what the compiler emits today.

### Missing `take`

```text
line 11: cannot pass 'file' by ownership - 'File' is not Copy;
         transfer ownership with 'take file',
         or create an independent value with 'file.clone()'
```

The `.clone()` half is offered only when the type is `Clone`; the `take` half
always is, because every value can be given away.

### Use after take

```text
line 6: use of taken value 'file' - its ownership transferred at line 5;
        use 'file.clone()' instead when both owners need a value
```

### Missing capability for `.clone()`

```text
line 7: no method 'clone' on Raw
```

### Invalid mutable borrow

```text
line 9: cannot borrow mutably for parameter 'n' through 'config'  - 
        its value is immutable; declare it 'var' (or 'let' to fix only the name)
```

### Rebinding `let`

```text
line 8: cannot reassign immutable binding 'user'
```

### Mutating `fin`

```text
line 7: cannot assign to member 'theme' through 'settings' - its value is
        immutable; declare it 'var' (or 'let' to fix only the name)
```

### Borrow across `await`

Not implemented - borrows are erased at call sites and lifetimes are not yet
checked. The intended shape:

```text
error: borrow of `data` may outlive its owner across `await`

help: transfer ownership with `take data`
help: clone the required value before awaiting
help: end the borrow before the suspension point
```

---

## 23. Complete operation table

| Operation | Syntax          | Required capability | Source remains valid | Usually allocates |
|---|-----------------|---|---:|---:|
| Implicit copy | `b = a`         | `Copy` | Yes | No |
| Explicit clone | `b = a.clone()` | `Clone` | Yes | Possibly |
| Ownership transfer | `b = take a`    | none | No | No |
| Shared borrow | `a.&`           | Borrowable value | Yes | No |
| Mutable borrow | `a.!`           | Mutable value and exclusive access | Yes | No |

---

## 24. Complete declaration table

| Keyword | Rebind | Mutate current value | Shared borrow | Mutable borrow |
|---|---:|---:|---:|---:|
| `var` | Yes | Yes | Yes | Yes |
| `let` | No | Yes | Yes | Yes |
| `val` | Yes | No | Yes | No |
| `fin` | No | No | Yes | No |

Copying and cloning still depend on `Copy` and `Clone`. Taking depends on
nothing: every value can be given away.

---

## 25. Compact examples

### Copy

```azora
var a: Int = 5
var b: Int = a
```

Both remain valid.

### Clone

```azora
var a: String = "Azora"
var b: String = a.clone()
```

Both remain valid and own independent values.

### Take

```azora
var a: String = "Azora"
var b: String = take a
```

Only `b` remains valid.

### Borrow

```azora
inspect(a.&)
modify(a.!)
```

Ownership stays with `a`.

### Borrowed return

```azora
func front(list: List<T>&): T&[list]
```

The returned reference may not outlive `list`.

### Async ownership

```azora
async func upload(image: Image) {
    await server.send(image.&)
}

var image: Image = loadImage()
upload(take image)
```

The async operation owns `image`.

---

## 26. Design summary

Azora's ownership vocabulary is intentionally small:

```azora
other = value        // implicit copy; requires Copy
other = value.clone()  // explicit duplication; requires Clone
other = take value   // explicit ownership transfer; always available

value.&               // shared borrow
value.!               // mutable borrow
```

The declaration vocabulary independently controls binding and value mutability:

```azora
var // mutable binding, mutable value
let // immutable binding, mutable value
val // mutable binding, immutable value
fin // immutable binding, immutable value
```

The design goals are:

- deterministic destruction;
- safe ownership by default;
- no implicit expensive cloning;
- no implicit transfer of move-only values;
- implicit copies only for explicitly `Copy` types;
- explicit `take` when ownership changes;
- explicit `.clone()` when a second owner needs independent state;
- compiler-inferred lifetimes;
- borrow-origin contracts instead of lifetime variables;
- safe behavior across functions, closures, collections, fields, and `await`.

```azora
impl Clone for Int {
    func clone[self: Self&]() {
        return self
    }
}
```
