# Azora Packages - DIP Implementation Profile

> Status: **v1 implemented in the JVM CLI** (roadmap A5, 2026-11-07).
> `azora new`, `build`, `run`, `test`, `add` and `remove`; path and git
> dependencies; `azora.lock`; workspaces. The code is
> `compiler/src/commonMain/kotlin/org/azora/lang/packages/` (format, manifest,
> lockfile, resolver, build plan - written once, behind `PackageHost`) and
> `app/src/main/kotlin/dev/azora/lang/Packages.kt` (the commands). Tests:
> `ManifestTest` (13) and `PackageResolverTest` (16, real git repositories and
> an empty cache). Not done: the native CLI's host (§8), and the items in §9.

A program that uses the Engine needs thirty-odd packages, the C files two of
them compile, and the frameworks they link. Before this DIP each tool found
those its own way: shell scripts listed source roots and `-framework` flags,
and AZLS read manifests with regular expressions. This DIP makes the manifest
the one description of a package, and makes `azora build` the one reader of it.

FULLSTACK_DIP §1 left the manifest's schema to "a package-management DIP";
this is that DIP. The format is AZON (§1), so that no tool needs a TOML, YAML
or JSON library.

---

## 1. AZON

AZON is the configuration format: JSON's values with less punctuation.

```azon
// A document is an object without its braces.
name: "demo"
count: 3
ratio: -1.5e2
enabled: true
missing: null
list: [ "a" "b" 3 ]          // commas are optional
nested: { key-with-dash: "x" }
/* block comments too */
```

- A document is an implicit object: `key: value` members, separated by
  newlines, spaces or commas. A document that starts with `{` is read as one
  object, so every JSON document is an AZON document.
- A key is a bare word (letters, digits, `_`, `-`, `.`, starting with a letter
  or `_`) or a quoted string.
- Values: strings (JSON escapes), numbers (kept as written), `true`, `false`,
  `null`, arrays, objects. A repeated key's last value wins.
- Comments: `//` and `#` to the end of the line, `/* … */`.
- An error names its line: `azora.azon: line 4: expected a value`.

The reader keeps each value's position in the text, which is how `azora add`
and `remove` edit a manifest without disturbing its comments or layout (§7).

## 2. The manifest

A directory is a project, a package or a workspace according to its manifest.

```azon
project: {
    name: "my-game"
    version: "0.1.0"
    entry: "main.az"         // default main.az
    src: "src"               // default src
}
dependencies: {
    azora-engine: { git: "https://example.org/azora-engine.git" tag: "v0.1.0" }
    my-lib: { path: "../my-lib" }
}
native: {
    frameworks: [ "AppKit" ]
}
```

### 2.1 File names

A manifest is named `azora.azon`, `package.azon` or `workspace.azon`. All
three are read the same way; the name does not decide what the directory is.
When a directory has more than one, `package.azon` is used, then
`workspace.azon`, then `azora.azon` - the order AZLS uses, so the editor and
the build never read different files. `azora new` writes `azora.azon`; the
Engine's manifests are `package.azon` and `workspace.azon`.

### 2.2 Project

`project:` is something built and run: `name`, `version`, `entry` (the file
holding `main`, relative to `src`), `src`. Its sources keep their place under
`src`, and need not declare modules.

### 2.3 Package

`package:` is something depended on: `name`, `version`, `module`, `src`.
`module` is the module prefix its sources declare (`engine.ecs`): the build
places each source under that prefix (§5), so `src/events.az` declaring
`module engine.ecs.events` is found where its module says it is. Other keys
(`kind`, `authors`, `license`) are kept and not interpreted.

`azora new` and `azora add` take package names of lowercase letters, digits,
`-` and `_`, starting with a letter. One manifest declares a project or a
package, not both.

### 2.4 Workspace

`workspace:` with `members:` - the directories of its packages and projects,
relative to the root - is a set of packages developed together:

```azon
workspace: { name: "azora-engine" version: "0.1.0-dev.1" }
members: [
    "packages/azora-core"
    "packages/azora-ecs"
]
package: { authors: [ "Azora" ] license: "Apache-2.0" }
```

A workspace root declares no project or package. A nameless `package:` at a
root is metadata for its members, not a package. `members` may also be written
inside `workspace:`.

Members depend on each other by `path`. One lockfile, at the root, covers every
member (§4).

### 2.5 Dependencies

`dependencies:` maps a package name to where the package is:

| Form | Meaning |
|---|---|
| `{ path: "../lib" }` | The package in that directory, relative to this manifest. |
| `{ git: "<url>" }` | The package of that name in the repository's default branch. |
| `{ git: "<url>" rev: "<commit>" }` | At that commit (a full or abbreviated hash). |
| `{ git: "<url>" tag: "<tag>" }` | At that tag. |
| `{ git: "<url>" branch: "<branch>" }` | At the tip of that branch. |

A dependency names exactly one of `path` and `git`, and at most one of `rev`,
`tag` and `branch`. The name must be the name the package's manifest declares:
`letters: { path: "../words" }` is an error when that package is `words`.

In a git repository the package is the repository's root package, or the
workspace member of that name. Two dependencies on one repository at one commit
share one checkout, so the Engine's packages depended on one by one resolve
their sibling packages to the same directories.

### 2.6 Native

`native:` is what a package or project adds to the link: system `frameworks`,
system `libs` (`-l`), and C `sources` compiled into the program, relative to
the manifest. A program links the union over its whole graph, in dependency
order, each entry once.

C sources are a bridge for today: the Engine's Objective-C trampolines are one
file, and roadmap B0 replaces it with Azora code. They are compiled by the same
`clang` that links (`AZORA_CLANG`, else `/usr/bin/clang`, else `clang` on
`PATH`).

## 3. Resolution

Resolution turns a manifest into the list of packages a build compiles.

1. Dependencies are followed transitively, depth first; a package comes after
   the packages it depends on.
2. A name stands for one package in the whole graph. Reaching a second
   directory for the same name is an error naming both.
3. A cycle is an error that spells it: `dependency cycle: app -> a -> b -> a`.
4. A `path` dependency of a git package must stay inside its repository: a
   checkout cannot depend on whatever happens to sit next to the cache.

There are no version ranges in v1. A git dependency is pinned by what it names
and by the lockfile; `version` is recorded, not compared.

### 3.1 The git cache

Git repositories live in one cache, `$AZORA_HOME` or else `~/.azora`:

```
~/.azora/git/db/<repository>-<fingerprint>/                 a bare clone
~/.azora/git/checkouts/<repository>-<fingerprint>/<commit>/ one per commit
```

`<repository>` is the last segment of the URL without `.git`; `<fingerprint>`
is the URL's 64-bit FNV-1a hash, so two repositories with one name do not
collide. `<commit>` is the commit's first 12 hex digits.

A repository is cloned once. A checkout is made once per commit and never
changed afterwards, so two projects pinned to different commits do not disturb
each other.

### 3.2 Which commit

- The lockfile's commit is used while the manifest asks for the same thing:
  same URL and same `rev`/`tag`/`branch`. Nothing is fetched unless that
  commit is missing from the cache.
- Otherwise - a new dependency, a changed reference, or `--update` - the
  repository is fetched and the reference resolved: `rev` as written,
  `refs/tags/<tag>`, `refs/heads/<branch>`, or `HEAD`.

Git is run as a program: `AZORA_GIT`, else `git` on `PATH`, else
`/usr/bin/git`, the first that runs (a `PATH` can lead to a git this machine
cannot execute; that one is passed over). Its failures are reported with what
was being resolved: `git rev-parse failed for words (tag:v9) in <url>`.

## 4. The lockfile

`azora.lock` records every package the graph resolved to:

```azon
// azora.lock - written by azora build, add and remove; do not edit.
version: 1
packages: [
    { name: "azora-core" version: "0.1.0-dev.1" source: "git" path: "packages/azora-core" url: "https://example.org/azora-engine.git" reference: "tag:v0.1.0" rev: "4f0c…" }
    { name: "my-lib" version: "0.2.0" source: "path" path: "../my-lib" }
]
```

- `source` is `path` or `git`. A path package's `path` is its directory
  relative to the lockfile; a git package's is its directory inside the
  repository (empty for the root).
- `reference` is what the manifest asked for (`rev:…`, `tag:…`, `branch:…` or
  `default`); `rev` is the full commit that answered.
- Entries are sorted by name, so the file changes only when the graph does.
- The file is written only when its text changes. It is meant to be committed.
- A `version` other than `1` is an error, not a guess.

In a workspace there is one lockfile, at the root, holding the union of every
member's packages. Members must agree: two members resolving one name to
different sources (two directories, or two commits) is an error naming both.
A command run inside a member resolves the whole workspace and writes the
root's lockfile, never one of its own.

## 5. The build plan

A resolved graph becomes a build plan: the sources to compile and what to link.

- A project's sources are library modules at their path under `src`, except
  the entry, which is compiled as the program.
- A package's sources are placed under its `module` prefix: in `azora-ecs`
  (`module: "engine.ecs"`), `src/events.az` is the library module
  `engine/ecs/events.az`.
- The link set is §2.6's union.

**A project** compiles its entry with every other source as a library, through
the LLVM target, and links `build/<name>.ll` with `clang -O2` (`-O0` with
`--debug`) into `build/<name>`.

**A package** has no program. `azora build` checks it: each of its sources is
compiled in turn as the entry, with its other sources and its dependencies as
libraries, through the LLVM target. One compile is not enough: the compiler
checks a library module only as far as the entry uses it, so a package compiled
only as a library would leave unreached code unchecked. Each source compiled as
the entry is checked in full - including what its `exposed import`s import,
which an entry names just as the same module does as a library.

**Tests** (`azora test`) are compiled the same way: each source that has a
`test` block is the entry, with the rest as libraries. They run in the
interpreter, or natively with `--llvm`, each test in its own process, linked
with the plan's link set.

## 6. Commands

A command works on the directory it runs in, or on the one `--project <dir>`
names. `azora run` and `azora test` with no file or directory argument mean
the project when the directory has a manifest; with one they keep their
single-file meaning.

| Command | Does |
|---|---|
| `azora new <name>` | Creates `<name>/azora.azon` and `<name>/src/main.az` (a greeting and a test). |
| `azora build [--update] [--debug]` | Resolves (writing the lockfile), then links a project or checks a package; at a workspace root, every member, reporting `N of M members built`. |
| `azora run [-- <args>]` | Builds the project and runs it with `<args>`. A package or a workspace root has no program to run, and says so. |
| `azora test [--llvm]` | Runs the `test` blocks of the project or package; at a root, of every member. |
| `azora add <name> --path <dir>` | Adds a path dependency. |
| `azora add <name> --git <url> [--rev … \| --tag … \| --branch …]` | Adds a git dependency. |
| `azora remove <name>` | Removes a dependency. |

`add` and `remove` edit the manifest's text: a dependency is added on its own
line inside `dependencies:` (the block is created when there is none), and
removing one takes its line and nothing else. Comments and layout survive.
The new manifest is kept only when the graph still resolves; otherwise the old
text is put back and the error is reported - a typo in a URL never leaves a
manifest that does not build.

## 7. One reader

`Azon`, `Manifest`, `Lockfile`, `PackageResolver` and `BuildPlan` are common
Kotlin. The machine is reached through `PackageHost` - files, a process, the
environment - so the CLIs supply a host and share everything else.

AZLS reads manifests with the same `Manifest.parse`, so the editor's package
boundaries, source roots and path dependencies are the build's. (It read them
with regular expressions before, which saw only the first of several
dependencies.)

## 8. Not done in v1

- **The native CLI** (`macosMain`) has no `PackageHost` yet; package commands
  are the JVM CLI's.
- **AZLS and git dependencies.** The editor indexes path dependencies; a git
  dependency's sources are in the cache, outside the editor's workspace, and
  are not indexed yet.
- **A workspace root's own dependencies** (`workspace.dependencies` shared by
  members) are not read; members declare their own.

## 9. Later, deliberately

These wait for a registry (roadmap D5) or for a need:

- a package registry, version ranges and a version solver;
- checksums of git checkouts in the lockfile (the commit hash pins the
  content today);
- per-target dependencies and optional features;
- an `--offline` flag (resolution already fetches nothing when the lockfile's
  commits are in the cache);
- vendoring.
