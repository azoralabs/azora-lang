# Azora Stability - DIP Implementation Profile

> Status: **policy decided** (roadmap A6, 2026-11-07). **Implemented**
> (2026-11-14): the decorators of §3 are validated where written and each use
> of a deprecated declaration is reported (§3.1); experimental uses are
> reported when asked for (§3.2, owner's decision); std's stable surface is
> generated and checked in (§2.1). **Not built:** the compatibility suite's
> release job (§5) and the release process (§6), 0.1 roadmap §11 items.

What a program written for Azora 0.1 can rely on, how that changes, and what
"stable" means for a declaration. The point is that upgrading the compiler is
never a gamble: either the program still means what it meant, or the compiler
says, by name, what changed and what to write instead.

---

## 1. Versions

Azora's version is `MAJOR.MINOR.PATCH`, for the language, the compiler, the
standard library and AZLS together: they ship as one, and std's
`package.version` must match the compiler's (the handshake the 0.1 roadmap
§7.2 describes).

- **A patch release** (`0.1.0` → `0.1.1`) never changes what a correct
  program means or whether it compiles. It fixes bugs, adds diagnostics for
  code that was already wrong, and may add declarations marked
  `@Experimental`.
- **A minor release before 1.0** (`0.1` → `0.2`) may change the language and
  std, but only through §4: a stable declaration or rule is deprecated first,
  with a warning that names the replacement.
- **1.0** makes the minor rule what the patch rule is: nothing stable breaks
  within `1.x`.

Two kinds of change are allowed in any release, because keeping them would be
worse: **a miscompile is fixed** (a program that printed the wrong answer
prints the right one), and **a soundness hole is closed** (a program that the
ownership or type rules should have rejected is rejected). Each one is listed
in the release notes with the diagnostic it produces.

## 2. The stable surface

The surface 0.1 promises is:

1. the language as the DIPs specify it, where their status says
   *implemented*; a section marked partial or pending is not promised until it
   is done;
2. the declarations of `std` that are visible outside their module and not
   marked `@Experimental`;
3. the command line: `azora run`, `check`, `test`, `compile`, `new`, `build`,
   `add` and `remove` with their documented options, and the `azora.azon` and
   `azora.lock` formats (PACKAGES_DIP.MD);
4. the text of diagnostics is **not** promised - their wording improves - but
   whether a program compiles is.

Not promised: the IR text, the LLVM IR and WAT the compiler emits, the
interpreter's internals, the layout of a type in memory (until the ABI decision,
0.1 roadmap §12.4), anything named `__…`, and AZLS's wire protocol beyond LSP.

The surface is enumerated by the compiler: a listing of every stable `std`
declaration with its signature, checked into the repository, so a change to it
is visible in review (§2.1).

### 2.1 The listing

`std/STABLE_SURFACE.txt` is generated from std's sources as parsed
(`StdSurface`), module by module in path order. It lists every declaration
visible outside its module - not `_private`, not `__internal`, not narrowed by
a visibility modifier, not in a `confined` module or a test scope - and not
`@Experimental`:

- functions and bridge functions with their type parameters, parameters
  (borrows, defaults, variadics), result, errors and `where` clause;
- packs with their visible fields, enums and error sets with their cases,
  variant types with their payloads, type aliases, decorators;
- specs with their members, and `impl` blocks with their visible members - a
  conformance (`impl Hash for Int`) is listed even with no member of its own,
  since it is what lets a value be used where the spec is asked for;
- each declaration's `@Since`, `@Stable` and `@Deprecated`, as written.

A member is left out with its type: an `impl` of an experimental type or spec,
and a member lifted from one, is experimental too. A default value is written
when it is a literal or a simple expression, and as `…` otherwise - that a
default exists is part of the surface even where its value is not shown.

A stable declaration may not name an experimental type in its signature - a
stable `sharedOf` handing out an experimental `Shared<T>` promises only until
`Shared` changes. `StdSurfaceTest` fails on each such contradiction, so the
type is made stable too or the declaration experimental.

`StdSurfaceTest` also regenerates the listing and fails, with the lines added
and removed, when it differs from the checked-in file. An intended change is
committed with the regenerated file:

```sh
AZORA_REGENERATE_SURFACE=1 ./gradlew :compiler:desktopTest --tests '*StdSurfaceTest*'
```

## 3. Marking declarations

Compiler-provided decorators (ANNOTATIONS_DIP §7):

| Decorator | Meaning |
|---|---|
| `@Stable(since: "0.1")` | Covered by §1 from that version. |
| `@Experimental(since: "0.1")` | May change or go in any release. A use outside std is reported as a warning naming it. |
| `@Deprecated(since: "0.2", replacement: "…")` | Still works; each use is a warning that names the replacement. |
| `@Since("0.1")` | The version a declaration appeared in, for one that is neither of the first two. |

A declaration cannot be both `@Stable` and `@Experimental`, and `@Since` is not
repeated next to a `since:` argument; both are compile errors.

A decorator written on an `impl` block applies to each member of the block that
does not say otherwise: a member's own `@Stable` or `@Since` makes it not
experimental, and nothing makes a member of a deprecated block not deprecated.
A member's own decorator does not extend to the type it is a member of, nor the
type's to its members: a use of the type is reported where the type is named.

### 3.1 Reporting a use

Each use of a `@Deprecated` declaration in the compiled unit's own code is a
warning naming it, the version it was deprecated in and its replacement:

```text
warning: line 6: 'stale' is deprecated since 0.1 - use 'fresh' instead
```

A use is any of:

| Written as | Declaration |
|---|---|
| `f(x)`, `f` as a value, `Type::make()` | a function, a bridge function, a member lifted to its type |
| `T(…)` | the type, and the `ctor` the construction calls |
| `.(…)` where a `T` is expected | the `ctor` the construction calls (the type is reported where it is named) |
| `x: T`, `as T`, `is T`, a type argument, `?! E`, `impl S for T` | a type, spec, alias or error set |
| `Color.Red`, `.Red` where a `Color` is expected | a case |
| `value.member(…)`, `value.prop`, `value.field` | an `impl` member, a property, a stored field |
| `shape.area()` where `shape` is spec-typed | the member the spec declares |
| `limit`, `limit = …` | a top-level value |
| `@Name` | a decorator |

A call that compile-time evaluation folds into its value is still reported: a
use written by name is read from the program as written, before evaluation; a
use through a value is reported by type resolution, the only stage that knows
the value's type. A local binding that shadows a declaration is not a use of it.

Not reported:

- a use inside a declaration that is itself deprecated (or, for §3.2,
  experimental), or inside its `impl` block or the type that block implements;
- a use inside a declaration marked `@Supress(kind: .Deprecated)` (or
  `.Experimental`), or anywhere in a unit whose `module` header carries it;
- code the compiler generated - a derivation's members, a spec's provided
  member copied into an implementation, a macro's expansion - and code of
  another module: each is reported when that module is the one compiled.
- uses the language makes implicitly rather than by name: an operator
  (`a + b` calling a deprecated `oper+`), indexing, iteration, string
  templates calling `Display`, and conversions with `as`. *Open.*

A compilation with `warningsAsErrors` refuses a deprecated use like any other
warning. `azora check` prints warnings to stderr.

### 3.2 Experimental uses are reported when asked for

Decided by the owner (2026-11-14): most of std is `@Experimental` in 0.1
(741 declarations, among them `ArrayList`, `List`, `listOf` and the maps), so a
warning on every use would put warnings on nearly every program. An
experimental use is reported only when asked for - `azora check --experimental`,
`Compiler.compile(reportExperimental = true)`, or `DiagnosticPolicy.reportExperimental`
for tooling - and never inside std itself:

```text
warning: line 9: 'ArrayList' is experimental (since 0.1) and may change or be removed in any release
```

## 4. Deprecation

Something stable is removed in three steps, each in a release of its own:

1. **Warn.** It is marked `@Deprecated` with its replacement, in a patch or
   minor release. Code that uses it still compiles and means the same.
2. **Refuse.** In the next minor release, a use is an error that names the
   replacement.
3. **Remove.** In the minor release after that, it is gone.

A language rule changes the same way: the old form is accepted with a warning,
then rejected with a diagnostic that shows the new form, then the diagnostic is
retired. Where the change can be made mechanically, the warning comes with an
AZLS code action that makes it.

## 5. The compatibility suite

`0.1.x` releases must keep passing, unchanged:

- the compiler's test suite as it stood at `0.1.0`, with native tools required
  (`AZORA_REQUIRE_NATIVE_TESTS=1`);
- `azora test std` and every std module checking;
- the Engine's qualification probes (`tests/qualify-native.sh`) and their
  sanitizer runs.

A test in that suite is changed between patch releases only to fix a test that
was itself wrong, and the change is named in the release notes.

## 6. Releases

A release is tagged, its notes list every change in the categories of §1
(fixes, newly rejected programs, deprecations, experimental additions), and its
artefacts - the CLI, std, AZLS - are built from the tag. Signing and
distribution belong to 0.1 roadmap §11's release-process item.
