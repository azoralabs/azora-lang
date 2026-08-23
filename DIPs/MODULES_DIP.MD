# Azora Modules & Scopes - DIP Implementation Profile

## Grammar

```text
MODULE ::= [exposed | confined | ambient | resident] module DOTTED.PATH

IMPORT ::= import IMPORT_CLAUSE ("," IMPORT_CLAUSE)*

IMPORT_CLAUSE ::= DOTTED.PATH
                | DOTTED.PATH "::" NAME
                | DOTTED.PATH "::" "*"
                | DOTTED.PATH "::" IMPORT_GROUP

IMPORT_GROUP ::= "{" GROUP_MEMBERS "}"
               | "[" GROUP_MEMBERS "]"

GROUP_MEMBERS ::= IMPORT_CLAUSE (SEPARATOR IMPORT_CLAUSE)*
                | "*" (SEPARATOR IMPORT_EXCLUSION)*

IMPORT_EXCLUSION ::= "without" NAME
                   | "without" "(" NAME (SEPARATOR NAME)+ ")"

SEPARATOR ::= "," | PHYSICAL_NEWLINE
```

- A file declares at most one `module`.
- `module a.b.c` must live at `a/b/c.az` (or the folder-index `a/b/b.az` for
  `module a.b`).

---

## 1. Module declaration

everything public by default (no need for keyword)
_ in front of variable means private

```azora
module std.time             // public, explicit import, all symbols public by default (overridable by private)
exposed module engine.core  // public, autoimported everywhere, all symbols exposed by default (overridable by private, confined, ambient)
confined module app.main    // package-only, explicit import, all symbols confined by default (overridable by private)
ambient module app.menu     // package-only, autoimported in package, all symbols ambient by default (overridable by private)
resident module app.shared  // public, autoimported in package, all symbols resident by default (overridable by private, confined, ambient)
```

## 2. Imports

```azora
import std.io::*                              // every exported symbol from std.io
import std.container::*                       // every exported symbol under std.container
import std.container.[list::*, map::*]        // grouped module clauses
import std.math::abs                          // one selected symbol

import a.b::{*, without x}
import a.b::{*, without x, without y}
import a.b::{*, without (x, y)}
```

### 2.1 Wildcard exclusions

`*` establishes a wildcard import set. `without` subtracts source-visible names
from that set before the declarations enter the importing scope:

```azora
import a.b::{*, without x}
```

If `a.b` exports `w`, `x`, `y`, and `z`, this imports `w`, `y`, and `z`. It does
not create a hidden declaration for `x`; to this file, `x` was not imported.

Repeated and grouped exclusions are equivalent:

```azora
import a.b::{*, without x, without y}
import a.b::{*, without (x, y)}
```

The parentheses after `without` are grammar-owned name grouping. They do not
construct a tuple. Physical newlines may replace commas:

```azora
import a.b::{
    *
    without (
        x
        y
    )
}
```

The rules are:

- `*` must be the first member;
- everything after `*` must be a `without` clause;
- one `without` may name one symbol or a parenthesized group of two or more;
- repeated and grouped exclusions preserve source order but have identical set
  semantics;
- duplicate exclusions are errors, including duplicates introduced by mixing
  repeated and grouped forms;
- `without` is legal only with a wildcard and cannot filter an explicit-only
  import group;
- the resolved module graph must confirm that each excluded name belongs to the
  wildcard set; excluding an unknown name is an error rather than a silent typo;
- one exclusion removes every wildcard-provided declaration with that short
  source name across the selected namespace;
- qualified exclusions are deliberately unsupported. Split a broad import into
  narrower clauses when only one descendant module's declaration should be
  removed.

These are rejected:

```azora
import a.b::{without x}            // no wildcard
import a.b::{x, without y}         // explicit group cannot use subtraction
import a.b::{*, x}                 // wildcard group accepts exclusions only
import a.b::{*, without x, without x}
import a.b::{*, without missing}   // missing is not exported by the wildcard set
```

### 2.2 Resolution and visibility

Import filtering happens after module and declaration visibility are known but
before short-name collision resolution and symbol injection. It applies equally
to runtime declarations, packs/types, constants, compile-time type functions,
and named type macros. Private `_name` declarations and declarations blocked by
module visibility never enter the wildcard set and therefore cannot be named as
valid exclusions.

The compiler retains exclusions on the structured `ImportSpec`; the legacy flat
`(path, selector)` compatibility view cannot represent them and must not be used
for semantic resolution. Re-exported imports retain their exclusion metadata.

An exclusion adds no runtime cost. The excluded declaration is absent from the
imported symbol table and therefore cannot trigger dependency injection or code
generation merely because the wildcard was written.

Diagnostics should cover the precise `without` name and explain one repair:

```text
cannot exclude 'missing' from 'import a.b::{*, without missing}':
the wildcard does not provide a symbol named 'missing'

import exclusion 'x' is written more than once

'without' in an import group requires '*' first;
write 'import a.b::{*, without x}'
```

- ⬜ **Only `confined` is enforced today.** `exposed`, `ambient`, `resident` axis are
  parsed and recorded on the declaration, and nothing yet compares them against
  the importing file's folder - the module system is where that check belongs
