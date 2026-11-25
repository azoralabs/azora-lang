// Port of AstValidator.kt.
#include "frontend/ast_validator.h"

#include "base/text.h"
#include "diagnostics/suggestions.h"
#include "frontend/ast_util.h"

#include <algorithm>

namespace azora {

namespace {

/** The name that says "this value arrives and is deliberately ignored". */
const char* const IGNORED_NAME = "_";

/** Kotlin `ImportSpec.leaves()`. */
void importLeaves(const ImportSpec& spec, std::vector<const ImportSpec*>& out) {
    if (spec.selector.kind == SelectorKind::Group) {
        for (const ImportSpec& member : spec.selector.members) importLeaves(member, out);
    } else {
        out.push_back(&spec);
    }
}

/** Whether [expr] reads the receiver - `self`, or a member of it. */
bool readsSelf(const Expr& expr) {
    switch (expr.kind()) {
        case ExprKind::Identifier: return cast<ExprIdentifier>(expr).name == "self";
        case ExprKind::Member: return readsSelf(cast<ExprMember>(expr).target);
        case ExprKind::MethodCall: {
            const ExprMethodCall& call = cast<ExprMethodCall>(expr);
            return readsSelf(call.target) || std::any_of(call.args.begin(), call.args.end(), readsSelf);
        }
        case ExprKind::Call: {
            const ExprCall& call = cast<ExprCall>(expr);
            return std::any_of(call.args.begin(), call.args.end(), readsSelf) || (call.receiver && readsSelf(call.receiver));
        }
        case ExprKind::Binary: return readsSelf(cast<ExprBinary>(expr).left) || readsSelf(cast<ExprBinary>(expr).right);
        case ExprKind::Unary: return readsSelf(cast<ExprUnary>(expr).operand);
        case ExprKind::IncDec: return readsSelf(cast<ExprIncDec>(expr).target);
        case ExprKind::Grouping: return readsSelf(cast<ExprGrouping>(expr).expr);
        default: return false;
    }
}

bool hasReturnInBody(const std::vector<Stmt>& body);

bool hasReturnIn(const std::vector<Stmt>& thenBranch, const std::optional<std::vector<Stmt>>& elseBranch) {
    return hasReturnInBody(thenBranch) || (elseBranch && hasReturnInBody(*elseBranch));
}

bool hasReturnInBody(const std::vector<Stmt>& body) {
    for (const Stmt& stmt : body) {
        bool found = false;
        switch (stmt.kind()) {
            // `return .Variant` fails the function, and a panic cannot fall
            // through either: both leave as surely as a value return does.
            case StmtKind::Return:
            case StmtKind::Throw:
            case StmtKind::Panic:
                found = true;
                break;
            // `inline for` bodies are expanded later; a return inside one counts.
            case StmtKind::InlineFor: found = hasReturnInBody(cast<StmtInlineFor>(stmt).body); break;
            case StmtKind::Try: found = hasReturnIn(cast<StmtTry>(stmt).body, cast<StmtTry>(stmt).catchBody); break;
            case StmtKind::If: found = hasReturnIn(cast<StmtIf>(stmt).thenBranch, cast<StmtIf>(stmt).elseBranch); break;
            case StmtKind::InlineIf:
                found = hasReturnIn(cast<StmtInlineIf>(stmt).thenBranch, cast<StmtInlineIf>(stmt).elseBranch);
                break;
            case StmtKind::DeepInlineIf:
                found = hasReturnIn(cast<StmtDeepInlineIf>(stmt).thenBranch, cast<StmtDeepInlineIf>(stmt).elseBranch);
                break;
            case StmtKind::Scope: found = hasReturnInBody(cast<StmtScope>(stmt).body); break;
            // `using value { … }` opens a receiver, not a function.
            case StmtKind::UsingContext: found = hasReturnInBody(cast<StmtUsingContext>(stmt).body); break;
            case StmtKind::While: found = hasReturnInBody(cast<StmtWhile>(stmt).body); break;
            case StmtKind::For: found = hasReturnInBody(cast<StmtFor>(stmt).body); break;
            case StmtKind::Loop: found = hasReturnInBody(cast<StmtLoop>(stmt).body); break;
            case StmtKind::When: {
                const StmtWhen& w = cast<StmtWhen>(stmt);
                for (const WhenBranch& branch : w.branches) {
                    if (hasReturnInBody(branch.body)) {
                        found = true;
                        break;
                    }
                }
                if (!found && w.elseBranch) found = hasReturnInBody(*w.elseBranch);
                break;
            }
            default:
                break;
        }
        if (found) return true;
    }
    return false;
}

void validateStmts(const std::vector<Stmt>& body, const std::string& funcName, std::vector<std::string>& errors);

void validateStmt(const Stmt& stmt, const std::string& funcName, std::vector<std::string>& errors) {
    switch (stmt.kind()) {
        // Injection resolves a body import and removes it; one that remains
        // named nothing a library provides.
        case StmtKind::Import: {
            const StmtImport& import = cast<StmtImport>(stmt);
            std::vector<const ImportSpec*> leaves;
            for (const ImportSpec& spec : import.use->specs) importLeaves(spec, leaves);
            std::string paths;
            for (size_t i = 0; i < leaves.size(); i++) {
                if (i > 0) paths += ", ";
                paths += leaves[i]->path;
            }
            errors.push_back("line " + str(import.line) + ": '" + paths +
                             "' does not name a library module this program can import");
            return;
        }
        case StmtKind::VarDecl:
            if (cast<StmtVarDecl>(stmt).name.empty()) {
                errors.push_back("line " + str(stmt.line()) + ": empty variable name in function '" + funcName + "'");
            }
            return;
        case StmtKind::FinDecl:
            if (cast<StmtFinDecl>(stmt).name.empty()) {
                errors.push_back("line " + str(stmt.line()) + ": empty variable name in function '" + funcName + "'");
            }
            return;
        case StmtKind::Assignment:
            if (cast<StmtAssignment>(stmt).name.empty()) {
                errors.push_back("line " + str(stmt.line()) + ": empty variable name in assignment");
            }
            return;
        case StmtKind::If:
            validateStmts(cast<StmtIf>(stmt).thenBranch, funcName, errors);
            if (cast<StmtIf>(stmt).elseBranch) validateStmts(*cast<StmtIf>(stmt).elseBranch, funcName, errors);
            return;
        case StmtKind::InlineIf:
            validateStmts(cast<StmtInlineIf>(stmt).thenBranch, funcName, errors);
            if (cast<StmtInlineIf>(stmt).elseBranch) validateStmts(*cast<StmtInlineIf>(stmt).elseBranch, funcName, errors);
            return;
        case StmtKind::DeepInlineIf:
            validateStmts(cast<StmtDeepInlineIf>(stmt).thenBranch, funcName, errors);
            if (cast<StmtDeepInlineIf>(stmt).elseBranch) {
                validateStmts(*cast<StmtDeepInlineIf>(stmt).elseBranch, funcName, errors);
            }
            return;
        case StmtKind::DeepInlineBlock: validateStmts(cast<StmtDeepInlineBlock>(stmt).body, funcName, errors); return;
        case StmtKind::NoInline: validateStmt(cast<StmtNoInline>(stmt).stmt, funcName, errors); return;
        case StmtKind::InlineBlock: validateStmts(cast<StmtInlineBlock>(stmt).body, funcName, errors); return;
        case StmtKind::InlineFor: validateStmts(cast<StmtInlineFor>(stmt).body, funcName, errors); return;
        case StmtKind::Scope: validateStmts(cast<StmtScope>(stmt).body, funcName, errors); return;
        case StmtKind::While: validateStmts(cast<StmtWhile>(stmt).body, funcName, errors); return;
        case StmtKind::For: validateStmts(cast<StmtFor>(stmt).body, funcName, errors); return;
        case StmtKind::Loop: validateStmts(cast<StmtLoop>(stmt).body, funcName, errors); return;
        case StmtKind::UsingContext: validateStmts(cast<StmtUsingContext>(stmt).body, funcName, errors); return;
        case StmtKind::When: {
            const StmtWhen& w = cast<StmtWhen>(stmt);
            for (const WhenBranch& branch : w.branches) validateStmts(branch.body, funcName, errors);
            if (w.elseBranch) validateStmts(*w.elseBranch, funcName, errors);
            return;
        }
        // Kotlin validates `ExprStmt(value)` here, which checks nothing.
        case StmtKind::Throw:
        case StmtKind::Panic:
            return;
        case StmtKind::Try:
            validateStmts(cast<StmtTry>(stmt).body, funcName, errors);
            if (cast<StmtTry>(stmt).catchBody) validateStmts(*cast<StmtTry>(stmt).catchBody, funcName, errors);
            return;
        case StmtKind::Defer: validateStmts(cast<StmtDefer>(stmt).body, funcName, errors); return;
        default:
            return;
    }
}

void validateStmts(const std::vector<Stmt>& body, const std::string& funcName, std::vector<std::string>& errors) {
    for (const Stmt& s : body) validateStmt(s, funcName, errors);
}

const Annotation* findAnnotation(const std::vector<Annotation>& annotations, const char* name) {
    for (const Annotation& a : annotations) {
        if (a.name == name) return &a;
    }
    return nullptr;
}

/** Kotlin's data-class text of a TypeAnnotation, as `'${func.returnType}'` prints it. */
std::string typeAnnotationText(const TypeAnnotation& annotation) {
    if (annotation.isExplicit()) return "Explicit(ref=" + toString(annotation.ref) + ")";
    return "Inferred";
}

struct Validation {
    AstValidator& validator;
    std::vector<std::string>& errors;

    /** Why [ann] names no decorator in scope, or none when it does. */
    std::optional<std::string> unknownDecorator(const Annotation& ann) {
        if (validator.customDecos.contains(ann.name)) return std::nullopt;
        if (validator.libraryDecorators == nullptr) return std::nullopt;
        const OrderedMap<std::string, LibraryDecorator>& libraries = *validator.libraryDecorators;
        const LibraryDecorator* provided = libraries.get(ann.name);
        if (provided == nullptr) {
            OrderedSet<std::string> candidates = validator.customDecos;
            for (const auto& [name, decorator] : libraries.entries) candidates.add(name);
            return "line " + str(ann.line) + ": unknown decorator '@" + ann.name + "'" +
                   didYouMean(ann.name, candidates.items, [](const std::string& it) { return "@" + it; });
        }
        if (provided->alwaysInScope) return std::nullopt;
        return "line " + str(ann.line) + ": unknown decorator '@" + ann.name + "' - '@" + ann.name + "' is provided by " +
               "'" + provided->module + "': add 'import " + provided->module + "::" + ann.name + "'";
    }

    /** `@Experimental`/`@Stable`/`@Since`/`@Deprecated` rules, and unknown decorators. */
    void validateStability(const std::vector<Annotation>& annotations) {
        for (const Annotation& ann : annotations) {
            // A lowercase name is a macro, not a decorator.
            if (!firstIsUpperCase(ann.name)) continue;
            if (std::optional<std::string> error = unknownDecorator(ann)) errors.push_back(*error);
        }
        const Annotation* experimental = findAnnotation(annotations, "Experimental");
        const Annotation* stable = findAnnotation(annotations, "Stable");
        const Annotation* since = findAnnotation(annotations, "Since");
        if (experimental && stable) {
            errors.push_back("line " + str(stable->line) + ": a declaration cannot be both @experimental and @stable");
        }
        if (since && (experimental || stable)) {
            errors.push_back("line " + str(since->line) +
                             ": @since is redundant with @Experimental(since:)/@Stable(since:) - use only one");
        }
        for (const Annotation& ann : annotations) {
            if (ann.name != "Experimental" && ann.name != "Stable") continue;
            const Expr* s = nullptr;
            for (const auto& [key, value] : ann.namedArgs) {
                if (key == "since") {
                    s = &value;
                    break;
                }
            }
            if (s && *s && !is<ExprStringLiteral>(*s)) {
                errors.push_back("line " + str(ann.line) + ": @" + ann.name + "(since: ...) requires a string version literal");
            }
        }
        if (since && (since->args.size() != 1 || !is<ExprStringLiteral>(since->args[0]))) {
            errors.push_back("line " + str(since->line) +
                             ": @Since accepts one positional string version argument; write @Since(\"0.1\")");
        }
        for (const Annotation& ann : annotations) {
            if (ann.name != "Deprecated") continue;
            for (const auto& [key, value] : ann.namedArgs) {
                if (!is<ExprStringLiteral>(value)) {
                    errors.push_back("line " + str(ann.line) + ": @Deprecated(" + key + ": ...) requires a string argument");
                }
            }
        }
    }

    void validateFunction(const FuncDecl& func) {
        std::vector<Annotation> annotations = func.annotations;
        for (const Param& p : func.params) annotations.insert(annotations.end(), p.annotations.begin(), p.annotations.end());
        validateStability(annotations);
        // `_` is not a name: a signature may ignore as many parameters as it likes.
        OrderedSet<std::string> paramNames;
        for (const Param& param : func.params) {
            if (param.name == IGNORED_NAME) continue;
            if (!paramNames.add(param.name)) {
                errors.push_back("line " + str(func.line) + ": duplicate parameter '" + param.name + "' in function '" +
                                 func.name + "'");
            }
        }
        // Non-Unit functions must have at least one return; a bodyless
        // declaration is compiler-provided and has nothing to check.
        TypeRef declaredReturn = func.returnType.ref;
        TypeRef successReturn = declaredReturn;
        if (TypeRefFailable* failable = as<TypeRefFailable>(declaredReturn)) successReturn = failable->ok;
        TypeRefNamed* successNamed = as<TypeRefNamed>(successReturn);
        bool returnsUnit = successNamed && successNamed->name == "Unit";
        if (declaredReturn && !returnsUnit && !func.body.empty()) {
            if (!hasReturnInBody(func.body)) {
                errors.push_back("line " + str(func.line) + ": function '" + func.name + "' declares return type " + "'" +
                                 typeAnnotationText(func.returnType) + "' but has no return statement");
            }
        }
        // A `Type` is a compile-time value; only `deepinline` may return one.
        if (successNamed && successNamed->name == "Type" && !func.isDeepInline) {
            errors.push_back("line " + str(func.line) + ": '" + func.name + "' returns 'Type', which only a " +
                             "'deepinline' member may do - a type is not a value at runtime");
        }
        // A declared type computed from `self` needs `deepinline` to fold through.
        if (TypeRefNamed* named = as<TypeRefNamed>(declaredReturn)) {
            if (std::any_of(named->valueArgs.begin(), named->valueArgs.end(), readsSelf) && !func.isDeepInline) {
                errors.push_back("line " + str(func.line) + ": '" + func.name + "' computes its type from 'self', which only a " +
                                 "'deepinline' member may do - the fold has to reach where the type is used");
            }
        }
        validateStmts(func.body, func.name, errors);
    }
};

}  // namespace

std::vector<std::string> AstValidator::validate(const Program& program) {
    std::vector<std::string> errors;
    Validation v{*this, errors};

    // Custom decorators declared via `deco Name { … }` are valid annotation names.
    customDecos = {};
    for (const TopLevel& item : program.items) {
        if (TopLevelDeco* deco = as<TopLevelDeco>(item)) customDecos.add(deco->name);
    }

    for (const TopLevel& item : program.items) {
        // Top-level runtime var/let are not thread-safe - only fin allowed.
        if (TopLevelVarDecl* var = as<TopLevelVarDecl>(item)) {
            if (!var->threadlocal) {
                std::string keyword = var->valueMutable ? "var" : "val";
                errors.push_back("line " + str(var->line) + ": top-level '" + keyword +
                                 "' is not allowed (not thread-safe). Use 'fin' for immutable globals or 'inline " +
                                 keyword + "' for compile-time variables");
            }
        } else if (TopLevelLetDecl* let = as<TopLevelLetDecl>(item)) {
            errors.push_back("line " + str(let->line) +
                             ": top-level 'let' is not allowed (not thread-safe). Use 'fin' for immutable globals or "
                             "'inline let' for compile-time variables");
        }
        // Each declaration answers for its own stability decorators.
        std::vector<const std::vector<Annotation>*> declarations;
        auto withFields = [&](const std::vector<Annotation>& own, const std::vector<PackField>& fields) {
            declarations.push_back(&own);
            for (const PackField& f : fields) declarations.push_back(&f.annotations);
        };
        switch (item.kind()) {
            case TopLevelKind::Pack: withFields(cast<TopLevelPack>(item).annotations, cast<TopLevelPack>(item).fields); break;
            case TopLevelKind::Deco: withFields(cast<TopLevelDeco>(item).annotations, cast<TopLevelDeco>(item).fields); break;
            case TopLevelKind::Solo: withFields(cast<TopLevelSolo>(item).annotations, cast<TopLevelSolo>(item).fields); break;
            case TopLevelKind::Slot: declarations.push_back(&cast<TopLevelSlot>(item).annotations); break;
            case TopLevelKind::Test: declarations.push_back(&cast<TopLevelTest>(item).annotations); break;
            case TopLevelKind::Bridge: declarations.push_back(&cast<TopLevelBridge>(item).annotations); break;
            case TopLevelKind::TypeAlias: declarations.push_back(&cast<TopLevelTypeAlias>(item).annotations); break;
            case TopLevelKind::VarDecl: declarations.push_back(&cast<TopLevelVarDecl>(item).annotations); break;
            case TopLevelKind::FinDecl: declarations.push_back(&cast<TopLevelFinDecl>(item).annotations); break;
            case TopLevelKind::LetDecl: declarations.push_back(&cast<TopLevelLetDecl>(item).annotations); break;
            case TopLevelKind::Enum: {
                const TopLevelEnum& e = cast<TopLevelEnum>(item);
                declarations.push_back(&e.annotations);
                for (const std::vector<Annotation>& a : e.variantAnnotations) declarations.push_back(&a);
                break;
            }
            case TopLevelKind::Fail: {
                const TopLevelFail& f = cast<TopLevelFail>(item);
                declarations.push_back(&f.annotations);
                for (const std::vector<Annotation>& a : f.variantAnnotations) declarations.push_back(&a);
                break;
            }
            case TopLevelKind::Spec: {
                const TopLevelSpec& s = cast<TopLevelSpec>(item);
                declarations.push_back(&s.annotations);
                for (const FuncDecl& m : s.methods) declarations.push_back(&m.annotations);
                break;
            }
            case TopLevelKind::Impl: declarations.push_back(&cast<TopLevelImpl>(item).annotations); break;
            default: break;
        }
        for (const std::vector<Annotation>* annotations : declarations) v.validateStability(*annotations);
    }

    // Test declarations.
    OrderedSet<std::string> testNames;
    for (const TopLevel& item : program.items) {
        TopLevelTest* test = as<TopLevelTest>(item);
        if (test == nullptr) continue;
        if (!testNames.add(test->name)) errors.push_back("line " + str(test->line) + ": duplicate test '" + test->name + "'");
        validateStmts(test->body, "test \"" + test->name + "\"", errors);
    }

    // Duplicate function names.
    OrderedSet<std::string> seen;
    for (const TopLevel& item : program.items) {
        TopLevelFunc* func = as<TopLevelFunc>(item);
        if (func == nullptr) continue;
        if (seen.add(func->decl.name)) continue;
        // A type's `[…]` literal calls one constructor; the lifted name
        // `Type__ctor.brackets` is an implementation detail.
        std::string owner = removeSuffix(func->decl.name, std::string("__") + BRACKET_CONSTRUCTOR);
        if (owner != func->decl.name) {
            errors.push_back("line " + str(func->decl.line) + ": type '" + owner +
                             "' declares more than one variadic constructor a '[…]' literal could call");
        } else {
            errors.push_back("line " + str(func->decl.line) + ": duplicate function '" + func->decl.name + "'");
        }
    }

    // Every function: program functions, then impl methods (except bridge and
    // `@UncheckedCast` impls), then solo methods.
    std::vector<const FuncDecl*> allFunctions;
    for (const TopLevel& item : program.items) {
        if (TopLevelFunc* func = as<TopLevelFunc>(item)) allFunctions.push_back(&func->decl);
    }
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        if (impl == nullptr) continue;
        bool suppress = impl->isBridge || findAnnotation(impl->annotations, "UncheckedCast") != nullptr;
        if (!suppress) {
            for (const FuncDecl& m : impl->methods) allFunctions.push_back(&m);
        }
    }
    for (const TopLevel& item : program.items) {
        if (TopLevelSolo* solo = as<TopLevelSolo>(item)) {
            for (const FuncDecl& m : solo->methods) allFunctions.push_back(&m);
        }
    }
    for (const FuncDecl* func : allFunctions) v.validateFunction(*func);
    return errors;
}

}  // namespace azora
