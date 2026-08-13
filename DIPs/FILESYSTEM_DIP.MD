# Azora Filesystem - DIP Implementation Profile

> Status: **implemented, except the `/` operator and the LLVM backend.**
> `std/filesystem.az` exists and is covered by `FilesystemStdlibTest` (15 tests).
> The path algebra, reads, writes, directories, metadata and temporaries all
> work under `azora run`. `oper /` is still blocked by §10.1, so [Path.join] is
> the spelling; §12 step 8 (the native path) is not done.

Azora can already touch the disk. It does so through `engine.io`, in the game
engine, over C stdio:

```azora
fin blob = readFile(path)      // an empty Blob when the file is not there
if !blob.ok() { … }// "empty" and "missing" are the same answer
writeTextFile(path, text)      // Bool: it worked, or something went wrong
```

Two things are wrong with that, and they are the reasons this module exists.

**A missing file, an unreadable file and an empty file are one value.** `Blob`
reports `ok()` false for all three. A tool that must tell the user *why* it
could not open something has nothing to tell them. Azora has error unions
(`ERROR_HANDLING_DIP.MD`) precisely so this does not have to be guessed.

**It is in the engine.** A compiler, a language server, a build tool and a test
runner all read files, and none of them should depend on a renderer to do it.

---

## 1. Why compiler bridges, not `bridge .C`

`std.filesystem` is declared as compiler bridges - `bridge func` implemented by
each backend and by the interpreter - not as `bridge .C` declarations over
`fopen`/`stat`.

This is not a preference. `azora run` executes through `IrInterpreter`, which
has no FFI, and a C declaration there compiles and then fails at the call:

```
Exception in thread "main" java.lang.IllegalStateException: Undefined function: getenv
	at org.azora.lang.backend.IrInterpreter.evalCall
```

An API that type-checks and then dies under the command people reach for first
is not usable. `std.os` settled this already (`VERSION_0_1_ROADMAP` §4.4); the
filesystem follows it, and shares the same `expect`/`actual` platform layer.

---

## 2. Paths are values, not strings

```azora
fin root = std::path("/usr/local")
fin bin  = root / "bin" / "azora"

bin.text            // "/usr/local/bin/azora"
bin.fileName        // "azora"
bin.extension       // ""
bin.parent          // Path("/usr/local/bin")
bin.isAbsolute      // true
```

`Path` is a pack over a `String`. `oper /` joins one segment, inserting exactly
one separator however many the operands carry - which is the bug that string
concatenation reintroduces in every program that does it by hand.

> `oper /` does not work across a realm boundary today (§10.1). `Path` lives in
> `realm std` and its callers do not, so this spelling is blocked until that is
> fixed. `join` (§2.1) is the same operation under a name that does work, and is
> what the first implementation should ship.

The path algebra is **pure**. `parent`, `fileName`, `extension`, `normalized`
and `/` compute on the text and never touch the disk, so they need no error
type, work on paths that do not exist, and can run at compile time.

This follows `std.time`, which models `Duration` and `Instant` as packs rather
than passing raw integers around. The cost is honest: every boundary with a
platform API converts, and `Path::of` / `.text` will appear at those seams.
Taking `String` everywhere would remove that friction and reintroduce separator
bugs, which is the trade this decides against.

### 2.1 Path operations

| member          | is            | example                              |
|-----------------|---------------|--------------------------------------|
| `text`          | `String`      | the path as written                  |
| `join`          | `Path`        | `path("a").join("b")` → `a/b`        |
| `/`             | `Path`        | `path("a") / "b"` → `a/b` (§10.1)    |
| `parent`        | `Path`        | `a/b/c` → `a/b`; `a` → `.`           |
| `fileName`      | `String`      | `a/b/c.az` → `c.az`                  |
| `stem`          | `String`      | `a/b/c.az` → `c`                     |
| `extension`     | `String`      | `a/b/c.az` → `az`; none → `""`       |
| `isAbsolute`    | `Bool`        | begins at a root                     |
| `normalized`    | `Path`        | resolves `.` and `..` textually      |
| `withExtension` | `Path`        | `c.az` → `c.ll`                      |

`normalized` is textual on purpose: resolving `..` through symlinks requires the
disk, gives a different answer, and belongs to §5's `canonical`.

---

## 3. Reading and writing

```azora
fin source = try std::readText(path)                 // String ?! FileError
fin bytes  = try std::readBytes(path)                // Array<Byte> ?! FileError
try std::writeText(path, source)                     // Unit ?! FileError
try std::writeBytes(path, bytes)
try std::appendText(path, line)
```

Every one of these is failable. There is no variant that returns an empty string
on failure, because that variant is what `engine.io` has and what this module
exists to replace.

**Writes are atomic.** `writeText` writes a temporary file beside the target and
renames it into place. A write interrupted half-way leaves the previous contents
intact rather than a truncated file. `engine.io::writeTextFile` already does
this, and it is the behaviour an editor's Ctrl-S depends on.

`appendText` is not atomic and cannot be - appending is defined as adding to
what is there. It is a separate function so that the difference is visible at
the call site rather than hidden in a mode flag.

---

## 4. Directories

```azora
fin entries = try std::listDirectory(path)           // Array<Path> ?! FileError
try std::createDirectory(path)                       // one level
try std::createDirectories(path)                     // and its parents
try std::remove(path)                                // a file, or an empty directory
try std::removeAll(path)                             // a directory and its contents
try std::copyFile(from, to)
try std::rename(from, to)
```

`listDirectory` returns full paths, not bare names. A bare name is only useful
after being joined back onto the directory it came from, so returning names
makes every caller do the same join - and one of them will get it wrong.

Entries are returned **sorted by path text**, so a directory walk is
reproducible. A filesystem is free to enumerate in any order it likes, and a
build that depends on that order fails on one machine in ten.

`remove` and `removeAll` are separate names rather than a `recursive: Bool`
parameter. `removeAll(path)` at a call site says what it will do; `remove(path,
true)` does not.

---

## 5. Metadata

```azora
fin info = try std::fileInfo(path)                   // FileInfo ?! FileError

info.kind            // FileKind.File | .Directory | .Symlink | .Other
info.size            // Long, bytes; 0 for a directory
info.modified        // std::Instant

std::exists(path)    // Bool - not failable
std::isFile(path)
std::isDirectory(path)

fin real = try std::canonical(path)   // resolves symlinks; needs the disk
```

`exists`, `isFile` and `isDirectory` return `Bool` rather than `Bool ?! E`. The
question "is there a file here" has a defensible answer when the path is
unreadable - no - and forcing `try` on a predicate makes the common shape
(`if std::exists(p)`) worse for no gain in precision. Anything that needs the
distinction calls `fileInfo` and reads the error.

`modified` is a `std::Instant`, not an integer, so it composes with `std.time`.

---

## 6. Errors

```azora
error FileError {
    NotFound,
    PermissionDenied,
    AlreadyExists,
    NotADirectory,
    IsADirectory,
    DirectoryNotEmpty,
    InvalidPath,
    ReadFailed,
    WriteFailed,
    Unsupported
}
```

The set is deliberately small and closed. It names the cases a caller can
plausibly *act* on - retry elsewhere, prompt for permission, pick another name -
and folds the rest into `ReadFailed` / `WriteFailed`. An error set that mirrors
every `errno` makes exhaustive handling impossible and teaches callers to write
a catch-all, which is the outcome it was meant to prevent.

`Unsupported` is what a target without a filesystem raises (§8). It is a
first-class case rather than a lie: the browser has no disk, and a program that
asks for one should be told so.

---

## 7. Temporary files

```azora
fin dir  = try std::temporaryDirectory()             // the system temp root
fin work = try std::createTemporaryDirectory("azora") // a fresh, unique one
```

`createTemporaryDirectory` creates and returns a directory that did not exist a
moment ago. A caller that builds its own name from a timestamp or a process id
races with every other copy of itself; making the standard library responsible
for uniqueness is the only place it can be done once.

There is no `deleteOnExit`. Azora has no exit-hook mechanism, and a function
that promised cleanup without one would be a lie. `removeAll` is the cleanup.

---

## 8. What each target does

| target        | filesystem                                                |
|---------------|-----------------------------------------------------------|
| desktop (JVM) | the real thing, via the platform layer                    |
| LLVM native   | the real thing, via the C runtime                         |
| Wasm / web    | every operation raises `FileError.Unsupported`            |
| iOS           | the app sandbox; paths outside it raise `PermissionDenied` |

The web target **refuses rather than pretends.** A virtual in-memory filesystem
would let a program appear to save work that no one can retrieve. If a browser
build needs storage, that is a different module with different semantics, and it
should be named for what it is.

`exists` on the web is `false`, not an error - consistent with §5, where the
predicate always has an answer.

---

## 9. What is not specified

- **Streaming.** `readText` reads the whole file. Readers and writers over
  incremental input are `std.io`'s (`VERSION_0_1_ROADMAP` §4.4), and this module
  should not grow a second, weaker version of them
- **Permissions.** No `chmod`, no ownership, no mode bits. They differ enough
  between POSIX and Windows that a common API would be wrong on both
- **Watching.** No change notification. It needs an event loop, which is a
  concurrency question (`CONCURRENCY_DIP.MD`), not a filesystem one
- **Symlink creation.** `canonical` resolves them and `FileKind.Symlink` reports
  them; creating one is deferred until there is a caller
- **Globbing.** `listDirectory` plus a filter covers it. A pattern language is a
  parser, and it belongs beside the regex decision, not here
- **Concurrency.** Two processes writing one file is theirs to coordinate.
  Atomic replacement (§3) is the only guarantee offered

---

## 10. Defects that block this

Measured against the compiler as it stands, not predicted.

### 10.1 An operator overload does not dispatch across a realm

A pack declaring `oper +` inside a named realm gets that operator when the call
site is **inside** the same realm, and does not when it is outside:

```azora
realm demo {
    pack Money { var cents: std::Int = 0 }
    impl Money {
        oper + [self: Self&](rhs: Self&): Self { return Money(self.cents + rhs.cents) }
    }
    func addInside(): std::Int { return (Money(500) + Money(250)).cents }   // 750
}

func main() {
    std::println(demo::addInside())                    // 750
    std::println((demo::Money(500) + demo::Money(250)).cents)
}
```

The last line fails at run time:

```
Cannot add {__type=__demo_Money, cents=500} and {__type=__demo_Money, cents=250}
```

The same pack declared **outside** any realm works from anywhere. So the fault is
in resolving a realm-qualified owner to its declared operator, not in operator
overloading - `IrGenerator` lowers a matched overload to a direct call, and this
one is not matched, so the expression survives to the backend as arithmetic.

This is not a filesystem bug and its blast radius is wider than this module:
every operator the standard library declares on a pack inside `realm std` is
reachable only from inside `std`.

- ⬜ Fix realm-qualified operator resolution, then §2's `/` works as written

---

## 11. Rules

1. Every operation that touches the disk is failable. No sentinel values, no
   empty-on-failure returns
2. Path algebra never touches the disk and never fails
3. `exists` / `isFile` / `isDirectory` are predicates, not failable operations
4. Writes replace atomically; appends do not, and say so by name
5. Directory listings are sorted and absolute
6. A target without a filesystem raises `Unsupported`; it never fakes one
7. Nothing here streams. Whole files only, until `std.io` has readers
8. `join` is the operation; `/` is sugar over it. No behaviour lives only in the
   operator, so the module is complete without it

---

## 12. Implementation plan

In order, each step ending somewhere the tree still builds and the suite still
passes:

0. **Fix realm-qualified operator resolution** (§10.1), or accept `join` alone
   for 0.1. Everything below works either way; only the `/` spelling depends on
   it, and shipping `join` first keeps the two decisions separate
✅ **`Path` and the path algebra.** Pure, testable without a disk, and no
   platform work. `join`, `parent`, `fileName`, `stem`, `extension`,
   `isAbsolute`, `normalized`, `withExtension` - and `oper /` delegating to
   `join` once §10.1 allows it
✅ **`FileError`, `FileKind`, `FileInfo`.** Declarations only
✅ **The platform layer.** `FsAccess.kt` `expect`s beside `OsAccess.kt`, with
   `jvmCommon` actuals doing the work and `web`/`ios` refusing
✅ **Reads and predicates.** `readText`, `readBytes`, `exists`, `isFile`,
   `isDirectory`, `fileInfo` - wired through `IrInterpreter`
✅ **Writes.** `writeText`, `writeBytes`, `appendText`, atomic replacement
✅ **Directories.** `listDirectory`, `createDirectory`, `createDirectories`,
   `remove`, `removeAll`, `copyFile`, `rename`
✅ **Temporaries.** `temporaryDirectory`, `createTemporaryDirectory`
8. **LLVM backend.** The native path, so a compiled program has the same API
✅ **Move `azora-ide` onto it,** replacing its use of `engine.io`. The IDE is
   the first real consumer and will find what the tests did not

Steps 1-2 are pure Azora. Step 3 onward needs Kotlin in the compiler.

---

## 13. Related

- `ERROR_HANDLING_DIP.MD` - `?!`, `try`, `catch`, and why sentinels are refused
- `BRIDGE_DIP.MD` - what a `bridge func` is and what each backend owes it
- `MODULES_REALMS_DIP.MD` - `module std.filesystem`, `realm std`
- `VERSION_0_1_ROADMAP.MD` §4.4 - the gap this closes, and `std.io` next to it
- `TESTING_TRACING_DIP.MD` - how the steps above get their tests
