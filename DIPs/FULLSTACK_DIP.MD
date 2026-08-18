# Azora Full-Stack Networking and Data - DIP

> Status: **proposed.** This DIP defines library architecture and semantics. It
> does not add language syntax, compiler intrinsics, or backend behavior yet.

Azora should let one program define its transport contracts, serialized values,
queries, and schemas once, then use them from a browser application, a native
client, a command-line tool, or a server. "Full stack" does not mean hiding the
platform. It means sharing the parts which are genuinely portable while making
platform capabilities explicit.

The initial surface consists of two standard-library families:

- `std.http`: portable HTTP messages, clients, endpoints, routing, middleware,
  streaming bodies, and AZON REST conventions.
- `std.db.core`: portable database lifecycle, transactions, schemas, typed
  expressions, queries, migrations, and driver contracts.

The standard library also ships a small in-house embedded relational database.
It is the deterministic default for local applications, tests, tools, offline
storage, and examples. PostgreSQL, SQLite, JSON, and other integrations belong
to the future package ecosystem rather than being hardcoded into the compiler.

AZON is the only text data format built into the standard library. HTTP bodies,
database exports, migration plans, document values, package manifests, and
configuration use AZON unless an application imports another format package.

---

## 1. Goals

1. **One contract across targets.** Request, response, endpoint, schema, query,
   and migration declarations are ordinary Azora values usable everywhere.
2. **No frontend/backend split in the core API.** `HttpClient`, local storage,
   serialization, validation, and shared endpoint contracts work in frontend
   code as well as server code.
3. **Target-honest capabilities.** Listening on a socket, opening a local file,
   or connecting directly to PostgreSQL is available only where the selected
   target provides that capability. Unsupported operations are diagnosed; they
   never silently degrade.
4. **AZON first.** Standard protocols do not depend on JSON. A future JSON
   package is an explicit interoperability dependency.
5. **Typed data access.** Queries are typed expression trees, not interpolated
   strings. Values and identifiers are kept separate by construction.
6. **Transactions are lexical.** Database work happens inside a transaction
   callback. There is no mutable process-global or thread-local current
   transaction.
7. **Portable drivers.** The compiler knows nothing about PostgreSQL, SQLite,
   browser persistence, TLS providers, or operating-system sockets.
8. **Safe resource lifetimes.** Responses, streams, connections, transactions,
   statements, cursors, and row views have explicit owners and cannot remain
   usable after their owner closes.
9. **Useful without an ORM.** The relational DSL is the foundation. Mapping a
   row to a pack is convenient, but hidden identity maps and active-record state
   are not required.
10. **Production-shaped from the start.** Cancellation, limits, pooling,
    migrations, observability, retries, idempotency, and security are part of
    the model rather than later decorations.

## 2. Non-goals

- Making network or database I/O a compiler primitive.
- Pretending every target can host an HTTP server or open a TCP connection.
- Building PostgreSQL or SQLite into `std`.
- Providing a universal abstraction over every kind of non-relational store.
- Hiding SQL features behind a lowest-common-denominator ORM.
- Treating a non-2xx HTTP response as a transport failure.
- Serializing arbitrary runtime objects through reflection.
- Using AZON text as the embedded database's internal page format.

---

## 3. Module and package layout

### 3.1 Standard library

| Module | Responsibility |
|---|---|
| `std.http` | Shared HTTP values, client, endpoint contracts, router, middleware |
| `std.http.client` | Client policies, redirects, cookies, retry integration |
| `std.http.server` | Listener and server lifecycle contracts |
| `std.db.core` | Driver-neutral database, transaction, schema, query, and migration APIs |
| `std.db` | In-house embedded relational database and convenient constructors |
| `std.db.sql` | SQL dialect and SQL-driver service-provider contracts, not a driver |
| `std.db.document` | AZON document-store contracts and the embedded document view |

`std.db.nosql` should **not** be the primary name. "NoSQL" groups document,
key-value, graph, column-family, and search databases even though they have
incompatible semantics. Azora should name the actual model. The first
non-relational model is `std.db.document`; future models may add
`std.db.keyvalue`, `std.db.graph`, or `std.db.search` without pretending they
are one interface.

### 3.2 Future package ecosystem

| Package | Responsibility |
|---|---|
| `postgresql` | PostgreSQL protocol, pooling, dialect, migrations, notifications |
| `sqlite` | SQLite engine/driver, dialect, native and WebAssembly storage adapters |
| `json` | JSON parser, serializer bridge, and HTTP content codec |
| `mysql` | Optional SQL driver and dialect |
| `http-tls-*` | Target-specific TLS providers where the runtime does not supply one |

Package names are resolved by the future package manager. Neither the parser
nor the compiler contains a list of these names. A package satisfies ordinary
specs declared in `std.http` or `std.db.core`.

The future manifest itself should be AZON, for example `azora.azon`. Its exact
schema belongs in a package-management DIP.

---

## 4. Shared full-stack contracts

The highest-value full-stack feature is not a server framework. It is a typed
endpoint value shared by callers and handlers.

```azora
import std.http
import std.serializer

@Serializable
pack CreateUser {
    fin name: String
    fin email: String
}

@Serializable
pack User {
    fin id: Long
    fin name: String
    fin email: String
}

@Serializable
variant error CreateUserFailure {
    InvalidEmail
    EmailAlreadyUsed
}

fin createUser = endpoint<CreateUser, User, CreateUserFailure>(
    method: .Post,
    path: "/users"
)
```

The endpoint value contains metadata, not executable transport code:

```azora
pack Endpoint<Request, Response, Failure> {
    fin method: HttpMethod
    fin path: RoutePattern
    fin requestCodec: ContentCodec<Request>
    fin responseCodec: ContentCodec<Response>
    fin failureCodec: ContentCodec<Failure>
}
```

The default codec is AZON whenever the three types implement
`AzonSerializer<T>`. The endpoint is usable from both sides:

```azora
fin created = try await client.call(createUser, CreateUser("Ada", "ada@example.org"))

router.handle(createUser) { request ->
    return try users.create(request)
}
```

This is not remote procedure call magic. HTTP method, path, headers, status,
cache behavior, and body format remain visible. The endpoint only removes the
duplicated type and codec declarations which otherwise drift apart.

### 4.1 Compatibility identity

Every endpoint has a stable identity derived from its declared module, name,
method, and path. Renaming a source symbol does not silently change a deployed
route when an explicit `id` is supplied.

Schema compatibility is checked independently for request, success, and
failure payloads. The package manager may later store an AZON API manifest for
compatibility checks in CI.

---

## 5. `std.http`

### 5.1 Core values

```azora
pack HttpMethod {
    fin value: String
}

impl HttpMethod:: {
    inline fin Get = HttpMethod("GET")
    inline fin Head = HttpMethod("HEAD")
    inline fin Post = HttpMethod("POST")
    inline fin Put = HttpMethod("PUT")
    inline fin Patch = HttpMethod("PATCH")
    inline fin Delete = HttpMethod("DELETE")
    inline fin Options = HttpMethod("OPTIONS")
}

pack HttpStatus {
    fin code: Int
}

pack Header {
    fin name: String
    fin value: String
}

pack Headers {
    confine fin entries: List<Header>
}

variant enum HttpBody {
    Empty
    Bytes(value: Bytes)
    Text(value: String, contentType: String)
    Azon(value: SerialValue)
    Stream(value: ByteStream)
}

pack HttpRequest {
    fin method: HttpMethod
    fin url: Url
    fin headers: Headers
    fin body: HttpBody
}

pack HttpResponse {
    fin status: HttpStatus
    fin headers: Headers
    fin body: HttpBody
}
```

Names in `Headers` are compared case-insensitively while original spelling is
preserved for diagnostics. Multiple values are retained. APIs must not collapse
`Set-Cookie`, forwarding headers, or extension fields into a single string.

`HttpMethod` validates the HTTP token grammar and therefore permits registered
extension methods without changing `std`. `HttpStatus` stores only the numeric
code; reason phrases are presentation metadata and are absent from newer HTTP
versions, so they are not part of response identity.

`Url` is a parsed value, not a string alias. It distinguishes scheme,
authority, path segments, query pairs, and fragment, and it performs percent
encoding only at the boundary.

### 5.2 Client contract

```azora
error HttpError {
    InvalidUrl,
    UnsupportedScheme,
    NameResolutionFailed,
    ConnectionFailed,
    TlsFailed,
    TimedOut,
    Cancelled,
    ProtocolViolation,
    RedirectLimitExceeded,
    RequestBodyTooLarge,
    ResponseBodyTooLarge,
    DecodeFailed
}

spec HttpClient {
    async func execute[self: Self&](request: HttpRequest): HttpResponse ?! HttpError
}
```

`execute` succeeds for every syntactically valid HTTP response, including 4xx
and 5xx statuses. `expectSuccess(response)` converts an unwanted status into a
typed application policy error. This keeps transport and application failures
separate.

Convenience functions are thin request builders:

```azora
fin response = try client.get("https://example.org/users")
fin user = try response.azon<User>()

fin created = try client.post("https://example.org/users") {
    azon(CreateUser("Ada", "ada@example.org"))
    header("If-None-Match", revision)
    timeout(seconds(10))
}
```

The trailing lambda configures an owned builder. It is not retained after the
request starts unless the API explicitly marks it escaping.

### 5.3 AZON over HTTP

The registered Azora media type should be requested before stabilization. Until
then, the provisional media type is:

```text
application/azon; charset=utf-8
```

Rules:

1. `Content-Type` describes the body actually sent.
2. Typed Azora endpoints send `Accept: application/azon` by default.
3. Error payloads are typed endpoint failures encoded as AZON.
4. Numbers retain AZON's exact textual representation; they are not routed
   through a floating-point intermediate.
5. Decoders apply `SerializerOptions` limits before allocating containers.
6. Unknown fields follow the target type's serializer metadata.

A future `json` package may register `application/json` codecs without changing
the endpoint or HTTP core types.

### 5.4 Server and router

```azora
spec HttpHandler {
    async func handle[self: Self&](request: HttpRequest): HttpResponse ?! HttpError
}

pack Router

impl Router {
    func route[self: Self!](method: HttpMethod, path: String, handler: HttpHandler)
    func handle<Request, Response, Failure>[self: Self!](
        endpoint: Endpoint<Request, Response, Failure>,
        handler: (Request) -> Response ?! Failure
    )
}

spec HttpServer {
    async func serve[self: Self!](handler: HttpHandler) ?! HttpError
    func stop[self: Self!]()
}
```

`std.http.server` is importable everywhere so shared libraries can name its
types. Constructing a listener requires a `NetworkListen` capability. Browser
targets do not provide that capability and report a compile-time target error
when an entry point tries to construct a server.

Routes are compiled into a deterministic matcher. Registration order does not
silently decide between equally specific routes; ambiguity is an error.

### 5.5 Middleware

Middleware is explicit function composition:

```azora
fin app = router
    .with(requestId())
    .with(accessLog(logger))
    .with(cors(corsPolicy))
    .with(auth(sessionVerifier))
    .with(bodyLimit(1_048_576))
```

```azora
spec HttpMiddleware {
    func wrap[self: Self&](next: HttpHandler): HttpHandler
}
```

Required standard middleware:

- request identifiers and structured tracing
- body and header limits
- timeout and cancellation propagation
- CORS with an explicit allow policy
- compression with decompression-bomb limits
- authentication hooks, without prescribing one identity provider
- redaction-aware access logging
- panic containment at the request boundary

Retries are a client policy, never an automatic server policy. The default
client retries nothing. A retry policy requires an idempotent method or an
explicit idempotency key and must honor cancellation and `Retry-After`.

### 5.6 Streaming and backpressure

Large bodies use `ByteStream`; they are not materialized into `Bytes` unless the
caller asks. A stream has one consumer, bounded buffering, cancellation, and
backpressure. Dropping a response closes its unread body.

The convenience `bytes`, `text`, and `azon` decoders require a maximum size.
Defaults are conservative and configurable on `HttpClient`, not through global
variables.

### 5.7 Target implementations

The public API is ordinary Azora code. Runtime packages provide the lowest I/O
layer:

| Target | Client | Server |
|---|---|---|
| interpreter | runtime network provider | runtime listener provider |
| LLVM | native sockets plus selected TLS provider | native listener |
| WebAssembly in browser | browser HTTP capability | unavailable |
| standalone WebAssembly | host capability when supplied | host capability when supplied |

No target may emulate a missing capability by returning empty data or running a
different protocol.

---

## 6. `std.db.core`

### 6.1 Principles

The core API models relational operations, not SQL text. A driver receives a
typed schema/query tree and either executes it directly or compiles it into its
native protocol. PostgreSQL and SQLite adapters may emit SQL internally, but
application values never become SQL through string interpolation.

The useful ideas taken from Exposed are:

- a typed DSL and a separate optional row-to-object layer
- all operations inside transactions
- a driver-neutral core separated from concrete connectivity
- explicit synchronous/asynchronous execution models
- schema inspection and migration planning as a separate concern

Azora deliberately departs in several places:

- there is no implicit current transaction or thread-local transaction stack
- AZON replaces JSON for built-in serialization and document values
- the embedded relational engine is standard and does not parse SQL
- query expressions are immutable values suitable for compile-time inspection
- ownership prevents cursors and row views from escaping closed resources

### 6.2 Driver-neutral contracts

```azora
error DbError {
    Closed,
    Cancelled,
    TimedOut,
    ConstraintViolation,
    UniqueViolation,
    ForeignKeyViolation,
    SerializationConflict,
    Deadlock,
    UnsupportedFeature,
    CorruptStorage,
    MigrationRequired,
    DriverFailure
}

enum Isolation {
    ReadCommitted,
    RepeatableRead,
    Snapshot,
    Serializable
}

pack TransactionOptions {
    fin isolation: Isolation = .Serializable
    fin readOnly: Bool = false
    fin timeout: Duration = seconds(30)
    fin maxAttempts: Int = 1
}

spec Database {
    async func read<T>[self: Self&](
        options: TransactionOptions,
        action: (Transaction&) -> T ?! DbError
    ): T ?! DbError

    async func write<T>[self: Self&](
        options: TransactionOptions,
        action: (Transaction!) -> T ?! DbError
    ): T ?! DbError

    async func close[self: Self!]() ?! DbError
}
```

Convenience overloads provide default options and trailing lambdas:

```azora
fin users = try await db.read { tx ->
    return try tx.select(Users).all()
}

try await db.write(.Serializable) { tx ->
    try tx.insert(Users, User(0, "Ada", "ada@example.org"))
}
```

A transaction callback may suspend only when its driver supports suspension.
Cancellation rolls back before the task completes. Retry reruns the **entire**
callback, so callbacks configured with `maxAttempts > 1` must not perform
uncoordinated external side effects.

The callback types shown above are provisional notation for suspension-aware
callables. Their final spelling must follow the callable-type decision in the
lambda DIP; the database API must not hide suspension behind a synchronous
function type.

### 6.3 Schema declarations

Schema is a typed value tied to a pack through property references:

```azora
@Serializable
pack User {
    fin id: Long
    fin email: String
    fin displayName: String
    fin createdAt: Instant
}

fin Users = table<User>("users") {
    column(User::id) { primaryKey(); generated() }
    column(User::email) { unique(); length(320) }
    column(User::displayName) { length(120) }
    column(User::createdAt)
    index("users_email", [User::email]) { unique() }
}
```

The table builder reads property type metadata. It does not infer persistence
from every field automatically: omitted fields are intentionally not stored.
Column names default to serializer names, so `@SerialName` can define a stable
external name shared by AZON and storage. A database-specific override remains
possible.

Schema objects include stable IDs for tables, columns, indexes, and constraints.
Migration matching uses IDs first and names second, preventing a rename from
being mistaken for drop-and-create.

### 6.4 Typed expression tree

The first implementation uses ordinary specs, conformances, and methods:

```azora
spec DbExpression<T>

pack Column<Row, T>
pack DbValue<T>

impl<Row, T> DbExpression<T> for Column
impl<T> DbExpression<T> for DbValue

impl<Row, T> Column {
    func equal[self: Self&](value: T): DbExpression<Bool>
    func notEqual[self: Self&](value: T): DbExpression<Bool>
    func lessThan[self: Self&](value: T): DbExpression<Bool>
    func lessOrEqual[self: Self&](value: T): DbExpression<Bool>
    func greaterThan[self: Self&](value: T): DbExpression<Bool>
    func greaterOrEqual[self: Self&](value: T): DbExpression<Bool>
}
```

Illustrative query syntax using that library surface:

```azora
fin predicate = all([
    Users[User::age].greaterOrEqual(18),
    Users[User::active].equal(true)
])

fin adults = try tx
    .from(Users)
    .where(predicate)
    .orderBy(Users[User::displayName].ascending)
    .limit(100)
    .all()
```

Comparison operators are deliberately **not** overloaded to build query nodes.
Azora's `oper==` belongs to `PartialEqual`, and `oper<=>` belongs to
`PartialOrder` or `Order`; their contracts describe value comparison rather
than query construction. The removed `impl oper... for ...` form must not
reappear in this library.

A later `std.db` macro may provide the more compact form:

```azora
fin adults = try tx.from(Users).where { row ->
    row.age >= 18 && row.active
}.all()
```

That macro expands the predicate syntax into the ordinary expression methods
above at compile time. It does not change runtime comparison dispatch and does
not require the compiler to know database semantics.

Literal values become bound parameters. Identifiers can only originate from
schema metadata, eliminating identifier injection as well as value injection.

The expression system must cover:

- nullability and three-valued relational logic
- arithmetic, comparison, boolean, string, time, and AZON operators
- joins, grouping, aggregates, distinct, ordering, limits, and offsets
- subqueries and common table expressions
- inserts, updates, deletes, upserts, and returning values
- feature checks for driver-specific operations

An escape hatch accepts a driver-owned typed fragment, never an untyped string
masquerading as a portable expression.

### 6.5 Mapping rows to packs

The default mapping is explicit and immutable:

```azora
fin users: List<User> = try tx.from(Users).all()
fin one: User? = try tx.from(Users).where(Users[User::id] == id).firstOrNull()
```

There is no mandatory active-record base type. A future DAO package may provide
identity maps, relationships, dirty tracking, and generated repositories, but
it must be layered over `std.db.core` and scoped to one transaction.

This avoids values which appear usable after their connection, transaction, or
entity cache has closed.

### 6.6 Relations

Foreign keys are schema values:

```azora
fin Posts = table<Post>("posts") {
    column(Post::id) { primaryKey(); generated() }
    column(Post::authorId) {
        references(Users, User::id) { onDelete(.Cascade) }
    }
    column(Post::body)
}
```

Relations do not trigger hidden queries when a field is read. Applications use
joins, explicit loaders, or repositories. This prevents accidental N+1 queries
and keeps I/O visible.

---

## 7. The in-house embedded relational database

`std.db` provides the standard implementation:

```azora
fin volatile = memoryDatabase()
fin local = try openDatabase("app.azdb")
```

It is intentionally small but not disposable. Required properties:

- typed relational tables and constraints
- ACID transactions
- snapshot isolation initially, serializable execution before stabilization
- primary, unique, and secondary B-tree indexes
- foreign keys with explicit update/delete actions
- write-ahead logging and crash recovery for persistent storage
- checksummed pages and corruption diagnostics
- deterministic in-memory mode for tests
- bounded statement and page caches
- online readers with one coordinated writer in the first implementation
- backup, restore, integrity check, and compact operations
- AZON import/export and schema/migration manifests

The internal storage is a versioned binary page format. AZON is used at public
boundaries, not for every internal row, because repeatedly parsing text would
waste memory, CPU, and storage and would weaken crash recovery.

### 7.1 Frontend and offline use

`memoryDatabase()` works on every target. Persistent embedded storage is
provided when a target exposes a durable-storage capability. In a browser this
is an origin-scoped store supplied to WebAssembly by the host. The API and
database semantics remain the same; filesystem paths are not invented where
the platform has no filesystem.

This makes `std.db` useful for local-first applications, cached API data,
editors, games, tests, and disconnected work without turning it into a
server-only library.

### 7.2 AZON document view

`std.db.document` exposes named collections of `SerialValue` or
`@AzonSerializable` packs:

```azora
fin drafts = db.documents<Draft>("drafts")
try await db.write { tx ->
    try drafts.put(tx, draft.id, draft)
}
```

The standard implementation stores documents in relational pages with indexed
AZON paths. It is a document model, not a promise that every external
non-relational database can implement identical consistency, query, and
transaction semantics.

---

## 8. SQL adapters and external databases

`std.db.sql` contains the service-provider interfaces needed by SQL packages:

```azora
spec SqlDialect {
    func compile[self: Self&](statement: DbStatement&): SqlCommand ?! DbError
    func capabilities[self: Self&](): DbCapabilities
}

spec SqlDriver {
    async func connect[self: Self&](options: DbConnectOptions&): SqlConnection ?! DbError
}
```

`postgresql` and `sqlite` implement these specs and expose constructors. An
application imports and injects the concrete package deliberately:

```azora
import postgresql

graph DataGraph {
    solo postgresql::pool(config.database) bind Database
}
```

The same schema and query values can be executed by the embedded engine,
PostgreSQL, or SQLite when all selected features overlap. A feature outside a
driver's capabilities produces a precise planning error naming the unsupported
operation. Drivers may expose namespaced extensions for native features rather
than forcing them into the common API.

The portable document-column type stores canonical AZON. A PostgreSQL adapter
may lower it to validated text or bytes, but must not silently translate it to
JSON or JSONB. Applications that need a vendor's JSON operators import the
future `json` package and the driver's explicit JSON extension API.

Secrets such as database passwords never belong in endpoint contracts, AZON
logs, generated API manifests, or client bundles. Connecting directly to a
server database from frontend code is rejected unless the target explicitly
provides the capability and the application deliberately configures it.

### 8.1 Pools

Pooling belongs to a driver package because connection behavior differs.
`std.db.core` defines observable pool concepts and limits but no universal
socket pool implementation.

A pool must have bounded size, bounded waiters, acquisition timeout,
cancellation-safe return, health checking, idle expiry, and leak diagnostics.
Creating an unbounded connection for every task is never the default.

---

## 9. Migrations

Migrations are data, and their canonical persisted form is AZON.

```azora
fin migration = migration("2026-08-09-create-users") {
    create(Users)
}

fin rename = migration("2026-08-10-user-name") {
    renameColumn(Users, from: "name", to: User::displayName)
}
```

Every migration has a stable ID, ordered dependencies, a checksum, a forward
plan, and optional rollback plan. Applied checksums are stored in a protected
metadata table.

Schema tooling provides:

- `inspect`: read the actual schema
- `diff`: compare actual and declared schemas
- `validate`: fail on mismatch without changing data
- `plan`: produce a deterministic AZON migration plan
- `apply`: execute an approved plan transactionally where supported

Automatic migration may create additive objects in development. It must never
drop data, narrow a type, or reinterpret a column automatically. Destructive
steps require an explicit migration.

Driver packages own dialect-specific migration lowering. The portable planner
owns stable identities, dependency ordering, checksums, and safety rules.

---

## 10. Transactions, concurrency, and cancellation

1. All reads and writes occur in a lexical transaction callback.
2. The transaction value is passed explicitly; it is not stored in
   `threadlocal` state.
3. A transaction cannot be used after its callback completes.
4. A cursor or borrowed row cannot outlive its transaction.
5. Cancellation triggers rollback and resource cleanup before completion.
6. Nested writes use savepoints only when the driver advertises them.
7. A nested transaction cannot silently choose another database.
8. Retried callbacks receive a new transaction and must recompute all reads.
9. Connection acquisition and query execution are bounded and cancellable.
10. Blocking drivers execute on a bounded blocking scheduler, never on an
    event-loop thread.

The in-house engine uses immutable query plans and owned result values, making
them safe to send only when their element types satisfy the language's
concurrency requirements.

---

## 11. HTTP and database integration

A conventional application keeps transport, transaction, and domain ownership
clear:

```azora
pack UserRepository {
    fin db: Database
}

impl UserRepository {
    async func create[self: Self&](input: CreateUser): User ?! CreateUserFailure {
        return try await self.db.write { tx ->
            if try tx.from(Users).where(Users[User::email] == input.email).exists() {
                return .EmailAlreadyUsed
            }
            return try tx.insertReturning(
                Users,
                User(0, input.name, input.email, now())
            )
        }
    }
}

router.handle(createUser) { request ->
    return try await repository.create(request)
}
```

The router owns the HTTP boundary. The repository owns the transaction
boundary. The domain error is the endpoint's typed failure. AZON encoding is
performed once at the edge.

An HTTP handler must not expose a transaction-backed cursor as a streaming
response unless it deliberately keeps a read transaction open and applies a
deadline. The safer default is pagination or copying owned result values before
the transaction closes.

---

## 12. Security requirements

### HTTP

- strict URL, header, method, and status parsing
- configurable header count/size and body size limits
- request smuggling defenses at protocol boundaries
- decompression limits
- TLS verification enabled by default
- redirects which strip credentials across origins
- cookie policy separated from raw headers
- explicit CORS and CSRF policy
- constant-time comparison helpers for secrets
- sensitive-header and field redaction in traces

### Database

- values are always bound, never interpolated into SQL
- identifiers originate from validated schema metadata
- database credentials use secret configuration values
- file permissions are restrictive by default
- persistent pages and WAL records are checksummed
- migration checksums detect modified history
- query, result, statement, and recursion limits are configurable
- driver errors are sanitized before crossing an HTTP boundary

AZON decoders use the existing serializer depth and input limits. A REST route
must not enable relaxed duplicate-field handling by default.

---

## 13. Observability

Both libraries emit structured events through ordinary tracing interfaces:

- HTTP method, normalized route, status, duration, byte counts, and request ID
- database operation, normalized query fingerprint, duration, rows, retries,
  pool wait, and transaction outcome
- migration ID, checksum, duration, and outcome

Values, full query text, credentials, authorization headers, cookies, and AZON
bodies are excluded by default. Applications opt specific safe fields in.

Interceptors are explicit values installed on a client, server, database, or
pool. There is no process-global mutable interceptor registry.

---

## 14. Testing

`std.http` provides an in-memory transport:

```azora
fin client = testClient(router)
fin response = try await client.call(createUser, sample)
assert response.name == "Ada"
```

`std.db` provides a deterministic in-memory database which uses the same query
planner, constraints, transactions, and migrations as persistent mode:

```azora
test "duplicate email is rejected" {
    fin db = memoryDatabase()
    try await migrate(db, schema)

    try await db.write { tx ->
        try tx.insert(Users, first)
        try tx.insert(Users, duplicate)
    }
}
```

Driver packages must run the shared database conformance suite. HTTP providers
must run the shared client/server protocol suite. Package-specific tests add
dialect and platform behavior but cannot replace conformance tests.

Required stress tests include cancellation during connect/read/write, pool
exhaustion, transaction retry, concurrent writers, crash recovery, partial WAL
writes, oversized AZON bodies, slow streams, and malformed protocol input.

---

## 15. Compiler responsibilities

The compiler is responsible only for existing general language features:

- checking types and ownership
- expanding ordinary macros and derives
- enforcing target capabilities exposed by imported modules
- compiling async functions and cancellation points
- generating serializers through the existing `@Serializable` mechanism

It does **not** know HTTP methods, status codes, SQL syntax, database vendors,
media types, route syntax, or package names.

Schema and endpoint declarations may use macros for ergonomics, but their
expanded form is ordinary packs, specs, functions, and immutable metadata.
Tooling reads that metadata through standard compile-time reflection rather
than private compiler tables.

---

## 16. Delivery plan

### Phase 1: portable values

- `Url`, `Headers`, `HttpRequest`, `HttpResponse`, and typed HTTP errors
- `Database`, `Transaction`, schema, expression, statement, and error types
- AZON content codec
- in-memory HTTP transport and database

### Phase 2: execution

- HTTP client providers for interpreter, LLVM, and WebAssembly hosts
- native HTTP listener provider
- streaming body and cancellation conformance
- embedded relational execution, constraints, and indexes

### Phase 3: contracts and tooling

- typed `Endpoint<Request, Response, Failure>`
- router and middleware
- schema validation and AZON migration plans
- IDE completion and navigation for routes, tables, columns, and migrations

### Phase 4: persistence

- embedded binary pages, WAL, recovery, backup, and compaction
- durable frontend storage provider
- performance and corruption testing

### Phase 5: package ecosystem

- package manager and AZON manifest
- PostgreSQL package
- SQLite package
- JSON interoperability package
- shared driver and codec conformance suites

No phase may ship a fake implementation for an unsupported target. A smaller
honest surface is preferable to an API which silently changes semantics.

---

## 17. Decisions to keep

1. `std.http` is shared, not classified as backend-only.
2. AZON is the standard HTTP and data interchange format.
3. JSON is a future package.
4. `std.db.core` is driver-neutral and vendor-neutral.
5. `std.db` ships an in-house embedded relational engine.
6. PostgreSQL and SQLite are packages implementing standard specs.
7. SQL text is an adapter detail, not the portable query representation.
8. Transactions are explicit lexical callbacks, never implicit thread-local
   state.
9. The typed relational DSL is primary; DAO-style mapping is optional.
10. `std.db.document` names the first non-relational model; no universal
    `std.db.nosql` abstraction is promised.
11. AZON is used for documents, APIs, exports, migration plans, configuration,
    and manifests, while the embedded engine uses an efficient binary page
    format internally.
12. The compiler contains no database, protocol, driver, or package-name
    special cases.

---

## 18. Influence

This proposal is informed by JetBrains Exposed 1.x, especially its separation
of a driver-neutral core from JDBC/R2DBC implementations, its typed DSL and DAO
choices, its mandatory transaction scopes, and its separate migration tooling:

- <https://www.jetbrains.com/help/exposed/about.html>
- <https://www.jetbrains.com/help/exposed/transactions.html>
- <https://www.jetbrains.com/help/exposed/migrations.html>
- <https://www.jetbrains.com/help/exposed/migration-guide-1-0-0.html>

Azora adopts the architectural lessons, not the JVM-specific APIs.

---

## 19. Related DIPs

- `DIPs/DEPENDENCY_INJECTION_DIP.MD`
- `DIPs/CONCURRENCY_DIP.MD`
- `DIPs/ERROR_HANDLING_DIP.MD`
- `DIPs/OWNERSHIP_BORROWING_DIP.md`
- `DIPs/LAMBDA_CONTEXT_CAPTURE_DIP.MD`
- `DIPs/PROPERTY_REFERENCES_DIP.MD`
- `DIPs/REFLECTION_DIP.MD`
- `DIPs/COMPILER_INTRINSIC_DIP.MD`
