# Azora Decorators — DIP Implementation Profile

`deco` declares a decorator (attribute); `@Name` applies it. Decorators attach
compile-time metadata, drive derive generators, and are queried via reflection.

## Grammar

```
DECL  ::= @[DECORATORS] VISIBILITY [bridge] deco NAME [for TARGETS] [bind BINDINGS] [{ FIELDS }]

TARGETS  ::= .Target | [.Target, .Target, …]
BINDINGS ::= Spec | [Spec, …] | [Spec for .Target, …]
FIELDS   ::= (fin | var) NAME : Type [= default]         // decorator arguments

APPLY ::= @Name
        | @Name(arg, name: arg)
        | @[Name, Name(args), …]                          // grouped, one row
```

Targets (`DecoTarget`): `.Pack`, `.Func`, `.Infx`, `.Prop`, `.Task`, `.Flow`,
`.Solo`, `.Slot`, `.Enum`, `.Deco`, `.Fail`, `.Field`, `.Param`, `.Var`, `.Fin`,
`.Let`, `.Test`, `.Ctor`, `.Dtor`, `.TypeAlias`, `.Bridge`, `.ImplOper`.

---

## 1. Declaring a decorator

```azora
deco Component                                    // marker, any target
deco SerialIgnore for .Field                       // restricted to fields
deco SerialName for [.Pack, .Enum, .Field] {       // with fields (arguments)
    fin value: String = ""
}
```

## 2. Applying decorators

```azora
@Component
pack Player { var speed = 8.0 }

@SerialName("display_name")
pack Account { … }

@System(.Update)                 // enum bare-dot argument
func step() { … }
```

### 2.1 Stacked and grouped

```azora
@First
@Second
pack A { … }

@[First, Second]                 // grouped — equivalent to stacking
pack B { … }

@[Component, System(.Update)]    // grouped with arguments
func moveSystem(world: World&) { … }
```

## 3. Targets

`for TARGETS` restricts where a decorator may be applied. A grouped decorator may
route bindings by target:

```azora
deco A for [.Pack, .Func] bind [X for .Pack, Y for .Func]
```

## 4. Binding to specs (derives)

`bind` connects a decorator to spec conformances it implies:

```azora
deco Serializable for .Pack bind [Serializer, JsonSerializer, AzonSerializer] {
    fin ignoreUnknownFields: Bool = false
    fin encodeDefaults: Bool = true
}
```

Applying `@Serializable` makes the pack conform to the bound specs (via the
compiler's derive generator — see the `@Derive` decorator in the Compiler
Intrinsic DIP).

## 5. Impl-style application

Decorators can also be applied through `impl`, including to specific members:

```azora
impl SerialName(value: "display_name") for Account::name
impl SerialIgnore for [Account::name, Account::password]
impl SerialIgnore for Account::*        // wildcard
```

## 6. Reflection

Query decorators at compile time:

```azora
std::reflect<P>.hasDeco<Serializable>          // Bool
std::reflect<P>.decoMeta<SerialName>.value      // a decorator field
```

See the Reflection DIP.

## 7. `bridge deco`

Compiler-provided decorators (`@Stable`, `@Experimental`, `@Deprecated`,
`@Derive`, `@Reactive`, `@EnforceNumFields`, `@UncheckedCast`, …) are declared
with `bridge deco` — see the Compiler Intrinsic DIP.
