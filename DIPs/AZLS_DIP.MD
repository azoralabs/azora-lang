# AZLS and Structured Compiler Diagnostics DIP

> Status: **Proposed**
> Scope: compiler diagnostics, source locations, fixes, the `azls` process,
> Language Server Protocol integration, and the Azora IntelliJ IDEA plugin
> Supersedes: the previous AZLS implementation profile in this file
> Intended implementation language: Kotlin first, with an API shape that can
> later be reproduced by the self-hosted Azora compiler

## 0. Decision summary

Azora will have one structured diagnostic system owned by the compiler. Lexer,
parser, macro expansion, module loading, semantic analysis, type checking,
effects, ownership, borrowing, CTCE, IR generation, and backends will report
sealed diagnostic variants rather than strings.

Every user-facing diagnostic will have, at minimum:

- a stable diagnostic code;
- a category and compiler stage;
- a severity;
- a primary source range with file identity, start, and end;
- a human-readable message;
- zero or more labeled related ranges;
- zero or more notes;
- zero or more structured suggested fixes;
- enough provenance to map generated code back to author-written code.

`azls` will be a long-lived external process. It will consume these structured
compiler values directly and publish them through the standard Language Server
Protocol (LSP) over standard input/output. The IntelliJ IDEA plugin will launch,
monitor, and communicate with that process as a thin LSP client. It will not
load `azls.jar` reflectively and it will not contain a second semantic analyzer.

The compiler remains the source of truth:

```text
Azora source snapshots
        |
        v
Compiler analysis session
  sealed diagnostic variants
        |
        v
AZLS normalization and snapshot validation
        |
        +--> publishDiagnostics
        +--> textDocument/codeAction
        +--> hover, completion, definition, symbols, semantic tokens
        |
        v
IntelliJ IDEA LSP client and editor UI
```

No component may recover a diagnostic's line, severity, symbol, import, or fix
by parsing its rendered message.

---

## 1. Motivation and current-state audit

The current compiler and language server already have the right high-level
relationship: AZLS calls the real compiler. The boundary between them is the
problem.

At the time of this proposal:

- `CompilationResult.Success` carries `warnings: List<String>`;
- `CompilationResult.Failure` carries `errors: List<String>`;
- `SemanticResult` and many individual passes carry `errors: List<String>`;
- warning severity is inferred from a `"warning:"` message prefix;
- frontend failures are commonly raised as `IllegalStateException` or
  `IllegalArgumentException`;
- `Compiler.withLibraryHint` regex-parses undefined-symbol text and appends an
  import suggestion;
- AZLS regex-parses `line N` from messages;
- the current AZLS `Diagnostic` has only `line`, `message`, and string severity;
- a diagnostic has no end position, source identity, document version, related
  location, stable code, or applicable edit;
- other files are concatenated into a textual `prelude`, after which line
  numbers are subtracted and prelude diagnostics are dropped;
- `AzlsStdio` uses LSP framing but implements a bespoke protocol, not JSON-RPC
  2.0 and LSP;
- the product integration is documented as reflective in-process loading,
  coupling host and server while still forcing a string boundary.

This works for a basic red marker on a line. It is too fragile for precise
diagnostics, ownership explanations, multi-file projects, reliable quick fixes,
multiple editors, incremental analysis, or a future self-hosted compiler.

The compiler already carries partial position data. `Token` has line and column,
and expressions generally have line, column, and length. That data must become a
coherent source model instead of being formatted into messages and reconstructed
later.

### 1.1 Why compiler-owned variants

An undefined name contains a queried name, namespace, candidate declarations,
possibly a provider module, and a span. A borrow conflict contains two or more
uses with a temporal relationship. A parser error contains the received token
and an expected-token set. Turning those facts into text discards the exact data
that tools need.

Typed variants allow:

- CLI and IDE to display the same underlying error;
- exact ranges without regexes;
- fixes based on symbol identity instead of text search;
- tests of semantics independent of English wording;
- renderer evolution without breaking clients;
- a self-hosted compiler to reproduce a stable conceptual contract.

### 1.2 Why a process boundary

A language server has different lifecycle and reliability needs from an editor
plugin. A separate process provides crash and memory isolation, cancellation,
version negotiation, independent updates, standard editor interoperability, and
no JVM/Kotlin ABI crossing into the plugin. It also gives a clean path from the
Kotlin compiler to a future native/self-hosted AZLS executable.

---

## 2. Goals

1. All expected, author-correctable failures are values, not thrown exceptions.
2. Every diagnostic identifies an exact range in a named source unit.
3. Multi-file diagnostics stay attached to their real files.
4. Generated declarations retain source provenance.
5. Diagnostic identity does not depend on rendered English.
6. Severity is structured; warning prefixes disappear.
7. Explanatory help is distinct from safely applicable edits.
8. Ownership and borrow errors explain all causally relevant locations.
9. AZLS analyzes without running code generation on every edit.
10. AZLS speaks standard LSP through JSON-RPC 2.0.
11. Stale document results are never published or applied.
12. New edits promptly cancel obsolete work.
13. IntelliJ is a thin LSP client, process supervisor, and UI adapter.
14. CLI, tests, AZLS, and build tools consume the same compiler diagnostics.
15. The design is portable to self-hosting and does not expose Kotlin class
    names as a wire protocol.

## 3. Non-goals

This proposal does not require every editor feature to ship immediately. Rename,
references, formatting, inlay hints, call hierarchy, and native debugging can
arrive later. It also does not:

- serialize compiler sealed classes directly over LSP;
- make every suggestion automatically applicable;
- treat a compiler panic as a normal source error;
- require full incremental type checking in phase one;
- require replacing the tolerant highlighter immediately;
- define the complete debugger protocol;
- force downstream stages to run when meaningful recovery is impossible;
- introduce localization in the first implementation.

---

## 4. Terminology

**Diagnostic** is a compiler-produced structured fact explaining a problem or
noteworthy condition.

**Diagnostic variant** is a concrete sealed subtype such as `UnexpectedToken`,
`UndefinedSymbol`, or `UseAfterTake`.

**Diagnostic code** is a stable public identifier such as `AZ-PAR-0003`.

**Primary span** is the range the editor underlines and the CLI leads with.

**Related span** is another labeled location needed to understand the problem,
such as the place where a value was taken.

**Suggestion** is human-readable guidance that may not be mechanically safe.

**Fix** is a structured edit proposal with a safety classification.

**Source snapshot** is immutable text with source identity and, when
editor-owned, a document version.

**Analysis snapshot** is the result computed from one consistent set of source
snapshots and compiler configuration.

**Poisoned node** is a recovery AST/semantic value that prevents cascades from a
known root error.

---

## 5. Source model

Structured diagnostics require reliable source locations, so the source model
must be implemented before the full diagnostic hierarchy.

### 5.1 Source identity

Every compiler input is a distinct `SourceUnit`:

```kotlin
@JvmInline
value class SourceId(val value: String)

@JvmInline
value class DocumentVersion(val value: Long)

data class SourceUnit(
    val id: SourceId,
    val uri: String,
    val displayPath: String,
    val text: String,
    val version: DocumentVersion? = null,
    val kind: SourceKind,
)

enum class SourceKind {
    USER,
    WORKSPACE_LIBRARY,
    STANDARD_LIBRARY,
    GENERATED,
    VIRTUAL,
}
```

`SourceId` is opaque within an analysis session. A URI is the external identity
used by LSP. Files use canonical `file:` URIs. Unsaved/generated content uses a
stable virtual scheme such as `azora-generated:`.

The compiler must not identify a source by a line offset into concatenated text.
The current `prelude + "\n" + source` approach is removed. A project is a set of
source units and a module graph.

### 5.2 Canonical spans

```kotlin
@JvmInline
value class TextOffset(val value: Int)

data class SourceSpan(
    val source: SourceId,
    val start: TextOffset,
    val endExclusive: TextOffset,
    val origin: SourceOrigin = SourceOrigin.Written,
) {
    init {
        require(start.value >= 0)
        require(endExclusive.value >= start.value)
    }
}
```

Ranges are half-open. Zero-width ranges are valid insertion points for missing
tokens.

`TextOffset` is deliberately opaque. Initially, Kotlin can use a `String` index
(UTF-16 code units). No protocol consumer may assume that representation. A
self-hosted compiler may later use UTF-8 byte offsets without changing LSP.

Line/column are derived through a snapshot-specific index:

```kotlin
enum class PositionEncoding { UTF16, UTF8, UTF32 }

data class SourcePosition(val line: Int, val character: Int)

interface LineIndex {
    fun position(offset: TextOffset, encoding: PositionEncoding): SourcePosition
    fun offset(position: SourcePosition, encoding: PositionEncoding): TextOffset?
}
```

Internal and LSP positions are zero-based. CLI display is one-based. This
removes the current mixture of one-based fields whose defaults are sometimes
zero.

Required tests cover LF/CRLF, empty files, empty final lines, surrogate pairs,
combining characters, tabs, EOF, malformed client positions, and every
negotiated position encoding.

### 5.3 Tokens and AST nodes

`Token.line` and `Token.column` are replaced or supplemented by `span`:

```kotlin
data class Token(
    val type: TokenType,
    val lexeme: String,
    val span: SourceSpan,
    val literal: Any? = null,
)
```

AST nodes that can receive diagnostics carry spans. Nodes also retain useful
component spans: a function has declaration, name, parameter, and return-type
spans. A diagnostic underlines the smallest meaningful range.

During migration, `LegacyLocationAdapter` may derive a coarse span from
line/column/length. New code must never derive a location from message text.

### 5.4 Generated-source provenance

Derives, macros, default spec members, interpolation lowering,
monomorphization, and other transforms generate code:

```kotlin
sealed interface SourceOrigin {
    data object Written : SourceOrigin

    data class MacroExpansion(
        val callSite: SourceSpan,
        val definition: SourceSpan?,
        val expansionName: String,
    ) : SourceOrigin

    data class DerivedMember(
        val requestSite: SourceSpan,
        val derivedSpec: String,
    ) : SourceOrigin

    data class Monomorphized(
        val useSite: SourceSpan,
        val genericDeclaration: SourceSpan,
    ) : SourceOrigin

    data class CompilerGenerated(
        val nearestUserSpan: SourceSpan?,
        val reason: String,
    ) : SourceOrigin
}
```

When generated code fails, the primary location is normally the author action
that requested it. Generated and definition locations are related information.
Raw generated text is only an optional debug view, never a fake line in a user
file.

### 5.5 Source manager

```kotlin
interface SourceManager {
    operator fun get(id: SourceId): SourceUnit?
    fun text(span: SourceSpan): String?
    fun range(span: SourceSpan, encoding: PositionEncoding): SourceRange?
    fun resolveToUserSource(span: SourceSpan): ResolvedOrigin
}
```

Passes carry IDs and spans, not editor or filesystem objects, preserving the
platform-neutral compiler core.

---

## 6. Compiler diagnostic model

### 6.1 Common contract

The common compiler module exposes:

```kotlin
sealed interface AzoraDiagnostic {
    val code: DiagnosticCode
    val stage: DiagnosticStage
    val severity: DiagnosticSeverity
    val primary: LabeledSpan
    val related: List<RelatedLocation> get() = emptyList()
    val notes: List<DiagnosticNote> get() = emptyList()
    val suggestions: List<DiagnosticSuggestion> get() = emptyList()
    val fixes: List<DiagnosticFix> get() = emptyList()
    val tags: Set<DiagnosticTag> get() = emptySet()
    val suppression: SuppressionKey? get() = null
}

@JvmInline
value class DiagnosticCode(val value: String)

data class LabeledSpan(val span: SourceSpan, val label: String? = null)
data class RelatedLocation(val span: SourceSpan, val message: String)
data class DiagnosticNote(val message: String)
data class DiagnosticSuggestion(val message: String)
```

The rendered message comes from the variant or a centralized renderer. Facts
needed by tools remain typed fields. `UndefinedSymbol`, for example, owns its
name, namespace, candidates, and provider module; no consumer extracts them from
a sentence.

### 6.2 Sealed families

Kotlin sealed interfaces provide exhaustiveness while concrete errors remain
data-class variants:

```kotlin
sealed interface LexerDiagnostic : AzoraDiagnostic
sealed interface ParserDiagnostic : AzoraDiagnostic
sealed interface ModuleDiagnostic : AzoraDiagnostic
sealed interface MacroDiagnostic : AzoraDiagnostic
sealed interface ValidationDiagnostic : AzoraDiagnostic
sealed interface SymbolDiagnostic : AzoraDiagnostic
sealed interface TypeDiagnostic : AzoraDiagnostic
sealed interface SemanticDiagnostic : AzoraDiagnostic
sealed interface EffectDiagnostic : AzoraDiagnostic
sealed interface OwnershipDiagnostic : AzoraDiagnostic
sealed interface BorrowDiagnostic : AzoraDiagnostic
sealed interface CtceDiagnostic : AzoraDiagnostic
sealed interface IrDiagnostic : AzoraDiagnostic
sealed interface BackendDiagnostic : AzoraDiagnostic
sealed interface ConfigurationDiagnostic : AzoraDiagnostic
sealed interface CompilerDiagnostic : AzoraDiagnostic
```

Examples:

```kotlin
data class UnexpectedCharacter(
    val character: String,
    override val primary: LabeledSpan,
) : LexerDiagnostic {
    override val code = DiagnosticCode("AZ-LEX-0001")
    override val stage = DiagnosticStage.LEXER
    override val severity = DiagnosticSeverity.ERROR
}

data class UnexpectedToken(
    val found: TokenType,
    val expected: Set<TokenExpectation>,
    val context: ParseContext,
    override val primary: LabeledSpan,
    override val fixes: List<DiagnosticFix>,
) : ParserDiagnostic {
    override val code = DiagnosticCode("AZ-PAR-0003")
    override val stage = DiagnosticStage.PARSER
    override val severity = DiagnosticSeverity.ERROR
}

data class UndefinedSymbol(
    val symbol: String,
    val namespace: SymbolNamespace,
    val candidates: List<SymbolCandidate>,
    val providerModule: ModuleId?,
    override val primary: LabeledSpan,
    override val fixes: List<DiagnosticFix>,
) : SymbolDiagnostic {
    override val code = DiagnosticCode("AZ-SYM-0001")
    override val stage = DiagnosticStage.SYMBOLS
    override val severity = DiagnosticSeverity.ERROR
}

data class UseAfterTake(
    val place: PlaceId,
    override val primary: LabeledSpan,
    val takenAt: SourceSpan,
    val alternatives: Set<OwnershipAlternative>,
) : OwnershipDiagnostic {
    override val code = DiagnosticCode("AZ-OWN-0002")
    override val stage = DiagnosticStage.OWNERSHIP
    override val severity = DiagnosticSeverity.ERROR
    override val related = listOf(
        RelatedLocation(takenAt, "ownership was transferred here")
    )
}
```

Exact Kotlin factoring may use base classes/delegation for common empty fields.
The semantic rule is fixed: expected user errors are typed variants that an
exhaustive compiler-side visitor can inspect.

### 6.3 Stage versus category

`DiagnosticStage` says where a problem was found:

```kotlin
enum class DiagnosticStage {
    CONFIGURATION,
    MODULE_LOADING,
    LEXER,
    PARSER,
    MACRO_EXPANSION,
    AST_VALIDATION,
    SYMBOLS,
    TYPES,
    SEMANTIC,
    EFFECTS,
    OWNERSHIP,
    BORROWING,
    CTCE,
    IR,
    BACKEND,
    COMPILER,
}
```

A separate category/suppression key says what the author controls. Unused names
may be discovered during liveness analysis but belong to `Unused`. Pipeline
refactors must not change user suppression behavior.

### 6.4 Severity and tags

```kotlin
enum class DiagnosticSeverity { ERROR, WARNING, INFORMATION, HINT }

enum class DiagnosticTag {
    UNNECESSARY,
    DEPRECATED,
    UNSAFE,
    EXPERIMENTAL,
    GENERATED,
}
```

Severity is never inferred from text. `warningsAsErrors` is a policy applied
after production. The diagnostic retains original severity; the build outcome
records whether policy made it fatal. LSP directly supports unnecessary and
deprecated tags; other tags remain in AZLS diagnostic data.

### 6.5 Stable codes

Codes use `AZ-<FAMILY>-<FOUR DIGITS>`:

| Family | Area |
|---|---|
| `CFG` | toolchain/compiler configuration |
| `MOD` | source units, modules, imports, cycles |
| `LEX` | lexical analysis |
| `PAR` | parsing/recovery |
| `MAC` | macros/generated syntax |
| `VAL` | structural validation |
| `SYM` | declarations, lookup, visibility |
| `TYP` | inference/type checking |
| `SEM` | remaining semantic rules |
| `EFF` | effects/purity |
| `OWN` | ownership, `take`, `lend`, destruction |
| `BOR` | borrows/lifetimes |
| `CTC` | compile-time evaluation |
| `IR` | IR creation/validation |
| `WASM` | WebAssembly backend |
| `LLVM` | LLVM backend |
| `CMP` | infrastructure/internal failures |

Codes are never reused. Wording may change without a new code; a semantic
change to the rule receives a new one. A registry test enforces uniqueness:

```kotlin
data class DiagnosticDescriptor(
    val code: DiagnosticCode,
    val title: String,
    val defaultSeverity: DiagnosticSeverity,
    val suppression: SuppressionKey?,
    val documentationPath: String?,
)
```

### 6.6 Occurrence identity

A code identifies a problem kind, not an occurrence. AZLS assigns a short-lived
`DiagnosticOccurrenceId` derived from analysis snapshot, code, source, span,
and typed discriminating data. It is used for code-action resolution, is not
persisted across restarts, and is not language specification.

### 6.7 Compiler bugs are different

Invariant failures, corrupted IR, or unexpected exceptions are not normal
author errors. The compiler boundary creates an `InternalCompilerFailure`
variant in the sealed `CompilerDiagnostic` family, with public code
`AZ-CMP-9999`, a safe explanation, crash ID, phase, and best-effort span. Full
stacks go only to logs. Cancellation is control flow, not a diagnostic.
Out-of-memory/runtime corruption may terminate the process so the plugin can
restart it.

---

## 7. Minimum diagnostic catalogue

### 7.1 Configuration and modules

- `AZ-CFG-0001`: standard library unavailable;
- `AZ-CFG-0002`: compiler/stdlib version mismatch;
- `AZ-CFG-0003`: invalid define or target configuration;
- `AZ-MOD-0001`: module not found;
- `AZ-MOD-0002`: imported declaration not found;
- `AZ-MOD-0003`: import cycle, with each edge related;
- `AZ-MOD-0004`: private/confined access;
- `AZ-MOD-0005`: ambiguous imported symbol;
- `AZ-MOD-0006`: module path/declaration mismatch;
- `AZ-MOD-0007`: source could not be read or decoded.

Import fixes use structured provider data. This replaces
`Compiler.withLibraryHint`, which currently regex-parses undefined-symbol text.

### 7.2 Lexer

Variants cover unexpected characters; unterminated string, character,
interpolation, or block comment; invalid escapes; malformed number bases,
suffixes, widths, and separators; compiler-reserved identifiers; malformed
Unicode escapes; and invalid/incomplete operator spellings.

The lexer reports to a sink and returns recovery tokens. It does not throw for
malformed author input. An unterminated string can consume to a safe boundary
and still emit a token so parser/highlighter work continues.

### 7.3 Parser

Variants cover unexpected tokens with bounded expectations, missing delimiters,
invalid modifier combinations, invalid assignment targets, malformed grouping,
collection, tuple, pattern, or generic syntax, invalid type tuples such as
`derives (...)`/`impl (...)`, duplicate named arguments/bindings, illegal
expression/type positions, ownership syntax, and recovery limits.

Receiver-specific parser variants distinguish named groups
`func<T> (self: T&).name()`, unnamed single receivers `func<T> T&.name()`,
unnamed multi-receiver groups `func<T, U> (T&, U&).name()`, and impl/spec
capability shorthands `func &.name()` / `func !.name()`. Migration diagnostics
replace legacy square receiver heads with parentheses, replace contextual
`with` with `using`, and replace removed `task`/`flow` declarations with
`async func` or an ordinary `func` returning the intended library type.
Computed properties use the observational subset of the receiver model
(`prop &.name`), and a targeted fix rewrites `prop name[self: Self&]` to that
form. `prop !.name`, an owned property receiver, or any mutable/owned slot in a
multi-receiver property is rejected with a fix that converts the declaration
to `func !.name()` / an owned function where mechanically safe. Returned borrow
origins use fences such as `T&|self|`; a bracket-delimited origin receives a
punctuation-only migration fix.

Lifecycle declarations have their own closed grammar: `ctor ()` and `dtor ()`
are receiver-free static startup/shutdown hooks, while `ctor .(...)` and
`dtor .()` are owned-instance construction/destruction. Parser diagnostics
reject `ctor &.`, `ctor !.`, `dtor &.`, and `dtor !.` with the explanation that
borrows never run lifecycle code. A legacy instance `ctor(args)` gets a fix to
`ctor .(args)`; a legacy `dtor { ... }` gets a fix to `dtor .() { ... }` when
the surrounding `impl` proves instance intent. These fixes are not offered
when `()` clearly denotes the static form.

Parser fixes may insert an unambiguous delimiter, remove a duplicate separator,
or replace a known obsolete spelling. A guessed AST is not machine-applicable.

### 7.4 Macros, derives, and generated declarations

Variants cover missing/ambiguous macro arms, failed matching, recursion/size
limits, invalid expanded syntax, unsupported derives, generated member
conflicts, and generated type/visibility/ownership failures.

For `pack Point derives (Equal, Hash, Display)`, failure in generated `Hash`
code points primarily to `Hash`; generated member and derive definition are
related locations.

### 7.5 Symbols, types, and semantics

Required variants include duplicate/undefined/ambiguous symbols, visibility,
argument count/names, type mismatch, unsatisfied spec bound, ambiguous impl,
missing required member, invalid associated type, non-exhaustive `when`,
unreachable code, invalid cast, immutable assignment, nullability misuse,
unused declarations/imports, deprecation, and duplicate canonical contextual
receiver types. The duplicate-receiver variant records both receiver spans, the
normalized base type, whether equality appeared only after generic
substitution, and fixes that suggest a wrapper context or an ordinary
parameter. It is used consistently for named receiver heads, lambda headers,
callable receiver types, expanded variadic receiver packs, `using` clauses, and
direct brace receiver groups.

Candidates are produced by semantic lookup. The renderer may say “did you
mean,” while fixes store exact spelling and symbol identity.

### 7.6 Effects

Variants cover pure-to-impure calls, escaped undeclared effects, reactive state
outside its owner, illegal reactive-owner calls, forbidden suspension, and
effect dependency cycles. Calls are primary; violated function contracts are
related. Widening an effect contract is normally `MAYBE_INCORRECT`.

### 7.7 CTCE

Variants cover non-evaluable expressions, wrong compile-time types, recursion,
step/memory/expansion budgets, forbidden I/O/FFI/process/network/clock/random
access, compile-time panic, unstable fixed point, and generated collision.
IDE mode uses deterministic strict limits and checks cancellation inside loops.

### 7.8 IR and backends

Variants cover lowering failure, invalid IR invariants, unsupported target
features, invalid ABI/bridge declarations, missing layout, and codegen failure.
Unsupported output must not be returned as comment text pretending to be a
valid artifact. Normal AZLS editing stops before backend work.

---

## 8. Ownership and borrowing diagnostics

Ownership errors usually describe a relationship over time, not one bad token.
They therefore require typed state and multiple spans. Azora terminology—
`take`, `lend`, shared `&`, exclusive mutable `!`—must be used in messages.

### 8.1 Analyzer state

```kotlin
sealed interface PlaceState {
    data object Available : PlaceState
    data class Taken(val at: SourceSpan) : PlaceState
    data class PartiallyTaken(val parts: Map<Projection, SourceSpan>) : PlaceState
    data class Lent(val at: SourceSpan, val expectedReturn: RegionId) : PlaceState
    data class Purged(val at: SourceSpan) : PlaceState
}

data class ActiveLoan(
    val id: LoanId,
    val place: PlaceId,
    val kind: LoanKind,
    val createdAt: SourceSpan,
    val region: RegionId,
    val usedUntil: SourceSpan?,
)
```

Dataflow joins preserve causal locations. Diagnostics remain deterministic when
several paths lead to the same invalid state.

### 8.2 Use after `take`

```azora
fin message = makeMessage()
send(take message)
print(message)
```

- Primary: `message` in `print(message)`.
- Related: `take message`, “ownership was transferred here.”
- Note: the value is unavailable after `take`.
- Suggestions: use `lend` if the callee contract returns ownership; explicitly
  `clone()` when `Clone` exists and an independent value is intended; or move
  the last use before transfer.

These are not silently applied: they differ in identity, performance, and
behavior. Clone may be a non-preferred action only when type analysis proves it
exists.

### 8.3 Take while borrowed

```azora
fin view = value.&
consume(take value)
use(view)
```

Primary is `take value`; borrow creation and last use of `view` are related. The
author may shorten the borrow or move the take. Reordering is not automatic
without an effect-preservation proof.

### 8.4 Shared/exclusive conflicts

The analyzer distinguishes exclusive-while-shared, shared-while-exclusive,
second exclusive loan, mutation while incompatibly borrowed, mutation through
a shared loan, and purge/destruction while borrowed. The new operation is
primary; every incompatible loan and, when known, its last use is related.

### 8.5 Borrowing an unstable temporary

```azora
fin view: User& = makeUser().&
```

The temporary does not provide a stable place for the requested lifetime. A
possible action introduces an owner:

```azora
fin owner = makeUser()
fin view: User& = owner.&
```

This has placeholders or `MAYBE_INCORRECT` applicability because naming and
scope require judgment.

### 8.6 Mutable borrow and binding mutability

When an exclusive mutable reference requires a `var` owner/binding, the borrow
is primary and the immutable declaration is related. Required/actual mutability
are typed data. “Change `fin` to `var`” is visible and non-preferred unless the
language defines it as a purely permissive local change.

### 8.7 Borrow escape

Required cases include returning a local borrow, storing it in a longer-lived
field/global, capturing it in an escaping closure, moving it to an async task,
retaining it across unsafe `await`, and returning a borrow whose declared origin
does not include the source. Escape is primary; owner, creation, and destination
lifetime are related. Messages name Azora origins, not internal region numbers.
For a missing or ambiguous public origin, the primary fix inserts or extends an
origin fence (`T&|self|` or `T&|left, right|`) using proven input bindings. An
empty fence and a fence naming a local/non-input binding are typed diagnostics,
not generic parser strings.

### 8.8 `lend` contract failures

Diagnostics cover lending to a non-lend parameter, consuming/purging the lent
value, failing to return it on every path, caller use while lending, callee
escape, and conflicting loans. Call is primary; parameter/return contract and
failing exits are related.

### 8.9 Partial moves

Taking a field/tuple element may leave an aggregate partially available.
Variants distinguish reusing that projection, whole-value use, overlapping
aliased projections, restoring availability by assignment, and destruction of
partial aggregates. Renderers show source field/tuple names; typed place
projections remain available for tests.

### 8.9.1 Construction and destruction

Definite-initialization diagnostics for `ctor .(...)` report every required
field not initialized on a successful exit, duplicate initialization of a
`fin` field, and reads of fields before initialization. Moving a named owned
parameter into an owned field may suggest `take` when required. Related spans
identify the field declaration and the control-flow exit that leaves it
uninitialized.

Destruction diagnostics cover explicit or implicit destruction while borrowed,
double destruction, attempted resurrection or escape of `self` from
`dtor .()`, and invalid access to an already destroyed field. Static lifecycle
diagnostics cover dependency cycles, use before successful static startup, and
illegal parameters or `self` access in `ctor ()` / `dtor ()`. If instance
construction fails after partial initialization, the diagnostic trace lists
the fields the compiler will clean up; the full `dtor .()` is not considered to
have run.

### 8.10 Branches and loops

A value taken on one branch yields “may have been taken” at later use, with the
branch transfer related. Loop analysis accounts for the next iteration. After a
root ownership error, poisoned state suppresses compatible downstream cascades
until an assignment restores a defined state.

### 8.11 Group fan-out and one-shot broadcast

```azora
fin {x, y} = next()   // next() evaluates once for each declaration
fin {x, y} <- next()  // next() evaluates once and broadcasts
```

Repeated evaluation can produce two independently owned results. One-shot `<-`
cannot silently create two owners for a non-copyable value:

- a `Copy` value may be copied to each target;
- a shared borrow may feed compatible shared-borrow targets;
- tuple/group destructuring moves distinct projections and is semantically
  different from broadcast;
- a non-`Copy` owned value cannot feed multiple owning bindings.

`AZ-OWN-0012` underlines `<-`, relates the targets, and offers choices: repeated
evaluation with `=`, shared borrowing, explicit selected clones, or tuple
destructuring. No automatic clone is inserted.

### 8.12 Proposed ownership codes

| Code | Meaning |
|---|---|
| `AZ-OWN-0001` | value taken twice |
| `AZ-OWN-0002` | use after `take` |
| `AZ-OWN-0003` | take/purge while borrowed |
| `AZ-OWN-0004` | value may be taken on a path |
| `AZ-OWN-0005` | invalid partial move/whole-value use |
| `AZ-OWN-0006` | lent value not returned |
| `AZ-OWN-0007` | lent value consumed/escaped |
| `AZ-OWN-0008` | caller use during lend |
| `AZ-OWN-0009` | destruction obligation unmet |
| `AZ-OWN-0010` | double destruction/purge |
| `AZ-OWN-0011` | forbidden transfer boundary |
| `AZ-OWN-0012` | one-shot broadcast creates multiple owners |
| `AZ-BOR-0001` | unstable place/temporary borrowed |
| `AZ-BOR-0002` | shared borrow conflicts with exclusive loan |
| `AZ-BOR-0003` | exclusive borrow conflicts with active loan |
| `AZ-BOR-0004` | mutation conflicts with active borrow |
| `AZ-BOR-0005` | mutation through shared borrow |
| `AZ-BOR-0006` | borrow outlives owner |
| `AZ-BOR-0007` | returned borrow has invalid origin |
| `AZ-BOR-0008` | borrow escapes via field/global/closure |
| `AZ-BOR-0009` | borrow crosses unsafe suspension |
| `AZ-BOR-0010` | origin missing/ambiguous |

---

## 9. Notes, suggestions, and fixes

The compiler must not treat helpful text and safe edits as synonyms.

### 9.1 Fix model

```kotlin
@JvmInline
value class FixId(val value: String)

enum class FixKind { QUICK_FIX, REFACTOR_REWRITE, SOURCE_FIX_ALL }

enum class FixApplicability {
    MACHINE_APPLICABLE,
    MAYBE_INCORRECT,
    HAS_PLACEHOLDERS,
    UNSPECIFIED,
}

data class DiagnosticFix(
    val id: FixId,
    val title: String,
    val kind: FixKind,
    val applicability: FixApplicability,
    val preferred: Boolean = false,
    val edits: List<SourceEdit> = emptyList(),
    val resolver: FixResolverKey? = null,
)

data class SourceEdit(
    val source: SourceId,
    val range: SourceSpan,
    val replacement: String,
    val expectedText: String? = null,
    val requiredVersion: DocumentVersion? = null,
)
```

Eager fixes contain edits. Expensive fixes use a resolver key and are computed
by `codeAction/resolve` against the retained snapshot.

### 9.2 Applicability

`MACHINE_APPLICABLE` means one unambiguous syntactic/semantic repair: insert one
missing `)`, import the only accessible provider, remove a duplicate separator,
or use an exact declared replacement. It does not mean the broader behavior is
desirable; it means the fix is not guessing between meanings.

Adding `clone()`, changing `fin` to `var`, widening effects, changing public
visibility, or choosing similar names is `MAYBE_INCORRECT`.
`HAS_PLACEHOLDERS` needs author-chosen names/types/members.

### 9.3 Validation

Before publishing/applying a fix, AZLS verifies document version, source
existence, valid ranges, optional expected text, no overlaps, and workspace
boundaries. Stale edits are never silently rebased by searching for similar
text.

### 9.4 Fix-all

Fix-all initially covers only registry-marked composable machine fixes, such as
unused imports. AZLS detects conflicts. Ownership, ambiguous imports, and public
API changes are excluded by default.

### 9.5 Suppression

The language currently spells `@Supress`; this DIP does not silently rename it.
Matching changes from rendered messages to `SuppressionKey` values such as
`Unused`, `Deprecated`, or a diagnostic code. Existing declaration/module scope
rules remain. Lexer and unrecovered parser errors are normally not annotation-
suppressible. “Suppress this” is offered only when AZLS can locate and safely
edit a legal annotation owner.

---

## 10. Collection and recovery

### 10.1 Diagnostic sink

```kotlin
interface DiagnosticSink {
    fun report(diagnostic: AzoraDiagnostic)
    fun isCancelled(): Boolean
    fun checkpoint()
}

class DiagnosticBag(
    private val policy: DiagnosticPolicy,
    private val limits: DiagnosticLimits,
) : DiagnosticSink {
    val diagnostics: List<AzoraDiagnostic>
    val hasFatalErrors: Boolean
}
```

The bag handles limits, deterministic ordering, suppression policy, and
cancellation. Pure passes may return local lists, but the element type is always
an `AzoraDiagnostic`.

### 10.2 Stage results

```kotlin
data class StageResult<T>(
    val value: T,
    val diagnostics: List<AzoraDiagnostic>,
    val recovery: RecoveryState = RecoveryState.COMPLETE,
)

enum class RecoveryState { COMPLETE, RECOVERED, PARTIAL }

sealed interface StageOutcome<out T> {
    data class Produced<T>(val result: StageResult<T>) : StageOutcome<T>
    data class Blocked(
        val diagnostics: List<AzoraDiagnostic>,
        val reason: BlockingReason,
    ) : StageOutcome<Nothing>
}
```

`Blocked` is a value: downstream stages requiring the missing result do not run.

### 10.3 Build and IDE modes

```kotlin
enum class AnalysisMode { BUILD, IDE }

data class AnalysisRequest(
    val sources: List<SourceUnit>,
    val roots: Set<SourceId>,
    val mode: AnalysisMode,
    val depth: AnalysisDepth,
    val target: Target?,
    val defines: Map<String, String>,
    val policy: DiagnosticPolicy,
    val cancellation: CancellationToken,
)

enum class AnalysisDepth {
    SYNTAX, SYMBOLS, TYPES, FULL_SEMANTIC, IR, BACKEND
}
```

Build mode may stop after fatal stage errors. IDE mode recovers more and usually
stops at full semantics. Both emit the same variants for the same root problem.

### 10.4 Recovery and cascade control

Lexer recovery tokens and parser `ErrorExpr`/`ErrorStmt`/`ErrorType` nodes carry
the root occurrence. Type analysis maps these to `ErrorType`; later passes do
not emit normal errors whose only cause is poison.

Recommended gating:

- lexing continues to a safe boundary;
- parsing synchronizes at newline, declaration boundary, or delimiter;
- symbols collect recovered declarations;
- type checking skips poison but checks unaffected declarations;
- effects/ownership run only on sufficiently typed bodies;
- IR/backends do not run for poisoned roots.

Per-file and global caps end with one “further diagnostics omitted” entry rather
than a flood.

### 10.5 Determinism

Diagnostics sort by URI, start, end, severity, code, then stable typed
discriminator. Deduplication uses code, spans, and typed facts—never message.

---

## 11. Compiler public API

```kotlin
data class AnalysisSnapshot(
    val id: AnalysisSnapshotId,
    val sources: SourceSetSnapshot,
    val program: Program?,
    val symbols: SymbolTable?,
    val semanticModel: SemanticModel?,
    val effects: List<EffectInfo>,
    val diagnostics: List<AzoraDiagnostic>,
    val completeness: AnalysisCompleteness,
)

sealed interface CompilationResult {
    val analysis: AnalysisSnapshot
    val diagnostics: List<AzoraDiagnostic>

    data class Success(
        override val analysis: AnalysisSnapshot,
        val artifacts: CompilationArtifacts,
        override val diagnostics: List<AzoraDiagnostic>,
    ) : CompilationResult

    data class Failure(
        override val analysis: AnalysisSnapshot,
        override val diagnostics: List<AzoraDiagnostic>,
    ) : CompilationResult
}

interface AzoraCompiler {
    fun analyze(request: AnalysisRequest): AnalysisSnapshot
    fun compile(request: CompilationRequest): CompilationResult
}
```

Success may include nonfatal diagnostics. Failure has a fatal diagnostic or an
explicit configuration/internal failure. Warnings-as-errors affects outcome,
not which list a string enters.

### 11.1 Tooling queries

`SemanticModel` exposes symbol-at-position, declaration/references, inferred
type, visible symbols, signatures, members/impls, documentation, semantic token
classification, and ownership/borrow state. AZLS reuses the snapshot instead of
reparsing separately for every completion, hover, and definition.

The tolerant lexical highlighter may remain for immediate paint, but semantic
answers come from this model.

### 11.2 Compatibility adapter

Migration may temporarily render structured diagnostics for old callers:

```kotlin
object LegacyDiagnosticRenderer {
    fun render(diagnostic: AzoraDiagnostic, sources: SourceManager): String
}
```

Reverse conversion is limited to marked
`LegacyUnstructuredDiagnostic(stage, severity, coarseSpan, message)`. It cannot
provide reliable fixes/candidates/related information. New diagnostics cannot
use it, and AZLS cannot regex-parse it.

---

## 12. Rendering

CLI example:

```text
error[AZ-OWN-0002]: `message` is no longer available
  --> src/main.az:8:7
   |
 5 | send(take message)
   |           ------- ownership was transferred here
...
 8 | print(message)
   |       ^^^^^^^ used here after transfer
   |
help: use `lend` if `send` returns ownership
help: clone explicitly if two independent values are intended
```

The renderer uses `SourceManager`, supports several files, and bounds excerpts.
ANSI is optional. Semantic tests assert subtype/code/data/spans; separate golden
tests cover English wording/layout. Published codes may link to documentation,
mapped by LSP to `codeDescription.href`.

---

## 13. AZLS architecture

### 13.1 Process model

Production is launched as:

```text
azls --stdio
```

The Kotlin launcher may run `java -jar azls.jar --stdio`. Stdin/stdout are
exclusively LSP JSON-RPC. Logs go to stderr or a log file. Protocol tests enforce
that no stray `println` corrupts stdout.

Default topology is one process per IntelliJ project/workspace, with LSP
workspace folders when needed. A shared daemon is out of scope.

### 13.2 Standard JSON LSP

LSP requires JSON-RPC 2.0 and `Content-Length` framing. JSON is therefore an
intentional interoperability exception even if Azora-owned formats prefer
AZON. The current framing can remain, but payloads become valid JSON-RPC:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {}
}
```

Responses use standard `result` or structured JSON-RPC `error`. Notifications
have no ID or response.

### 13.3 Components

```text
AzlsMain
  ProcessTransport
  JsonRpcDispatcher
  LspSession
    WorkspaceManager
      DocumentStore
      ProjectGraph
      LibraryIndex
      AnalysisScheduler
      SnapshotCache
    DiagnosticsService
    CodeActionService
    CompletionService
    HoverService
    DefinitionService
    SymbolService
    SemanticTokensService
    CompilerFacade
```

Protocol DTOs stay separate from compiler variants. This prevents Kotlin
serialization/class layout from becoming a public wire ABI.

### 13.4 Lifecycle

Mandatory methods/notifications are `initialize`, `initialized`, `shutdown`,
`exit`, `$/cancelRequest`, `didOpen`, `didChange`, `didClose`, configuration
changes, and advertised workspace-folder changes. Before initialize and after
shutdown, only lifecycle-valid traffic is accepted.

### 13.5 Initial capabilities

The first complete server advertises text sync, publish diagnostics, completion,
hover, definition, document symbols, semantic tokens or transitional tolerant
highlighting, code actions, and code-action resolve. Signature help, references,
rename, workspace symbols, formatting, hints, and hierarchy follow later.

### 13.6 No custom source/prelude requests

Clients do not call `diagnostics(source, prelude)`. They open/change documents.
AZLS constructs the project from open buffer overlays, unopened workspace files,
dependency roots, selected stdlib, and compiler configuration. Other-file
diagnostics publish to their own URIs rather than being dropped.

---

## 14. Workspace state, cancellation, and caching

### 14.1 Immutable snapshots

```kotlin
data class DocumentSnapshot(
    val uri: String,
    val version: Long,
    val text: String,
    val lineIndex: LineIndex,
    val contentHash: ContentHash,
)
```

Each analysis captures an immutable source set and never reads mutable editor
text during a pass. Open buffers shadow disk files.

### 14.2 Changes

Full synchronization is acceptable first. Incremental changes follow after
range-edit tests. Versions must increase. Invalid edits trigger controlled
resynchronization/reset, never corrupted offsets.

### 14.3 Scheduling

On edit AZLS installs the snapshot, cancels older work, runs syntax quickly,
debounces semantic analysis (roughly 100–200 ms configurable), and publishes
only if all relevant versions still match. Save or semantic query may request
immediate work.

Cancellation checkpoints exist between stages and inside long type/symbol
walks, fixed-point CTCE, macros, and ownership dataflow. Cancellation produces
no diagnostic.

### 14.4 Analysis tiers

AZLS may retain a fast syntax/symbol snapshot and a full semantic snapshot.
Completeness is explicit. It must not present an old semantic result as current
unless all dependencies relevant to the query are unchanged.

### 14.5 Dependency invalidation

The graph records module ownership, imports/re-exports, macro/generated
dependencies, public signatures, impl/associated-type dependencies, compiler and
stdlib version, defines, and target. Initial implementation may reanalyze the
workspace with cancellation; the graph provides a path to fine invalidation.

### 14.6 Cache keys

Keys include content hashes, compiler/stdlib version, target, feature flags,
defines, dependency signatures, analysis mode, and depth. No cache is keyed only
by a concatenated prelude hash.

### 14.7 Global compiler state

Current process-global resets such as parser fragment macros and `IrType.aliases`
are unsafe for concurrent requests. Move them into `CompilationSession`. Until
then, serialize compiler entry calls behind a narrow lock. This is a temporary
safety measure, not the final architecture.

---

## 15. LSP diagnostic mapping

| Compiler field | LSP field |
|---|---|
| primary span | `range` |
| severity | `severity` |
| code | `code` |
| documentation URL | `codeDescription.href` |
| rendered summary | `message` |
| unnecessary/deprecated | `tags` |
| related locations | `relatedInformation` |
| occurrence/version/stage/fix IDs | `data` |
| compiler identity | `source: "azora"` |

Ranges use negotiated encoding; UTF-16 is fallback. Versions accompany
diagnostics where supported.

Example:

```json
{
  "jsonrpc": "2.0",
  "method": "textDocument/publishDiagnostics",
  "params": {
    "uri": "file:///workspace/src/main.az",
    "version": 17,
    "diagnostics": [
      {
        "range": {
          "start": { "line": 7, "character": 6 },
          "end": { "line": 7, "character": 13 }
        },
        "severity": 1,
        "code": "AZ-OWN-0002",
        "source": "azora",
        "message": "`message` is unavailable after ownership was transferred",
        "relatedInformation": [
          {
            "location": {
              "uri": "file:///workspace/src/main.az",
              "range": {
                "start": { "line": 4, "character": 10 },
                "end": { "line": 4, "character": 22 }
              }
            },
            "message": "ownership was transferred here"
          }
        ],
        "data": {
          "occurrence": "d-17-4af83c",
          "stage": "ownership",
          "documentVersion": 17,
          "fixes": ["use-lend", "clone-before-take"]
        }
      }
    ]
  }
}
```

AZLS groups by URI and publishes empty lists when old diagnostics clear.
Generated URIs appear only if the client can open them; otherwise they map to a
user origin with a note.

---

## 16. LSP code actions

1. Client requests `textDocument/codeAction` with displayed diagnostics.
2. AZLS reads occurrence/fix IDs from diagnostic data.
3. It verifies the retained snapshot and document version.
4. Eager fixes map to `WorkspaceEdit`.
5. Expensive actions return unresolved server data.
6. Client calls `codeAction/resolve` when selected.
7. AZLS resolves against current state and validates every edit.

Local fixes map to `quickfix`, semantic rewrites to `refactor.rewrite`, and safe
batch cleanup to `source.fixAll.azora`. Only machine-applicable non-conflicting
fixes are preferred by default.

For missing imports, `UndefinedSymbol` supplies provider module and declaration
identity. The import organizer respects grouped imports, aliases, ordering, and
visibility; it does not append text derived from an error sentence.

For a missing `)`, the parser supplies an insertion span and expected token.
The edit is exposed only while that snapshot remains valid.

---

## 17. Other language features

### Completion

Completion uses visible symbols and tolerant syntax context from the retained
model. Items carry symbol IDs for resolve/documentation/import edits. Blanking
the current line remains only a transitional recovery fallback.

### Hover

Hover uses typed declaration metadata and parsed documentation, not line
scraping. The doc parser understands accepted Azora forms, including `///` and
supported documentation blocks. Hover range is the exact symbol.

### Definition

Definition returns real URI/range links for current source, workspace,
dependency, and stdlib. The current “external name with line zero” model goes.

### Document symbols

Symbols include declaration and selection spans and useful hierarchy.

### Highlighting and semantic tokens

The tolerant `AzHighlighter` may remain for half-written code and must share
compiler vocabulary. Long term, LSP semantic tokens add resolved functions,
types, fields, variants, parameters, macros, annotations, and modifiers. A
consistency test compares compiler/highlighter boundaries for valid syntax.
IntelliJ may keep local lightweight lexical paint, but no duplicate resolution.

### Debugging

The current interpreter-only polled debugger should not become ad hoc LSP
methods. Future editor debugging uses DAP through a separate process/endpoint.
Language diagnostics remain independent of debugging.

---

## 18. IntelliJ IDEA plugin contract

### 18.1 Responsibilities

The plugin locates a toolchain, launches one server, initializes LSP, sends
document/workspace lifecycle, displays diagnostics/related info/actions, maps
cancellation, captures stderr, restarts boundedly, reports incompatibility, and
stops the process with the project.

It does not load the compiler reflectively, concatenate a prelude, parse
diagnostic text, or maintain another symbol table.

### 18.2 Toolchain discovery

Recommended order:

1. explicit project toolchain;
2. IntelliJ Azora SDK;
3. environment/toolchain manager;
4. conventional user install such as `~/.azora/bin/azls`;
5. plugin-bundled compatible server only as explicit fallback.

The plugin passes root/config/toolchain metadata in initialize options. It does
not guess repository-relative stdlib paths.

### 18.3 Version handshake

`serverInfo`/initialize returns AZLS version, compiler version, diagnostic schema
version, language edition, stdlib constraints, and experimental capabilities.
Mismatch is one toolchain error, not a flood of undefined names.

### 18.4 Crash recovery

The plugin distinguishes clean shutdown, launch failure, incompatibility,
unexpected exit, and crash loop. Unexpected exits use bounded exponential
backoff; open documents replay after initialization. After a small retry limit,
it stops and shows logs/restart action.

### 18.5 UI

Diagnostics use exact editor ranges. Related locations navigate. Quick fixes
are previewable. Non-machine ownership guidance is shown as a choice, never as
a guaranteed automatic repair.

---

## 19. Safety and trust

Opening an untrusted project must not execute arbitrary behavior.

In `AnalysisMode.IDE`:

- no entry point or backend executable runs;
- no FFI is invoked;
- compile-time filesystem access is denied except defined read-only inputs;
- process, network, environment mutation, clock, and random access are denied;
- macro/CTCE recursion, steps, memory, and output are bounded;
- dependency roots and workspace-edit paths are validated.

Forbidden compile-time capability produces a CTCE diagnostic. Trusted builds
may use a separate explicit policy.

AZLS is local by default. Source is not placed in logs unless verbose logging is
explicitly enabled. No telemetry/upload is implied. AZLS returns edits; the
editor owns confirmation/application, especially outside the workspace.

---

## 20. Reliability and resources

Separate boundaries handle malformed JSON-RPC, invalid parameters,
cancellation, user diagnostics, compiler internal failures, EOF, and resource
exhaustion. Malformed requests return JSON-RPC errors without process death.
Compiler panics invalidate one snapshot and log a crash ID while stdout stays
valid.

Configurable limits cover document size, diagnostics per file/workspace, macro
depth/output, CTCE steps/memory, ownership iterations, retained snapshots,
concurrent analysis, and log rotation. Semantic limit breaches produce specific
diagnostics; operational limits may degrade to syntax-only service.

Protocol handling stays responsive while analysis runs. Read-only requests can
share immutable snapshots. New workspace snapshots install atomically.
Concurrent compiler calls wait until global state is session-local.

---

## 21. Packaging and self-hosting

The Kotlin phase may keep a self-contained `azls.jar`, fronted by a stable
platform launcher. It includes compiler analysis core and LSP serialization but
excludes LLVM native/backend-only dependencies unless required.

The installed toolchain selects stdlib; tests inject explicit roots. The package
contains version/license metadata and optionally a diagnostic docs index.

A future self-hosted server may be native. Because IntelliJ speaks LSP and no
Kotlin types cross the process boundary, replacement requires no plugin redesign.

---

## 22. Performance expectations

Measure cold startup, warm edit, signature edit, completion, hover, action
resolve, and project invalidation. Required properties:

- syntax feedback begins promptly;
- cancellation is observed within bounded checkpoints;
- stale diagnostics never publish;
- idle analysis settles without repeated compilation;
- repeated queries on unchanged snapshots do not recompile;
- stdlib indexes survive edits;
- cache retention bounds memory.

Concrete latency budgets should be set from representative workspace benchmarks
rather than guessed in this DIP.

---

## 23. Testing strategy

### 23.1 Compiler diagnostics

Each variant tests subtype/code, severity/stage/suppression, primary span,
related labels, typed fields, notes/suggestions, fixes/applicability,
warnings-as-errors, ordering, and deduplication. Meaning is not tested only by
whole English strings.

### 23.2 Positions and edits

Property/example tests round-trip offsets and UTF-16/8/32 positions across CRLF,
Unicode, empty ranges, EOF, and multi-line spans. Machine fixes must apply to
their exact snapshot and match expected text.

### 23.3 Recovery

Malformed programs verify no expected lexer/parser exceptions, unaffected later
diagnostics, poison cascade control, caps, and removal after root repair.

### 23.4 Ownership

Test straight-line code, branches, loops, early returns, errors, closures,
fields, indexes, tuple projections, take/lend, shared/exclusive loans, await,
destruction, one-shot broadcast, and interprocedural return contracts. Assert
both violating and causal locations.

### 23.5 Rendering

Golden tests cover CLI layout, files, generated provenance, Unicode, color,
terminal width, and elision. These are separate from semantic variant tests.

### 23.6 Process/LSP

Launch real `azls --stdio` and test framing, initialize/shutdown, sync, publish
and clear, ranges, related info, cancellation, stale versions, multiple files,
actions/resolve, malformed/unknown requests, stdout cleanliness, and recovery.

### 23.7 IntelliJ

Launch packaged AZLS and test discovery, mismatch UI, diagnostics/navigation,
action preview/apply, document replay after restart, and project-close cleanup.

### 23.8 Cross-surface contract

Given identical workspace/config, CLI and AZLS agree on code, span, severity,
and related locations. Presentation may differ.

### 23.9 Existing AZLS tests

Behavior cases remain useful but migrate to LSP. Tests tied to prelude, line-only
DTOs, reflection, repository-relative stdlib, or external-definition-by-name are
replaced with tests of the new contract.

---

## 24. Migration plan

### Phase 0: freeze contracts

- create code registry and common packages;
- define spans, severity, suggestion, and fix semantics;
- test code uniqueness;
- forbid new string diagnostics and document the legacy adapter.

Exit: new compiler code has one approved structured route.

### Phase 1: source infrastructure

- add source IDs/units/spans/manager;
- add token offsets and propagate AST spans;
- implement line indexes/encoding conversion;
- add generated provenance.

Exit: frontend tests assert exact spans without message parsing.

### Phase 2: lexer/parser

- convert expected lexer exceptions to variants/recovery tokens;
- convert parser user errors while retaining true invariant exceptions;
- add poison nodes/cascade control;
- add safe delimiter fixes.

Exit: malformed source does not escape frontend as
`IllegalArgumentException`/`IllegalStateException`.

### Phase 3: modules/validation

- convert library loading, imports, cycles, visibility, AST validation;
- represent source units/module graph directly;
- remove prelude concatenation;
- replace regex import hints with provider metadata.

Exit: module diagnostics use real URI and cycles have related edges.

### Phase 4: semantic/types

- convert symbols/import resolution/type resolution;
- convert decorators, derives, macros, CTCE, effects;
- introduce `ErrorType` propagation;
- expose `SemanticModel`;
- remove warning prefixes.

Exit: `SemanticResult` has no `List<String>`.

### Phase 5: ownership/borrowing

- separate unused-name liveness as needed;
- implement place/loan identity and control-flow origins;
- implement section 8 catalogue;
- add carefully classified actions.

Exit: every ownership error explains current and causal locations.

### Phase 6: compiler outcomes/renderers

- replace `CompilationResult` string lists;
- add `analyze` separate from `compile`;
- add CLI/compatibility rendering;
- structure backend failures;
- sessionize global state.

Exit: public compiler APIs expose no diagnostic strings as data.

### Phase 7: real LSP

- make `AzlsStdio` JSON-RPC 2.0/LSP;
- implement document/workspace stores;
- publish versioned structured diagnostics;
- migrate completion/hover/definition/symbols/highlighting;
- add cancellation/snapshots;
- keep logs off stdout.

Exit: a generic LSP client works without an adapter.

### Phase 8: actions and IntelliJ

- implement action/resolve mapping;
- make plugin launch/supervise AZLS;
- remove reflective loading and source/prelude calls;
- add restart/version handling.

Exit: plugin parses no compiler message and has no semantic copy.

### Phase 9: incremental hardening

- add dependency invalidation and safe concurrency;
- add cache eviction/resource limits;
- enforce untrusted CTCE/macro capabilities;
- benchmark/tune;
- expand standard LSP features.

Exit: representative workspaces meet published resource budgets.

### 24.1 Removal gates

Remove after callers migrate:

- string `CompilationResult` errors/warnings;
- semantic `errors: List<String>`;
- warning-prefix classification;
- AZLS `lineOf`/`cleanMessage`;
- regex `withLibraryHint`;
- line-only AZLS diagnostic DTO;
- prelude concatenation/line subtraction;
- reflective string APIs;
- bespoke stdio methods.

---

## 25. Acceptance criteria

1. Expected production compiler errors are variants or explicitly temporary
   legacy wrappers.
2. AZLS production code never regex-parses compiler messages.
3. No warning is classified by prefix.
4. Diagnostics point into any workspace/dependency source.
5. Ownership/borrow conflicts carry causal locations.
6. Generated failures map to author-visible origin.
7. At least parser, missing-import, and ownership-guidance actions work through
   standard LSP.
8. Stale diagnostics/fixes are rejected by version.
9. Process tests pass lifecycle, sync, diagnostics, cancellation, shutdown, and
   framing.
10. IntelliJ uses process LSP rather than reflection.
11. Untrusted projects cannot execute unrestricted CTCE/FFI/process/network.
12. CLI and AZLS agree on codes and spans.
13. Global mutable compiler state cannot leak across analyses.
14. Prelude/line-subtraction diagnostics are gone.

---

## 26. Rejected alternatives

**Keep strings with a stricter format:** still loses relationships, provenance,
typed candidates, and safe edits.

**Define diagnostics only in AZLS:** duplicates or weakens CLI/build behavior;
the discovering pass owns the facts.

**Serialize Kotlin variants to the plugin:** exposes Kotlin ABI and blocks easy
self-hosted replacement.

**Keep reflective production loading:** does not isolate CPU/memory/crashes and
prevents standard editor interoperability. In-process adapters are test-only.

**Keep textual prelude:** loses source identity and produces invalid cross-file
locations/edits.

**Use AZON for LSP:** no longer standard LSP; every editor would need an adapter.

**Automatically insert clone:** changes identity/performance/behavior and must be
an explicit author choice.

**Publish without versions:** creates stale ranges, flicker, and unsafe edits.

---

## 27. Open implementation choices

- existing Kotlin LSP library versus a small audited dispatcher;
- exact persistent collection types;
- pull diagnostics after push stabilizes;
- amount of virtual generated source exposed;
- initial invalidation granularity;
- platform toolchain layout;
- full versus delta semantic tokens first;
- local versus online diagnostic documentation.

None may reintroduce message parsing, source concatenation, stale edits, or a
reflective production boundary.

---

## 28. Recommended first vertical slice

1. Add source IDs and token spans.
2. Implement `UnexpectedCharacter`, `UnterminatedString`, `UnexpectedToken`,
   `MissingToken`, `UndefinedSymbol`, and `TypeMismatch` variants.
3. Return them from an analysis-only compiler entry point.
4. Implement LSP initialize, document sync, and publish diagnostics.
5. Launch it from a minimal IntelliJ client.
6. Add a missing-delimiter action.
7. Verify cancellation and stale-version suppression.
8. Wrap remaining diagnostics explicitly as transitional legacy values.

This proves source identity, variants, transport, plugin lifecycle, and a real
fix before the wider conversion. Ownership and borrowing then use proven
infrastructure.

---

## 29. Final rule

> The compiler reports facts; AZLS schedules, validates, and translates them;
> the editor presents them. No layer guesses what another layer meant.
