# Azora Quantum Computing - DIP

> Status: **proposal.** `std.quantum` provides the first backend-neutral circuit
> and algorithm surface. The keyword proposals in this document are deliberately
> not reserved by the lexer yet; they need parser, type-system, and backend
> agreement before they become language syntax.

Azora should be able to describe a quantum program once and execute it through
different providers: a deterministic simulator in tests, OpenQASM for portable
export, a native simulator, or a remote QPU such as IBM Quantum, IonQ, Rigetti,
or Quantinuum. The language should make the quantum/classical boundary visible,
but it should not bake one vendor's gate set or transport protocol into the
compiler.

The initial implementation therefore has three layers:

1. `std.quantum` contains circuit values, gates, measurement samples, and
   source-level algorithm builders.
2. A backend bridge accepts a circuit and returns measurement samples. A bridge
   can target a simulator, a local native runtime, a file exporter, or a remote
   service.
3. Future language syntax can make common circuit code concise after the
   semantics are proven by the library API.

```azora
import std.quantum
import std.io

func main() {
    fin bell = std::bellPair()
    // A configured backend supplies the bridge implementation.
    fin samples = std::execute(std::connect("simulator"), bell, 1024)
    std::println(samples[0].count)
}
```

The current source-level spelling is intentionally ordinary Azora. A program
that only constructs a circuit can be compiled without a QPU; a program that
calls `execute` requires a backend implementation at link or deployment time.

---

## 1. Goals and non-goals

### Goals

- Represent circuits independently of a vendor SDK.
- Keep classical control, measurement, and backend effects explicit.
- Provide reusable implementations of small, well-known algorithms.
- Make qubit count, gate order, measurement basis, and shot count inspectable.
- Allow a compiler backend to lower a circuit to native instructions or export
  it as OpenQASM.
- Make illegal qubit indices and invalid shot counts fail before execution.
- Give simulators and QPUs the same `Circuit` input and `Sample` output shape.

### Non-goals for the first version

- Hiding network calls behind ordinary pure functions.
- Claiming that the interpreter is a fault-tolerant quantum computer.
- Choosing a single universal gate set for every target.
- Automatically moving arbitrary classical values into quantum memory.
- Pretending that mid-circuit measurement, reset, noise, and feed-forward have
  identical costs on every provider.

---

## 2. The first standard-library surface

The first library module is `std.quantum`, backed by
[`std/quantum.az`](../std/quantum.az). It provides:

| API | Purpose |
|---|---|
| `GateKind`, `Gate`, `Circuit` | A backend-neutral circuit IR |
| `Sample` | One measured bit string and its occurrence count |
| `connect`, `execute` | The explicit device bridge |
| `bellPair` | Bell-state preparation and measurement |
| `teleportation` | Three-qubit teleportation protocol skeleton |
| `deutschJozsa` | Deutsch-Jozsa circuit for a parity oracle mask |
| `bernsteinVazirani` | Bernstein-Vazirani circuit for a hidden bit string |
| `groverSearch` | Grover search for one marked basis state |
| `quantumFourierTransform` | QFT circuit with controlled phase rotations |

The algorithms return a `Circuit`; they do not silently execute it. This makes
them useful for inspection, testing, OpenQASM export, and execution on different
backends.

```azora
import std.quantum

func makeSearchCircuit(): std::Circuit {
    return std::groverSearch(3, 5)
}

func makeFourierCircuit(): std::Circuit {
    return std::quantumFourierTransform(4)
}
```

The `GateKind.MultiControlledZ` and classically conditioned gates are explicit
IR nodes. A backend may decompose them into its native basis, or reject them
with a capability diagnostic instead of silently changing the algorithm.

---

## 3. Execution and backend boundary

The library exposes an opaque `QuantumDevice` and bridge functions:

```azora
bridge pack QuantumDevice
bridge func connect(backend: String): QuantumDevice
bridge func execute(
    device: QuantumDevice,
    circuit: Circuit,
    shots: Int,
): Array<Sample>
```

`connect` and `execute` are not simulator implementations. They are the stable
ABI boundary for a target. A backend must define:

- how a device name maps to a local simulator, a compiler, or a remote QPU;
- which gate kinds and qubit counts it accepts;
- whether it supports mid-circuit measurement, reset, and classical control;
- how authentication, retries, timeouts, and cost limits are handled;
- how provider failures become Azora diagnostics.

The first backend targets should be:

1. `simulator` - deterministic and seeded in tests;
2. `openqasm3` - writes a portable OpenQASM 3 artifact;
3. a native simulator - useful for local development;
4. provider adapters - kept outside the core compiler and standard library.

`shots` is a runtime resource request, not a quantum-language keyword. A backend
must reject `shots <= 0` and report the actual number of completed shots.

---

## 4. Proposed keyword vocabulary

These names are proposals for a later syntax layer. Until the parser and type
checker specify them, they remain ordinary identifiers and the library API above
is the canonical spelling.

| Proposed word | Context | Intended meaning |
|---|---|---|
| `qubit` | declaration | Declares a quantum register or a statically sized qubit handle |
| `circuit` | declaration | Declares a circuit-producing function or circuit value |
| `gate` | statement | Appends a gate operation to the active circuit |
| `prepare` | expression/statement | Initializes a qubit or register in a named basis state |
| `measure` | expression/statement | Measures a qubit/register into classical storage |
| `observe` | expression | Requests an expectation value or sampled observation |
| `oracle` | declaration | Declares the reversible oracle used by an algorithm |
| `control` | block | Applies operations conditionally on one or more qubits |
| `rotate` | statement | Emits a parameterized rotation gate |
| `basis` | clause | Selects the X, Y, or Z measurement basis |
| `shots` | parameter clause | Selects the number of repeated executions |
| `backend` | configuration clause | Selects the execution or export target |
| `classical` | type/effect annotation | Marks data and blocks that run outside the quantum state |

### 4.1 Contextuality rule

The common words `measure`, `control`, `basis`, `shots`, and `backend` should be
contextual before they are reserved. For example, this must remain legal in a
non-quantum module:

```azora
func measure(value: std::String): std::Int { return value.size }
```

The parser can recognize `measure` as quantum syntax only after a `qubit`,
`circuit`, or `quantum` context has been established. A future lexer token table
must not make ordinary application code stop compiling merely because it uses
one of these words.

### 4.2 Candidate syntax

The following is illustrative, not accepted syntax yet:

```azora
circuit bellPair(qubit a, qubit b) {
    gate H(a)
    gate CNOT(a, b)
    measure a, b basis Z into classical result
}
```

The accepted library spelling remains:

```azora
fin bell = std::bellPair()
```

---

## 5. Quantum/classical type model

The language needs to distinguish a qubit handle from a classical value. A
qubit is not a `Bool`, cannot be printed, and cannot be used in an ordinary
`if`. Only measurement produces classical data.

The eventual type model should include:

```text
Qubit                 one logical qubit handle
QRegister<N>          statically sized register, when N is known
QRegister             dynamically sized register for backend APIs
Circuit               an owned sequence of operations
Measurement<T>        classical result produced by observe/measure
```

The first library version uses `Int` indices in `Circuit` so it can be used by
today's compiler. A future type checker can add linear or affine checking:

- a qubit may be used by gates but never copied as a classical value;
- a measured qubit cannot be used as a coherent control unless it is explicitly
  reset or the backend supports the operation;
- a circuit builder owns its gate list and can be moved into `execute`;
- classical values may control later operations only through explicit control
  nodes.

This is a safety feature, not an optimization. It prevents a program from
accidentally reading a quantum state as if it were a classical variable.

---

## 6. Algorithm contracts

The first algorithms have deliberately narrow contracts:

### Bell pair

`bellPair()` prepares `( |00> + |11> ) / sqrt(2)` and measures both qubits in
the Z basis. An ideal backend should return only `00` and `11`, with roughly
equal counts.

### Teleportation

`teleportation()` emits the standard three-qubit protocol, including the
classically conditioned correction nodes. The input-state preparation is a
separate concern; this circuit demonstrates the protocol and its feed-forward
boundary.

### Deutsch-Jozsa

`deutschJozsa(qubits, mask)` uses a parity oracle. `mask == 0` is the constant
zero oracle; a non-zero mask is balanced. The final input register is measured
in the Z basis and distinguishes those cases in the ideal model.

### Bernstein-Vazirani

`bernsteinVazirani(secret)` treats the `secret` array as a classical hidden bit
string and emits the oracle plus the interference steps that recover it with
one oracle query.

### Grover search

`groverSearch(qubits, markedState)` emits the uniform superposition, a marked
state phase oracle, and the diffusion operator. The iteration count is chosen
as `floor(pi * sqrt(2^n) / 4)` with a minimum of one iteration.

### Quantum Fourier transform

`quantumFourierTransform(qubits)` emits Hadamards, controlled phase rotations,
and the final register-reversal swaps. It does not perform the inverse transform
or phase estimation; those belong in a later module once controlled arbitrary
unitaries are specified.

---

## 7. Capability negotiation

No backend supports every circuit. Execution must validate the circuit before
submitting it. The backend capability model should report at least:

```text
maxQubits
nativeGates
supportsMidCircuitMeasurement
supportsReset
supportsClassicalControl
supportsParameterizedGates
supportsNoiseModel
```

Decomposition is allowed when it is semantics-preserving and visible in the
compiled artifact. A backend must not silently drop a gate, turn a measurement
into a random classical bit, or execute a circuit with fewer qubits than it
declares.

---

## 8. Roadmap

- [x] Add a backend-neutral `Circuit` and `Gate` representation.
- [x] Add source-level Bell, teleportation, Deutsch-Jozsa,
  Bernstein-Vazirani, Grover, and QFT builders.
- [x] Add an explicit `QuantumDevice` bridge boundary.
- [ ] Add a deterministic state-vector simulator for unit tests.
- [ ] Add OpenQASM 3 export.
- [ ] Add circuit validation and backend capability diagnostics.
- [ ] Specify qubit ownership and measurement effects in the type checker.
- [ ] Decide which proposed words are contextual and which, if any, become
  reserved keywords.
- [ ] Add a `quantum` target to the bridge target model only after one backend
  implementation demonstrates the ABI.

---

## 9. Design rules

1. Quantum execution is explicit; constructing a circuit never performs I/O.
2. A backend is selected by an explicit device value or build configuration.
3. `Circuit` is inspectable and serializable before it is submitted.
4. Measurements produce classical values; qubits are not ordinary booleans.
5. Vendor-specific features live in adapters, not in the core language.
6. Proposed keywords remain contextual until real programs and parser tests
   demonstrate that reservation is safe.
7. Unsupported operations fail at validation or submission, never silently.
