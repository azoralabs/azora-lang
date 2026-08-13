# Azora Cybersecurity and Secure-by-Construction Programming - DIP

> Status: **proposal.** `std.security` provides the first defensive security
> contracts and bridge surface. The language-level `secret`, `sensitive`,
> `untrusted`, `capability`, and `audit` forms described here remain contextual
> proposals until the parser, type checker, runtime, and tooling agree on their
> semantics.

Azora should make secure behavior the path of least resistance. The language
cannot make an application secure by itself, but it can make several dangerous
mistakes difficult to express, visible in code review, and diagnosable before
deployment:

- secrets should not be ordinary strings that can flow into logs;
- authority should be passed as a capability, not discovered through ambient
  global state;
- untrusted input should carry a provenance that a sink can require to be
  validated or encoded;
- cryptography should be a provider-backed contract, not a collection of
  hand-written primitives;
- audit records should be structured and redaction-aware;
- unsafe FFI, raw pointers, deserialization, and process execution should be
  explicit effects;
- dependency and artifact integrity should be part of the build contract.

The first library implementation is [`std/security.az`](../std/security.az).
It deliberately keeps cryptographic and platform-sensitive operations behind
audited bridges while providing ordinary Azora values for policies, password
checks, redaction markers, and audit events.

```azora
import std.security

func recordAccess() {
    fin event = std::auditEvent(
        "read-secret",
        "service:payments",
        std::SecurityLevel.Restricted,
        std::AuditOutcome.Allowed,
        std::redacted("secret-value"),
    )
    fin sink = std::auditSink("security-events")
    std::emit(sink, event)
}
```

`std.security` is not an exploit-development toolkit. It is for protecting
applications, services, users, devices, and data; security testing belongs in
isolated test environments and must follow applicable authorization and law.

## 1. Design basis

This DIP follows established defensive guidance rather than inventing an
Azora-specific cryptographic doctrine:

- [OWASP ASVS 5.0](https://owasp.org/www-project-application-security-verification-standard/)
  is the application-security verification baseline.
- [OWASP Secrets Management](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)
  informs secret lifecycle, least privilege, revocation, rotation, and audit.
- [OWASP Cryptographic Storage](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)
  informs provider-backed key storage and separation of keys from data.
- [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
  informs memory-hard password KDF defaults and the prohibition on plaintext
  password storage.
- [OWASP Logging](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
  informs structured, useful, tamper-aware logging without sensitive payloads.
- [OWASP SCVS](https://owasp.org/www-project-software-component-verification-standard/)
  informs dependency, component, and software-supply-chain verification.
- [NIST SP 800-57 Part 1](https://csrc.nist.gov/pubs/sp/800/57/pt1/r5/final)
  informs key lifecycle management, including generation, use, rotation,
  revocation, archival, and destruction.

These are design references, not a claim that a program becomes compliant just
by importing `std.security`. Compliance remains a system, process, deployment,
and organizational responsibility.

## 2. Goals and non-goals

### Goals

- Make secret material distinct from ordinary application data.
- Prevent accidental logging, interpolation, serialization, and display of
  secret values through library and compiler contracts.
- Provide provider-backed cryptographic operations with algorithm and key
  metadata attached to values.
- Make constant-time comparison available for authentication material.
- Make capabilities explicit, attenuable, revocable, and auditable.
- Give untrusted data a path to validated or encoded sinks.
- Provide structured audit events with classification and outcome fields.
- Make security-sensitive FFI and process/network authority explicit.
- Support OS keychains, HSMs, cloud KMS, secret managers, Web Crypto, and test
  providers behind one Azora ABI.
- Add compiler and IDE diagnostics for likely security mistakes.
- Make secure defaults testable in the CPU interpreter and enforceable by native
  backends.

### Non-goals for the first version

- Implementing cryptographic primitives in Azora source.
- Promising protection from a fully compromised operating system or runtime.
- Replacing an identity provider, HSM, KMS, SIEM, WAF, endpoint agent, or
  incident-response process.
- Automatically deciding which users or services should have authority.
- Treating encoding as validation or hashing as encryption.
- Hiding network, file, process, reflection, deserialization, or FFI effects.
- Providing offensive payloads, exploit automation, credential theft, malware,
  or unauthorized scanning primitives.

## 3. Security model

Azora security has four boundaries:

```text
untrusted input -> validate/parse -> typed application data
secret manager  -> opaque Secret<T> -> crypto/provider operation
capability      -> authorize(action, resource) -> effectful operation
event           -> redact/classify -> tamper-aware audit sink
```

The compiler should preserve provenance and symbol identity across these
boundaries. A value must not become trusted merely because a variable was
renamed, cast to `Any`, serialized and deserialized, or passed through an
untyped bridge.

### 3.1 Confidentiality, integrity, availability, and accountability

Security controls should state which property they provide:

| Property | Azora mechanism |
|---|---|
| Confidentiality | `Secret<T>`, opaque keys, authenticated encryption, classification |
| Integrity | `Digest`, signatures, authenticated ciphertext, verified artifacts |
| Authenticity | `Signer`, `Verifier`, capabilities, explicit identity references |
| Availability | deadlines, bounded inputs, quotas, cancellation, fail-safe resource limits |
| Accountability | typed `AuditEvent`, redaction, outcome, subject, tamper-aware sinks |

No API may describe a digest as encryption, a signature as secrecy, or a
capability check as authentication. The type names and documentation should
make those distinctions obvious.

## 4. Secret values and sensitive data

### 4.1 The `Secret<T>` boundary

Secrets are not strings with a comment saying “private.” A secret obtained from
a secret manager, password prompt, keychain, HSM, or attestation service should
be represented as `Secret<T>` and should remain opaque by default.

The safe API must reject or warn on these flows:

- `Secret<T>` into `println`, string interpolation, formatting, or ordinary
  serialization;
- `Secret<T>` into an audit detail, error message, URL, query, or shell command;
- conversion from `Secret<T>` to `String` or `Array<UByte>` without an explicit
  `unsafe`/provider boundary;
- storing secret values in global mutable state or unbounded caches;
- sending a secret to an AI model, GPU buffer, quantum circuit, or network
  bridge unless the receiving declaration explicitly requires a secret;
- copying, cloning, or returning a secret from a function when a borrowed or
  opaque provider operation would suffice.

The provider may keep the value in a protected memory region, use a hardware
keystore, or expose only an operation such as `deriveKey`, `sign`, or
`decrypt`. `wipe` is an explicit lifecycle operation, but it is not a promise
that the operating system, garbage collector, swap, crash dump, or hardware has
no historical copy.

### 4.2 Classification and taint

The first library surface uses `SecurityLevel` in policies and audit events.
The language should later support contextual annotations:

```azora
@Sensitive(level: Restricted)
fin accessToken = std::loadSecret<String>("payments/access-token")

@Untrusted(source: "http-request")
fin input = request.body
```

The compiler should track a conservative flow label:

- a value derived from restricted data is restricted unless an approved
  declassification function is called;
- untrusted data cannot reach a sensitive sink without validation, encoding, or
  an explicit reviewable escape hatch;
- concatenation, interpolation, collection insertion, closure capture, and
  serialization propagate the strongest relevant label;
- a cast does not remove a label;
- a bridge declaration must declare whether it preserves, consumes, or exports
  labels.

This is a static aid, not a replacement for threat modeling or runtime access
control.

### 4.3 Redaction

Redaction must be structured rather than a last-minute regular expression over
an already-rendered log line. `std.security.redacted(label)` produces a marker
without accepting the secret value, so callers can avoid placing sensitive data
in the wrong object in the first place.

Future structured logging should accept a record containing public fields and
explicit redacted fields. It should not accept `Any` and hope to discover
secrets after formatting.

## 5. Cryptography and key management

### 5.1 Provider-backed only

Azora must not encourage handwritten cryptography. `std.security` declares
provider operations; native adapters use audited platform libraries, OS
facilities, HSMs, KMSs, or Web Crypto. The bridge must record:

- algorithm and version;
- key identifier and provider identity;
- nonce/IV generation policy;
- associated data and domain-separation label;
- key usage, expiry, rotation, and revocation status;
- whether the operation ran in software, a secure enclave, or an HSM.

An algorithm enum is not an endorsement for every use case. Provider policy may
disable algorithms, require a minimum key configuration, or reject a target
that cannot meet the application's security profile.

### 5.2 Hashes, MACs, signatures, and encryption

The type boundaries are intentional:

```text
hash(data) -> Digest
sign(signer, message) -> Signature
verify(verifier, message, signature) -> Bool
encrypt(key, plaintext, associatedData) -> Ciphertext
decrypt(key, ciphertext, associatedData) -> plaintext or provider failure
```

`Ciphertext` retains its algorithm, nonce, data, and authentication tag. A
decrypting provider authenticates before releasing plaintext. Associated data
is explicit because metadata such as tenant, version, or resource identity may
need integrity protection without confidentiality.

Passwords use a password KDF, not a fast general-purpose digest. The library's
baseline options are deliberately conservative, but providers may increase
memory, iterations, or parallelism as hardware changes. Applications must not
write password hashes into logs or compare them with ordinary string equality.

### 5.3 Key lifecycle

Key handles must support a lifecycle:

```text
generate/import -> activate -> use -> rotate -> suspend/revoke -> destroy/archive
```

Raw key bytes should not be the normal application representation. A key handle
should be bound to an intended purpose and provider. Encrypting data with a
signing key, using a key outside its tenant, or using a revoked key must fail
closed. Key identifiers and cryptographic metadata may be logged; key material
must not be.

### 5.4 Constant-time operations

Authentication and MAC/signature verification must use provider-backed
constant-time operations where the primitive requires it. `constantTimeEqual`
exists for byte sequences, but callers should prefer `verifyPassword`, `verify`,
or a provider MAC API so length, algorithm, and encoding rules are not rebuilt
in application code.

## 6. Capabilities and authorization

Azora should use capability-oriented authority for new APIs:

```azora
fin canRead = std::grant("report-worker", "read", "reports:monthly")
fin limited = std::attenuate(canRead, "read", "reports:monthly:2026-08")
if std::authorize(limited, "read", "reports:monthly:2026-08") {
    readReport(limited)
}
```

Capabilities are unforgeable provider values. They are not strings, role names,
or booleans. `attenuate` may reduce action, resource, scope, time, or quota;
it may never add authority. `revoke` invalidates a capability or its lease.

The language/runtime should make these properties explicit:

- sensitive effects accept a capability parameter rather than reading ambient
  credentials;
- a capability is linear or otherwise ownership-aware when its provider lease
  is exclusive;
- capability propagation across async tasks, AI tools, GPU kernels, quantum
  backends, plugins, and FFI is explicit;
- a child process receives only capabilities included in its launch contract;
- a remote call carries an attenuated delegation token, never the application's
  root credential;
- authorization decisions include principal, action, resource, and outcome in
  the audit event without logging bearer secret contents.

Role-based and policy-based authorization remain valid provider strategies.
`Capability` is the language boundary that prevents authority from being
implicitly ambient, not a demand that every organization abandon its IAM model.

## 7. Untrusted input and safe sinks

The standard library should eventually provide typed wrappers for common trust
boundaries:

```text
UntrustedText       raw external text
ValidatedText       text accepted by a named validation contract
HtmlText            encoded for HTML context
SqlParameter        bound parameter, never query concatenation
PathSegment         normalized and policy-checked path component
Url                 parsed URL with scheme/host policy applied
```

These types must not be confused with generic `String`. Encoding is contextual:
HTML, shell, SQL, JSON, URL, and header contexts require different rules.
Validation must be performed before interpretation, and a validation result
must carry the rule or schema identity that accepted it.

Deserialization is a security boundary. A decoder must enforce depth, size,
recursion, numeric, duplicate-field, and unknown-field policies before building
an application value. A bridge must not deserialize arbitrary code, functions,
or reflective types by default.

## 8. Authentication and identity

`std.security` supplies crypto and authorization contracts; it does not become an
identity provider. A later `std.identity` module may add typed subjects,
credentials, sessions, WebAuthn/passkeys, OIDC, mTLS, and token validation.

The boundary should distinguish:

- authentication: who or what is making a claim;
- authorization: whether that principal may perform an action;
- session management: how claims expire, rotate, and revoke;
- impersonation/delegation: which authority was intentionally transferred;
- recovery: how loss or compromise is handled.

Tokens must be parsed and verified by a provider that checks algorithm
allow-lists, issuer, audience, expiry, not-before, nonce, key rotation, and
signature before returning a typed claim set. Decoding a token is not verifying
it.

## 9. Auditing, logging, and observability

Audit events are security records, not debug strings. A useful event includes:

- timestamp from a synchronized trusted clock;
- event/action name;
- principal or service subject;
- target resource;
- classification;
- allowed/denied/failed outcome;
- correlation/request identifier;
- provider and policy version;
- reason code and redacted detail.

Audit sinks must support append-only or tamper-evident storage, access control,
retention, clock-skew handling, backpressure, and incident export. The default
policy is fail-closed for configured security-critical events when the sink
cannot accept an event; applications may choose a documented availability-first
policy for lower-risk telemetry.

The compiler and formatter should warn when a sensitive or untrusted value is
passed to a log function. Logging entire request bodies, prompts, tokens,
passwords, encryption keys, and raw authorization headers should be opt-in,
reviewable, and normally rejected by policy.

## 10. Unsafe, FFI, and native boundaries

`unsafe` does not mean “security exempt.” It means the programmer takes on a
stronger proof obligation. Unsafe blocks and bridge declarations should carry:

- a reason and owner;
- accepted input and output contracts;
- memory and lifetime assumptions;
- trust and confidentiality labels;
- required capabilities;
- test and review metadata.

The compiler should flag unsafe code that crosses into process execution,
filesystem mutation, raw sockets, dynamic library loading, deserialization,
secret access, or code generation. Native adapters should be isolated and
versioned; a `bridge` signature is not a sandbox.

## 11. Supply-chain and build security

Azora package and build tooling should support:

- lockfiles with cryptographic hashes;
- signed package and compiler artifacts;
- provenance and reproducible-build metadata;
- dependency license and vulnerability policy hooks;
- vendoring or mirror configuration for controlled environments;
- revocation and yanked-version handling;
- generated stdlib source hashes in compiler artifacts;
- separation of build, signing, and release credentials;
- secret scanning that understands Azora source, generated IR, docs, and tests.

The compiler should expose a security manifest containing compiler version,
stdlib version, source hashes, dependency identities, enabled unsafe features,
bridge targets, and artifact signatures. A deployment system can then decide
whether that manifest satisfies its policy.

## 12. Runtime isolation and resource limits

Sandboxing is a runtime/backend responsibility, but Azora should make the
contract visible. A sandbox profile may restrict:

- files and directories;
- network destinations and protocols;
- child processes and dynamic libraries;
- environment and hardware access;
- GPU/quantum/AI resources;
- CPU, memory, time, output, and recursion budgets.

The profile must be passed explicitly to a process, plugin, model tool, or
untrusted module. “No capability supplied” means no authority, not “use the
process's ambient permissions.” Resource limits are security controls against
denial of service as well as reliability controls.

## 13. Proposed contextual vocabulary

These words should be contextual until a security declaration or type context
has been established:

| Word | Context | Meaning |
|---|---|---|
| `secret` | type/declaration | Opaque sensitive material or secret boundary |
| `sensitive` | annotation | Confidentiality classification or taint source |
| `untrusted` | annotation/type | Data that has crossed an external trust boundary |
| `validate` | expression/effect | Applies a named validation contract |
| `sanitize` | expression | Legacy-friendly term; should be discouraged when context is unknown |
| `redact` | logging/data-flow | Removes or masks sensitive data before observation |
| `capability` | type/parameter | Explicit authority value |
| `authorize` | effect | Checks action/resource authority |
| `audit` | effect/block | Emits a structured security event |
| `attest` | provider operation | Verifies a key, device, artifact, or workload identity |
| `sealed` | type/module | Prevents extension or deserialization outside a policy |
| `taint` | analysis | Tracks data provenance through expressions and calls |

The words must remain legal identifiers in ordinary modules. This avoids the
same compatibility problem that broad hard keywords would create for existing
application code.

## 14. `std.security` initial surface

The implemented source-level module is [`std/security.az`](../std/security.az).
The pure helpers are interpreter-testable; bridge declarations are provider
boundaries for production runtimes.

| API | Purpose |
|---|---|
| `SecurityLevel`, `AuditOutcome` | Classification and audit outcomes |
| `HashAlgorithm`, `AeadAlgorithm`, `SignatureAlgorithm` | Explicit crypto metadata |
| `PasswordAlgorithm`, `PasswordHashOptions`, `PasswordPolicy` | Password KDF and policy contracts |
| `Digest`, `Ciphertext`, `Signature` | Algorithm-attached cryptographic values |
| `Secret<T>`, `KeyHandle`, `Signer`, `Verifier` | Opaque provider-managed sensitive handles |
| `Capability`, `AuditSink` | Explicit authority and audit destinations |
| `defaultSecurityPolicy`, `defaultPasswordPolicy` | Safe policy values |
| `passwordMeetsPolicy`, `validPasswordHashOptions` | Local preflight validation |
| `redacted`, `auditEvent` | Safe structured event construction |
| `secureRandom`, `hash`, `constantTimeEqual` | Provider cryptographic primitives |
| `loadSecret`, `deriveKey`, `passwordHash`, `verifyPassword` | Secret and password lifecycle |
| `encrypt`, `decrypt`, `sign`, `verify` | Authenticated encryption and signatures |
| `auditSink`, `emit` | Structured security auditing |
| `grant`, `attenuate`, `authorize`, `revoke` | Least-privilege capabilities |
| `wipe` | Explicit provider-managed sensitive cleanup |

`std.random` remains suitable for simulation and games. It must not be used for
keys, nonces, passwords, session identifiers, authorization tokens, or any
security-sensitive value. `std.security.secureRandom` is the deliberate API
for cryptographic randomness.

## 15. Interoperation with Azora subsystems

### AI and ML

AI prompts, tool arguments, embeddings, model files, and inference outputs may
contain restricted data. `std.ai` and `std.ml` should carry security labels and
capabilities in future revisions. A tool must receive only the capabilities it
needs, and prompt/output logging must respect redaction policy.

### GPU

GPU buffers can persist beyond a function call and may be visible to other
workgroups or devices. A future `std.gpu` security integration should require
explicit classification for uploads, zero or wipe buffers before reuse, avoid
secret data in debug captures, and include resource labels in pipeline
reflection. `Secret<T>` should not implicitly convert to a GPU buffer.

### Quantum

Quantum circuits can expose measurement results, remote-provider metadata, and
credentials. `std.quantum` backends should accept capabilities and use the
same audit/redaction boundary as other remote providers. A circuit value is not
secret merely because it is quantum; its classification comes from its data and
purpose.

### Filesystem, OS, and networking

File, process, environment, and network APIs should accept explicit capability
or sandbox profiles. Path normalization, URL parsing, TLS verification, and
certificate policy belong in typed APIs rather than string conventions.

## 16. Diagnostics and IDE support

The language server should provide symbol-based security navigation:

- go-to-definition from a capability use to its grant/attenuation origin;
- show the source and transformations of a sensitive or untrusted value;
- show why a sink requires validation or redaction;
- show the key/provider/algorithm identity behind a crypto operation;
- show every bridge that can export a restricted value;
- show the audit event and policy attached to a security-sensitive effect.

Diagnostics should include safe fixes such as “redact this field,” “use
constant-time verification,” “add a capability parameter,” “validate for this
context,” or “replace `std.random` with `std.security.secureRandom`.” They must
never print the value that triggered the diagnostic.

## 17. Testing and verification

Security support is incomplete without adversarial and negative tests. The
compiler/runtime suite should include:

- secret-to-log, secret-to-string, and secret-to-serialization rejection tests;
- untrusted-to-sink tests for HTML, SQL, URL, shell, JSON, and header contexts;
- constant-time verification API tests and provider conformance vectors;
- KDF parameter boundary and password policy tests;
- nonce reuse and ciphertext authentication failure tests in provider suites;
- capability attenuation, revocation, delegation, and confused-deputy tests;
- audit redaction, sink failure, clock skew, and tamper-evidence tests;
- dependency lock, artifact signature, provenance, and reproducibility tests;
- FFI label-preservation and unsafe-boundary tests;
- fuzzing for parsers, decoders, validators, protocol messages, and resource
  limit handling;
- memory-safety, race, denial-of-service, and cancellation tests;
- a security regression database with a minimized reproducer for every fixed
  compiler or stdlib vulnerability.

Security tests should run against fake providers and real audited providers.
Fake providers test contracts and failure paths; they do not prove production
cryptographic strength.

## 18. Implementation roadmap

1. **Current library layer:** ship `std.security` policy values, typed crypto
   records, opaque handles, capabilities, redaction, and audit bridges.
2. **Provider contract:** define native, Web Crypto, HSM/KMS, and test-provider
   ABIs with algorithm, key lifecycle, error, and audit metadata.
3. **Security diagnostics:** warn on insecure randomness, secret sinks, unsafe
   deserialization, missing authorization, and unaudited sensitive effects.
4. **Taint and labels:** add symbol-based sensitive/untrusted flow tracking
   through expressions, collections, async tasks, bridges, and generated code.
5. **Capability runtime:** add attenuation, revocation, delegation, sandbox
   profiles, and structured authorization decisions.
6. **Secure serialization and networking:** add context-specific validation,
   encoding, TLS/certificate policy, size/depth limits, and typed claims.
7. **Supply-chain security:** add lockfile verification, signed artifacts,
   provenance manifests, dependency policy, and secret scanning.
8. **Assurance:** publish threat models, provider conformance vectors, fuzzing
   harnesses, vulnerability disclosure guidance, and security release notes.

## 19. Security principles

1. Secure defaults beat secure folklore.
2. Secrets are opaque values, not strings with good intentions.
3. Authority is explicit, narrow, attenuable, and revocable.
4. Cryptography is delegated to audited providers.
5. Validation and encoding are context-specific and typed.
6. Redaction happens before formatting or observation.
7. Failure of an authentication, integrity, or audit control fails closed unless
   an explicit availability policy says otherwise.
8. Unsafe and bridge boundaries are visible, reviewable, and attributable.
9. Security metadata follows symbol identity, not variable names or generated
   text matching.
10. A security API is successful only when its negative paths are tested.

Azora cannot promise that every program is secure. It can make the secure path
clear, the insecure path explicit, and the evidence required for trust part of
the language and build artifacts.
