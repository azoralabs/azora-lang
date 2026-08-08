# Azora Testing and Tracing - DIP Implementation Profile

> Status: **implemented**, except where marked. Tests run under the interpreter
> and natively; §6 is what stands between this and a runner you would depend on.

A test is a named block in the file it tests. There is no test framework, no
registration, no separate test source root, and no assertion library - a test is
a block, and `assert` is the language's own statement.

```azora
test "duration normalizes overflow" {
    fin value = std::duration(2L, 1500000000L)
    assert value.seconds == 3L { "overflow must carry into seconds" }
}
```

The standard library relies on this heavily: `std/serializer.az` alone carries
56 of them, beside the code they test.

---

## 1. `test`

```azora
test "name" { … }
```

A top-level declaration. The name is a **string literal**, not an identifier, so
it can say what it means rather than what an identifier permits.

A test body is an ordinary block: any statement, any declaration in scope. It
fails when an assertion inside it fails, and passes otherwise - there is no
"expected" mechanism and nothing to return.

### 1.1 `.This` and `.All`

```azora
test "a" { … }              // .This, the default
test "b" { … }
test .All "everything"      // runs a and b as one test
```

`.This` runs only that body. `.All` aggregates **every `This` test in the same
source file** into a single test, in declaration order.

`.All` is the only form allowed to omit its body - an aggregate usually has
nothing of its own to do. When one is present the individual tests **disappear
from the run**: `IrGenerator` emits the aggregate and drops the `This` tests
rather than emitting both, so a file with an `.All` reports one result.

That is a real trade. A file with `.All` gets one line of output and one failure
message; without it, one line per test. The aggregate exists so a module can be
checked as a unit, and it costs the per-test reporting.

- ⬜ Whether a failing aggregate should say *which* child failed is undecided.
  It currently reports the first assertion message and nothing about where

### 1.2 `realm test` - declarations only tests may use

A fixture is code the tests need and the program must not reach. `realm test`
is the visibility rule that says so:

```azora
realm test {
    func fixture(): Int { return 7 }
}

test "the fixture is reachable from a test" {
    assert fixture() == 7 { "fixture should be usable from a test" }
}
```

Whatever is declared inside is ordinary code, emitted like any other
declaration - the realm changes *who may refer to it*, not what it compiles to.
It is not a namespace either: the names stay unmangled, so a test calls
`fixture()` and not `test__fixture()`.

Reaching one from the program is an error:

```
func main() { fin unused = fixture() }
// 'fixture' is declared in a 'realm test' and can only be used from a test in this file
```

A test-realm member may use its siblings, since it is already test-only.

#### Reach

The ordinary `expose` / `protect` / `confine` ladder bounds which tests can see
it, and a bare `realm test` is file-local rather than public - the opposite
default to an ordinary declaration, because a fixture is local until it says
otherwise:

| form                  | visible to               |
|-----------------------|--------------------------|
| `realm test`          | tests in this file       |
| `confine realm test`  | tests in this file       |
| `protect realm test`  | tests in this folder     |
| `expose realm test`   | tests in any file        |

A `realm test` may not nest inside another - the inner one would say nothing the
outer has not already said.

- ⬜ **Reach is recorded, not yet enforced across files.** The rule that only a
  test may use these declarations is enforced; *which* file's tests is carried
  on the declaration and waits on the same module-system check that `protect`
  waits on (`MODULES_REALMS_DIP.MD` §5.1)
- ⬜ **Only calls are checked.** A test realm's packs and enums are recorded, but
  referring to one as a *type* from program code is not yet refused

---

## 2. `assert`

Covered in full by `CONTRACTS_ASSERTION_DIP.MD`; what matters here is that the
test framework **is** the assert statement:

```azora
assert condition { message }
```

A failing assertion raises, the runner catches it, and the message becomes the
test's failure text. That is the whole protocol - which is why there is no
`assertEquals`, no matcher vocabulary, and nothing to learn beyond the language.

- ⬜ A failure reports the message and not the values. `assert a == b` says what
  the author wrote, never what `a` and `b` were, which is the single largest
  gap in the experience of using it

---

## 3. Running them

```
azora test <file.az>
azora test <directory>
azora test <file.az> --llvm
```

A directory is walked recursively for `.az` files, in sorted path order.

Output is one line per test:

```
✓ std/time.az :: duration normalizes positive nanosecond overflow
✗ std/time.az :: instant serializer uses canonical ISO text
    Assertion failed: Instant serializer wire form changed

12 passed, 1 failed
```

### 3.1 Two engines

| flag      | runs                                        |
|-----------|---------------------------------------------|
| (default) | the interpreter                             |
| `--llvm`  | compiled natively through `clang`, per test |

`--llvm` is what makes the test suite a parity check rather than an interpreter
check: the same `test` block, the same assertions, a different backend. If no
`clang` is present, or the IR carries no test symbols, that file is reported as
unrunnable rather than silently skipped.

- ⬜ There is no `--wasm`. The third backend cannot be tested this way, which is
  part of why its degradations went unnoticed

### 3.2 Exit codes

A failing **assertion** always fails the run. A **compile error** only fails it
under `--strict`.

That asymmetry is deliberate and worth knowing: many test files exercise
features that do not compile yet, so a plain `azora test` over the tree reports
them without turning the whole run red. `--strict` is what CI should use.

- ⬜ CI does not currently use it, because there is no CI (§9.2 of the roadmap)

### 3.3 Compilation mode

Tests compile with `release = false`. So they run against the debug lowering,
including the `__dbg` line markers - and a release-only miscompile cannot be
caught by the test suite at all.

- ⬜ Run the suite in both modes, or state that release is untested

---

## 4. `trace`

```azora
trace "reached here"                 // [DEBUG] reached here
trace .Warn "falling back"           // [WARN] falling back
trace { "computed: ${value}" }       // [DEBUG] computed: 42
```

A diagnostic print carrying a level. The level is a `std::LogLevel` - `Debug`
(the default), `Info`, `Warn`, `Error` - written as a leading `.Variant`.

Output is `[LEVEL] message`, always. There is no sink, no filter and no way to
turn it off.

The block form exists so a message can be computed over several lines without
parenthesising, and it binds the level as its receiver.

- ⬜ **`trace` cannot be disabled.** A release build prints every trace in the
  program. There is no level threshold, no compile-time removal, and no way to
  route it anywhere but standard output
- ⬜ **No structured output.** The message is a string; nothing carries fields,
  a timestamp, or the source location

### 4.1 `inline trace`

```azora
inline trace "specialising for ${Ty}"
```

The compile-time form: it prints while the compiler runs, which is how a
metaprogram reports what it is generating. It disappears from the output
program.

---

## 5. `panic`

```azora
panic "unrecoverable"
inline panic "unreachable at compile time"
```

`panic` aborts. It is not catchable and not an error in the `T ?! E` sense - the
distinction between "this program is wrong" and "this operation failed" is the
distinction between `panic` and an error set.

`inline panic` fails the compile.

---

## 6. What is missing

The gap between this and a runner worth depending on.

- ⬜ **No assertion diagnostics.** `assert a == b` reports the author's message,
  never the operand values (§2). This is the first thing to fix - everything
  else here is convenience by comparison
- ⬜ **No test filtering.** No `--filter`, no name pattern, no way to run one
  test out of a file
- ⬜ **No setup or teardown**, and no fixtures. A test that needs a prepared
  world builds it inline, every time
- ⬜ **No parametrised tests.** `inline for` over a compile-time list could
  generate them, and nothing tries
- ⬜ **No machine-readable output.** No JUnit XML, no AZON report - so no CI
  integration, no history, no flake tracking
- ⬜ **No timing.** Nothing reports how long a test took, so a slow suite has no
  way to be diagnosed
- ⬜ **No skip or expected-failure marker.** A test for an unimplemented feature
  can only be commented out
- ⬜ **Tests are not isolated.** They share one interpreter and one global
  scope, in declaration order, so one test's globals are visible to the next
- ⬜ **No coverage.** Nothing measures which code the suite reaches
- ⬜ **`--wasm` does not exist** (§3.1), and neither does a release-mode run
  (§3.3)

---

## 7. Rules

1. A `test` is a top-level declaration whose name is a string literal.
2. A test passes unless an assertion in it fails.
3. `.This` is the default and runs its own body. `.All` aggregates every `This`
   test in the same file and suppresses them individually.
4. `.All` is the only form that may omit its body.
5. `assert` is the whole assertion mechanism; the runner turns a raised
   assertion into a failure with its message.
6. `azora test` runs the interpreter by default and native code under `--llvm`.
7. A failing assertion always fails the run; a compile error does so only under
   `--strict`.
8. `trace` prints `[LEVEL] message` unconditionally. `inline trace` prints at
   compile time and leaves nothing behind.

---

## 8. Related

- `CONTRACTS_ASSERTION_DIP.MD` - `assert`, `inline assert`, and contracts
- `CTCE_DIP.MD` - `inline trace` and `inline panic`
- `ROADMAPs/VERSION_0_1_ROADMAP.MD` §9 - the 75 failing tests and the CI gap
