# Azora Bugs

Failing tests and known defects, measured against the current tree.

- **97 failing tests** of 1654 (`./gradlew :compiler:desktopTest`).
- Every entry is `file:line  testName`, at the line the test is declared.
- §5 records eight causes fixed so far (162 -> 97).

Most of what remains is stale test sources (§1, §2). §6 is the one open design
item: collection members the compiler still supplies without a declaration.

---

## 1. Test sources on the old dialect (28)

### 1.1 `@arr[...]` -> `@arr[...]` (3)

A value macro is invoked through the scope that declares it. `@arr` takes
`[...$items]` only - a `key: value` entry belongs to `@map`.

```
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:285  arrayContainsScalarsAndStrings
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:540  packedCollectionsSupportWideValues
compiler/src/desktopTest/kotlin/org/azora/lang/codegen/LlvmAggregateExecTest.kt:569  realArraySumViaLoop
```

### 1.2 Scope qualification (25)

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

A canonical symbol is `__<scope path>_<local>`: `scope x::y { func z }` is
`__x_y_z`, `println` is `__std_println`.

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
compiler/src/commonTest/kotlin/org/azora/lang/codegen/TupleVariadicTest.kt:279       tupleTypeRequiresItsDeclaredScope
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
