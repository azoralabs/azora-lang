# Azora Modules & Realms - DIP Implementation Profile

Modules organize files; realms organize namespaces within them; `import` and
`use realm` control visibility. A `scope` is the third, unrelated thing: a
lexical block inside a function body.

## Grammar

```
MODULE ::= [export] [expose | protect | confine] module DOTTED.PATH

IMPORT ::= import DOTTED.PATH               // one module
         | import DOTTED.PATH.*             // every module below a namespace
         | import DOTTED.PATH.{a, b}        // grouped
         | import DOTTED.PATH.name          // selective symbol
         | export import DOTTED.PATH        // re-export

REALM       ::= realm PATH::SUB { DECLARATIONS }        // a namespace
            |   use realm PATH::SUB { DECLARATIONS }    // members stay bare
LABELED     ::= [inline | deepinline] realm ["label"] { … }

SCOPE       ::= scope { BODY }              // a nested lexical scope (statement)
```

`import` loads a module. `use` is kept for the two places that are not module
imports: `use realm X { … }`, which opens a realm with its members left bare,
and `use as "…"`, which names a foreign symbol in a `bridge` or `spec`.

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

## 3. Realms (namespaces)

A `use realm PATH::SUB { … }` groups declarations under a `::`-separated realm
path. Members are reached with the full path:

```azora
use realm std {
    fin PI = 3.14159
    func abs(x: Double): Double { … }
}

// elsewhere, after `import std.math`:
fin p = std::PI
fin a = std::abs(-2.0)
```

- The realm path (`std`) is independent of the module file path
  (`std.math`).
- Flat convention: many stdlib utilities live in `use realm std` and are reached
  as `std::name` (e.g. `std::println`, `std::listOf`).

## 4. Anonymous & labeled realms

```azora
scope {                           // nested lexical scope; shadows outer names
    var x = 2
    std::println(::x)            // ::x reaches the enclosing scope
}

deepinline realm "config" {       // labeled realm: reflection metadata + inline
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
