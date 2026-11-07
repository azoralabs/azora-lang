# Azora ecosystem roadmap — 2026-11

Written 2026-11-06. This is the short- and long-term plan for the language,
the Engine and Studio together. It sits above the
[150-step delivery plan](ECOSYSTEM_DELIVERY_PLAN.md) and the
[0.1 roadmap](VERSION_0_1_ROADMAP.MD). Those stay the detailed checklists; this
document puts them in order.

## Principles

These apply to every track and every change.

1. **Azora all the way down.** The only foreign code is the operating system
   and the GPU driver. The runtime, the standard library, the Engine and Studio
   use no third-party libraries. Windowing, input, audio, GPU backends, the
   shader compiler, image, font and audio decoders, and networking are written
   in Azora. OS interfaces are called directly through Azora's own FFI: the C
   ABI, the Objective-C runtime, COM, JNI and browser APIs.
2. **Legitimate code only.** No hardcoded results, no special case for a test,
   a demo or a sample, and no placeholder that returns something plausible.
   A capability that is missing is an explicit compiler or runtime diagnostic
   with its name. Tests are never weakened, skipped or rewritten to pass.
3. **Nothing hardcoded in the tools.** Studio's panels, commands, inspectors,
   templates, themes, icons and file types come from reflection or data files.
4. **Evidence closes a milestone.** A milestone closes on a test, a probe or a
   build running on the target, never on a checkbox or a test count.

## The goals

1. **Language 0.1 stable**, then 1.0 with a stability guarantee.
2. **Every OS and every graphics backend.** The Engine runs on macOS, iOS,
   Android, Windows, Linux and the web, and renders through **Metal, Vulkan,
   DX12, GLES and WebGPU** behind one GPU interface. A game is written once and
   never names a backend.
3. **A professional Studio**: it opens a complete game, plays it inside the
   editor, previews it on many devices at once, and is extended with plugins.
4. **Packages**: a package manager and registry, so the Engine, games and
   editor plugins are ordinary packages.
5. **Self-hosting**: the Azora compiler is written in Azora and builds itself.
6. **A flagship game as the qualification.** A complete, shipping-quality 2D
   mobile game, released on the App Store and Google Play and built entirely
   with Azora, is the gate that proves goals 1–4 together.

### Platform and backend matrix

| OS | Primary backend | Also supported | Platform layer (in Azora) |
|---|---|---|---|
| macOS | Metal | — | AppKit through the Objective-C runtime |
| iOS / iPadOS | Metal | — | UIKit through the Objective-C runtime |
| Android | Vulkan | GLES 3 (older devices) | NDK native activity, JNI |
| Windows | DX12 | Vulkan | Win32 |
| Linux | Vulkan | GLES 3 | Wayland and X11 wire protocols, spoken directly |
| Web | WebGPU | WebGL 2 (the GLES backend) | Browser APIs through generated host glue |
| Headless (CI, servers, tests) | null device | — | none |

A cell counts as supported only when CI renders the reference scenes on it
(B8). A backend that cannot express a feature fails the build with a named
diagnostic; it never draws something different without saying so.

## Decisions to confirm

These change scope and must be decided by the project owner before the track
that depends on them starts.

| # | Question | Options |
|---|---|---|
| D-1 | ✅ *Decided 2026-11-07: keep them as build tools.* Does the "no third-party code" rule cover the **toolchain**? Today the compiler emits LLVM IR and uses LLVM/clang and the system linker (§12.3); platform packaging uses vendor tools (Apple code signing, Android packaging). Nothing of LLVM, clang, the system linker or the vendor packagers ships inside a program; they stay build-time tools (TARGETS_DIP.MD §2). | — |
| D-2 | ✅ *Decided 2026-11-07: the OS's TLS where the OS has one.* **TLS and cryptography.** Apple and Windows programs use the OS TLS through its own API; Azora's TLS 1.3 is written for Linux and Android only, with test vectors, constant-time review and fuzzing (B11). | — |
| D-3 | ✅ *Decided 2026-11-07: emit DXIL directly.* **DX12 shader bytecode.** The Azora shader compiler writes DXIL as it writes SPIR-V and MSL; no Windows shader compiler is used, and Shader Model 6 is available (B7). | — |
| D-4 | ✅ *Decided 2026-11-07: keep them out of the Engine.* **Store purchases and ad networks.** Apple's store API is an OS framework; Google Play billing and ad networks are reachable only through their vendors' client libraries. No vendor client library enters an Engine package; a project that needs Google Play billing or an ad network adds it itself. StoreKit, an OS framework, may be bridged like any other. | — |
| D-5 | ✅ *Decided 2026-11-07: copy at the call.* **A `String` returned by a `bridge`.** No DIP says whether Azora borrows the foreign memory or owns a copy. Today it borrows: `fin t = bufferText(b); closeBuffer(b); println(t)` reads freed memory. (A template such as `"${t}"` now always builds its own string.) A `String` a bridge returns is copied into Azora memory where the call returns (BRIDGE_DIP §6). Implementation pending. | — |
| D-6 | ✅ *Decided 2026-11-06: follow the DIP.* **By-value parameters of non-`Copy` types** transfer ownership: callers write `take` or `.clone()`, the callee destroys what it was given unless it moves it on, and read-only parameters are declared `T&`. Done and qualified the same day (progress log). | — |
| D-7 | ✅ *Decided 2026-11-07: require `take` or `.clone()`.* **A binding initialised from a place** (`var b = a`, `fin v = h.items`) is a non-owning alias today, not the move or copy §10 describes. A binding initialised from a place owns what it holds: a non-`Copy` value needs `take`, `.clone()` or a borrow (`a.&`, `a.!`), as D-6 did for parameters (OWNERSHIP_BORROWING_DIP). Implemented the same day (progress log). | — |

## Where things stand (measured 2026-11-06)

**Language (`azora-lang`, branch `0.1.0-dev`)**

- Compiler: Kotlin Multiplatform, about 66K lines in `commonMain`. A Kotlin/Native
  build (`azora.kexe`) already runs with no JVM.
- Backends: an interpreter, LLVM, and WebAssembly. WebAssembly lags: it has no
  text operations and no deep `clone`.
- Full suite: 2,871 tests and 6 failures at the start of the day; at its end,
  with native tools required, **2,878 tests, 0 failures, 0 skipped** (A1, see
  the progress log). AZLS: 91 tests, 0 failures.
- Package manager: none in the CLI. `azora-engine/tools/azpm.py` (206 lines of
  Python) resolves `workspace.azon` and `package.azon` path dependencies for the
  Engine build only.
- Self-hosting: `experimental_self_hosted/frontend/token.az`, 255 lines.

**Engine (`azora-engine`)**

- About 35K lines of Azora in 33 packages: ECS (sparse sets), UI ECS with
  retained composition, docking, gizmos, scene, tilemap, physics 2D/3D,
  animation, audio, navigation.
- Rendering and platform: Metal on macOS only, plus a WebGL module. There is no
  iOS, Android, Windows or Linux support, and no Vulkan, GLES, DX12 or WebGPU.
- Not yet "Azora all the way down": a small C runtime (`runtime/src/ffi`) and
  image decoding through CoreGraphics.
- Native qualification passes `headless-ecs`, `scheduled-ecs`, `constructor-ui`
  and `game`. `owned-ecs` fails: a dropped `World` does not destroy its
  components.

**Studio (`azora-studio/native`)**

- About 5.7K lines of Azora on the Engine UI: docking, a reflected inspector,
  a viewport with gizmos and a fly camera, an icon atlas, a theme from AZON,
  and scripted input for verification.
- The launcher redesign is in progress. There are 5 of 12 templates. There is
  no play-in-editor, no device preview, no plugin loading and no native
  language service.

**The gap in one line:** the language and the editor shell are strong; what
does not exist yet is **a backend-neutral GPU interface with Vulkan, GLES,
DX12 and WebGPU beside Metal, a platform layer for every OS beyond macOS, the
media and network stacks in Azora, a 2D runtime at production quality, a
package manager, and a complete game opened in Studio.**

---

## Short term — the next 3 to 4 months

Four tracks. A and B start now; C starts when A's ownership work lands and B
reaches mobile; D runs alongside from week 3.

### Track A — Language 0.1 stable (weeks 1–8)

| # | Milestone | Done when |
|---|---|---|
| A1 | ✅ *Closed 2026-11-06.* **Zero failing tests.** Fix `SemanticFactsTest` ×2 (tooling facts lost after a type error, and for imported calls), WebAssembly `clone` of a pack (`LiteralFactoryExecTest`), and `FoundationAbiExecTest`'s choice of a sanitizer toolchain that hangs. | `AZORA_REQUIRE_NATIVE_TESTS=1` full run: 0 failures. |
| A2 | ✅ *Closed 2026-11-06, D-6 included.* **Ownership is complete (F09/F11).** Recursive field destruction (the `owned-ecs` World), storing a non-`Copy` local without `take` (a checker error per the ownership DIP, `return` included), `"${x}"` copying its single value, and a derived `clone` sharing array fields. By-value parameters follow the DIP (D-6); owned `World` resources in `Array<Any>` remain (see the progress log). | `owned-ecs` passes; ASan/UBSan clean on the Engine probes and on Studio's scripted drag benchmark. |
| A3 | ✅ *Closed 2026-11-07; every item fixed or, where a capability is missing, a named diagnostic (progress log). Open from it: specializing generic calls over arrays whose elements are narrower than an erased slot (rejected today, GENERICS_DIP §21.2; part of A6's generics decision), `?.` on LLVM and WebAssembly, an owned pointer or spec value in a WebAssembly `clone`.* **No known miscompiles (§1.1).** Checking derivations against field capabilities, `hash` parity across backends, the `Out` conflict rule, spec return types adopted by impls, an explicit-type-argument `Clone` generic folding `T.typeName`, library bridges that collide with a program's own names, and recursive `clone` on WebAssembly (packs owning packs, arrays, functions or pointers are an explicit diagnostic today). Erased generics on LLVM (found 2026-11-06): a tuple built at a concrete layout and read through an erased `(K, V)` - `Int` is 32-bit, so `(Int, Int)` packs its components at 0/4 where `(Any, Any)` reads 0/8 (a tuple written in place at the call is now built at the callee's layout; one held in a variable or an array is not, plan items 021/053); an erased `V?` holding `0` reads as `null` (`LinkedHashMap<String, Int>` with value `0`); and `"${map.get(k)}"` rendering an erased `V?`. | Each one has a regression test on every backend. |
| A4 | ✅ *Closed 2026-11-07 (progress log). `Char` is a code point and strings count code points on every backend (user decision); the five transforms are Azora code over generated Unicode data. Open: a native `String` is never destroyed, and a bridge's borrowed argument is not freed after the call (language-wide, not text-specific).* **Text on LLVM.** `contains`, `trim`, `replace`, `split`, `toUpper`, `toLower` and `toChars` are native: UTF-8 aware and leak-free. | They run under ASan with the interpreter's output. |
| A5 | ✅ *Closed 2026-11-07 (PACKAGES_DIP.MD; progress log). Open from it: the native CLI's package host, git dependencies in AZLS, and the Engine's packages checked in full (8 of 31 compile - item 072).* **Package manager v1 (`azpm` in the CLI).** `azora new`, `build`, `run`, `test`, `add`, `remove`; an `azora.azon` manifest; path and git dependencies; `azora.lock`; workspaces (the Engine already is one). | A clean machine creates a project, adds the Engine by git, builds and tests it. |
| A6 | ✅ *Closed 2026-11-07: D-1 to D-5 and D-7 decided by the owner (table above); stability in STABILITY_DIP.MD, targets, toolchain and the web path in TARGETS_DIP.MD, generics in GENERICS_DIP §21, self-hosting below (Track E) and in the 0.1 roadmap §12.1. D-7 implemented the same day; D-5 still needs implementing.* **Decisions written down.** Stability policy (§11); target triples (macOS arm64/x64, iOS arm64, Android arm64-v8a, Windows x64/arm64, Linux x64/arm64, wasm32); the web path (the in-house WebAssembly backend brought to parity with LLVM, with browser host glue generated by Azora); the generics strategy (erasure plus monomorphization where it pays); self-hosting accepted and staged as Track E; and D-1 to D-7 above. | Merged in the DIPs and this roadmap. |

**0.1 stable ships when A1–A6 are done.** Estimate: **8 weeks of focused work
(range 6–12).** The range comes from A2 (ownership) and A5 (packages).

### Track B — Engine on every OS and backend, written in Azora (months 1–10)

The interface comes first; then the backends and platforms in the order the
flagship game needs them (Apple and Android), then the rest.

**The GPU interface (`azora-gpu`).** Modelled on WebGPU's object model, because
it maps onto Metal, Vulkan and DX12 almost one to one and onto GLES through a
thin translation:

- Objects: adapter, device, queue, buffer, texture, texture view, sampler,
  shader module, render and compute pipeline, bind group and layout,
  command encoder, render and compute pass, surface (swapchain).
- Resource lifetimes follow Azora's ownership model: a destroyed buffer cannot
  be used, and a frame's transient resources are released when its command
  buffer completes.
- Capabilities are queried, not assumed (compute, storage textures, MSAA
  counts, texture formats, limits). A missing feature is a build or startup
  error with its name, never a silent fallback.
- A **null device** for headless tests and servers.

**The Azora shader language.** Shaders are written once, in Azora's own shader
language, and compiled by a shader compiler written in Azora to SPIR-V
(Vulkan), MSL (Metal), GLSL ES 3.0 (GLES and WebGL 2), WGSL (WebGPU) and
DXIL (DX12, emitted directly: D-3). The same compiler reflects bind-group layouts, so
pipelines and Azora code agree.

| # | Milestone | Done when |
|---|---|---|
| B0 | **Azora runtime in Azora.** Move the C runtime pieces (`runtime/src/ffi`) into Azora FFI intrinsics; decode images in Azora instead of CoreGraphics. | The Engine and Studio build with no C sources of their own. |
| B1 | **Cross-compilation and packaging** for every target triple; `azora build <platform>` produces the platform package (app bundle, APK, executable, web page). | An empty Azora app starts on every OS in the matrix. |
| B2 | **Platform layer per OS**, in Azora on the OS interfaces of the matrix: windows and surfaces, touch, mouse, keyboard, gamepad, text input (IME), clipboard, lifecycle (pause, resume, background save), display metrics and safe areas, files. | Input and safe areas are correct on 3 phones, 2 desktops and 2 browsers. |
| B3 | **GPU interface, Metal and the null device.** Today's Metal code moves under the interface. | Every existing Engine probe and Studio still render, now through the interface. |
| B4 | **Shader compiler** with the SPIR-V, MSL and GLSL ES targets first. | Each reference shader compiles and passes the targets' own validators. |
| B5 | **Vulkan and GLES 3** (Android, then Linux and Windows for Vulkan). | The reference scenes at 60 fps on a mid-range Android phone with each. |
| B6 | **WebGPU** in the browser (plus the WGSL target). | The reference scenes in Chrome, Safari and Firefox. |
| B7 | **DX12** through COM from Azora (plus the HLSL/DXIL target). | The reference scenes on Windows 11 at Vulkan's frame time or better. |
| B8 | **Backend conformance CI.** Reference scenes (sprites, text, UI, 3D, MSAA, render targets, compute) on every cell of the matrix, compared against golden images with a tolerance, with frame-time budgets per cell. | A red cell blocks a merge; the matrix above is generated from the results. |
| B9 | **Media in Azora.** PNG, JPEG and QOI decoders; a TrueType/OpenType rasterizer with SDF atlases; WAV and Ogg Vorbis decoders; audio output per OS and a mixer. | Each decoder passes its format's public conformance suite; audio plays glitch-free on every OS. |
| B10 | **2D runtime at production quality**, on every backend: sprite batching and atlases, animation, particles, parallax, tweening, UI ECS, localization, save files in AZON. | Each has an Engine probe in B8's CI. |
| B11 | **Networking and profiling in Azora.** Sockets, HTTP/1.1, WebSocket, TLS (D-2); a frame profiler with a Studio view. | HTTP and TLS pass their test vectors and interoperate with public servers. |

The flagship game needs B0–B5 and B9–B11 on Apple and Android. B6–B8 can run
in parallel under a second owner.

**Estimate.** The from-scratch rule makes this the largest track. With one
owner: about **5–7 months** for the Apple and Android path, and another
**4–6 months** for WebGPU, DX12, Linux, Windows and the conformance CI. A
second owner on B6–B8 brings both to about 7 months.

### Track C — The flagship game (months 3–8)

A complete 2D mobile game built entirely with Azora: menus, gameplay and
physics, localization, saves, online features and store builds.

| # | Milestone | Done when |
|---|---|---|
| C1 | **Game model and saves** in pure Azora logic. | Its tests pass as Azora `test` blocks. |
| C2 | **Gameplay loop and physics.** | Recorded play sessions replay to the same frames (golden captures). |
| C3 | **Every screen in UI ECS.** | Screenshots match their references on the device matrix. |
| C4 | **Online features.** | They pass against a test server on both mobile OSes. |
| C5 | **Release.** Store builds from `azora build --release`. | 60 fps on a mid-range Android phone and an older iPhone, zero sanitizer reports, a crash-free week of internal testing, accepted by both stores. |

### Track D — Studio becomes professional (weeks 3 to month 4)

| # | Milestone | Done when |
|---|---|---|
| D1 | **The launcher and the 12 templates**, every template ECS- and UI-ECS-based, with real content. | Every template creates, builds and plays from the launcher. |
| D2 | **Open a complete project** (the flagship game): hierarchy, inspector, assets, scenes from AZON, Console and Problems fed by the compiler. | Edit → save → reopen keeps everything. |
| D3 | **Play-in-editor.** Studio builds the project and runs it inside the viewport (a child process whose frames are shared with Studio), with stop, restart and logs in the Console. | Pressing Play runs the game in the viewport. |
| D4 | **Multi-device preview.** A device catalogue in AZON (screen size, density, safe areas, cutouts, orientation); several live instances side by side in device frames, each with its own input and save, with optional synced navigation and snapshots. Built as the first editor plugin (D5). | Four devices run the game at once at 60 fps. |
| D5 | **Editor plugin system v1.** A plugin is an Azora package that declares `@Panel`, `@Command`, `@Inspectable`, asset importers and viewport tools. Studio discovers them by reflection, as it already does for its own panels, and rebuilds itself with the project's plugins (compile-time composition, no ABI needed). A Packages page in Studio uses `azpm`. | Device preview and one sample third-party plugin install with `azora add`, with no Studio source change. |
| D6 | **Native language service.** The semantic-facts API (A1) inside Studio: diagnostics as you type, completion, go to definition, hover. | Under 100 ms on a 2K-line file. |
| D7 | **Native menu bar, floating panels as OS windows, undo and redo.** | Verified by scripted input only. |

---

## Long term — months 4 to 18

### Track E — Self-hosting (months 3–9)

Accepted (A6, 2026-11-07): the 0.1 compiler is the Kotlin one, and the Azora
compiler is built in these stages after it. Start when 0.1 is stable; a moving
language makes a second compiler twice the work. Every stage is useful on its
own:

1. **E1 Lexer and parser in Azora** (from `experimental_self_hosted/token.az`).
   Differential test: on every `.az` file in std, the Engine, Studio and the test
   fixtures, the Azora and Kotlin parsers produce the same AST dump. This is
   also the largest stress test of the language itself.
2. **E2 Semantic analysis and IR** behind the same `IrProgram` text form: both
   compilers emit identical IR for the 2.9K test programs.
3. **E3 Code generation in Azora.** `azc` (written in Azora) compiles itself;
   stage 2 and stage 3 binaries are bit-identical (the bootstrap fixpoint).
4. **E4 Switch over.** `azc` becomes the shipped compiler. The Kotlin compiler is
   frozen as stage 0 for bootstrapping from source, and AZLS and Studio use
   `azc`'s semantic facts directly.

Estimate: **5–7 months** for E1–E4. E1 alone is about 6 weeks and pays off
immediately (a fuzzer, a formatter, Studio parsing without Kotlin).

### Track F — Ecosystem (months 6–18)

- **Package registry**: publish, semantic versioning, signing, yanking, docs per
  package. The Engine's 33 packages are the first tenants.
- **Editor plugins v2**: dynamic loading once the ABI decision (§12.4) allows
  it, so plugins install without rebuilding Studio; a plugin catalogue on the
  same registry.
- **Hot reload** of game code in play-in-editor (reload the game module, keep
  the World).
- **Debugger**: debug information from the code generator, breakpoints in Studio.
- **Studio on every desktop OS**: Windows and Linux builds of Studio and the
  launcher on the same GPU interface and platform layer.
- **The flagship game in the browser** (WebGPU with the WebGL 2 fallback) as the
  web target's gate.
- **3D**: a complete 3D game as the second flagship, then the 3D templates.
- **Consoles** later, as backends behind the same interface.
- **1.0**: the stability policy becomes a guarantee.

---

## Order of work

```
months      1        2        3        4        5        6        7        8
A lang      [A1][A2 ownership][A3][A4][A5 packages][A6]  0.1 ✔
B mobile    [B0][B1 triples][B2 platform][B3 GPU+Metal][B4 shaders][B5 Vulkan+GLES][B9 media][B10 2D][B11 net]
B others                          [B6 WebGPU   ][B7 DX12        ][B8 conformance CI]
C game                                      [C1][C2 gameplay][C3 screens][C4][C5 release]
D studio      [D1 ][D2 open][D3 play][D4+D5 plugins][D6][D7]
E self-host                         (starts after 0.1) [E1 parser][E2 ...
```

- One owner per track; sessions that write code never touch the same files.
- Dependencies: A2 before C (the game must not leak), B1–B5 before C2,
  B9–B10 before C3, C1 before D2, A5 before D5.

## Risks

| Risk | Effect | Mitigation |
|---|---|---|
| The from-scratch scope (platform layers, five backends, shader compiler, codecs, TLS) | Track B takes longer than the estimate | Order strictly by what the flagship game needs; every component has a conformance suite from day one, so progress is measured, not guessed |
| Cryptography written in-house (Linux and Android, D-2) | Security defects | Published test vectors, constant-time review, fuzzing, an external review before 1.0 |
| Ownership edge cases keep surfacing (F09) | Crashes or leaks on device | A sanitizer gate on every Engine probe and on the game's tests from week 1 |
| Five backends drift apart | A scene looks right on one backend and wrong on another | One interface shaped like WebGPU, one shader source, golden-image CI on every cell (B8); no backend-specific code above `azora-gpu` |
| DX12 and Windows need a Windows machine | B7 cannot be verified on a Mac | A Windows CI runner from month 3; Vulkan on Windows first |
| Erased generics cost speed in hot loops | Frame time over budget | Measure the gameplay loop early; monomorphize ECS hot paths if needed (§12.2) |
| Self-hosting started too early | Two compilers to fix | Start E1 only at 0.1; E1 is differential, so it cannot drift |

## First steps

1. ~~Close A1~~ — done 2026-11-06.
2. ~~A2~~ — done 2026-11-06, D-6 included. ~~A3~~, ~~A4~~ and ~~A5~~ — done 2026-11-07.
3. ~~Settle D-1 to D-5 and D-7~~ — decided 2026-11-07; D-7 implemented; implement D-5.
4. Write three DIPs before code: the `azora-gpu` interface, the platform layer,
   and the shader language.
5. B1 spike: an empty Azora program on the iOS simulator and the Android
   emulator, which shows the real size of Track B.
6. D1: finish the launcher.
