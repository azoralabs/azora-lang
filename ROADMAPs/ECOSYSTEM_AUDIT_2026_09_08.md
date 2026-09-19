# Ecosystem inspection — 2026-09-08

This is a measured starting point and one completed parser repair, not a
production-readiness assessment. Language, Engine, and Studio are separate
repositories. Existing local edits were present in all three and were preserved.

Tests and DIPs are evidence, not authoritative specifications. A failing test
must be checked against current intent and the implementation before changing
either. In particular, syntax migrations can invalidate both source fixtures
and assertions about internal AST nodes.

## Completed repair: nested generic delimiters

`Parser` split `>>` into a consumed token and a pending `>`, but declaration
parameter lists did not consume the pending delimiter. Consequently,
`func<T, I: Indexed<T>> findIndex(...)` failed to parse; an extra `>` could make
the malformed declaration pass instead.

The pending delimiter was also ignored when reading commas and type suffixes.
That attached an outer argument to an inner type in
`Pair<Box<Box<Int>>, Bool>`, and attached `?` at the wrong level in
`Box<Box<Int>>?`.

The repair makes generic lists honor pending closers before consuming outer
commas, shares the generic close handling, and prevents inner types from taking
outer suffixes or callable receiver syntax. Declaration lists still reject extra
closers. Ordinary `>>` expressions retain their shift operator.

`GenericDelimiterTest` covers nested bounds, defaults at several depths, argument
nesting, nullable suffixes, generic calls, missing/extra delimiters, and shifts.
It also checks the nested argument structure delivered to semantic symbol
collection. This does **not** implement conformance-bound enforcement: the
existing `parseTypeParams` still discards non-const inline bounds, which remains
a separate correctness issue.

## Measured validation

Commands run from `azora-lang`, using the local Gradle cache:

```sh
./gradlew :compiler:desktopTest --offline --console=plain
./gradlew :compiler:desktopTest --offline --console=plain --tests 'org.azora.lang.frontend.*'
./gradlew :compiler:desktopTest --offline --console=plain --tests '*GenericDelimiterTest' --tests '*StdlibParseDiagnosticTest'
```

- Initial full run: **2,204 tests, 1,749 failures**. Many fail during standard
  library loading before reaching the behavior their test names describe. These
  numbers do not represent 1,749 independently established implementation bugs.
- Frontend comparison with the same nine new regressions: original parser
  **337 tests / 28 failures**; repaired parser **337 tests / 20 failures**.
  No previously passing frontend test became a failure. The nine new regressions
  pass; eight exposed failures in the original parser.
- Standard library source parsing still fails in **26 files**. In
  `std/algorithm/search.az`, the first failure moved from the generic declaration
  at line 130 to `arr.size>..0` at line 147. The repair does not make the full
  standard library load successfully.
- The remaining frontend failures include old assertion message syntax, old
  import selector syntax, removed bracketed receivers, and expectations about
  increment AST representation. They require individual review, not automatic
  restoration of the behavior the tests demand.

## Integration observations and next work

1. **Reconcile standard library source with current grammar and semantics.**
   `StdlibParseDiagnosticTest` lists the per-file failures. Many use the removed
   assertion form `assert condition { message }`; the current parser and
   `DIPs/CONTRACTS_ASSERTION_DIP.MD` specify `assert condition panic message`.
   Other failures involve control-flow bodies, lifecycle bodies, range spelling,
   and the now-reserved `then` identifier. Do not mechanically rewrite all errors:
   some may be parser defects or incomplete design migrations. Review stale
   tests alongside the source migration, then rerun semantic and backend tests.

2. **Reproduce and close ownership/type-safety gaps independently of stdlib loading.**
   `TypeResolver` registers shared parameters with `mutable = false` while
   `VariableSymbol.valueMutable` defaults to true. Shared receiver writes also
   have a special direct-`self` check. This is an inspection finding requiring
   focused semantic reproducers, including nested fields, indices, reborrows,
   method calls, and shadowing. No ownership fix is included here. Inline generic
   bounds being discarded also needs an end-to-end semantic repair.

3. **Validate real Engine packages against the repaired compiler.**
   `azora-engine/packages/azora-ecs/src/ecs.az` still has bracketed receiver
   declarations such as `func spawn[self: Self&]`, and mutating world operations
   declare shared receivers. Resolve the access contracts, rather than just
   translating their syntax. Exercise entity reuse, parent lifetimes, storage
   removal, query conflicts, and schedule dependencies before claiming ECS
   safety. Engine build/run validation was not performed in this pass.

4. **Keep Studio on compiler-owned language intelligence.**
   Studio's `JarAzoraLanguageIntel` already loads AZLS; AZLS depends on the
   compiler and uses `Compiler.analyze`. The node editor separately vendors four
   frontend files through `:azora-sdk:nodes:domain:syncAzoraLangFrontend`.
   Its parser differs from Language HEAD and has pre-existing local edits, so
   blindly copying the repaired parser would be inappropriate. Reconcile that
   snapshot and validate node round trips and AZLS diagnostics after establishing
   a usable compiler/stdlib baseline. Studio build/run validation and installed
   AZLS replacement were not performed in this pass.

The README and version roadmap test counts are historical; use current reports
and reviewed failure classifications for subsequent decisions.
