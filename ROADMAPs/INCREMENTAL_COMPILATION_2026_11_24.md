# Incremental Compilation for Engine Game Builds (2026-11-24)

**Goal.** An edit to a game's own sources should rebuild in well under ten seconds.
The Engine should be compiled once for each Engine or compiler change, then reused.

**State before.** `azora-engine/tools/build.sh` stages the game's resolved Engine
packages and its `src/` into one tree. The native compiler then compiles the whole
program into a single LLVM module, and `clang -O2` compiles and links it. An unchanged
rebuild was already skipped by a fingerprint stamp. Any edit paid the full price.

Measurements in this note use two programs:

- A game project of about 3,000 lines, using 40 Engine packages (about 53,000 lines),
  with headless checks.
- The `dolomites-drive` template.

## 1. Where the time went

Native compiler, game project, before this work:

| step | time |
|---|---|
| `azora.kexe compile llvm` (whole program) | 22.9 s |
| `clang -O2` on the 18 MB module | 10.5 s |
| full build | 34 s |

A Flight Recorder profile of the same compiler on the JVM gave this split:

| share | step |
|---|---|
| 35% | `LlvmCodegen.referencesSymbol` |
| 23% | `IrSymbolCanonicalizer` (fixed earlier the same day) |
| 19% | semantic analysis |
| 12% | `ReflectDecoExpander` |
| 10% | `IrOptimizer.reach` |
| 5% | library injection |

`LlvmCodegen.referencesSymbol` searched the whole 18 MB module text once per
`bridge` declaration.

## 2. What was done

### 2.1 Whole-program hot spots, with byte-identical output

Each change was checked by compiling the game project before and after it and
comparing the `.ll` with `cmp`.

| change | where |
|---|---|
| `referencesSymbol` reads the `@names` once, then only the text appended since. | `backend/LlvmCodegen.kt` |
| Reachability tests each function's name against the member, type and operator sets. It used to spell out types × types × operators names on every round. | `ir/IrOptimizer.implicitTargetsAmong` |
| A reflection query no longer collects every decorator site of the program. Sites, decorators and impls are indexed once per program. | `semantic/DecoratorMetadata` |
| `lookupTypeStatic` and `inferredStatic` find a function by its `__…` tail through an index. They used to read every function name per miss. | `semantic/SymbolTable` |
| Closure-borrow provenance is an identity-keyed map. It used to be a list searched per binding, so it grew quadratically. | `semantic/TypeResolver` |
| `program.functions.find { … }` (which rebuilt the list) is now an index made once per program. | `semantic/TypeResolver` |

The native CLI also sizes Kotlin/Native's collector at start: the minimum heap is
1,024 MB, and `AZORA_COMPILER_MIN_HEAP_MB=0` keeps the runtime's sizing. A compile
keeps most of what it allocates, and starting small made the collector stop it to
mark the growing heap again and again. Output is unaffected.

JVM-hosted compile of the game project: 11.0 s → 5.4 s. It was 14 s before the
day's earlier canonicalizer fix. Native: see §5.

### 2.2 The program as parts, each part's object kept

`backend/LlvmUnits.kt` splits the module the code generator wrote into parts that
compile and link on their own. The CLI option is
`azora compile llvm <file> --parts=N -o <dir>`, supported by both the native and the
JVM CLI.

- **Functions.** Each function the program defines goes in exactly one of N parts,
  chosen by an FNV-1a hash of its name. Adding or changing functions changes only
  the parts that hold them.
- **Variables.** The program's variables are defined in `globals.ll`. Mutable
  `internal` globals (reactive storage, singletons) become external there, so they
  stay single. Other parts declare what they use.
- **Numbered names.** The code generator numbers some names in one sequence over the
  whole program:
  - string constants (`@.str.N`),
  - drop functions (`@__azora_drop_*_N`),
  - closure, task and C-callback bodies,
  - their context types (`%azora.lambda.ctx.N`).

  So one new string in a game used to renumber about 600 Engine functions. Each part
  now holds its own private copy of each of these it uses, renumbered by where the
  part first uses it.

  No copied function is compared by address: in the generated code, every
  function-pointer comparison is against null. String constants are compared by
  address, because an enum value is a pointer to its variant's name. The copies of one
  string still end up at one address only because the linker merges identical
  `__cstring` literals (§6.1).
- **Runtime functions.** The compiler's runtime functions (`@__azora_alloc`,
  `@__azora_free`, ...) go into every part that calls them as `linkonce_odr`. Each
  part inlines them as the single module did, and the linker keeps one copy.
- **Deterministic text.** A part's functions, declarations and types are sorted by
  name. A part's text therefore depends only on the functions it holds, not on where
  they sat in the program.

Measured on the game project, changed files out of 17 (16 parts plus globals):

| edit | changed parts |
|---|---|
| change a constant | 1 |
| add a string literal | 1 |
| add a closure | 1 |
| add a component and a system (changes the reflected `systemPlan`, `dispatchGame` and the specializations) | 4 |

`build.sh` changes:

- **`build` and `run`** ask for `--parts=${AZORA_BUILD_PARTS:-16}`. Each part is
  compiled to `.azora-build/objects/<sha256 of part text, clang identity and
  AZORA_OPTIMIZE>.o`.
- **Compilation** covers only the missing objects, in parallel (`xargs -P ncpu`).
  Each object is written to a temporary name and renamed when complete, so an
  interrupted build never leaves a broken object to reuse.
- **Linking** puts the objects together. Objects this build did not use are removed.
- **`release`** keeps the single module and `clang -O2` on it: every function stays
  inlinable into every other, for the executable that ships.

### 2.3 What splitting costs at run time

Without the single module, clang cannot inline from part to part. On the game
project's headless checks, which are CPU work through physics, water, wind, flight
and boats, user time went from 0.53 s to 0.57 s (+7%).

The owner's decision (2026-11-24): development builds accept this; the shipped
release inlines everything (`release`).

ThinLTO across the parts would recover the inlining, and ld64's `-cache_path_lto`
would keep the per-part backends. It linked in 0.15 s from cache, but one water check
of the game project failed (`index -1 out of bounds`). The same module unsplit under
ThinLTO passes, and so does the plain split build. The cause is in §6.1: enum values
are compared by address.

## 3. Why the front end still reads the Engine on every build

The whole-program steps below are why a game cannot yet be compiled against a
precompiled Engine interface. A real separate compilation needs an answer for each.

| whole-program step | what it needs across an Engine/game boundary |
|---|---|
| `reflect<*>.withAnnot<System>` (`ReflectDecoExpander`) unrolls over every decorated declaration of the program, game included. | The Engine's reflecting bodies (`systemPlan`, dispatchers, inspector registries) cannot be compiled with the Engine. The interface carries them as checked templates, and the program step expands them over the Engine's and the game's declarations. |
| Variadic and inferred specializations (`VariadicMonomorphizer`, `InferredTypeArgs`, `Witnesses`) instantiate per use (`QueryOf_Hull_R_HullMotion_R`). | A specialization whose arguments name a game type belongs to the program step. One over Engine types only can be cached with the Engine. The interface carries the generic declarations' checked bodies. Erased generics have one body, compiled once. |
| `inline` functions and CTFE (`InlineCallables`, `CtfeEvaluator`) splice or interpret bodies at the call site. | The interface carries the checked bodies of `inline` functions and of everything CTFE may call, not only signatures. |
| `IrOptimizer.shakeLibrary` keeps only what the program reaches. | A precompiled Engine is not shaken per game. Its functions are emitted in their own sections and the linker strips the unused ones (`-dead_strip`). |
| `IrSymbolCanonicalizer` spells names knowing every type of the program. | A name must depend only on its declaration (module, scope, name), never on what else the program declares. Otherwise a game type could change an Engine function's name. |
| Spec dispatch tables and type ids (`specTypeIds`, numbered in program order). | Ids are assigned at the program step, and Engine code reads them through a table the program step writes. |
| The numbered names of §2.2. | Already solved per part by renumbering. A cached Engine module would need names derived from their owner instead. |

The cache key for such an interface would be the hash of the Engine sources, the
compiler and the `-D` defines. The game's compile would then parse and analyse only
its own sources plus the interface. That is the next step. It is a change to the
semantic pipeline's input, which today is one merged AST, so it is a project of its
own.

Until then, the whole-program front end is the floor of an edit build. §2.1 cut it
roughly in half.

§7 (2026-11-25) is that step for analysis and code generation: the Engine's functions
are analysed and generated once, and a game build only declares them. Its parsing and
injection still read the Engine's declarations (§7.6).

## 4. How to use it

```sh
tools/build.sh <project> run      # parts, kept objects (Studio's Run)
tools/build.sh <project> build    # same, no launch
tools/build.sh <project> release  # one module, full inlining, for shipping
AZORA_BUILD_PARTS=32 tools/build.sh <project> build   # more, smaller parts
AZORA_PACKAGE_INTERFACE=0 tools/build.sh <project> build   # whole program, no package interface (§7)
```

With a compiler that supports it, `build` and `run` build on the packages' interface
in `.azora-build/interface` (§7). The compiler says which way each build went on
stderr (`azora: package interface used: …`, `… made: …`, `… not used: <why>`). The
same, by hand:

```sh
azora compile llvm src/main.az --parts=16 -o parts \
    --interface=.azora-build/interface --interface-sources=engine/ecs,engine/math \
    --interface-compiler="<compiler identity>"
# parts/link.txt: `link <path>` interface parts to link with parts/*.ll,
#                 `prepare <path>` interface parts to compile for later builds
```

## 5. Timings

Game project and `dolomites-drive`, Apple M-series (10 cores), native compiler,
`tools/build.sh … build`, measured 2026-11-24. "Before" is the compiler built at 13:54
that day (including the earlier canonicalizer fix), building one module. Every row
except "no change" includes the staging and resolving `build.sh` does.

| build | before | after |
|---|---|---|
| native `compile llvm`, game project alone | 22.9 s | 4.0 s |
| game: first build with a new compiler (all shaders re-cooked) | 31.9 s | 16.1 s |
| game: no change | 0.4 s | 0.5 s |
| game: edit a constant | 31.6 s | 6.1 s (1 of 17 parts compiled) |
| game: edit, new component and system | 31.8 s | 6.3 s (4 of 17 parts) |
| dolomites-drive: first build (shaders re-cooked) | 24.8 s | 10.4 s |
| dolomites-drive: edit a constant | 24.9 s | 4.8 s (1 of 17 parts) |
| dolomites-drive: `release` (one module) | 24.9 s | 13.0 s |

Of the native compile's 22.9 s → 4.0 s:

- **`referencesSymbol`:** 11.0 s left once fixed.
- **The other §2.1 changes:** 5.5 s.
- **The collector's starting heap (1,024 MB):** 4.0 s. The native CLI sets it at
  start (`AZORA_COMPILER_MIN_HEAP_MB`, `0` = the runtime's own sizing). Peak memory
  becomes 1.35 GB instead of 0.72 GB.

**How each claim was checked:**

- **Front-end changes.** The game project and `dolomites-drive` compile to the same
  `.ll`, byte for byte, as with the compiler before these changes. The comparison is
  native against native, and JVM-hosted against JVM-hosted (see §6.2).
- **Parts against one module.**
  - The game's headless checks print the same lines.
  - `dolomites-drive` built both ways prints the same log and draws the same frame
    120.
  - `LlvmUnitsExecTest` runs a program built as 1, 3 and 8 parts against the whole
    module and checks that an edit changes only the part that holds it.
  - `LlvmUnitsTest` covers variables, thread-locals, numbered types, quoted text and
    undeclared symbols.
- **Test suite.** Compiler and AZLS: 3,302 tests, 0 failures.

## 6. Open findings

1. **Enum values compared by address; ThinLTO breaks it.**
   - **What enum equality is.** An enum value reaches LLVM as a pointer to its
     variant's name (`c"PreUpdate\00"`), and `==` on enums is `icmp eq i8*`, so equal
     values need equal addresses.
   - **Why it holds today.** The code generator emits one constant per distinct
     string. In split builds the parts each hold a private copy, and Apple's linker
     merges identical literals in `__cstring` sections (three parts carry
     `"PreUpdate"`, the executable one).
   - **What ThinLTO changes.** It imports `runFrames` from one part into another and
     promotes the string the function passes as its phase to a symbol of its own
     (`.str.69.llvm.<hash>`). That symbol has its own address, so `dispatchEditor`
     matched no system and a later check failed. This was found by bisecting: two
     parts under ThinLTO, then the import threshold, then the one import.
   - **The fix.** Make the split keep the whole module's identity itself, without
     relying on the linker: one definition per distinct string for the program,
     named by its content (in `globals.ll`), with an `available_externally` copy
     where a part reads it. That would also make ThinLTO usable.
2. **System order differs by platform.** The JVM-hosted compiler and the native
   compiler order the systems in `dispatchGame`/`systemPlan` differently for the same
   sources. This predates this work. A system order that depends on the host
   platform means some pass iterates a hash map. That is worth finding, since
   same-phase systems may depend on order.
3. **Native runtime overhead.** The native compiler is now faster than the
   JVM-hosted one (4.0 s vs 5.4 s). About 16% of its main thread is still Kotlin/Native
   reaching thread-local state (`_tlv_get_addr`), seen with `sample`. That is the
   runtime's, not the compiler's.

## 7. The package interface (2026-11-25)

**Goal.** A game build does not analyse or generate the Engine's code again. The
Engine's functions are compiled once, kept with a checked description of them, and
linked into every build after.

### 7.1 What is kept

`compiler/src/commonMain/kotlin/org/azora/lang/PackageInterface.kt`.

- **`PackageInterface`.** A text file (`interface.txt`), and the Engine's code as LLVM
  parts beside it, named by the hash of their text. For each precompiled function it
  records:
  - its declaration, as its definition spells it (`declare …`);
  - the name it had in the tree, so the game build can find it there;
  - what its code needs the game to define (functions, variables, members asked for
    by name), for shaking;
  - what it keeps of, hands back from, and allocates for its callers (the
    `OwnershipCleanup` facts);
  - which array parameters it reads the elements of (`ElementReads`).

  It also records the numbers the code was compiled with: spec type ids and witness
  descriptors with what each is used for. And it records the names the packages'
  declarations had, what the parts declare that the game must define, the struct
  layouts the parts use, and the variables only the parts use (reactive storage, the
  error slot).
- **`PackageBuild`.** Keeps interfaces in a directory (`.azora-build/interface/<key>`)
  and decides, for each build, which of the three below happens.

### 7.2 The key, and why no language rule changed

The key hashes everything the precompiled code follows from:

- the package sources and the standard library;
- the compiler's identity (`build.sh` passes the binary's path, time and size);
- the defines and the optimization mode;
- the game's **naming** of the packages: its module, its imports, and those of its
  declared names that some package or standard-library source also spells.

The naming is part of the key because names reach run time. A library declaration
the game cannot name gets its module's identity (`engine__physics__core__Gravity`),
and that spelling is what `T.typeName` and `reflect<X>.declName` return. The ECS keys
resources and storages by `T.typeName`. So the Engine is compiled under the game's own
naming: the game's entry with only its imports of packages, and the game's colliding
names marked as shadowing (`StdlibInjector.shadowedByProgram`). Every name, and every
string made from one, is then what the whole program has.

A new game function, or any edit to a function body, keeps the key. Changing an
import, declaring a name a package also uses, or editing the Engine changes it.

### 7.3 A build

1. **No interface for the key.** The whole program compiles as before, and its result
   is what this build links. Then the packages are compiled on their own:
   - **Roots:** the package declarations the program injected
     (`StdlibInjector.rootDeclarations`).
   - **Kept:** what the program kept after shaking.
   - **Left to the game:** functions that enumerate `reflect<*>` (and callers of an
     `inline` one that does; `ReflectDecoExpander.enumerating`), witness and spec
     dispatchers, copied helpers, the runtime and `main`.

   The rest become the interface's parts (`LlvmUnits.splitOnly`). `build.sh` compiles
   those parts' objects in the same build, so the next build finds them.
2. **An interface for the key.** The game compiles with the whole front end. Then:
   - **Marked, not analysed.** The functions the interface holds are marked
     `FuncDecl.precompiled`. Compile-time evaluation, type resolution, bound checks
     and inlining skip their bodies, and IR generation lowers only their signatures
     (`IrFunction.isPrecompiled`). Their defaults are still resolved, and
     compile-time evaluation can still run their bodies.
   - **Declared, not defined.** Code generation declares them. Spec type ids and
     witness descriptors start from the interface's numbering. Ownership,
     element-read and reachability facts come from the interface.
   - **Linked.** The game's parts link with the interface's parts.
3. **The game uses a package declaration the interface lacks.** The game compiles it
   itself, so this build is still exact. The interface is then made again with that
   declaration among its roots.

**Checks.** Before a build on an interface is used, it is checked against the
interface. If any check fails, the whole program is compiled instead, and the build
prints why (`azora: package interface not used: …`). The checks:

- the packages' declarations have the names the interface gave them;
- no game declaration takes a name the interface uses;
- every spec implementation the interface's code may dispatch to is the game's too,
  with the same functions;
- the game's functions that the interface's code calls keep, hand back and allocate
  as that code assumed;
- every precompiled function's declaration matches its definition;
- everything the interface's parts declare is defined by the game, with the same
  declaration;
- struct layouts match.

### 7.4 Splitting changes

- **Helpers copied per part.** The generated `==`/`hash` helpers
  (`__structural_*_N`) and valued-enum helpers (`__enum_value_N`, `__enum_case_N`) are
  numbered over the program. Like drop functions, each part now keeps its own copy.
- **Intrinsic bodies.** The code generator writes some bodies for any program the same
  way (`charAt`, `substring`, …). Interface parts carry them as `linkonce_odr`.
- **Global initializers.** Interface parts never copy `__azora_init_*`: initializing
  the variables is the game's.

### 7.5 How it was checked

`llequiv.py` (a session tool, not in the repository) compares two programs function by
function. It numbers locals by first use and replaces each module-private helper,
string and numbered type by its content. Two programs were compared: the whole
program, as parts, against the game's parts plus the interface's parts, both from the
same staged sources.

| program | functions | precompiled | differing |
|---|---|---|---|
| game project | 1,738 | 1,450 | 4 |
| `dolomites-drive` | 1,647 | 1,469 | 4 |
| game project, a function added to the entry that calls three Engine functions it never used | 1,742 | 1,450, and the 3 compiled by the game | 4 |
| game project, a constant edited, on the interface the row above extended | 1,738 (and 3 unused) | 1,453 | 8 |

Every function that differs is a spec dispatcher (`__dyn_*`). Each calls the same
function for each type; only the type ids differ:

- The game's own specializations are numbered after the interface's types.
- An interface made again numbers its types in its own order, and the game follows
  it.

The interface's code was compiled with the same numbers, and no other function
differs. An interface made again may hold functions the current program no longer
calls; they are linked and unused.

**The whole-program path is unchanged.** For the same staged sources, it writes the
same `.ll` byte for byte (`cmp`):

- the native compiler before and after this work;
- the JVM-hosted compiler before and after the later hot-spot changes (§7.6).

That holds for both programs.

**What the programs do.**

- **The game project's headless checks.** Built through `build.sh` on the interface,
  they print `expected.txt` exactly (`harness.sh`: PASS).
- **`dolomites-drive` frame 120.** Captured from a build on the interface and from a
  whole-program build (`AZORA_CAPTURE`), it differs from the whole-program frame by a
  mean of 0.62 to 0.76 per channel. Two runs of the whole-program build differ by
  0.85: the frame varies between runs (rain), and the interface adds nothing to that.
  A side-by-side image shows the same frame.

The checker found four faults before they shipped. Each is fixed:

- **Ownership facts.** `OwnershipCleanup` read what a callee keeps from its body. With
  bodies absent, the game freed values precompiled code kept. That would have been a
  use after free at run time.
- **Numbered helpers.** `__structural_*` and `__enum_*` names meant different types in
  the two compiles.
- **Intrinsic bodies.** The game build did not write intrinsic bodies its own code
  does not call.
- **Operator names.** Operator member names (`oper==`) broke the interface's text
  format.

`PackageInterfaceTest` builds a package and a program both ways, links them with
clang and compares what they print. The package has owned arrays crossing the
boundary, a closure, a global and `reflect<*>` over the program's functions. It also
checks:

- an edit that makes the program use a new package function;
- an interface whose recorded declaration disagrees with the program, which must be
  refused, with the whole program built instead;
- the interface's text round trip, the `IrType` codec, and SHA-256 against the
  standard vectors.

Compiler and AZLS: 3,313 tests (3,219 and 94), 0 failures.

### 7.6 What a build on the interface still spends, and the hot spots fixed

**The profile.** A native profile (`xctrace`, Time Profiler) of the game project built
on its interface, with the first binary of this work, split the compile like this:

| share | step |
|---|---|
| 33% | injection |
| 26% | analysis, 11 points of it compile-time folding |
| 10% | LLVM code generation |
| 9% | parsing |
| 7% | IR generation |
| 6% | monomorphization |

The Engine's bodies are no longer analysed or generated. Its declarations are still
parsed and injected, so that the game is checked against exactly what the whole
program checks it against.

**Hot spots fixed.** These changes help whole-program builds too. Each was checked
byte for byte (§7.5):

| change | where |
|---|---|
| An impl's walk key was formatted again on every visit to its type. It is now made once per indexed impl. | `StdlibInjector.attachImplsForType` |
| A `withAnnot` loop search re-read the whole program and everything injected each time the walk ran dry. It now reads each item once, in the same order. | `StdlibInjector.enumeratedDeclarations` |
| One injection walked the program's items twice, and the items it injected twice. The names are now read once and reused. | `StdlibInjector.inject` |
| Compile-time folding copied its constant maps for every function. A precompiled function with no defaults now returns at once. | `CtfeEvaluator.evaluate` |
| A builder appended to a builder was copied character by character; the module is megabytes long. The text is now appended as a string. | `LlvmCodegen.generate` |
| `isCopied` ran a regex for every symbol use. A prefix test now comes first. | `LlvmUnits` |

**Next.** The rest of the floor is reading the Engine's declarations:

- parsing the package sources (they could be kept parsed);
- injecting and collecting their symbols. A precompiled declaration could enter the
  game's tree as its signature only, with the interface recording what its body
  pulls in.

Both keep the game's checking exact only if the interface records more. They are
separate steps.

### 7.7 Timings

Apple M-series, 10 cores. Measured 2026-11-25 with three unrelated processes each
holding a core (load 8 to 14), so every row compares programs timed side by side in
the same minutes.

- **Before:** the native compiler of 2026-11-24 19:51.
- **After:** the native compiler with this work.

The compile step is `compile llvm … --parts=16`, median of 5 runs, interleaved.

| build | before | after, whole program | after, on the interface |
|---|---|---|---|
| game project: compile step | 4.45 s | 3.96 s | 2.44 s |
| `dolomites-drive`: compile step | 3.9 s | 3.6 s | 1.95 s |
| game project: `build.sh` edit a constant | 5.3–5.5 s | – | 3.3–3.4 s (1 of 33 parts compiled) |
| `dolomites-drive`: `build.sh` edit a constant | 4.55 s | – | 2.8–2.9 s (1 of 33 parts) |
| game project: first build with a new compiler (interface made, shaders cooked) | 16.2 s | – | 15.8 s |
| game project: no change | 0.4 s | – | 0.4 s |

How the first builds and the switches between modes behave:

- **The first build** compiles the whole program, then the Engine on its own (about
  9 s of compile step in all). Its 16 interface parts compile alongside the program's
  parts, so the build costs about what it did before.
- **The first build on a new interface** compiles all 16 of the game's new, smaller
  parts once (game project 4.0 s, `dolomites-drive` 3.3 s). After that, an edit
  compiles the part that holds it.
- **Switching compilers or `AZORA_PACKAGE_INTERFACE`** drops the other mode's objects
  (`build.sh` keeps only what a build names), so the next build in the other mode
  compiles its parts again.
