# Azora Stability - DIP Implementation Profile

> Status: **policy decided** (roadmap A6, 2026-11-07); its tooling is not built.
> The decorators of §3 parse and are validated (`AstValidator`), but a use of a
> deprecated or experimental declaration is not yet reported, the public
> surface is not yet enumerated (§2), and there is no compatibility suite job
> (§5). Each is a 0.1 roadmap §11 item.

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
is visible in review. *Not built yet.*

## 3. Marking declarations

Compiler-provided decorators (ANNOTATIONS_DIP §7):

| Decorator | Meaning |
|---|---|
| `@Stable(since: "0.1")` | Covered by §1 from that version. |
| `@Experimental(since: "0.1")` | May change or go in any release. A use outside std is reported as a warning naming it. |
| `@Deprecated(since: "0.2", replacement: "…")` | Still works; each use is a warning that names the replacement. |
| `@Since("0.1")` | The version a declaration appeared in, for one that is neither of the first two. |

A declaration cannot be both `@Stable` and `@Experimental`, and `@Since` is not
repeated next to a `since:` argument; both are compile errors today.

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
