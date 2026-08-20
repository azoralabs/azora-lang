# Azora Modules & Scopes - DIP Implementation Profile

## Grammar

```
MODULE ::= [exposed | confined | ambient | resident] module DOTTED.PATH

IMPORT ::= import DOTTED.PATH.*             // full symbols from module
         | import DOTTED.PATH.[a.*, b.*]    // grouped
         | import DOTTED.PATH.name          // selective symbol
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
import std.io::*                  // module
import std.container::*           // every module under std.container
import std.container.[list::*, map::*]
import std.math::abs              // selective symbol
```


- ⬜ **Only `confined` is enforced today.** `exposed`, `ambient`, `resident` axis are
  parsed and recorded on the declaration, and nothing yet compares them against
  the importing file's folder - the module system is where that check belongs
