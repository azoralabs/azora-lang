# Contributing to Azora

Thank you for your interest in contributing to Azora. This document outlines the rules and expectations for all contributors. These rules are **strictly enforced**, pull requests that violate them will be rejected without review.

## Rules

### 1. Open an Issue First

**Every change must have a corresponding issue.** Do not open a pull request without an approved issue. This applies to bug fixes, features, refactors, and documentation changes alike. The only exceptions are typo fixes of three words or fewer.

### 2. One Pull Request, One Concern

Each pull request must address **exactly one issue**. Do not bundle unrelated changes. Do not sneak in refactors, formatting changes, or "improvements" alongside a bug fix. If you find something else that needs fixing, open a separate issue.

### 3. No Breaking Changes Without a Proposal

Any change that alters public-facing behavior, modifies the language syntax or semantics, changes the standard library API, or removes/renames anything that existing code depends on **must** go through the [Azora Proposal Process](README.md#language-proposals). Open a GitHub Discussion in the `Proposals` category and wait for acceptance before writing code.

### 4. Tests Are Mandatory

- Every bug fix must include a test that reproduces the bug.
- Every new feature must include tests covering its behavior.
- Every code generator change must include tests for **all** targets (Kotlin, JavaScript, C#, Python, LLVM IR).
- All existing tests must pass. Run `./gradlew :compiler:desktopTest` locally before pushing.

### 5. Code Style

- Follow the existing code style exactly. Do not reformat files you did not change.
- Kotlin code follows [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Azora (`.az`) files follow the patterns established in `Internal/Std/`.
- No trailing whitespace. No unused imports. No commented-out code.

### 6. Commit Discipline

- Write clear, imperative commit messages: `Fix parser crash on empty block` not `fixed stuff`.
- Each commit must compile and pass tests independently.
- Squash fixup commits before requesting review. The final PR should have a clean, logical commit history.
- Do **not** include merge commits. Rebase onto `main` before submitting.

### 7. Documentation

- Public API changes must include documentation updates.
- Standard library additions must include `/** */` doc comments following the existing style in `Internal/Std/`.
- Do not add documentation for internal implementation details.

### 8. No Dependencies Without Approval

Do not add new third-party dependencies without prior approval in the issue discussion. Azora aims to minimize its dependency footprint. If a dependency is necessary, justify it clearly and consider the impact on all target platforms.

### 9. Licensing

All contributions are submitted under the [Apache License 2.0](LICENSE). By submitting a pull request, you certify that you have the right to submit the code under this license and that you agree to the [Developer Certificate of Origin](https://developercertificate.org/).

### 10. Review Process

- All pull requests require at least one approving review from a maintainer.
- Address all review comments. Do not resolve conversations yourself, let the reviewer resolve them.
- Do not force-push after review has started. Push new commits so reviewers can see the delta.
- Maintainers may close stale PRs (no activity for 30 days) without merging.

## Setting Up Your Development Environment

```sh
# Clone your fork
git clone https://github.com/<your-username>/azora-lang.git
cd azora-lang

# Build
./gradlew build

# Run all tests
./gradlew :compiler:desktopTest

# Run the CLI
./gradlew :app:run --args="run your_script.az"
```

### Prerequisites

- JDK 17+
- Gradle 9.1+ (wrapper included)

## What to Work On

Look for issues tagged `good first issue` or `help wanted`. If you want to work on something, comment on the issue to claim it before starting. Do not start work on issues already assigned to someone else.

## Reporting Bugs

When reporting a bug, include:

1. Azora version (`azora version`)
2. Operating system and JDK version
3. Minimal `.az` source code that reproduces the issue
4. Expected behavior
5. Actual behavior (include full error output)

## Requesting Features

Feature requests must include:

1. A clear description of the problem the feature solves
2. Proposed syntax or API (if applicable)
3. How it interacts with existing language features
4. Impact on each compilation target (Kotlin, JavaScript, C#, Python, LLVM IR)
