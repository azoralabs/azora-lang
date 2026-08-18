# Azora GPU Programming - DIP

> Status: **proposal.** `std.gpu` defines the first backend-neutral host API.
> The `kernel` declaration and GPU address-space syntax described here are the
> proposed language layer; they are not reserved words until the parser,
> type-checker, and compiler backends agree on their semantics.

Azora should let a developer write a shader in Azora, type-check it with the
same symbol and generic rules as the rest of the language, and execute it on a
GPU without hand-maintaining a second WGSL, GLSL, HLSL, Metal, or CUDA source
file. The source of truth should be the Azora kernel. A target backend may
lower that kernel to its native shader representation, but generated shader
text is an artifact, not application source.

The design in this DIP chooses three properties together:

1. **One source language.** A kernel is an Azora declaration with a restricted,
   statically checked body.
2. **Explicit host/device boundaries.** Buffers, compilation, dispatch, and
   synchronization are visible in host code; a GPU call is never hidden inside
   a pure function.
3. **Portable semantics first.** The common kernel subset targets WebGPU/WGSL,
   SPIR-V, Metal, HLSL, and CUDA-style backends. Native extensions are explicit
   and capability-checked rather than silently emulated.

The initial source-level API is available in [`std/gpu.az`](../std/gpu.az).
It lets the compiler and standard library prove the host-side model now while
the compiler-native kernel syntax is implemented incrementally.

```azora
import std.gpu

func main() {
    fin device = open(GpuBackend.Default)
    fin source = computeShader(
        "addVectors",
        "fn addVectors() { /* portable kernel source */ }",
        64,
    )
    fin shader = compile(device, source)
    fin pipeline = pipeline(device, shader)
    dispatch(device, pipeline, dispatchFor(4096, 64))
    finish(device)
}
```

The bridge calls in this example are intentionally explicit. A simulator can
be used in tests, while a WebGPU, Vulkan, Metal, DirectX, or CUDA adapter can
provide the same ABI in production.

## 1. Goals and non-goals

### Goals

- Write compute, vertex, and fragment programs in Azora syntax.
- Make buffer element types, mutability, address spaces, and resource bindings
  visible to the type checker.
- Compile one kernel to several GPU targets where the portable subset permits.
- Keep device allocation, upload, dispatch, readback, and synchronization
  explicit and inspectable.
- Make the common one-dimensional compute case concise without hiding its
  workgroup geometry.
- Reject unsupported operations and resource layouts with a diagnostic before
  a backend submits work.
- Allow generated target source and shader reflection to be inspected for
  debugging and reproducible builds.
- Interoperate with `std.ml` tensors and `std.quantum` exports through explicit
  buffer and artifact bridges.

### Non-goals for the first version

- Replacing a full graphics engine, window system, or asset pipeline.
- Guaranteeing identical floating-point results across GPU vendors.
- Silently running GPU code on the CPU when a requested device is unavailable.
- Exposing vendor-specific intrinsics in the portable kernel subset.
- Making every Azora function callable from a kernel. Host I/O, allocation,
  reflection, exceptions, dynamic dispatch, and bridge calls are host-only.
- Adding a second shader language embedded as an opaque string literal.

## 2. The ergonomic model

The ergonomic unit is a **kernel**, not a string and not a manually assembled
pipeline. A kernel has:

- an entry-point name;
- typed parameters in explicit GPU address spaces;
- a body written in the kernel-safe Azora subset;
- a declared or inferred workgroup shape;
- compiler-generated resource layout and reflection metadata.

The proposed spelling is:

```azora
@WorkgroupSize(64, 1, 1)
kernel addVectors(
    storage output: GpuBuffer<Float>,
    storage left: GpuBuffer<Float>,
    storage right: GpuBuffer<Float>,
    uniform count: Int,
) {
    fin i = gpu::globalId.x
    if i < count {
        output[i] = left[i] + right[i]
    }
}
```

The host side remains ordinary Azora:

```azora
import std.gpu

func runAdd(device: GpuDevice, values: Array<Float>) {
    fin left = upload<Float>(device, values, BufferUsage.Storage)
    fin right = upload<Float>(device, values, BufferUsage.Storage)
    fin output = buffer<Float>(device, values.size, BufferUsage.Storage)
    fin program = pipeline(device, compile(device, addVectors))

    dispatch(device, program, dispatchFor(values.size, 64))
    finish(device)
    return download<Float>(device, output)
}
```

This separates the two kinds of complexity that are often accidentally mixed:
the kernel describes *what each invocation computes*, and host code describes
*when work is submitted and how memory is owned*. The common case stays short,
but synchronization and transfers remain visible when they matter.

### 2.1 Why a typed kernel block is the target

Three alternatives were considered:

| Design | Benefit | Problem |
|---|---|---|
| Raw WGSL/GLSL strings | Available immediately | No Azora type checking, symbol navigation, or portability |
| A host-side builder DSL | Easy to prototype | Verbose for control flow and becomes a second AST API |
| Typed `kernel` declarations | One source, normal tooling, compiler lowering | Requires a restricted kernel type/effect checker |

Typed kernel declarations are the long-term choice. `std.gpu.computeShader`
exists as an interim artifact-construction API, but it must not become the
permanent authoring experience.

### 2.2 Contextual vocabulary

The proposed words remain contextual until GPU syntax is established. Existing
Azora programs must continue to use names such as `kernel`, `buffer`, `uniform`,
and `group` in ordinary code.

| Proposed word | Context | Meaning |
|---|---|---|
| `kernel` | declaration | A compute entry point with GPU-safe semantics |
| `shader` | declaration/type | A vertex, fragment, or compute program |
| `storage` | parameter qualifier | Read/write device memory or read-only storage |
| `uniform` | parameter qualifier | Small read-only constant data |
| `workgroup` | parameter/annotation | Shared memory or local execution group |
| `vertex` | declaration | A vertex-stage shader |
| `fragment` | declaration | A fragment-stage shader |
| `dispatch` | host operation | Submit compute workgroups |
| `draw` | host operation | Submit a render pipeline |
| `barrier` | kernel statement | Synchronize workgroup memory |
| `globalId` | GPU builtin | Invocation ID across the dispatch |
| `localId` | GPU builtin | Invocation ID inside its workgroup |
| `workgroupId` | GPU builtin | Workgroup ID in the dispatch |
| `binding` | resource metadata | Stable resource slot or generated binding |

The contextuality rule is important. This must remain legal outside a GPU
declaration:

```azora
func dispatch(queue: String): Int { return queue.size }
```

The parser recognizes GPU-only forms only after `kernel`, `shader`, or a GPU
address-space qualifier has established that context.

## 3. Kernel type and effect rules

The kernel checker should use the same resolved symbols as host code. It must
not match resources by spelling or perform a second name-based lookup. A
parameter's type and its declaration identity determine its binding, and the
compiler emits the reflected binding from that symbol.

### 3.1 Allowed kernel values

The portable subset initially allows:

- `Bool`, integer and floating-point scalar types;
- fixed-size vectors and matrices;
- immutable value packs composed of those types;
- `GpuBuffer<T>` and read-only `uniform` values;
- `Array<T, N>` when `N` is compile-time known;
- GPU builtins such as `gpu::globalId` and `gpu::localId`;
- ordinary arithmetic, comparisons, conditionals, bounded loops, and calls to
  other `@kernel` helpers.

`String`, dynamic arrays, `ArrayList`, host references, reflection handles,
  bridge packs, and closures are host-only unless a future DIP explicitly adds
  a device representation.

### 3.2 Address spaces

Address spaces are part of the parameter type, not comments:

| Space | Default access | Intended use |
|---|---|---|
| `uniform` | read-only | Small constants, dimensions, camera/model data |
| `storage` | read-only unless `inout` | Large buffers and tensors |
| `workgroup` | read/write | Fast shared scratch memory |
| `private` | read/write | Per-invocation locals |

The compiler rejects writing through a read-only parameter. It also rejects a
host buffer whose element type or usage does not match the kernel signature.

### 3.3 Memory and synchronization

Workgroup memory is not coherent by default. `barrier` is a compiler-checked
workgroup operation and cannot be used to synchronize unrelated workgroups.
The first portable implementation should support a workgroup barrier and a
storage-memory barrier where the target can provide one. A backend that cannot
honour a requested ordering must produce a capability error.

Bounds checks are explicit in portable kernels. The canonical pattern is:

```azora
fin i = gpu::globalId.x
if i < count {
    output[i] = input[i] * scale
}
```

The compiler may prove a check redundant or lower it efficiently, but it must
not remove a check merely because a host dispatch currently looks safe.

## 4. Pipeline and resource model

`std.gpu` exposes opaque handles for resources owned by a device:

```text
GpuDevice                 selected adapter and queue
GpuBuffer<T>               typed device allocation
GpuShader                  compiled shader artifact
GpuPipeline                validated shader plus resource layout
WorkgroupSize              local x/y/z shape
DispatchSize               submitted workgroup x/y/z counts
ShaderSource               inspectable intermediate source artifact
```

The normal flow is:

```text
host values -> upload -> typed buffer -> compile kernel -> pipeline
                                     -> dispatch -> finish -> download
```

Resource bindings are generated from parameter symbols in declaration order.
An explicit `@binding(group, slot)` annotation is allowed only when an external
ABI requires a stable number. Duplicate bindings, incompatible usages, and
unbound resources are compile-time or pipeline-creation errors.

The pipeline layout must be inspectable. The future API should expose a
read-only description containing each binding's symbol identity, address space,
element type, group, and slot. This keeps shader debugging and IDE navigation
based on symbols rather than matching names in generated target code.

## 5. `std.gpu` initial surface

The initial backend-neutral module is implemented in [`std/gpu.az`](../std/gpu.az).
Its pure helpers are usable in the interpreter and tests; its bridge operations
are the stable boundary for actual GPU adapters.

| API | Purpose |
|---|---|
| `GpuBackend`, `ShaderStage`, `BufferUsage` | Portable target and resource vocabulary |
| `QueueKind`, `GpuFeature`, `ReductionOp`, `MemoryOrder` | Explicit execution and capability vocabulary |
| `WorkgroupSize`, `DispatchSize`, `LaunchConfig`, `ShaderSource` | Geometry and inspectable source values |
| `GpuLayout`, `rowMajor`, `columnMajor`, `tiled`, `linearIndex` | No-copy tensor views and tiled data movement |
| `workgroup`, `dispatch`, `dispatchFor`, `dispatchFor3d`, `launch` | Validate and derive execution geometry |
| `computeShader`, `shader` | Interim source artifact constructors |
| `open`, `info`, `stream`, `commands` | Device, limits, queues, and command encoding |
| `buffer`, `upload`, `download`, `uploadAsync`, `downloadAsync` | Typed and asynchronous memory bridges |
| `compile`, `pipeline` | Compile and validate shader resources |
| `bindBuffer`, `bindUniform`, `copyBuffer`, `fillBuffer` | Symbol-slot resource binding and copies |
| `dispatch`, `launch`, `draw`, `submit`, `wait`, `finish` | Explicit execution and synchronization |
| `barrier`, `subgroupReduce`, `workgroupReduce`, `atomicAdd` | Portable parallel primitives |
| `shared`, `copyAsync`, `matrixMultiplyAccumulate` | Tiling, async memory, and matrix acceleration |

The standard library does not pretend to implement a vendor runtime. A
WebGPU/WGSL adapter is a good first production target because it provides a
portable validation boundary; Vulkan/SPIR-V, Metal, DirectX, and CUDA adapters
can follow without changing the source-level API.

## 11. Capability bar: beyond a Mojo-class GPU 1.0

The target is not to win by making unportable assembly intrinsics look nicer.
The target is to make the whole GPU workflow more capable: source authoring,
type safety, layout control, scheduling, performance portability, diagnostics,
and integration with the rest of Azora.

Mojo's official GPU documentation is a useful baseline. It describes GPU
functions launched through `DeviceContext`, device buffers and asynchronous
queue operations, warp/block/cluster primitives, asynchronous copies and
atomics, layout-aware tensors, and architecture-specific matrix-multiply
operations. See the [GPU package](https://docs.modular.com/mojo/std/gpu/),
[DeviceContext](https://docs.modular.com/mojo/std/gpu/host/device_context/DeviceContext),
[LayoutTensor](https://docs.modular.com/mojo/kernels/layout/layout_tensor/LayoutTensor/),
[warp and block operations](https://docs.modular.com/mojo/manual/gpu/block-and-warp),
and [MMA operations](https://docs.modular.com/mojo/std/gpu/compute/mma/mma).

Azora's acceptance bar is:

| Capability | Azora 1.0 requirement |
|---|---|
| Kernel authoring | A typed `kernel` declaration in Azora, with ordinary symbol resolution and IDE navigation |
| Host execution | Multiple explicit queues, command lists, events, deferred readbacks, and structured `await` integration |
| Memory | Typed buffers, no-copy strided/tiled views, ownership-aware transfers, and checked host-side geometry |
| GPU hierarchy | Grid, workgroup, subgroup, cluster, and invocation builtins with portable fallbacks |
| Synchronization | Workgroup/device/system barriers, atomics with explicit memory order, and async global-to-shared copies |
| Tensor performance | Layout-driven tiling, vectorized loads, reductions/scans, and a portable MMA abstraction |
| Portability | WebGPU, Vulkan, Metal, DirectX 12, CUDA, HIP, and a deterministic CPU reference target |
| Specialization | Compile-time feature selection, target capabilities, cacheable artifacts, and autotuned launch variants |
| Safety | Resource/type/layout validation, no accidental blocking, explicit unsafe escape hatches, and source-mapped diagnostics |
| Tooling | Go-to-definition from host bindings to kernel parameters, generated shader preview, profiling, and trace capture |
| AI integration | `std.ml.Tensor` views and fused GPU operations without forcing a second tensor representation |

This is a stronger product goal than “a bridge function that can launch a
kernel.” It gives Azora a coherent programming model for both a small shader
and a production tensor pipeline.

### 11.1 What “better” means in measurable terms

The claim must be testable. Azora should be considered ahead only when the
following are true for the same workload and target:

- the kernel is written once and the compiler can emit at least two native
  targets without source changes;
- a resource binding can be renamed in source without breaking host code,
  because binding identity comes from symbols and reflected signatures;
- a tiled tensor view changes logical layout without copying its backing buffer;
- an async upload, kernel launch, and readback can overlap on independent
  queues, with `wait` only at the consumer boundary;
- a missing capability fails during pipeline validation with a source location,
  instead of failing after opaque backend submission;
- a CPU reference run and a GPU run can share the same kernel test vectors;
- a debug build can show generated target code, resource layout, launch shape,
  memory traffic, and timing for the same Azora declaration.

Performance is still measured on real hardware. API size alone is not a
performance claim.

## 12. GPU architecture layers

Azora's GPU stack is deliberately layered so a high-level user does not need to
learn all of the low-level vocabulary, while an expert can reach it without
leaving the language.

### Layer 0: typed resources

`GpuBuffer<T>`, `GpuTensor<T>`, `GpuShared<T>`, `GpuLayout`, `GpuDeviceInfo`,
and `GpuCapabilities` describe data and limits. A buffer is never an untyped
void pointer in portable code. A tensor is a view, not an owning copy.

### Layer 1: kernels

`kernel` and `shader` declarations are normal Azora symbols with a device-safe
effect. The compiler produces a `ShaderSource`-like intermediate artifact with
the kernel's resolved parameter identities, address spaces, layout metadata,
and source map.

### Layer 2: portable parallel primitives

The common library provides subgroup reductions and scans, workgroup
reductions, barriers, atomics, shared memory, and asynchronous copies. A
backend can lower these to warp, wave, block, or SIMD instructions. The
portable behavior is specified independently of the vendor spelling.

### Layer 3: tuned target features

Tensor-core MMA, subgroup matrix operations, cluster synchronization, mesh
shaders, ray tracing, and vendor cache controls are opt-in features. A kernel
declares what it needs; `GpuCapabilities` and pipeline validation decide if the
selected device can provide it. There is no silent “best effort” lowering for a
required feature.

### Layer 4: fused operations

`std.gpu` should ship reusable, layout-aware operations for map, zip, reduce,
scan, sort, histogram, convolution, matrix multiplication, and attention. A
fused operation may choose a tuned kernel internally, but its buffer/layout
contract remains visible and it can fall back to the CPU reference target for
testing.

## 13. Layouts, tensors, and memory movement

The main ergonomic lesson from GPU tensor programming is that a flat buffer is
not enough. The same data may be row-major, column-major, strided, vectorized,
or tiled for a particular workgroup. `GpuLayout` is therefore a first-class
value and `GpuTensor<T>` is a non-owning view over a typed buffer.

```azora
fin storage = uploadAsync<Double>(stream, values, BufferUsage.Storage)
fin layout = tiled(@arr[4096, 4096], 32, 32, 1)
fin matrix = view<Double>(storage, layout)
```

The compiler and adapters must preserve these invariants:

- a view never changes the owner or copies the buffer;
- shape, stride, tile, and element type are reflected in a pipeline binding;
- host-side indexing is checked against the view shape;
- kernel-side unchecked access requires an explicit `unsafe` region or a proof
  that the compiler can emit;
- a layout conversion is an operation that may copy, while a view conversion
  never does.

The standard layout API should grow to include vectorized element layouts,
masked edge tiles, hierarchical tiles, alignment, and partitioning across
invocations. Those are the pieces needed for efficient matrix multiplication,
convolution, stencil, and attention kernels.

## 14. Hierarchy and collective operations

The compiler-native builtins should expose a consistent hierarchy:

```text
gpu::globalId       invocation coordinate in the dispatch
gpu::localId        coordinate inside the workgroup
gpu::workgroupId    workgroup coordinate in the grid
gpu::globalSize     total invocation extent
gpu::localSize      workgroup extent
gpu::subgroup       subgroup identity and lane count
```

The API must define the participation contract for every collective:

- subgroup operations require all active lanes in the participating subgroup;
- workgroup operations require all non-divergent invocations in the group;
- a barrier in divergent control flow is rejected unless uniformity is proven;
- cluster and grid operations are available only when the backend advertises
  the required feature;
- reductions and scans specify identity values, numeric behavior, and whether
  the result is broadcast or returned only to a leader.

This is more useful than exposing a bag of vendor intrinsics because a kernel
can be validated and lowered consistently across warp sizes of 32 or 64.

## 15. Async execution and command graphs

GPU work is asynchronous by default. `GpuStream`, `GpuCommandList`, and
`GpuEvent` make the dependency graph explicit:

```text
upload(A) ───────┐
                 ├─> launch(kernel) ──> download(C) ──> await result
upload(B) ───────┘
```

The API must support:

- independent compute, graphics, and transfer queues;
- event-to-event dependencies without blocking the CPU;
- deferred readbacks that wait only when resolved;
- command-list reuse when the resource layout is unchanged;
- graph capture and replay for iterative inference/training workloads;
- cancellation and deadlines through the host effect system;
- profiling timestamps attached to events and source-mapped kernel spans.

`finish(device)` remains a convenience for small programs. Production code
should prefer `wait(event)` or a structured `await` so unrelated work can
continue.

## 16. High-performance kernel features

The portable kernel subset should cover the common performance ladder:

1. scalar elementwise code;
2. vectorized loads and stores;
3. workgroup tiling with shared memory;
4. subgroup shuffle/reduce/scan;
5. asynchronous global-to-shared copies;
6. atomics and memory-ordering fences;
7. cooperative multi-workgroup algorithms;
8. matrix multiply-accumulate through tensor cores or matrix cores;
9. target-specific extensions behind `requires GpuFeature.X`.

The syntax should keep the fast path readable:

```azora
@workgroupSize(128, 1, 1)
kernel reduceSum(
    storage input: GpuBuffer<Float>,
    storage output: GpuBuffer<Float>,
    uniform count: Int,
) {
    fin i = gpu::globalId.x
    fin value = if i < count { input[i] } else { 0.0f }
    fin total = gpu::subgroupSum(value)
    // The compiler lowers this to the portable workgroup reduction primitive.
    fin result = gpu::workgroupSum(total)
    if gpu::localId.x == 0 {
        output[gpu::workgroupId.x] = result
    }
}
```

The exact syntax is still proposed, but the semantic requirements are fixed:
the compiler knows which values are uniform, which operations are collective,
where synchronization is needed, and which target feature is required.

## 17. Safety and diagnostics as performance features

GPU errors are expensive when they surface only after a long compile or a
device reset. Azora should report:

- the kernel declaration and source span for an unsupported device operation;
- the resolved symbol and address space for every binding;
- the expected and actual element type/layout for every buffer;
- the workgroup, shared-memory, register, and feature requirements;
- whether a collective is called from divergent control flow;
- whether a buffer view can exceed its owner allocation;
- which compile option enabled fast math or relaxed determinism.

`unsafe gpu` may permit raw pointers, unchecked layouts, vendor intrinsics, or
manual barriers, but its boundary must be visible in source and in artifact
metadata. Safe kernels are the default. This is a major ergonomic advantage:
the common path can remain high-level without making expert performance work
impossible.

## 18. Acceptance tests and benchmarks

GPU support is not complete when the declarations parse. The implementation
must include:

- a CPU reference executor for every portable kernel primitive;
- golden tests for symbol-derived bindings and generated shader source;
- cross-target compilation tests for WGSL, SPIR-V, Metal, CUDA, and HIP where
  the backend is installed;
- correctness tests for map, tiled matmul, reduce, scan, histogram, sort, and
  attention-shaped workloads;
- race and barrier diagnostics with intentionally divergent kernels;
- async overlap tests proving upload, execution, and readback do not introduce
  accidental device-wide synchronization;
- layout tests proving views are zero-copy and stride calculations are correct;
- numerical tests for deterministic mode and documented fast-math differences;
- benchmark reports containing throughput, latency, transfer time, occupancy,
  shared-memory usage, and compilation/cache time.

The benchmark suite should compare Azora's generated code with a hand-written
reference for each target. “Better than Mojo” is only meaningful per workload,
device, and compiler configuration.

## 19. Revised implementation roadmap

1. **Library contract:** land the expanded `std.gpu` values and bridge surface,
   including layouts, queues, events, atomics, collectives, async copies, and
   MMA handles.
2. **CPU reference:** implement pure/reference semantics for layouts,
   dispatch geometry, reductions, scans, and small kernels.
3. **Kernel syntax:** parse contextual `kernel`, address-space qualifiers,
   builtins, workgroup annotations, and `requires` feature clauses.
4. **Kernel checker:** enforce device-safe calls, ownership, layout bounds,
   uniform control flow, collective participation, and barrier legality.
5. **WGSL/SPIR-V:** lower the portable subset with symbol maps, reflection,
   capability checks, and generated-source previews.
6. **Native performance:** add CUDA/HIP, Metal, Vulkan, and DirectX backends;
   implement vectorization, tiling, async copy, subgroup operations, and MMA.
7. **Graph/runtime:** add queue dependency graphs, graph capture/replay,
   event profiling, cancellation, and memory pooling.
8. **Tooling and libraries:** add IDE navigation, kernel diagnostics, autotuning,
   fused `std.ml` operations, graphics stage helpers, and benchmark dashboards.

The standard is ambitious on purpose: Azora should compete on the complete GPU
developer experience, not only on whether a single kernel can be launched.

## 6. Backend and artifact boundary

Each backend must declare capabilities before pipeline creation:

- supported shader stages and address spaces;
- maximum workgroup dimensions and storage sizes;
- supported scalar widths and matrix layouts;
- available subgroup operations and atomic operations;
- whether readback, timestamps, or presentation is supported.

`open` selects a backend, but it does not silently fall back to another
one. `GpuBackend.CpuFallback` is an explicit test/development choice, not a
hidden recovery path. A compiled shader artifact should include:

- normalized kernel symbol identity;
- target and compiler version;
- generated source or binary;
- resource layout;
- required capabilities;
- source map back to Azora declarations.

This makes shader compilation cacheable and diagnostics actionable.

## 7. ML, quantum, and graphics interoperability

`std.ml.Tensor` can be uploaded as a typed storage buffer after its shape and
layout are validated. A future convenience function may accept a tensor
directly, but it must preserve an explicit copy/ownership boundary.

`std.quantum` can export circuits or measurement data to a GPU simulator via a
backend bridge. That is a separate execution target from a physical QPU and
must not be represented as if it were the same device.

Vertex and fragment shaders share the same kernel-safe expression subset, but
their entry-point interfaces are stage-specific. A render pipeline must reject
missing or incompatible vertex/fragment locations before drawing.

## 8. Safety, determinism, and resource limits

- GPU execution is effectful; `open`, allocation, upload, dispatch, draw,
  readback, and finish are never pure operations.
- Device handles and buffers are owned resources. A future ownership checker
  may make use-after-destroy impossible; until then, adapters must reject it.
- Dispatch dimensions, buffer sizes, and workgroup sizes are validated before
  submission.
- Integer overflow in geometry calculation is an error, not wraparound.
- Timeouts, memory budgets, and cancellation belong to the device bridge and
  must be explicit in a later options value.
- Floating-point behavior is target-dependent unless a kernel opts into a
  stricter portable precision mode.
- Debug builds should support CPU reference execution for deterministic small
  kernels, but that mode must be selected explicitly.

## 9. Implementation plan

1. **Current library layer:** ship `std.gpu` handles, source artifacts,
   geometry helpers, and bridge declarations.
2. **Kernel parser context:** add contextual `kernel`/`shader` declarations and
   GPU builtin resolution without reserving ordinary identifiers globally.
3. **Kernel checker:** enforce address spaces, device-safe calls, resource
   access, bounded control flow, and explicit barriers.
4. **Portable lowering:** lower compute kernels to WGSL first, retaining symbol
   maps and generated binding metadata.
5. **Native backends:** add SPIR-V/Vulkan, Metal, DirectX, and CUDA adapters as
   separate bridge implementations.
6. **Tooling:** add syntax highlighting, go-to-definition from a host binding
   to its kernel parameter, generated-shader preview, and capability diagnostics.

## 10. Open questions

- Should `kernel` be a declaration modifier or a distinct declaration kind?
- Should workgroup size be an annotation, a typed constant, or both?
- Which subgroup and atomic operations belong in the portable subset?
- Should render-stage locations use generated symbols or require explicit
  `@location` annotations at the stage boundary?
- How should async queue submission integrate with Azora's future effect and
  cancellation model?

The answers should preserve the central rule: shader code is Azora code with a
small, explicit device-safe subset, and execution is an explicit effect through
`std.gpu` rather than an accidental side effect of a normal function call.
