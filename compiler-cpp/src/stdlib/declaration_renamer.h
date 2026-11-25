// Port of stdlib/DeclarationRenamer.kt.
//
// Gives library declarations the identity their module binds them to. The
// program is one flat namespace, so a library declaration that source may not
// name carries a name no source can spell (`lib__seq__Vector`), and every
// reference its module makes to it is rewritten to match. [names] maps each
// spelling the module uses to that identity; a name it does not contain is
// left as written. `Owner__member` moves with its owner. A parameter, local or
// lambda parameter shadows a value of the same name, and a type parameter
// shadows a type, exactly as in the source.
#pragma once

#include "base/ordered.h"
#include "frontend/ast.h"

#include <functional>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace azora {

/**
 * Every import the program writes: at file scope, and opening any of its
 * blocks (Kotlin `Program.writtenImports()`).
 */
std::vector<TopLevelUseImport*> writtenImports(Arena& arena, const Program& program);

struct DeclarationRenamer;

/**
 * Calls [onImportingBlock] with each block of [item] that imports, in exactly
 * the order DeclarationRenamer::item() would, without building the renamed
 * copy: what Kotlin gets from a renamer whose result it discards. Keep the two
 * traversals in step.
 */
void forEachImportingBlock(const TopLevel& item,
                           const std::function<void(const std::vector<StmtImport*>&, const std::vector<Stmt>&)>& onImportingBlock);

struct DeclarationRenamer {
    using Names = OrderedMap<std::string, std::string>;
    /** What each body `import` binds: spelling → identity, within its block (by node identity). */
    using BodyImports = std::unordered_map<const StmtImport*, Names>;
    /** Called with each block that imports and the statements it scopes. */
    using ImportingBlock = std::function<void(const std::vector<StmtImport*>&, const std::vector<Stmt>&)>;

    DeclarationRenamer(Arena& arena, Names names, BodyImports bodyImports = {}, ImportingBlock onImportingBlock = nullptr)
        : arena(arena), names(std::move(names)), bodyImports(std::move(bodyImports)),
          onImportingBlock(std::move(onImportingBlock)) {}

    /** [ref], as a type written at the top of its module. */
    TypeRef type(const TypeRef& ref);

    TopLevel item(const TopLevel& item);

    /** Values shadow by name; types by type parameter; renames are the body imports in force. */
    struct Scope {
        std::unordered_set<std::string> values;
        std::unordered_set<std::string> types;
        std::unordered_map<std::string, std::string> renames;
    };

    Arena& arena;
    Names names;
    BodyImports bodyImports;
    ImportingBlock onImportingBlock;

    std::optional<std::string> renamed(const std::string& name) const;
    std::string declared(const std::string& name) const;
    std::string value(const std::string& name, const Scope& scope) const;
    std::string typeName(const std::string& name, const Scope& scope) const;

    FuncDecl func(const FuncDecl& decl, const Scope& owner, const std::string& name);
    Param param(const Param& p, const Scope& scope);
    PackField field(const PackField& f, const Scope& scope);
    std::vector<Annotation> annotations(const std::vector<Annotation>& list, const Scope& scope);
    TypeAnnotation annotation(const TypeAnnotation& a, const Scope& scope);
    TypeRef typeMacroCall(const TypeRefNamed& ref, const Scope& scope);
    TypeRef typeRef(const TypeRef& ref, const Scope& scope);
    std::vector<TypeRef> typeRefs(const std::vector<TypeRef>& refs, const Scope& scope);
    std::vector<Stmt> block(const std::vector<Stmt>& body, const Scope& scope);
    Stmt stmt(const Stmt& s, const Scope& scope);
    Expr expr(const Expr& e, const Scope& scope);
    std::vector<Expr> exprs(const std::vector<Expr>& items, const Scope& scope);
};

}  // namespace azora
