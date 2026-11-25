// Port of Compiler.kt - in part (see compiler.h).
#include "driver/compiler.h"

#include "base/failure.h"
#include "base/text.h"
#include "diagnostics/diagnostics.h"
#include "frontend/ast_validator.h"
#include "frontend/callback_impl_normalizer.h"
#include "frontend/debug_instrumenter.h"
#include "frontend/lexer.h"
#include "frontend/macro_expander.h"
#include "frontend/parser.h"
#include "gpu/gpu_module.h"
#include "ir/ir_type.h"
#include "semantic/comparison_deriver.h"
#include "semantic/front_rewrites.h"
#include "semantic/program_passes.h"
#include "semantic/reflect_deco_expander.h"
#include "semantic/serialization_deriver.h"
#include "semantic/variadic_monomorphizer.h"
#include "stdlib/az_stdlib.h"

namespace azora {

namespace {

FrontendResult failure(std::vector<std::string> errors) {
    FrontendResult result;
    result.errors = std::move(errors);
    return result;
}

/** Kotlin `catch (e: IllegalStateException)` (and IllegalArgumentException when [arguments]). */
bool caught(const CompileFailure& error, bool arguments) {
    return error.kind == FailureKind::IllegalState || (arguments && error.kind == FailureKind::IllegalArgument);
}

std::string undefinedLine(int32_t line, const std::string& name, const std::string& module, SymbolNamespace ns) {
    UndefinedSymbol diagnostic;
    diagnostic.symbol = name;
    diagnostic.namespace_ = ns;
    diagnostic.providerModule = module;
    return "line " + str(line) + ": " + DiagnosticRenderer::summary(diagnostic);
}

}  // namespace

Compiler::Compiler(std::vector<LibrarySource> sources, OrderedSet<std::string> exports) : exports(std::move(exports)) {
    for (LibrarySource& source : sources) {
        if (!GpuModule::isGpuSource(source.source)) librarySources.push_back(std::move(source));
    }
}

FrontendResult Compiler::frontend(Arena& arena, const std::string& source, bool debug,
                                  const OrderedMap<std::string, std::string>& defines) {
    // Clear per-compilation state
    Parser::resetFragmentMacros();
    IrTypes::aliases().clear();

    std::shared_ptr<StdlibInjector> libraries;
    try {
        libraries = StdlibInjector::create(librarySources, defines);
    } catch (const CompileFailure& error) {
        // An unusable standard library is something the user can fix: a Failure, not a crash.
        if (!caught(error, true)) throw;
        return failure({error.message});
    }
    auto failed = [&](std::vector<std::string> errors) {
        FrontendResult result = failure(std::move(errors));
        result.libraries = libraries;
        return result;
    };

    // 1-2. Lexer and parser, seeded with the standard library's compile-time bindings.
    Program rawAst;
    try {
        AzStdlib::loadPrograms();
        TypeListEnv lists = AzStdlib::comptimeLists();
        EnumEnv enums = AzStdlib::declaredEnums();
        TypeListScopes scopes = AzStdlib::comptimeListScopes();
        Parser parser(arena, Lexer(source).tokenize(), &lists, &enums, false, &scopes);
        rawAst = parser.parse();
    } catch (const CompileFailure& error) {
        if (!caught(error, true)) throw;
        return failed({error.message});
    }

    // 2a. Debug builds: instrument statements with `__dbg(line)` markers.
    Program parsed = debug ? DebugInstrumenter::instrument(arena, rawAst) : rawAst;

    // 2a-0 .. 2a-ter. The library checks, in Kotlin's order.
    if (std::vector<std::string> errors = libraries->unusableLibraries(parsed); !errors.empty()) return failed(errors);
    if (std::vector<std::string> errors = libraries->validateImports(parsed); !errors.empty()) return failed(errors);
    if (std::vector<std::string> errors = libraries->validateModuleCycles(parsed); !errors.empty()) return failed(errors);
    std::vector<UnresolvedContractAccess> contracts = libraries->unresolvedContracts(parsed);
    if (!contracts.empty()) {
        std::vector<std::string> errors;
        for (const UnresolvedContractAccess& access : contracts) {
            errors.push_back(undefinedLine(access.line, access.name, access.module, SymbolNamespace::SPEC_OR_DECORATOR));
        }
        return failed(errors);
    }
    TypeAccessValidation typeAccess = libraries->validateTypeAccess(parsed);
    if (!typeAccess.unresolved.empty() || !typeAccess.errors.empty()) {
        std::vector<std::string> errors;
        for (const UnresolvedTypeAccess& access : typeAccess.unresolved) {
            errors.push_back(undefinedLine(access.line, access.name, access.module, SymbolNamespace::TYPE));
        }
        errors.insert(errors.end(), typeAccess.errors.begin(), typeAccess.errors.end());
        return failed(errors);
    }
    if (std::vector<std::string> errors = libraries->validateImplPackDeclarations(parsed); !errors.empty()) return failed(errors);
    if (std::vector<std::string> errors = libraries->validatePrivateAccess(parsed); !errors.empty()) return failed(errors);

    // 2b. Standard library: append the declarations the program references.
    Program initiallyInjected = ScopeQualifiedImplTargets::resolve(
        arena, SpecDefaults::apply(arena, CallbackImplNormalizer::normalize(arena, libraries->inject(parsed, exports))));
    // (StabilityIndex / WrittenUses read the program for semantic analysis; they change nothing here.)

    // Decorator derives, comparison derives, casts and Display.
    SerializationDeriver::Result serialization = SerializationDeriver::derive(arena, initiallyInjected);
    if (!serialization.errors.empty()) return failed(serialization.errors);
    ComparisonDeriver::Result comparison = ComparisonDeriver::derive(arena, serialization.program);
    if (!comparison.errors.empty()) return failed(comparison.errors);
    Program casts = CastDeriver::rewrite(arena, comparison.program);
    Program displayed = DisplayDeriver::derive(arena, casts);
    Program injected =
        SpecDefaults::apply(arena, CallbackImplNormalizer::normalize(arena, libraries->inject(displayed, exports)));

    // 2b-bis. `inline for X in reflect<*>.withAnnot<D>` loops.
    Program reflected = ReflectDecoExpander::expand(arena, injected);

    // 2c. `meta` macros.
    Program macroExpanded;
    try {
        macroExpanded = MacroExpander::expand(arena, reflected);
    } catch (const CompileFailure& error) {
        if (!caught(error, false)) throw;
        return failed({error.message});
    }

    // 2c-bis. Re-inject what the expansions reference; strip re-added `__infix__` markers.
    Program macroReInjected = CallbackImplNormalizer::normalize(arena, libraries->inject(macroExpanded, exports));
    {
        std::vector<std::string> reinjectedInfix;
        bool anyMeta = false;
        for (const TopLevel& item : macroReInjected.items) {
            const TopLevelMeta* meta = as<TopLevelMeta>(item);
            if (!meta) continue;
            anyMeta = true;
            std::string name = removePrefix(meta->name, "__infix__");
            if (name != meta->name) reinjectedInfix.push_back(name);
        }
        if (!reinjectedInfix.empty() || anyMeta) {
            std::vector<TopLevel> items;
            for (const TopLevel& item : macroReInjected.items) {
                if (!is<TopLevelMeta>(item)) items.push_back(item);
            }
            macroReInjected.items = std::move(items);
            for (const std::string& op : reinjectedInfix) macroReInjected.infixOperators.add(op);
        }
    }

    // 2d. Monomorphize, re-inject what type macros referenced, monomorphize again.
    Program monomorphized;
    try {
        Program typeExpanded = VariadicMonomorphizer::monomorphize(arena, InferredTypeArgs::apply(arena, macroReInjected));
        Program typeReInjected = SpecDefaults::apply(
            arena, MacroExpander::expand(arena, CallbackImplNormalizer::normalize(arena, libraries->inject(typeExpanded, exports))));
        monomorphized = VariadicMonomorphizer::monomorphize(arena, InferredTypeArgs::apply(arena, typeReInjected));
    } catch (const CompileFailure& error) {
        if (!caught(error, false)) throw;
        return failed({error.message});
    }
    ReturnedVariants::Result returned = ReturnedVariants::resolve(arena, monomorphized);
    if (!returned.errors.empty()) return failed(returned.errors);
    Program ast = returned.program;

    // 3. AST validation.
    OrderedMap<std::string, LibraryDecorator> decorators = libraries->libraryDecorators();
    if (std::vector<std::string> errors = AstValidator(&decorators).validate(ast); !errors.empty()) return failed(errors);

    // What the first SemanticPipeline.analyze is handed (no package interface: nothing is precompiled).
    FrontendResult result;
    result.program = InferredTypeArgs::apply(arena, Witnesses::addDescriptorFields(arena, ast));
    result.libraries = libraries;
    return result;
}

}  // namespace azora
