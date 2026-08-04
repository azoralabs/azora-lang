# Azora Ownership, Borrowing, Copying, Cloning, and Moving

> Status: design proposal for Azora `0.0.5`.

Azora uses ownership and borrowing to provide deterministic destruction and memory safety without exposing Rust-style lifetime parameters in ordinary code.

The model is built around:

- four binding/value mutability modes: `var`, `let`, `val`, and `fin`;
- implicit copying for `Copyable` types;
- explicit duplication with `clone`;
- explicit ownership transfer with `take`;
- shared borrows with `T&`;
- mutable borrows with `T!`;
- compiler-inferred lifetimes;
- optional borrow-origin annotations such as `T&[source]`;
- ownership-safe asynchronous functions.

---

## 1. Core capability specifications

```azora
bridge spec Movable

bridge spec Cloneable { self& } // bridge spec Cloneable { self: Self& } - ok too

bridge spec Copyable: Movable, Cloneable
```

The recommended spelling is `Cloneable`, not `Clonable`, because `Cloneable` is the established English and programming spelling.

### `Movable`

A `Movable` value may transfer its ownership to another binding, function, field, task, or return value.

```azora
var file: File = File.open("data.txt")
var owned: File = take file
```

After `take file`, the original binding no longer contains a usable value.

### `Copyable`

A `Copyable` value is duplicated implicitly when used by value.

```azora
var x: Int = 10
var y: Int = x
```

Both `x` and `y` remain valid.

A `Copyable` type must also be safely movable and cloneable, so `Copyable` extends both `Movable` and `Cloneable`.

### `Cloneable`

A `Cloneable` value may be duplicated explicitly with `clone`.

```azora
var original: String = String("Azora")
var duplicate: String = clone original
```

Both values remain valid and own independent state.

---

## 2. The four declaration modes

Azora separates two independent properties:

1. **Binding mutability** — may the name be rebound to another value?
2. **Value mutability** — may the value be mutated through this owner?

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
rename(user!)
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
modify(user!)
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
inspect(config&)
```

A mutable borrow is not allowed:

```azora
modify(config!) // Error
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
fin appName: String = String("Azora")
fin origin: Vec3 = Vec3(0.0, 0.0, 0.0)
```

Only shared borrows may be created:

```azora
inspect(settings&) // Allowed
modify(settings!)  // Error
```

---

## 7. Binding mutability is separate from ownership capability

The declaration keyword does not decide whether a value can be copied, cloned, or moved.

The type capabilities decide that.

| Declaration | Copy | Clone | Take |
|---|---:|---:|---:|
| `var` | If `Copyable` | If `Cloneable` | If `Movable` |
| `let` | If `Copyable` | If `Cloneable` | If `Movable` |
| `val` | If `Copyable` | If `Cloneable` | If `Movable` |
| `fin` | If `Copyable` | If `Cloneable` | If `Movable` |

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

Copying happens automatically when a value implements `Copyable`.

```azora
var a: Int = 42
var b: Int = a

a += 1

trace a // 43
trace b // 42
```

Passing a `Copyable` value by value also copies it:

```azora
func printNumber(value: Int) {
    trace value
}

var number: Int = 42
printNumber(number)

trace number // Still valid
```

Returning a `Copyable` local copies it:

```azora
func answer(): Int {
    let value: Int = 42
    return value
}
```

### Typical `Copyable` types

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
Real
Decimal
small value packs containing only Copyable fields
```

A user-defined type may implement or derive `Copyable`:

```azora
pack Vec2 {
    var x: Float
    var y: Float
}

impl Copyable for Vec2
```

The compiler should derive `Copyable` only when:

- every field is `Copyable`;
- bytewise or fieldwise copying cannot duplicate exclusive resource ownership;
- the type does not require a stable address;
- the type has no incompatible destruction behavior.

---

## 9. Explicit cloning

`clone` creates another independently owned value.

```azora
var text: String = String("Hello")
var other: String = clone text

text.append("!")

trace text  // Hello!
trace other // Hello
```

`clone value` conceptually invokes the `Cloneable` contract:

```azora
value.clone()
```

### Collections

```azora
var original: List<Int> = std::listOf(1, 2, 3)
var duplicate: List<Int> = clone original

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
cache(clone config)

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

`take` requires `Movable`.

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

Without `take`, passing a non-`Copyable` value by ownership is rejected:

```azora
process(file)
// Error: File is not Copyable.
// Use `take file` to transfer ownership.
```

When cloning is supported, the diagnostic may also suggest:

```text
Use `clone file` to create an independent value.
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

For a non-`Copyable` named local, use `take` to make the transfer explicit:

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
    return clone config
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
func first(a: String&, b: String&): String&[a] {
    return a
}
```

`String&[a]` means that the returned borrow originates from `a`.

### Method receiver origin

```azora
func value[self: Self&](): Int&[self] {
    return self.value&
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
): std::tupleOf(String&[a], String&[b]) {
    return (a, b)
}
```

Each tuple element keeps its own borrow origin.

### Slices and views

```azora
func slice(
    text: String&,
    start: Int,
    end: Int
): std::StringView&[text] {
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
process(clone config)

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
    return clone user.name
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

A `Copyable` value may be copied into an escaping closure.

### Clone capture

```azora
var message: String = String("Hello")

let callback = {
    let owned: String = clone message
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
var image: std::Unique<Image> = std::uniqueOf(Image())
renderer.upload(take image)

trace image // Error
```

It should not be `Copyable`.

It may or may not be `Cloneable`, depending on whether cloning duplicates the pointed-to value:

```azora
var duplicate: std::Unique<Image> = clone image
```

### `Shared<T>`

`Shared<T>` may be `Cloneable` by incrementing a reference count.

It may also be `Copyable` only if Azora intentionally considers reference-count increments cheap and implicit. A safer design is:

```azora
var second: std::Shared<Image> = clone first
```

This keeps reference-count changes explicit.

### `Atomic<T>`

An atomic shared owner should follow the same principle as `Shared<T>`, with thread-safe reference counting.

### `Weak<T>`

Cloning a weak reference duplicates the non-owning handle, not the object.

---

## 19. Non-movable types

Some values require a stable address after initialization.

Such a type does not implement `Movable`.

Possible examples include:

- self-referential state;
- pinned async state;
- objects registered with native APIs by stable address;
- synchronization primitives with address-sensitive internals.

```azora
pack StableRegistration {
    // ...
}
```

Without `Movable`, this is rejected:

```azora
var registration: StableRegistration = StableRegistration()
var other: StableRegistration = take registration
// Error: StableRegistration is not Movable
```

A dedicated stable-owner type may be used instead.

---

## 20. Generic constraints

Capabilities may be used as generic constraints.

```azora
func transfer<T>(value: T): T
where T is Movable {
    return take value
}
```

```azora
func duplicate<T>(value: T&): T
where T is Cloneable {
    return clone value
}
```

```azora
func repeat<T>(value: T, count: Int): List<T>
where T is Copyable {
    var result: std::List<T> = std::listOf()

    for _ in 0..<count {
        result.add(value)
    }

    return take result
}
```

For type varargs:

```azora
deepinline prop AllCopyable<...T>: Bool
where T is Copyable {
    return true
}
```

Here `T is Copyable` means every type in the type varargs satisfies `Copyable`.

---

## 21. Recommended derivation rules

### `Movable`

Automatically derive when:

- every field is `Movable`;
- moving the value preserves all internal invariants;
- the type does not require a stable address.

### `Copyable`

Automatically derive only when:

- every field is `Copyable`;
- implicit duplication is cheap enough for the language's policy;
- copying cannot duplicate exclusive ownership;
- the type has no incompatible destructor;
- the type does not depend on object identity.

### `Cloneable`

Automatically derive when:

- every field is `Cloneable`;
- fieldwise cloning produces an independent valid value;
- custom invariants do not require a manual implementation.

Example:

```azora
pack UserProfile {
    var name: String
    var tags: std::List<String>
}

impl Cloneable for UserProfile { self& -> // or self: Self& -> // here we support this syntax only, because clonable does not have fields and is declared with { self& }
    return UserProfile(
        name: clone self.name,
        tags: clone self.tags
    )
}
```

---

## 22. Diagnostics

Clear diagnostics are essential.

### Missing `take`

```text
error: cannot pass `file` by ownership
`File` is Movable but not Copyable

help: transfer ownership with:
    process(take file)

help: create an independent value with:
    process(clone file)
```

### Use after take

```text
error: use of taken value `file`

`file` transferred ownership here:
    process(take file)

help: use `clone file` instead when both owners need a value
```

### Invalid mutable borrow

```text
error: cannot mutably borrow `config`
`config` was declared with `val`, so its current value is immutable
```

### Rebinding `let`

```text
error: cannot rebind `user`
`user` was declared with `let`
```

### Mutating `fin`

```text
error: cannot mutate `settings`
`settings` was declared with `fin`
```

### Borrow across `await`

```text
error: borrow of `data` may outlive its owner across `await`

help: transfer ownership with `take data`
help: clone the required value before awaiting
help: end the borrow before the suspension point
```

---

## 23. Complete operation table

| Operation | Syntax | Required capability | Source remains valid | Usually allocates |
|---|---|---|---:|---:|
| Implicit copy | `b = a` | `Copyable` | Yes | No |
| Explicit clone | `b = clone a` | `Cloneable` | Yes | Possibly |
| Ownership transfer | `b = take a` | `Movable` | No | No |
| Shared borrow | `a&` | Borrowable value | Yes | No |
| Mutable borrow | `a!` | Mutable value and exclusive access | Yes | No |

---

## 24. Complete declaration table

| Keyword | Rebind | Mutate current value | Shared borrow | Mutable borrow |
|---|---:|---:|---:|---:|
| `var` | Yes | Yes | Yes | Yes |
| `let` | No | Yes | Yes | Yes |
| `val` | Yes | No | Yes | No |
| `fin` | No | No | Yes | No |

Copying, cloning, and taking still depend on `Copyable`, `Cloneable`, and `Movable`.

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
var a: String = String("Azora")
var b: String = clone a
```

Both remain valid and own independent values.

### Take

```azora
var a: String = String("Azora")
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
    await server.send(image&)
}

var image: Image = loadImage()
upload(take image)
```

The async operation owns `image`.

---

## 26. Design summary

Azora's ownership vocabulary is intentionally small:

```azora
other = value        // implicit copy; requires Copyable
other = clone value  // explicit duplication; requires Cloneable
other = take value   // explicit ownership transfer; requires Movable

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
- implicit copies only for explicitly `Copyable` types;
- explicit `take` when ownership changes;
- explicit `clone` when a second owner needs independent state;
- compiler-inferred lifetimes;
- borrow-origin contracts instead of lifetime variables;
- safe behavior across functions, closures, collections, fields, and `await`.
