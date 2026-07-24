# Azora Modules & Zones — DIP Implementation Profile

Modules organize files; zones organize namespaces within them; `import` and
`friend zone` control visibility.

## Grammar

```
MODULE ::= [export] [expose | protect | confine] module DOTTED.PATH

IMPORT ::= import DOTTED.PATH               // one module
         | import DOTTED.PATH.*             // every module below a namespace
         | import DOTTED.PATH.{a, b}        // grouped
         | import DOTTED.PATH.name          // selective symbol
         | export import DOTTED.PATH        // re-export

ZONE       ::= zone { BODY }                 // anonymous lexical scope
NAMED_ZONE ::= friend zone PATH::SUB { DECLARATIONS }
LABELED    ::= [inline | deepinline] zone ["label"] { … }
```

- A file declares at most one `module`.
- `module a.b.c` must live at `a/b/c.az` (or the folder-index `a/b/b.az` for
  `module a.b`).

---

## 1. Module declaration

```azora
module std.time
export module engine            // export: auto-imported by every downstream unit
protect module app.internal     // importable only within the declaring folder
confine module app.main         // private (not importable)
```

Visibility precedes `module`; `export` publishes the module so its declarations
are auto-injected with no `import`.

## 2. Imports

```azora
import std.io                   // module
import std.container.*          // every module under std.container
import std.container.{list, map}
import std.math.abs             // selective symbol
export import engine.render     // re-export (facade pattern)
```

Importing a module is the **only** way to make its symbols referenceable. A type
name used in an annotation needs its defining module imported directly.

## 3. Zones (namespaces)

A `friend zone PATH::SUB { … }` groups declarations under a `::`-separated zone
path. Members are reached with the full path:

```azora
friend zone std::math {
    fin PI = 3.14159
    func abs(x: Real): Real { … }
}

// elsewhere, after `import std.math`:
fin p = std::math::PI
fin a = std::math::abs(-2.0)
```

- The zone path (`std::math`) is independent of the module file path
  (`std.math`).
- Flat convention: many stdlib utilities live in `friend zone std` and are reached
  as `std::name` (e.g. `std::println`, `std::listOf`).

## 4. Anonymous & labeled zones

```azora
zone {                           // nested lexical scope; shadows outer names
    var x = 2
    std::println(::x)            // ::x reaches the enclosing scope
}

deepinline zone "config" {       // labeled zone: reflection metadata + inline
    fin DEBUG = true
}
```

## 5. Visibility recap

| Modifier | Scope |
|----------|-------|
| `expose` (default) | importable everywhere |
| `protect` | importable within the declaring folder |
| `confine` | private to the compilation unit |
| `shield` (fields) | externally read-only, internally mutable |

(`intern`/`shared`/`weak` were removed.)
