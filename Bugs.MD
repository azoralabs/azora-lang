# Azora Bugs

Failing tests and known defects, measured against the current tree.

- **97 failing tests** of 1654 (`./gradlew :compiler:desktopTest`).
- Every entry is `file:line  testName`, at the line the test is declared.
- §5 records eight causes fixed so far (162 -> 97).

Most of what remains is stale test sources (§1, §2). §6 is the one open design
item: collection members the compiler still supplies without a declaration.

---

## 1. Test sources on the old dialect (28)

### 1.1 `@arr[...]` -> `@std::arr[...]` (3)

A value macro is invoked through the realm that declares it. `@std::arr` takes
`[...$items]` only - a `key: value` entry belongs to `@std::map`.

```
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:285  arrayContainsScalarsAndStrings
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:540  packedCollectionsSupportWideValues
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:569  realArraySumViaLoop
```

### 1.2 Realm qualification (25)

`Any`, `Int`, `String`, `Long`, `Unit`, `Double`, `@Deprecated`, `floor`,
`reverse`, `stringLength`. Author-written, so correctly reported.

```
compiler/src/commonTest/kotlin/org/azora/lang/codegen/CastIsLambdaTest.kt:18              asCastInt
compiler/src/commonTest/kotlin/org/azora/lang/codegen/CastIsLambdaTest.kt:22              asCastString
compiler/src/commonTest/kotlin/org/azora/lang/codegen/EngineArchitectureSyntaxTest.kt:76  libraryDefinedResourceAndQueryTypesExpandAfterImportResolution
compiler/src/commonTest/kotlin/org/azora/lang/codegen/EnumWhenTest.kt:33                  typedDeclarationsAcceptContextualEnumVariants
compiler/src/commonTest/kotlin/org/azora/lang/codegen/LambdaTest.kt:409                   aReceiverLambdaBindsItsContextByName
compiler/src/commonTest/kotlin/org/azora/lang/codegen/LambdaTest.kt:428                   aReceiverLambdaTakesOrdinaryParametersToo
compiler/src/commonTest/kotlin/org/azora/lang/codegen/LambdaTest.kt:66                    lambdaInInterpolation
compiler/src/commonTest/kotlin/org/azora/lang/codegen/LoopTest.kt:211                     reverseFunctionCall
compiler/src/commonTest/kotlin/org/azora/lang/codegen/ReturnTypeDeclarationTest.kt:106    aFailableFunctionThatYieldsAValueMustDeclareIt
compiler/src/commonTest/kotlin/org/azora/lang/codegen/ReturnTypeDeclarationTest.kt: 94     aFailableFunctionMayOmitTheOkTypeAndIsUnit
compiler/src/commonTest/kotlin/org/azora/lang/codegen/StdlibInjectionTest.kt:261          ifExpressionElseIfChain
compiler/src/commonTest/kotlin/org/azora/lang/codegen/StdlibInjectionTest.kt:264          expressionBodiedFunction
compiler/src/commonTest/kotlin/org/azora/lang/codegen/StdlibInjectionTest.kt:90           userDefinitionShadowsStdlib
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TestAssertTraceTest.kt:158          trace_acceptsShorthandAndRuntimeLogLevels
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TestAssertTraceTest.kt:174          trace_liftsBodiesIntoTypedIrFunctions
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TestAssertTraceTest.kt:408          inlineTrace_acceptsShorthandAndInlineBindings
compiler/src/commonTest/kotlin/org/azora/lang/codegen/ThirdPartyFormatTest.kt:81          aThirdPartyTextFormatRoundTripsAGeneratedCodec
compiler/src/commonTest/kotlin/org/azora/lang/codegen/Tier2MetaprogrammingTest.kt:113     annotationOnVarAndPack
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmCodegenExecTest.kt:558         longArithmetic
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmCodegenExecTest.kt:591         traceUsesRuntimeLevelAndImplicitReceiver
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmStdlibExecTest.kt:18           floorCeilRoundNative
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmStdlibExecTest.kt:23           ifExpressionNative
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/ReadmeSnippetTest.kt:99            everyReadmeSnippetCompiles
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/WasmCodegenExecTest.kt:185         interpolatesRealAndLong
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/WasmCodegenExecTest.kt:43          traceUsesRuntimeLevelAndImplicitReceiver
```

---

## 2. Golden output on the old mangling (17)

A canonical symbol is `__<realm path>_<local>`: `realm x::y { func z }` is
`__x_y_z`, `std::println` is `__std_println`.

```
compiler/src/commonTest/kotlin/org/azora/lang/codegen/CodegenGoldenTest.kt:167        llvmFullOutputScalar
compiler/src/commonTest/kotlin/org/azora/lang/codegen/ContainerStdlibTest.kt:20       everyContainerSourceParsesAndIsSerializableMetadata
compiler/src/commonTest/kotlin/org/azora/lang/codegen/MetaprogrammingTest.kt:18       inlineFunc_bodySubstitutedAtCallSite
compiler/src/commonTest/kotlin/org/azora/lang/codegen/MetaprogrammingTest.kt:216      fullMetaprogramming_allInlineRulesWithGlobals
compiler/src/commonTest/kotlin/org/azora/lang/codegen/StdlibInjectionTest.kt:176      stdlibIndexExposesCollectionPacks
compiler/src/commonTest/kotlin/org/azora/lang/codegen/StdlibInjectionTest.kt:93       programsWithoutStdlibAreUntouched
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TupleVariadicTest.kt:16         genericImplReflectedFieldLoopParses
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:341         repeatedUseOfOneCombinationReportsOnce
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeRefTest.kt:100              genericNamedTypeAnnotation
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeRefTest.kt:50               immutableCollectionTypeAnnotations
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeRefTest.kt:63               mutableCollectionTypeAnnotations
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:406   globalArrayInitializerRunsBeforeMain
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:441   typedSetLiteral
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:452   typedStdlibCollectionLiteralsExposeSize
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:496   setClearAndEmptyProperties
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmRegressionExecTest.kt:103  taskThreadsInitializeThreadLocalAggregates
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmRegressionExecTest.kt:234  threadLocalStdlibCollectionsInitializeObjects
```

---

## 3. Removed constructs still exercised (2)

`impl oper... for Type` was removed; an operator is declared beside its type as
`oper[] [self: Type&](index: Int): T { ... }`.

```
compiler/src/commonTest/kotlin/org/azora/lang/codegen/Tier1PolishTest.kt:480            operIndexGetAndSet
compiler/src/commonTest/kotlin/org/azora/lang/semantic/SerializationDeriverTest.kt:185  generatedPrimitiveCodecBodiesPassSemanticAnalysis
```

---

## 4. Behaviour and assertion failures (50)

The genuine tail. Some assert an *intended* rejection and fail only because the
message changed. One reads `../Internal/Std/Time.az`, gone since the standard
library moved to `std/`.

```
compiler/src/commonTest/kotlin/org/azora/lang/codegen/ArrayStdlibTest.kt:22          arrayOfAndLiteralProduceEquivalentValues
compiler/src/commonTest/kotlin/org/azora/lang/codegen/CollectionCtorTest.kt:154      query_tup_type_and_loop
compiler/src/commonTest/kotlin/org/azora/lang/codegen/CollectionCtorTest.kt:180      alloc_buffer_and_pointer_index
compiler/src/commonTest/kotlin/org/azora/lang/codegen/CollectionCtorTest.kt:70       var_when_matches_held_type
compiler/src/commonTest/kotlin/org/azora/lang/codegen/ConstGenericArrayTest.kt:29    sizedArrayTypeHoldsCompileTimeSize
compiler/src/commonTest/kotlin/org/azora/lang/codegen/ContainerStdlibTest.kt:48      coreContainersExecuteTogether
compiler/src/commonTest/kotlin/org/azora/lang/codegen/ConvertSpecTest.kt:46          fromIsAStaticReachedThroughTheType
compiler/src/commonTest/kotlin/org/azora/lang/codegen/Feature003SyntaxTest.kt:374    operInsideRegularImplIsRejected
compiler/src/commonTest/kotlin/org/azora/lang/codegen/ModulesTest.kt:213             scopeIsJustAnIdentifierNotANamespaceKeyword
compiler/src/commonTest/kotlin/org/azora/lang/codegen/StabilityDecoratorTest.kt:96   unknownDecoratorIsRejected
compiler/src/commonTest/kotlin/org/azora/lang/codegen/StdlibInjectionTest.kt:219     importedPackCarriesItsFieldDecoratorImplementations
compiler/src/commonTest/kotlin/org/azora/lang/codegen/StdlibInjectionTest.kt:354     importRejectsDoubleColonSyntax
compiler/src/commonTest/kotlin/org/azora/lang/codegen/StdlibInjectionTest.kt:99      rootModuleContainsCompilerPredefinedDeclarations
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TestAssertTraceTest.kt:241     directTrace_acceptsExplicitShorthandLevel
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TestAssertTraceTest.kt:257     directTrace_acceptsQualifiedExplicitLevel
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TestAssertTraceTest.kt:425     inlineTrace_acceptsTopLevelInlineBindings
compiler/src/commonTest/kotlin/org/azora/lang/codegen/Tier1PolishTest.kt:420         infxMethodCalledInfix
compiler/src/commonTest/kotlin/org/azora/lang/codegen/Tier4ConcurrencyTest.kt:94     channelWithProducerTask
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TupleTest.kt:12                tupleOfAndTupleTypeRemainSupported
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TupleTest.kt:50                tupleTypeSyntaxIsRejectedWithMigration
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TupleVariadicTest.kt:279       tupleTypeRequiresItsDeclaredRealm
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:113        fullyQualifiedStdlibPromoteDoesNotRequireImport
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:120        fixedTypePropertyResolvesReturnType
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:132        exactOverloadWinsBeforeVariadicOverload
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:145        typePropertiesCanCallOtherTypeProperties
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:156        aVariadicTypePropertySupportsBindingsLoopsAndRank
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:174        aStatementIfWithoutAnElseFallsThrough
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:203        variadicConstraintProducesDiagnostic
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:214        recursiveTypePropertyProducesDiagnostic
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:241        aConstraintViolatingCandidateIsSkippedWhenAnotherApplies
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:257        aConcreteInvalidSpecializationIsAnError
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:272        variadicOverloadSelectionPrefersTheLongestFixedPrefix
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:301        genericSatisfyingItsConstraintsIsAccepted
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:309        genericWithADifferentSatisfyingCombinationIsAccepted
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:317        genericViolatingNominalConformanceIsRejected
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:32         parserRetainsStructuredTypePropertyDeclaration
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:329        genericViolatingAConstRangeIsRejected
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeFunctionTest.kt:72         aTypePropertyBindingNeedsAnExplicitTypeAnnotation
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeRefTest.kt:107             nestedGenericNamedTypeAnnotation
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeRefTest.kt:133             arrayGenericNameRequiresExactlyOneTypeArgument
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeRefTest.kt:139             bracketCollectionTypesAreRejected
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeRefTest.kt:146             removedCollectionTypeSpellingsAreRejected
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TypeRefTest.kt:92              removedTupleTypeAnnotationIsRejected
compiler/src/commonTest/kotlin/org/azora/lang/codegen/WebsiteExamplesTest.kt:431     ch26_maps_int_keys
compiler/src/commonTest/kotlin/org/azora/lang/codegen/WebsiteExamplesTest.kt:533     ch31_channel
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:324  mapLiteralReadsIntegerKeys
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:348  mapInsertsMissingEntry
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:373  mapLengthAndEmptyProperties
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/WasmCodegenExecTest.kt:293    arrayIndexAndLength
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TimeStdlibTest.kt:20  timeSourceAndAllEmbeddedTestsParse
```

---

## 5. Fixed

### 5.1 Intrinsics were intercepted by unmangled name (6 tests)

`IrInterpreter` matched builtins as `stringLength`, `charAt`, ... but a
canonicalized call arrives as `__std_stringLength`. Fixed by `symbolDenotes` in
`ir/Mangling.kt`, which matches the tail of a canonical symbol and names no
realm: `__x_y_z` denotes `z` whatever `x::y` is.

### 5.2 The compiler synthesized a type it then rejected (18 tests)

An untyped lambda parameter gets `Any` as a placeholder. `validateTypeAccess`
applied a **source-level** rule to it and demanded `std::Any`. `TypeRef.Named`
now carries `synthesized`, and the validator skips those.

### 5.3 `trace`'s `LogLevel` was invisible to injection (8 tests)

`trace "x"` lowers to a synthesized `__defaultLogLevel()`, but no program using
`trace` writes that name, so the enum was never injected.
`StdlibInjector.BUILTIN_TYPE_DEPENDENCIES` maps the builtin to what it needs.

### 5.4 A bridge target could not be reached through its realm (5 tests)

`parseBridgeTarget` knew `.C` and `Target.C` but not `std::Target.C`.
Both it and `noteBridgeTargetConstant` now accept a realm path and match the
enum by its own name.

### 5.5 `is` and spec bounds rejected a realm-qualified type (11 tests)

`x is std::Int` read the type with a single `consume(IDENTIFIER)`, leaving the
`::` for the enclosing construct. A shared `parseQualifiedTypeName` reads the
full path, and `IrGenerator` canonicalizes it - without that, `is std::Int`
compiled and evaluated false.

### 5.6 Keyed macro arguments were unimplemented (5 tests)

`@std::map["k": v]` could not be written: the `[...${key: value}]` arm stored
its capture names and the invocation side was never built. Added
`Expr.MapEntryArg`, keyed parsing, and `tryZipSpread`, which expands
`...std::mapEntry($key, $value)` once per index.

### 5.7 `isEmpty` / `isNotEmpty` worked without being declared (7 tests)

Both were hardcoded in `TypeResolver`, `IrGenerator`, `IrInterpreter`,
`LlvmCodegen` and `WasmCodegen`, so they resolved on a raw `Array` with no
declaration anywhere - and the call form `x.isEmpty()` was accepted too, because
a builtin carries no `MemberCallStyle`.

The hardcoding is removed and both are declared in `std/container/array.az`:

    impl<T, N: Int> Array<T, N> {
        prop isEmpty[self: Self&]: Bool = self.size == 0
        prop isNotEmpty[self: Self&]: Bool = self.size > 0
    }

Every other collection already declared its own, which is what showed the
hardcoding was never load-bearing.

### 5.8 A generic `impl` on an aggregate builtin did not work (part of 5.7)

Three separate blockers, each hiding the next:

- `Array<T, N>` with `N` a *type parameter* was not a resolvable type -
  `IrNode.resolve` accepted only a literal const or `*`. Both mean "no fixed
  length", so they now resolve the same way.
- `SymbolCollector` built `self` from `TypeRef.Named(item.typeName)` - a bare
  name - so `impl<T, N: Int> Array<T, N>` produced a receiver of `Array` with
  zero arguments. It now applies the declaration to its own type parameters.
- Nothing looked for declared members on an aggregate receiver. `TypeResolver`
  now consults them through the type constructor, and `IrGenerator` lowers such
  a member to a call rather than a field read.

---

## 6. Open: collection members the compiler still supplies

`Array` declares `isEmpty` and `isNotEmpty` (§5.7). These are still hardcoded,
with no declaration anywhere:

- `Array`: `size`, `length` (`data` is a declared field)
- builtin `map[K, V]`: `size`, `length`, `containsKey`, `clear`, `isEmpty`, `isNotEmpty`
- builtin `set[T]`: `size`, `length`, `add`, `remove`, `contains`, `clear`, `isEmpty`, `isNotEmpty`

Declaring them needs one thing §5.8 did not: a **bodyless** member. `prop size`
has no Azora body - the backend supplies the count - so it must be a
`bridge prop`, which registers a member without defining a callable function.
The resolver would then need to find its declared type without a
`FunctionSymbol`, and `IrGenerator` must keep emitting `IrExpr.Member` for it
rather than a call, since there is no function to call.

Separately, `IrType.Map` and `IrType.Set` exist as compiler builtins even though
`std`'s `Map` and `Set` are ordinary packs. Removing those types is the larger
half of this, and touches the resolver, the IR and all three backends.
