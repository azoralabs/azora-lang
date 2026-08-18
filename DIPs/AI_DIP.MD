# Azora AI and Machine Learning - DIP

> Status: **proposal with an initial standard-library surface.** `std.ai` and
> `std.ml` define backend-neutral APIs now. Provider runtimes, accelerators, and
> model formats remain bridge implementations. The keyword proposals below are
> contextual until the parser, type checker, and effect system agree on them.

Azora should make it practical to build AI systems without making one model
vendor, accelerator, or deployment protocol part of the language. The same
program should be able to use a local model, a remote API, an ONNX-style native
runtime, or a future accelerator backend through explicit adapters.

The design has two complementary layers:

- `std.ai` is for model-facing applications: conversations, prompts, tool
  declarations, embeddings, generation options, and explicit model calls.
- `std.ml` is for numerical and training work: tensors, datasets, metrics,
  activation helpers, model handles, optimizers, and training boundaries.

Both layers keep side effects visible. Constructing a prompt, tensor, or dataset
is ordinary local computation. Calling a model, loading a checkpoint, training,
or saving an artifact crosses a bridge and can fail, consume resources, access
private data, or perform network I/O.

```azora
import std.ai

func answer(model: AiModel, question: String): Message {
    fin options = generationOptions()
    return ask(model, "You are a concise assistant.", question, options)
}
```

The initial implementation uses ordinary Azora types and bridge functions. A
future language layer may add compact syntax, but code written against the
library API remains the compatibility baseline.

---

## 1. Goals and non-goals

### Goals

- Keep provider and accelerator choices outside the core language.
- Make network, model execution, training, and file persistence explicit.
- Support both hosted inference and local/native inference.
- Represent prompts, conversations, tools, embeddings, tensors, datasets, and
  training reports with inspectable Azora values.
- Make resource limits, privacy boundaries, and model capabilities visible.
- Permit deterministic fake backends for tests.
- Allow `std.ai` and `std.ml` to interoperate without making either one depend
  on a particular framework.

### Non-goals for the first version

- Claiming that a bridge call is pure or infallible.
- Shipping a complete neural-network framework in the standard library.
- Hiding prompt construction or tool invocation inside magical compiler syntax.
- Making model-generated text trusted program code by default.
- Choosing a single tensor layout, quantization format, tokenizer, or vendor.
- Letting a model access files, network services, secrets, or tools implicitly.

---

## 2. `std.ai`: model-facing applications

The first AI module is [`std/ai.az`](../std/ai.az). It provides:

| API | Purpose |
|---|---|
| `Role`, `Message`, `Conversation` | Structured chat history |
| `GenerationOptions` | Bounded and reproducible generation configuration |
| `ToolSpec` | A description of an explicitly approved tool |
| `AiModel` | An opaque provider/model handle |
| `connect`, `complete`, `embed` | Provider bridge boundary |
| `ask` | A small convenience wrapper for one user request |

```azora
import std.ai

func summarize(model: AiModel, text: String): Message {
    fin options = generationOptions()
    options.maxTokens = 256
    return ask(model, "Summarize faithfully.", text, options)
}
```

`Conversation` stores structured messages rather than one interpolated string.
This preserves role boundaries and gives provider adapters enough information to
serialize the request correctly.

### 2.1 Generation policy

`GenerationOptions` should carry limits and policy rather than provider-specific
flags:

```text
maxTokens       hard output budget
temperature     sampling temperature
topP            nucleus sampling threshold
seed            reproducibility request; nullable by convention
stop            output stop strings
allowTools      whether the provider may return a tool request
```

Providers may support only a subset. Unsupported options must be reported in a
capability response or execution error; they must not be silently ignored when
that would change safety, cost, or determinism.

### 2.2 Tools and agents

`std.ai` describes tools but does not execute arbitrary model-generated calls.
An application owns the dispatch loop:

```azora
pack ToolSpec {
    var name: String
    var description: String
    var schema: String
}
```

The `schema` is deliberately a string in the first version so JSON Schema,
OpenAPI fragments, or an application-defined schema can be used. A later
version may add a typed schema tree.

The intended loop is:

1. send a conversation and the allow-listed tool specifications to `complete`;
2. inspect the returned message and validate any tool request;
3. ask the application to authorize and execute the tool;
4. append the tool result as a structured message;
5. continue until the application reaches its turn or cost limit.

The model never gets ambient access to Azora functions, files, environment
variables, network sockets, or secrets.

---

## 3. `std.ml`: numerical and training primitives

The first ML module is [`std/ml.az`](../std/ml.az). It provides:

| API | Purpose |
|---|---|
| `Tensor` | Values plus an explicit shape |
| `Dataset` | Paired input and target tensors |
| `Activation` | Portable activation vocabulary |
| `TrainingOptions`, `TrainingReport` | Bounded training configuration and results |
| `relu`, `softmax`, `argmax` | Small backend-independent numerical helpers |
| `meanSquaredError`, `accuracy` | Basic metrics |
| `MlModel`, `MlOptimizer` | Opaque backend handles |
| `loadModel`, `optimizer`, `fit`, `predict`, `saveModel` | Training/inference bridge |

The standard library does not pretend that a tensor implementation is cheap or
that all models fit in ordinary memory. A backend may use CPU memory, GPU/TPU
memory, mmap, distributed storage, or a remote service.

```azora
import std.ml
import std.container.array

func classify(scores: Array<Double>): Int {
    return argmax(softmax(scores))
}
```

`Tensor` uses `Array<Double>` and `Array<Int>` in the first API because those
types are available in the current compiler. A future version can add dtypes,
strides, device placement, sparse tensors, and automatic differentiation
without changing the model-handle boundary.

### 3.1 Training boundary

Training is explicit and bounded:

```azora
bridge pack MlModel
bridge pack MlOptimizer
bridge func fit(
    model: MlModel,
    dataset: Dataset,
    optimizer: MlOptimizer,
    options: TrainingOptions,
): TrainingReport
```

The implementation must report epochs, examples, final loss, and whether it
stopped because of the configured limit. It must not silently train forever or
write checkpoints outside an application-provided path.

---

## 4. Backend and model-format boundary

All provider/framework integration belongs behind bridges or packages built on
bridges. Candidate backend families include:

- local CPU/GPU inference and training;
- ONNX, GGUF, safetensors, and other model artifacts;
- hosted text and embedding APIs;
- CUDA, ROCm, Metal, WebGPU, and future accelerator runtimes;
- distributed training and serving systems.

An adapter should expose capabilities such as:

```text
model identifier and revision
supported input/output types
maximum context or tensor dimensions
supported dtypes and devices
maximum generation tokens
tool-calling support
streaming support
determinism and seed behavior
privacy and data-retention policy
```

The adapter must make its failure mode visible. Authentication failures,
timeouts, rate limits, unsupported operations, OOM conditions, and invalid model
artifacts should be distinguishable from ordinary model output.

---

## 5. Proposed keyword vocabulary

These words are proposals, not currently reserved Azora keywords:

| Word | Context | Intended meaning |
|---|---|---|
| `model` | declaration/type context | Declares or names an inference/training model |
| `prompt` | expression/block | Builds a structured model input |
| `infer` | statement/expression | Performs model inference through an explicit effect |
| `train` | statement/block | Runs a bounded training operation |
| `embed` | expression | Produces a vector representation |
| `agent` | declaration | Declares an application-owned model/tool loop |
| `tool` | declaration | Declares an allow-listed callable capability |
| `dataset` | declaration | Declares a training/evaluation data source |
| `tensor` | type/expression | Refers to numerical tensor values |
| `gradient` | type/expression | Refers to derivative values or propagation |
| `checkpoint` | statement/configuration | Saves or restores a model state |
| `evaluate` | statement/expression | Computes metrics on held-out data |
| `temperature` | option clause | Controls sampling randomness |
| `context` | option/type clause | Defines model input context or execution scope |
| `redact` | policy statement | Removes or masks sensitive data before a call |

### 5.1 Contextuality rule

The words `model`, `context`, `tool`, `evaluate`, and `temperature` are common
application names. They should remain ordinary identifiers outside an AI/ML
context. For example, this must remain legal:

```azora
func model(context: String): String {
    return context
}
```

The parser should recognize proposed AI syntax only after an explicit `ai`,
`ml`, `model`, `agent`, or `dataset` context, or after a declaration uses a
known AI type. The first implementation therefore uses ordinary functions and
types instead of changing the compiler keyword table.

### 5.2 Candidate syntax

Illustrative future syntax, not accepted today:

```azora
model assistant use "provider/model" {
    context maxTokens = 256
}

agent supportBot(model assistant) {
    tool lookupOrder(orderId: String): String
    infer prompt userMessage
}
```

The accepted spelling remains explicit library calls:

```azora
fin model = connect("provider", "model")
fin reply = complete(model, conversation, options)
```

---

## 6. Effects, privacy, and safety

AI operations should be treated as effectful even when they look like ordinary
functions. The eventual effect system should distinguish at least:

```text
ModelIO       network or accelerator model execution
Training      parameter updates and checkpoint writes
ToolCall      application-approved external actions
SensitiveIO   access to secrets or private data
```

Until those effects exist, bridge functions are the explicit boundary.

Required safety rules:

1. Model output is data, not trusted Azora code.
2. Tool calls are validated against an application allow-list.
3. Prompt and tool data must preserve origin/role boundaries.
4. Applications should be able to redact or reject sensitive inputs before a
   provider call.
5. Model, dataset, and checkpoint paths should be explicit.
6. Resource limits must be enforced by the adapter, not merely documented.
7. Logging must support redaction; prompts and outputs should not be logged by
   default when they may contain private data.
8. A provider must not be able to introduce arbitrary declarations into the
   compiler or runtime through model output.

---

## 7. Interoperation with quantum and other accelerators

`std.ml` and `std.quantum` are separate by default. A hybrid algorithm can use
both through an application-owned bridge or a future `HybridModel` abstraction:

```text
classical tensor/model -> circuit parameters -> quantum samples -> loss -> update
```

The compiler should not assume that gradients through a quantum backend exist.
A provider must declare whether gradients are analytic, parameter-shift,
finite-difference, unavailable, or estimated from samples.

This keeps the quantum proposal in `DIPs/QUANTUM_DIP.MD` compatible with ML
without coupling the two standard-library modules prematurely.

---

## 8. Roadmap

- [x] Add structured `std.ai` messages, conversations, and generation options.
- [x] Add `std.ml` tensors, datasets, metrics, and model/optimizer boundaries.
- [ ] Add deterministic fake AI and ML backends for compiler tests.
- [ ] Add streaming response support with cancellation and backpressure.
- [ ] Add typed tool schemas and validated tool-call values.
- [ ] Add tokenizer and model-artifact packages outside the core stdlib.
- [ ] Add device placement, dtypes, strides, and automatic differentiation.
- [ ] Add model/checkpoint capability negotiation and versioned artifacts.
- [ ] Specify AI effects in the type/effect system.
- [ ] Decide which proposed words, if any, become contextual parser forms.

---

## 9. Design rules

1. AI/ML computation is explicit; constructing values does not perform I/O.
2. Provider and accelerator integrations live behind bridges or packages.
3. Model output is untrusted data unless an application validates it.
4. Tools are capabilities owned and dispatched by the application.
5. Resource, privacy, and determinism policies are part of the API contract.
6. Proposed keywords remain contextual until real programs demonstrate that
   reserving them is safe.
