# Azora Language Server (`azls`) - DIP Implementation Profile

> Status: **implemented**, and 16 of its 42 tests fail. The failures are not
> flakes: the server points at a standard-library layout that no longer exists
> (§6.1), and its hover reads a comment style the standard library does not use
> (§6.2).

`azls` gives an editor language intelligence for `.az` sources. It is not a
separate parser: it calls the real compiler, so a diagnostic in the editor is
the diagnostic the build produces.

---

## 1. How it is loaded

Packaged as a self-contained `azls.jar`. Azora Studio loads it through a
`URLClassLoader` and calls it **reflectively**, so every method takes and
returns plain `String`s and no type crosses the classloader boundary.

That constraint explains the shape of the whole API: it is a set of
string-in / string-out functions rather than an object model.

- ⬜ The wire format is **JSON**. Every other structured format in the project
  is AZON - JSON came out of the serializer for exactly this reason. A language
  server that speaks a format the language does not ship is the same
  inconsistency as a package manager that reads TOML

---

## 2. Capabilities

| method        | gives                                                      |
|---------------|------------------------------------------------------------|
| `highlight`   | colorizer spans                                            |
| `diagnostics` | compiler errors and warnings                               |
| `complete`    | keywords, builtins, user symbols, locals, fields, variants |
| `hover`       | a signature, and a doc comment                             |
| `definition`  | where a name is declared                                   |
| `symbols`     | a document outline of top-level declarations               |
| `version`     | the server's version                                       |

Plus a debug session (§4).

### 2.1 The `prelude`

Every query takes an optional `prelude`: the rest of the compilation unit -
other project files, installed libraries - so cross-file symbols resolve.
Diagnostics are then mapped **back** to the edited document's line numbers, and
an error that lands inside the prelude is dropped rather than reported at a line
the author cannot see.

The prelude index is memoized on its hash, because Studio passes the same
prelude on every keystroke.

---

## 3. Highlighting is deliberately not the compiler's lexer

`AzHighlighter` is a second, independent scanner. The reason is in its own doc:
an editor colorizer must survive half-typed code - unterminated strings, stray
characters - and must classify **comments**, which the compiler's lexer discards.
It never throws; an unrecognised character is skipped.

It does share the one thing that matters: the keyword set comes from
`AzoraSyntaxVocabulary`, so a keyword added to the language colours correctly
without touching the highlighter.

Span kinds: `keyword`, `string`, `interpolation-punctuation`, `number`,
`comment`, `function`, `variable`, `parameter`, `type`, `annotation`, `macro`,
`char`.

- ⬜ Two scanners means two chances to disagree. Nothing checks that the
  highlighter and the lexer tokenize the same source the same way

---

## 4. Debugging

A single session, polled from the editor:

| call             | does                             |
|------------------|----------------------------------|
| `start`          | begins a run                     |
| `setBreakpoints` | 1-based document lines           |
| `resume`         | continue from a pause            |
| `step`           | advance one statement            |
| `stop`           | terminate                        |
| `drainOutput`    | collect what the program printed |

The interpreter drives it: `DebugInstrumenter` marks statements in debug builds
with `__dbg(line)`, and the session pauses on marked lines. Locals are reported
as name/value strings at each pause.

- ⬜ One session at a time, polled rather than event-driven
- ⬜ Debugging is interpreter-only. There is no native debugging, and the
  roadmap's DWARF item is what would give it

---

## 5. Test state

42 tests. **26 pass, 16 fail.** The passing set covers highlighting, completion,
hover, definition, outline and diagnostics; the failures cluster into two
causes, both real defects rather than test problems.

---

## 6. Why the 16 fail

### 6.1 It points at a standard library that does not exist

```
java.io.FileNotFoundException: ../Internal/Std/Math/Math.az
```

`StdlibParseTest` reads `../Internal/Std`. The standard library is `std/`, and
has been for some time. Everything that depends on stdlib symbols resolving
fails with it:

- `completesStdlibFunctions` - *"stdlib abs should complete: []"*
- `stdlibConstantsComplete` - *"PI should complete: []"*
- `completesGroupedStdlibImports`, `completesSelectiveStdlibImportFromContainingModule`
- `libraryModuleSectionsAreImportGated`

Five capability tests reporting an empty completion list, because the index
behind them is empty.

- ⬜ Point the server at `std/`, or at the installed library tree the roadmap's
  §4.3 describes

### 6.2 Hover reads a comment style the standard library does not use

`docCommentAbove` scrapes the lines above a declaration and accepts `///` and
`//`. It does **not** understand `/** … */`.

`std/math.az` contains **65** `/** */` blocks and **zero** `///` lines. The same
holds across the standard library.

So hovering a standard-library symbol shows a signature and no documentation -
every doc comment the standard library carries is invisible to the editor. The
one hover-doc test passes because it is written with `///`.

- ⬜ Teach `docCommentAbove` the `/** */` form, which is what the codebase
  actually writes

### 6.3 The rest

- `cleanProgramHasNoDiagnostics` and `preludeSymbolsResolve` report
  *"undefined function 'println' - add 'use std.io'"*: the server still assumes
  the `use` import model, which the language replaced with `import`
- Five debug-session tests time out for the same reason - the program under
  debug does not compile
- `definitionReportsExternalSymbolByName` throws a `NullPointerException`
- `highlightsInterpolationInsideStrings` and
  `importedFunctionsAreHighlightedButUnknownCallsAreNot` fail on highlighting

---

## 7. What is missing

- ⬜ **Go-to-definition across files.** `definition` finds declarations in the
  current file and reports an external one by name only
- ⬜ **No rename, no find-references.** Both are the refactoring floor
- ⬜ **No formatter**, so no format-on-save (and `azora fmt` does not exist)
- ⬜ **No code actions**, so a diagnostic that names its fix cannot apply it -
  which is a waste, because the compiler's diagnostics are unusually good at
  naming fixes
- ⬜ **No signature help**, no inlay hints, no semantic-token protocol
- ⬜ **Not an LSP server.** It is a reflective jar with a bespoke interface, so
  every editor that is not Azora Studio needs an adapter. Whether to speak LSP
  is the open question - it would make VS Code, Neovim and JetBrains work with
  no per-editor work
- ⬜ **No incremental analysis.** Every query recompiles; §8.1's incremental
  work is what would fix the latency

---

## 8. Rules

1. `azls` calls the real compiler. An editor diagnostic is a build diagnostic.
2. Every method is string-in, string-out, because Studio calls it reflectively
   across a classloader boundary.
3. The highlighter is a separate error-tolerant scanner, and takes its keyword
   set from the compiler's vocabulary.
4. The `prelude` carries the rest of the compilation unit; diagnostics inside it
   are dropped rather than misreported.
5. Debugging runs on the interpreter, driven by `__dbg` markers in debug builds.

---

## 9. Related

- `TESTING_TRACING_DIP.MD` - `trace`, and the debug output the session drains
- `COMPILER_INTRINSIC_DIP.MD` §3.5 - `__dbg`
- `ROADMAPs/VERSION_0_1_ROADMAP.MD` §7.2, §7.3, §8.1
