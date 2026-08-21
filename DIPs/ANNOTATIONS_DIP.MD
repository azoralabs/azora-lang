# Azora Decorators - DIP Implementation Profile

`annot` declares a decorator (attribute); `@Name` applies it. Decorators attach
compile-time metadata, drive derive generators, and are queried via reflection.

## Grammar

```
DECL  ::= @[DECORATORS] VISIBILITY [bridge] annot @NAME [for TARGETS] [bind BINDINGS] [{ FIELDS }]

TARGETS  ::= .Target | [.Target, .Target, …] | .*
BINDINGS ::= Spec | [Spec, …] | [Spec for .Target, …]
FIELDS   ::= (fin | var) NAME : Type [= default]         // decorator arguments

APPLY ::= @Name
        | @Name(arg, name: arg)
        | @[Name, Name(args), …]                          // grouped, one row
```

Targets (`DecoTarget`, declared in `std/core.az`): `.Pack`, `.Func`,
`.AsyncFunc`, `.Prop`, `.AsyncProp`, `.Enum`, `.VariantEnum`, `.EnumValue`,
`.Error`, `.VariantError`, `.ErrorValue`, `.UnsafeUnion`, `.UnionValue`,
`.Annot`, `.Field`, `.Param`, `.Var`, `.Let`, `.Val`, `.Fin`, `.Test`, `.Ctor`,
`.Dtor`, `.TypeAlias`, `.Bridge`, `.Oper`.

---

## 1. Declaring a decorator

```azora
annot @Component                                    // marker, any target
annot @SerialIgnore for .Field                       // restricted to fields
annot @SerialName for [.Pack, .Enum, .Field] {       // with fields (arguments)
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

@[First, Second]                 // grouped - equivalent to stacking
pack B { … }

@[Component, System(.Update)]    // grouped with arguments
func moveSystem(world: World&) { … }
```

## 3. Targets

`for TARGETS` restricts where a decorator may be applied. A grouped decorator may
route bindings by target:

```azora
annot @A for [.Pack, .Func] bind [X for .Pack, Y for .Func]
```

## 4. Binding to specs (derives)

`bind` connects a decorator to spec conformances it implies:

```azora
annot @Serializable for .Pack bind [Serializer, AzonSerializer] {
    fin ignoreUnknownFields: Bool = false
    fin encodeDefaults: Bool = true
}
```

Applying `@Serializable` makes the pack conform to the bound specs (via the
compiler's derive generator - see the `@Derive` decorator in the Compiler
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
reflect<P>.hasAnnot<Serializable>          // Bool
reflect<P>.annotMeta<SerialName>.value      // a decorator field
```

See the Reflection DIP.

## 7. `bridge deco`

Compiler-provided decorators (`@Stable`, `@Experimental`, `@Deprecated`,
`@Derive`, `@EnforceNumFields`, `@UncheckedCast`, …) are declared
with `bridge deco` - see the Compiler Intrinsic DIP.
