// Port of semantic/CtfeEvaluator.kt, first half: the entry points, top-level
// inline items, and statement folding (inline if/for/fin, inline and
// deepinline blocks, inline function bodies spliced at a call).
#include "semantic/ctfe_evaluator.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"
#include "frontend/local_names.h"

namespace azora {

namespace {

template <class K, class H, class F>
K* edit(Arena& arena, const H& h, F&& f) {
    K c = cast<K>(h);
    f(c);
    return make(arena, std::move(c));
}

template <class T>
void append(std::vector<T>& out, const std::vector<T>& more) {
    out.insert(out.end(), more.begin(), more.end());
}

template <class K, class V>
void putAll(OrderedMap<K, V>& into, const OrderedMap<K, V>& from) {
    for (const auto& [key, value] : from) into.set(key, value);
}

}  // namespace

const FuncDecl* CtfeEvaluator::functionNamed(const Program& program, const std::string& name, bool inlineOnly /*= false*/) {
    if (indexedItems != &program.items) {
        functionsByName.clear();
        inlineFunctionsByName.clear();
        for (const TopLevel& item : program.items) {
            const TopLevelFunc* func = as<TopLevelFunc>(item);
            if (!func) continue;
            const FuncDecl& decl = func->decl;
            functionsByName.emplace(decl.name, &decl);
            if (decl.isInline) inlineFunctionsByName.emplace(decl.name, &decl);
        }
        indexedItems = &program.items;
    }
    const auto& index = inlineOnly ? inlineFunctionsByName : functionsByName;
    auto found = index.find(name);
    return found == index.end() ? nullptr : found->second;
}

CtfeEvaluator::Exprs CtfeEvaluator::foldCompileTimeExpr(const Expr& expr, const Program& program) {
    compileTimeDepth++;
    Finally restore{[&] { compileTimeDepth--; }};
    return foldExpr(expr, program);
}

CtfeEvaluator::CtfeResult CtfeEvaluator::evaluateTopLevel(const Program& program) {
    inlineEnv.clear();
    std::vector<std::string> errors;
    activeErrors = &errors;
    // Kotlin keys the function index by the items list's identity; C++ by its address, so a new pass starts fresh.
    indexedItems = nullptr;
    Items resolved = resolveTopLevelItems(program.items, program, errors);
    topLevelConstants = inlineEnv;
    topLevelConstantTypes = inlineTypes;
    activeErrors = nullptr;
    CtfeResult result;
    result.program = program;
    result.program.items = std::move(resolved.first);
    result.changed = resolved.second;
    result.errors = std::move(errors);
    return result;
}

CtfeEvaluator::CtfeResult CtfeEvaluator::evaluate(const Program& program) {
    bool changed = false;
    std::vector<std::string> errors;
    activeErrors = &errors;
    indexedItems = nullptr;

    // Phase A: Resolve top-level inline items
    inlineEnv.clear();
    putAll(inlineTypes, seedConstantTypes);
    Items resolved = resolveTopLevelItems(program.items, program, errors);
    if (resolved.second) changed = true;
    // Phase A consumed the top-level `inline` items; carry their values into Phase B.
    Env topLevelEnv = inlineEnv;

    auto foldParams = [&](const FuncDecl& decl) {
        std::vector<Param> params;
        for (const Param& param : decl.params) {
            if (!param.defaultValue) {
                params.push_back(param);
                continue;
            }
            Exprs folded = foldCompileTimeExpr(param.defaultValue, program);
            if (folded.second) changed = true;
            Param copy = param;
            copy.defaultValue = folded.first;
            params.push_back(std::move(copy));
        }
        return params;
    };
    auto hasDefaults = [](const FuncDecl& decl) {
        for (const Param& p : decl.params) {
            if (p.defaultValue) return true;
        }
        return false;
    };
    auto shadowParams = [&](const FuncDecl& decl, const std::vector<Stmt>& body) {
        std::vector<std::string> names;
        for (const Param& p : decl.params) names.push_back(p.name);
        names.push_back(decl.receiverName);
        shadowConstants(names, body);
    };

    // Phase B: Resolve inline constructs inside function bodies
    auto foldDecl = [&](const FuncDecl& decl) -> FuncDecl {
        // A precompiled member with no defaults has nothing left to fold here.
        if (decl.precompiled && !hasDefaults(decl)) return decl;
        inlineEnv.clear();
        putAll(inlineEnv, seedConstants);
        putAll(inlineEnv, topLevelEnv);
        putAll(inlineTypes, seedConstantTypes);
        reflectionTypes.clear();
        for (const Param& p : decl.params) reflectionTypes[p.name] = displayName(p.type);
        erasedTypeParams = decl.isInline ? std::unordered_set<std::string>{}
                                         : std::unordered_set<std::string>(decl.typeParams.begin(), decl.typeParams.end());
        std::vector<Param> newParams = foldParams(decl);
        FuncDecl copy = decl;
        copy.params = newParams;
        // A precompiled member's body was folded when its package was compiled.
        if (decl.precompiled) return copy;
        shadowParams(decl, decl.body);
        Stmts body = foldBody(decl.body, program, errors);
        if (body.second) changed = true;
        copy.body = std::move(body.first);
        return copy;
    };

    std::vector<TopLevel> newItems;
    newItems.reserve(resolved.first.size());
    for (const TopLevel& item : resolved.first) {
        // A member is code like any other: `inline if` inside an impl method folds here.
        if (is<TopLevelImpl>(item)) {
            newItems.push_back(edit<TopLevelImpl>(arena, item, [&](TopLevelImpl& c) {
                std::vector<FuncDecl> methods;
                for (const FuncDecl& m : c.methods) methods.push_back(foldDecl(m));
                c.methods = std::move(methods);
            }));
        } else if (is<TopLevelSolo>(item)) {
            newItems.push_back(edit<TopLevelSolo>(arena, item, [&](TopLevelSolo& c) {
                std::vector<FuncDecl> methods;
                for (const FuncDecl& m : c.methods) methods.push_back(foldDecl(m));
                c.methods = std::move(methods);
            }));
        } else if (const TopLevelFunc* func = as<TopLevelFunc>(item)) {
            const FuncDecl& decl = func->decl;
            if (decl.precompiled && !hasDefaults(decl)) {
                // Precompiled, and no defaults: nothing here to fold.
                newItems.push_back(item);
                continue;
            }
            inlineEnv.clear();
            // Seed top-level compile-time constants so they resolve inside function bodies too.
            putAll(inlineEnv, seedConstants);
            putAll(inlineEnv, topLevelEnv);
            reflectionTypes.clear();
            for (const Param& p : decl.params) reflectionTypes[p.name] = displayName(p.type);
            erasedTypeParams = decl.isInline ? std::unordered_set<std::string>{}
                                             : std::unordered_set<std::string>(decl.typeParams.begin(), decl.typeParams.end());
            // A parameter default is code the caller runs, so it is folded like a body.
            std::vector<Param> newParams = foldParams(decl);
            FuncDecl copy = decl;
            copy.params = newParams;
            if (!decl.precompiled) {
                shadowParams(decl, decl.body);
                Stmts body = foldBody(decl.body, program, errors);
                if (body.second) changed = true;
                copy.body = std::move(body.first);
            }
            newItems.push_back(make(arena, TopLevelFunc{copy}));
        } else if (const TopLevelTest* test = as<TopLevelTest>(item)) {
            // Test bodies may contain compile-time constructs; fold them too.
            inlineEnv.clear();
            putAll(inlineEnv, seedConstants);
            putAll(inlineEnv, topLevelEnv);
            reflectionTypes.clear();
            erasedTypeParams.clear();
            shadowConstants({}, test->body);
            Stmts body = foldBody(test->body, program, errors);
            if (body.second) changed = true;
            newItems.push_back(edit<TopLevelTest>(arena, item, [&](TopLevelTest& c) { c.body = std::move(body.first); }));
        } else {
            newItems.push_back(item);
        }
    }

    activeErrors = nullptr;
    CtfeResult result;
    result.program = program;
    result.program.items = std::move(newItems);
    result.changed = changed;
    result.errors = std::move(errors);
    return result;
}

void CtfeEvaluator::shadowConstants(const std::vector<std::string>& params, const std::vector<Stmt>& body) {
    for (const std::string& name : params) inlineEnv.remove(name);
    for (const std::string& name : localNamesDeclaredIn(body)) inlineEnv.remove(name);
}

// -- Top-level inline resolution ----------------------------------------------

CtfeEvaluator::Items CtfeEvaluator::resolveTopLevelItems(const std::vector<TopLevel>& items, const Program& program,
                                                         std::vector<std::string>& errors) {
    bool changed = false;
    std::vector<TopLevel> result;
    for (const TopLevel& item : items) {
        Items resolved = resolveTopLevelItem(item, program, errors);
        if (resolved.second) changed = true;
        append(result, resolved.first);
    }
    return {result, changed};
}

CtfeEvaluator::Items CtfeEvaluator::resolveTopLevelItem(const TopLevel& item, const Program& program,
                                                        std::vector<std::string>& errors) {
    auto global = [&](auto* node, auto kindTag) -> Items {
        using K = std::remove_pointer_t<decltype(kindTag)>;
        // A global's initializer is code, and the only place it can be folded.
        Exprs folded = foldCompileTimeExpr(node->initializer, program);
        if (!folded.second) return {{item}, false};
        return {{edit<K>(arena, item, [&](K& c) { c.initializer = folded.first; })}, true};
    };
    auto binding = [&](const std::string& name, const Expr& initializer, const TypeRef* type) -> Items {
        Exprs folded = foldCompileTimeExpr(initializer, program);
        if (!isConstant(folded.first)) return {{item}, false};
        inlineEnv.set(name, folded.first);
        if (type && *type) inlineTypes.set(name, *type);
        return {{}, true};
    };
    switch (item.kind()) {
        case TopLevelKind::VarDecl: return global(as<TopLevelVarDecl>(item), (TopLevelVarDecl*)nullptr);
        case TopLevelKind::FinDecl: return global(as<TopLevelFinDecl>(item), (TopLevelFinDecl*)nullptr);
        case TopLevelKind::LetDecl: return global(as<TopLevelLetDecl>(item), (TopLevelLetDecl*)nullptr);
        case TopLevelKind::InlineVar: {
            const TopLevelInlineVar& v = cast<TopLevelInlineVar>(item);
            return binding(v.name, v.initializer, nullptr);
        }
        case TopLevelKind::InlineFin: {
            const TopLevelInlineFin& v = cast<TopLevelInlineFin>(item);
            return binding(v.name, v.initializer, &v.type);
        }
        case TopLevelKind::InlineLet: {
            const TopLevelInlineLet& v = cast<TopLevelInlineLet>(item);
            return binding(v.name, v.initializer, nullptr);
        }
        case TopLevelKind::InlineAssignment: {
            const TopLevelInlineAssignment& a = cast<TopLevelInlineAssignment>(item);
            if (!inlineEnv.contains(a.name)) {
                errors.push_back("line " + str(a.line) + ": inline assignment to undefined inline variable '" + a.name + "'");
                return {{}, false};
            }
            Exprs folded = foldCompileTimeExpr(a.value, program);
            if (!isConstant(folded.first)) return {{item}, false};
            inlineEnv.set(a.name, folded.first);
            return {{}, true};
        }
        case TopLevelKind::InlineIf: {
            const TopLevelInlineIf& s = cast<TopLevelInlineIf>(item);
            Exprs cond = foldCompileTimeExpr(s.condition, program);
            const ExprBoolLiteral* b = as<ExprBoolLiteral>(cond.first);
            if (!b) return {{item}, false};
            std::vector<TopLevel> branch = b->value ? s.thenBranch : s.elseBranch.value_or(std::vector<TopLevel>{});
            return {resolveTopLevelItems(branch, program, errors).first, true};
        }
        case TopLevelKind::InlineBlock:
            return {resolveTopLevelInlineBlock(cast<TopLevelInlineBlock>(item).body, program, errors).first, true};
        case TopLevelKind::DeepInlineBlock:
            return {resolveTopLevelDeepInlineBlock(cast<TopLevelDeepInlineBlock>(item).body, program, errors).first, true};
        case TopLevelKind::DeepInlineIf: {
            const TopLevelDeepInlineIf& s = cast<TopLevelDeepInlineIf>(item);
            Exprs cond = foldCompileTimeExpr(s.condition, program);
            const ExprBoolLiteral* b = as<ExprBoolLiteral>(cond.first);
            if (!b) return {{item}, false};
            std::vector<TopLevel> branch = b->value ? s.thenBranch : s.elseBranch.value_or(std::vector<TopLevel>{});
            return {resolveTopLevelDeepInlineBlock(branch, program, errors).first, true};
        }
        case TopLevelKind::InlineAssert: {
            const TopLevelInlineAssert& s = cast<TopLevelInlineAssert>(item);
            Exprs cond = foldCompileTimeExpr(s.condition, program);
            const ExprBoolLiteral* b = as<ExprBoolLiteral>(cond.first);
            if (!b) return {{item}, false};
            if (!b->value) {
                Exprs message = foldExpr(s.message, program);
                const ExprStringLiteral* text = as<ExprStringLiteral>(message.first);
                errors.push_back("line " + str(s.line) + ": " + (text ? text->value : std::string("inline assert failed")));
            }
            return {{}, true};
        }
        case TopLevelKind::InlineTrace: {
            const TopLevelInlineTrace& s = cast<TopLevelInlineTrace>(item);
            Exprs level = foldCompileTimeExpr(traceLevel(s.level, s.line), program);
            Exprs message = foldExpr(s.message, program);
            std::optional<std::string> levelName = traceLevelName(level.first);
            std::optional<std::string> messageText = constantText(message.first);
            if (levelName && messageText) {
                errors.push_back("warning: [" + uppercase(*levelName) + "] " + *messageText);
                return {{}, true};
            }
            return {{edit<TopLevelInlineTrace>(arena, item, [&](TopLevelInlineTrace& c) {
                         c.message = message.first;
                         c.level = level.first;
                     })},
                    level.second};
        }
        // Funcs, declarations, tests and (already expanded) macros pass through.
        default: return {{item}, false};
    }
}

CtfeEvaluator::Items CtfeEvaluator::resolveTopLevelInlineBlock(const std::vector<TopLevel>& body, const Program& program,
                                                               std::vector<std::string>& errors) {
    std::vector<TopLevel> result;
    for (const TopLevel& item : body) {
        if (is<TopLevelFunc>(item)) {
            result.push_back(item);
        } else {
            append(result, resolveTopLevelItem(item, program, errors).first);
        }
    }
    return {result, true};
}

CtfeEvaluator::Items CtfeEvaluator::resolveTopLevelDeepInlineBlock(const std::vector<TopLevel>& body, const Program& program,
                                                                   std::vector<std::string>& errors) {
    std::vector<TopLevel> result;
    for (const TopLevel& item : body) {
        switch (item.kind()) {
            case TopLevelKind::InlineVar:
            case TopLevelKind::InlineFin:
            case TopLevelKind::InlineLet:
            case TopLevelKind::InlineAssignment:
            case TopLevelKind::InlineBlock:
            case TopLevelKind::DeepInlineBlock:
            case TopLevelKind::DeepInlineIf:
            case TopLevelKind::InlineAssert:
            case TopLevelKind::InlineTrace: append(result, resolveTopLevelItem(item, program, errors).first); break;
            case TopLevelKind::InlineIf:
                for (const TopLevel& r : resolveTopLevelItem(item, program, errors).first) {
                    append(result, resolveTopLevelItem(r, program, errors).first);
                }
                break;
            // noinline funcs, runtime declarations, tests and macros pass through.
            default: result.push_back(item); break;
        }
    }
    return {result, true};
}

// -- Body-level folding ---------------------------------------------------------

CtfeEvaluator::Stmts CtfeEvaluator::foldBody(const std::vector<Stmt>& body, const Program& program, std::vector<std::string>& errors) {
    bool changed = false;
    std::vector<Stmt> result;
    for (const Stmt& stmt : body) {
        Stmts folded = foldStmt(stmt, program, errors);
        if (folded.second) changed = true;
        append(result, folded.first);
    }
    return {result, changed};
}

CtfeEvaluator::Stmts CtfeEvaluator::foldScopedBody(const std::vector<Stmt>& body, const Program& program,
                                                   std::vector<std::string>& errors) {
    Env savedInline = inlineEnv;
    std::unordered_map<std::string, std::string> savedReflectionTypes = reflectionTypes;
    Finally restore{[&] {
        inlineEnv = savedInline;
        reflectionTypes = savedReflectionTypes;
    }};
    return foldBody(body, program, errors);
}

CtfeEvaluator::Stmts CtfeEvaluator::foldStmt(const Stmt& stmt, const Program& program, std::vector<std::string>& errors) {
    auto declaration = [&](auto kindTag) -> Stmts {
        using K = std::remove_pointer_t<decltype(kindTag)>;
        const K& s = cast<K>(stmt);
        recordReflectionType(s.name, s.type, s.initializer);
        Exprs init = foldExpr(s.initializer, program);
        return {{edit<K>(arena, stmt, [&](K& c) { c.initializer = init.first; })}, init.second};
    };
    switch (stmt.kind()) {
        case StmtKind::Import: return {{stmt}, false};
        case StmtKind::DeepInlineIf: return foldDeepInlineIf(cast<StmtDeepInlineIf>(stmt), stmt, program, errors);
        case StmtKind::DeepInlineBlock: return foldDeepInlineBlock(cast<StmtDeepInlineBlock>(stmt), program, errors);
        case StmtKind::NoInline: return {{cast<StmtNoInline>(stmt).stmt}, false};
        case StmtKind::InlineBlock: return foldInlineBlock(cast<StmtInlineBlock>(stmt), program, errors);
        case StmtKind::InlineIf: return foldInlineIf(cast<StmtInlineIf>(stmt), stmt, program, errors);
        case StmtKind::InlineFor: return foldInlineFor(cast<StmtInlineFor>(stmt), stmt, program, errors);
        case StmtKind::InlineFin: return foldInlineConst(cast<StmtInlineFin>(stmt).name, cast<StmtInlineFin>(stmt).initializer, program, errors);
        case StmtKind::InlineLet: return foldInlineConst(cast<StmtInlineLet>(stmt).name, cast<StmtInlineLet>(stmt).initializer, program, errors);
        case StmtKind::InlineVar: return foldInlineConst(cast<StmtInlineVar>(stmt).name, cast<StmtInlineVar>(stmt).initializer, program, errors);
        case StmtKind::InlineAssignment: {
            const StmtInlineAssignment& a = cast<StmtInlineAssignment>(stmt);
            return foldInlineAssignment(a.name, a.value, a.line, program, errors);
        }
        case StmtKind::If: {
            const StmtIf& s = cast<StmtIf>(stmt);
            Exprs cond = foldExpr(s.condition, program);
            Stmts thenBranch = foldScopedBody(s.thenBranch, program, errors);
            std::optional<std::vector<Stmt>> elseBranch;
            bool elseChanged = false;
            if (s.elseBranch) {
                Stmts folded = foldScopedBody(*s.elseBranch, program, errors);
                elseBranch = std::move(folded.first);
                elseChanged = folded.second;
            }
            bool changed = cond.second || thenBranch.second || elseChanged;
            return {{edit<StmtIf>(arena, stmt, [&](StmtIf& c) {
                         c.condition = cond.first;
                         c.thenBranch = std::move(thenBranch.first);
                         c.elseBranch = std::move(elseBranch);
                     })},
                    changed};
        }
        case StmtKind::VarDecl: return declaration((StmtVarDecl*)nullptr);
        case StmtKind::FinDecl: return declaration((StmtFinDecl*)nullptr);
        case StmtKind::LetDecl: return declaration((StmtLetDecl*)nullptr);
        case StmtKind::Assignment: {
            Exprs value = foldExpr(cast<StmtAssignment>(stmt).value, program);
            return {{edit<StmtAssignment>(arena, stmt, [&](StmtAssignment& c) { c.value = value.first; })}, value.second};
        }
        case StmtKind::Return: {
            const StmtReturn& s = cast<StmtReturn>(stmt);
            if (!s.value) return {{stmt}, false};
            Exprs value = foldExpr(s.value, program);
            return {{edit<StmtReturn>(arena, stmt, [&](StmtReturn& c) { c.value = value.first; })}, value.second};
        }
        case StmtKind::ExprStmt: {
            const StmtExprStmt& s = cast<StmtExprStmt>(stmt);
            // Try inline function body substitution
            if (std::optional<std::vector<Stmt>> inlined = tryInlineFuncCall(s.expr, program)) return {*inlined, true};
            Exprs value = foldExpr(s.expr, program);
            return {{edit<StmtExprStmt>(arena, stmt, [&](StmtExprStmt& c) { c.expr = value.first; })}, value.second};
        }
        case StmtKind::Scope: {
            Stmts body = foldScopedBody(cast<StmtScope>(stmt).body, program, errors);
            return {{edit<StmtScope>(arena, stmt, [&](StmtScope& c) { c.body = std::move(body.first); })}, body.second};
        }
        case StmtKind::Assert: {
            const StmtAssert& s = cast<StmtAssert>(stmt);
            Exprs cond = foldExpr(s.condition, program);
            Exprs message = foldExpr(s.message, program);
            return {{edit<StmtAssert>(arena, stmt, [&](StmtAssert& c) {
                         c.condition = cond.first;
                         c.message = message.first;
                     })},
                    cond.second || message.second};
        }
        case StmtKind::Trace: {
            const StmtTrace& s = cast<StmtTrace>(stmt);
            Exprs level = foldExpr(traceLevel(s.level, s.line), program);
            Exprs message = foldExpr(s.message, program);
            return {{edit<StmtTrace>(arena, stmt, [&](StmtTrace& c) {
                         c.message = message.first;
                         c.level = level.first;
                     })},
                    message.second || level.second};
        }
        case StmtKind::InlineAssert: {
            const StmtInlineAssert& s = cast<StmtInlineAssert>(stmt);
            Exprs cond = foldCompileTimeExpr(s.condition, program);
            const ExprBoolLiteral* b = as<ExprBoolLiteral>(cond.first);
            if (!b) return {{stmt}, false};
            if (!b->value) {
                Exprs message = foldExpr(s.message, program);
                const ExprStringLiteral* text = as<ExprStringLiteral>(message.first);
                errors.push_back("line " + str(s.line) + ": " + (text ? text->value : std::string("inline assert failed")));
            }
            return {{}, true};
        }
        case StmtKind::InlineTrace: {
            const StmtInlineTrace& s = cast<StmtInlineTrace>(stmt);
            Exprs level = foldCompileTimeExpr(traceLevel(s.level, s.line), program);
            Exprs message = foldExpr(s.message, program);
            std::optional<std::string> levelName = traceLevelName(level.first);
            std::optional<std::string> messageText = constantText(message.first);
            if (levelName && messageText) {
                errors.push_back("warning: [" + uppercase(*levelName) + "] " + *messageText);
                return {{}, true};
            }
            return {{edit<StmtInlineTrace>(arena, stmt, [&](StmtInlineTrace& c) {
                         c.message = message.first;
                         c.level = level.first;
                     })},
                    level.second};
        }
        case StmtKind::While: {
            const StmtWhile& s = cast<StmtWhile>(stmt);
            Exprs cond = foldExpr(s.condition, program);
            Stmts body = foldScopedBody(s.body, program, errors);
            bool changed = cond.second || body.second;
            if (!changed) return {{stmt}, false};
            return {{edit<StmtWhile>(arena, stmt, [&](StmtWhile& c) {
                         c.condition = cond.first;
                         c.body = std::move(body.first);
                     })},
                    true};
        }
        case StmtKind::For: {
            const StmtFor& s = cast<StmtFor>(stmt);
            Exprs iterable = foldExpr(s.iterable, program);
            Exprs step = s.step ? foldExpr(s.step, program) : Exprs{Expr{}, false};
            Stmts body = foldScopedBody(s.body, program, errors);
            bool changed = iterable.second || step.second || body.second;
            if (!changed) return {{stmt}, false};
            return {{edit<StmtFor>(arena, stmt, [&](StmtFor& c) {
                         c.iterable = iterable.first;
                         c.step = step.first;
                         c.body = std::move(body.first);
                     })},
                    true};
        }
        case StmtKind::Loop: {
            Stmts body = foldScopedBody(cast<StmtLoop>(stmt).body, program, errors);
            if (!body.second) return {{stmt}, false};
            return {{edit<StmtLoop>(arena, stmt, [&](StmtLoop& c) { c.body = std::move(body.first); })}, true};
        }
        case StmtKind::Break:
        case StmtKind::Continue:
        // runtime storage, never fold a location into a value
        case StmtKind::Exchange: return {{stmt}, false};
        case StmtKind::IndexAssign: {
            const StmtIndexAssign& s = cast<StmtIndexAssign>(stmt);
            Exprs target = foldExpr(s.target, program);
            Exprs index = foldExpr(s.index, program);
            Exprs value = foldExpr(s.value, program);
            bool changed = target.second || index.second || value.second;
            if (!changed) return {{stmt}, false};
            return {{edit<StmtIndexAssign>(arena, stmt, [&](StmtIndexAssign& c) {
                         c.target = target.first;
                         c.index = index.first;
                         c.value = value.first;
                     })},
                    true};
        }
        case StmtKind::DerefAssign: {
            const StmtDerefAssign& s = cast<StmtDerefAssign>(stmt);
            Exprs target = foldExpr(s.target, program);
            Exprs value = foldExpr(s.value, program);
            if (!target.second && !value.second) return {{stmt}, false};
            return {{edit<StmtDerefAssign>(arena, stmt, [&](StmtDerefAssign& c) {
                         c.target = target.first;
                         c.value = value.first;
                     })},
                    true};
        }
        case StmtKind::MemberAssign: {
            const StmtMemberAssign& s = cast<StmtMemberAssign>(stmt);
            Exprs target = foldExpr(s.target, program);
            Exprs value = foldExpr(s.value, program);
            // `self.m$r$c = …` splices on the left as it does on the right.
            std::optional<std::string> spliced = spliceName(s.name);
            bool changed = target.second || value.second || spliced;
            if (!changed) return {{stmt}, false};
            return {{edit<StmtMemberAssign>(arena, stmt, [&](StmtMemberAssign& c) {
                         c.target = target.first;
                         c.value = value.first;
                         if (spliced) c.name = *spliced;
                     })},
                    true};
        }
        case StmtKind::When: {
            const StmtWhen& s = cast<StmtWhen>(stmt);
            bool changed = false;
            Exprs scrutinee = foldExpr(s.scrutinee, program);
            if (scrutinee.second) changed = true;
            std::vector<WhenBranch> branches;
            for (const WhenBranch& b : s.branches) {
                std::vector<Expr> patterns;
                for (const Expr& p : b.patterns) {
                    Exprs folded = foldExpr(p, program);
                    if (folded.second) changed = true;
                    patterns.push_back(folded.first);
                }
                Stmts body = foldScopedBody(b.body, program, errors);
                if (body.second) changed = true;
                WhenBranch branch;
                branch.patterns = std::move(patterns);
                branch.body = std::move(body.first);
                branch.line = b.line;
                branch.column = b.column;
                branches.push_back(std::move(branch));
            }
            std::optional<std::vector<Stmt>> elseBranch;
            if (s.elseBranch) {
                Stmts folded = foldScopedBody(*s.elseBranch, program, errors);
                if (folded.second) changed = true;
                elseBranch = std::move(folded.first);
            }
            if (!changed) return {{stmt}, false};
            StmtWhen when;
            when.scrutinee = scrutinee.first;
            when.branches = std::move(branches);
            when.elseBranch = std::move(elseBranch);
            when.line = s.line;
            when.column = s.column;
            when.length = s.length;
            return {{make(arena, std::move(when))}, true};
        }
        case StmtKind::Throw: {
            const StmtThrow& s = cast<StmtThrow>(stmt);
            Exprs value = foldExpr(s.value, program);
            if (!value.second) return {{stmt}, false};
            return {{make(arena, StmtThrow{value.first, s.line, s.column, s.length})}, true};
        }
        case StmtKind::Panic: {
            const StmtPanic& s = cast<StmtPanic>(stmt);
            Exprs message = foldExpr(s.message, program);
            // `inline panic` is a compile-time abort: if reached during CTCE, stop the compiler.
            if (s.inlinePanic) {
                const ExprStringLiteral* text = as<ExprStringLiteral>(message.first);
                fail("inline panic: " + (text ? text->value : kotlinToString(message.first)));
            }
            if (!message.second) return {{stmt}, false};
            return {{make(arena, StmtPanic{message.first, false, s.line, s.column, s.length})}, true};
        }
        case StmtKind::Yield: {
            const StmtYield& s = cast<StmtYield>(stmt);
            Exprs value = foldExpr(s.value, program);
            if (!value.second) return {{stmt}, false};
            return {{make(arena, StmtYield{value.first, s.line, s.column, s.length})}, true};
        }
        case StmtKind::RemDecl: {
            Exprs init = foldExpr(cast<StmtRemDecl>(stmt).initializer, program);
            return {{edit<StmtRemDecl>(arena, stmt, [&](StmtRemDecl& c) { c.initializer = init.first; })}, init.second};
        }
        case StmtKind::Effect: {
            const StmtEffect& s = cast<StmtEffect>(stmt);
            Stmts body = foldScopedBody(s.body, program, errors);
            bool dependencyChanged = false;
            std::optional<std::vector<Expr>> dependencies;
            if (s.dependencies) {
                std::vector<Expr> values;
                for (const Expr& d : *s.dependencies) {
                    Exprs folded = foldExpr(d, program);
                    dependencyChanged = dependencyChanged || folded.second;
                    values.push_back(folded.first);
                }
                dependencies = std::move(values);
            }
            return {{edit<StmtEffect>(arena, stmt, [&](StmtEffect& c) {
                         c.body = std::move(body.first);
                         c.dependencies = std::move(dependencies);
                     })},
                    body.second || dependencyChanged};
        }
        case StmtKind::UsingContext: {
            const StmtUsingContext& s = cast<StmtUsingContext>(stmt);
            bool valuesChanged = false;
            std::vector<Expr> values;
            for (const Expr& v : s.values) {
                Exprs folded = foldExpr(v, program);
                valuesChanged = valuesChanged || folded.second;
                values.push_back(folded.first);
            }
            Stmts body = foldScopedBody(s.body, program, errors);
            return {{edit<StmtUsingContext>(arena, stmt, [&](StmtUsingContext& c) {
                         c.values = std::move(values);
                         c.body = std::move(body.first);
                     })},
                    valuesChanged || body.second};
        }
        case StmtKind::Try: {
            const StmtTry& s = cast<StmtTry>(stmt);
            bool changed = false;
            Stmts body = foldScopedBody(s.body, program, errors);
            if (body.second) changed = true;
            std::optional<std::vector<Stmt>> catchBody;
            if (s.catchBody) {
                Stmts folded = foldScopedBody(*s.catchBody, program, errors);
                if (folded.second) changed = true;
                catchBody = std::move(folded.first);
            }
            if (!changed) return {{stmt}, false};
            return {{make(arena, StmtTry{std::move(body.first), s.catchName, std::move(catchBody), s.line, s.column, s.length})}, true};
        }
        case StmtKind::Defer: {
            const StmtDefer& s = cast<StmtDefer>(stmt);
            Stmts body = foldScopedBody(s.body, program, errors);
            if (!body.second) return {{stmt}, false};
            return {{make(arena, StmtDefer{std::move(body.first), s.line, s.column, s.length})}, true};
        }
        default: AZORA_UNREACHABLE();
    }
}

// -- deepinline / inline blocks -------------------------------------------------

CtfeEvaluator::Stmts CtfeEvaluator::foldDeepInlineBlock(const StmtDeepInlineBlock& stmt, const Program& program,
                                                        std::vector<std::string>& errors) {
    std::vector<Stmt> result;
    for (const Stmt& inner : stmt.body) append(result, foldDeepInlineStmt(inner, program, errors).first);
    return {result, true};
}

CtfeEvaluator::Stmts CtfeEvaluator::foldDeepInlineStmt(const Stmt& stmt, const Program& program, std::vector<std::string>& errors) {
    switch (stmt.kind()) {
        // noinline cancels the inline - pass through with substitutions only
        case StmtKind::NoInline: return foldStmt(cast<StmtNoInline>(stmt).stmt, program, errors);
        // var/fin/let -> compile-time binding
        case StmtKind::VarDecl: return foldInlineConst(cast<StmtVarDecl>(stmt).name, cast<StmtVarDecl>(stmt).initializer, program, errors);
        case StmtKind::FinDecl: return foldInlineConst(cast<StmtFinDecl>(stmt).name, cast<StmtFinDecl>(stmt).initializer, program, errors);
        case StmtKind::LetDecl: return foldInlineConst(cast<StmtLetDecl>(stmt).name, cast<StmtLetDecl>(stmt).initializer, program, errors);
        // assignment -> compile-time reassignment
        case StmtKind::Assignment: {
            const StmtAssignment& a = cast<StmtAssignment>(stmt);
            return foldInlineAssignment(a.name, a.value, a.line, program, errors);
        }
        // if -> evaluate condition, then deep-inline the taken branch
        case StmtKind::If: {
            const StmtIf& s = cast<StmtIf>(stmt);
            Exprs cond = foldExpr(s.condition, program);
            const ExprBoolLiteral* b = as<ExprBoolLiteral>(cond.first);
            if (!b) return {{edit<StmtIf>(arena, stmt, [&](StmtIf& c) { c.condition = cond.first; })}, false};
            const std::vector<Stmt>* branch = b->value ? &s.thenBranch : (s.elseBranch ? &*s.elseBranch : nullptr);
            std::vector<Stmt> result;
            if (branch) {
                for (const Stmt& inner : *branch) append(result, foldDeepInlineStmt(inner, program, errors).first);
            }
            return {result, true};
        }
        case StmtKind::DeepInlineIf: return foldDeepInlineIf(cast<StmtDeepInlineIf>(stmt), stmt, program, errors);
        case StmtKind::InlineIf: return foldInlineIf(cast<StmtInlineIf>(stmt), stmt, program, errors);
        case StmtKind::InlineFin: return foldInlineConst(cast<StmtInlineFin>(stmt).name, cast<StmtInlineFin>(stmt).initializer, program, errors);
        case StmtKind::InlineLet: return foldInlineConst(cast<StmtInlineLet>(stmt).name, cast<StmtInlineLet>(stmt).initializer, program, errors);
        case StmtKind::InlineVar: return foldInlineConst(cast<StmtInlineVar>(stmt).name, cast<StmtInlineVar>(stmt).initializer, program, errors);
        case StmtKind::InlineAssignment: {
            const StmtInlineAssignment& a = cast<StmtInlineAssignment>(stmt);
            return foldInlineAssignment(a.name, a.value, a.line, program, errors);
        }
        case StmtKind::InlineBlock: return foldInlineBlock(cast<StmtInlineBlock>(stmt), program, errors);
        case StmtKind::DeepInlineBlock: return foldDeepInlineBlock(cast<StmtDeepInlineBlock>(stmt), program, errors);
        // Assert/Trace in deepinline -> treated as inline assert/trace
        case StmtKind::Assert: return foldStmt(asInlineAssert(cast<StmtAssert>(stmt)), program, errors);
        case StmtKind::Trace: return foldStmt(asInlineTrace(cast<StmtTrace>(stmt)), program, errors);
        // Everything else -> runtime with substitutions applied
        default: return foldStmt(stmt, program, errors);
    }
}

Stmt CtfeEvaluator::asInlineAssert(const StmtAssert& s) {
    return make(arena, StmtInlineAssert{s.condition, s.message, s.line, s.column, s.length});
}

Stmt CtfeEvaluator::asInlineTrace(const StmtTrace& s) {
    return make(arena, StmtInlineTrace{s.message, s.line, s.column, s.length, s.level});
}

CtfeEvaluator::Stmts CtfeEvaluator::foldInlineBlock(const StmtInlineBlock& stmt, const Program& program, std::vector<std::string>& errors) {
    std::vector<Stmt> result;
    for (const Stmt& inner : stmt.body) append(result, foldInlineStmt(inner, program, errors).first);
    // always changed - the block itself is removed
    return {result, true};
}

CtfeEvaluator::Stmts CtfeEvaluator::foldInlineStmt(const Stmt& stmt, const Program& program, std::vector<std::string>& errors) {
    switch (stmt.kind()) {
        // var/fin/let inside inline block -> compile-time binding
        case StmtKind::VarDecl: return foldInlineConst(cast<StmtVarDecl>(stmt).name, cast<StmtVarDecl>(stmt).initializer, program, errors);
        case StmtKind::FinDecl: return foldInlineConst(cast<StmtFinDecl>(stmt).name, cast<StmtFinDecl>(stmt).initializer, program, errors);
        case StmtKind::LetDecl: return foldInlineConst(cast<StmtLetDecl>(stmt).name, cast<StmtLetDecl>(stmt).initializer, program, errors);
        // if inside inline block -> compile-time conditional
        case StmtKind::If: {
            const StmtIf& s = cast<StmtIf>(stmt);
            Stmt inlineIf = make(arena, StmtInlineIf{s.condition, s.thenBranch, s.elseBranch, s.line, s.column, s.length});
            return foldInlineIf(cast<StmtInlineIf>(inlineIf), inlineIf, program, errors);
        }
        // assignment inside inline block -> compile-time reassignment
        case StmtKind::Assignment: {
            const StmtAssignment& a = cast<StmtAssignment>(stmt);
            return foldInlineAssignment(a.name, a.value, a.line, program, errors);
        }
        case StmtKind::InlineIf: return foldInlineIf(cast<StmtInlineIf>(stmt), stmt, program, errors);
        case StmtKind::InlineFin: return foldInlineConst(cast<StmtInlineFin>(stmt).name, cast<StmtInlineFin>(stmt).initializer, program, errors);
        case StmtKind::InlineLet: return foldInlineConst(cast<StmtInlineLet>(stmt).name, cast<StmtInlineLet>(stmt).initializer, program, errors);
        case StmtKind::InlineVar: return foldInlineConst(cast<StmtInlineVar>(stmt).name, cast<StmtInlineVar>(stmt).initializer, program, errors);
        case StmtKind::InlineAssignment: {
            const StmtInlineAssignment& a = cast<StmtInlineAssignment>(stmt);
            return foldInlineAssignment(a.name, a.value, a.line, program, errors);
        }
        case StmtKind::InlineBlock: return foldInlineBlock(cast<StmtInlineBlock>(stmt), program, errors);
        // Assert/Trace inside inline block -> treated as inline assert/trace
        case StmtKind::Assert: return foldStmt(asInlineAssert(cast<StmtAssert>(stmt)), program, errors);
        case StmtKind::Trace: return foldStmt(asInlineTrace(cast<StmtTrace>(stmt)), program, errors);
        // Everything else passes through as runtime code with inline substitutions applied
        default: return foldStmt(stmt, program, errors);
    }
}

CtfeEvaluator::Stmts CtfeEvaluator::foldInlineConst(const std::string& name, const Expr& initializer, const Program& program,
                                                    std::vector<std::string>& /*errors*/) {
    Exprs folded = foldCompileTimeExpr(initializer, program);
    if (!isConstant(folded.first)) return {{}, false};
    inlineEnv.set(name, folded.first);
    return {{}, true};
}

CtfeEvaluator::Stmts CtfeEvaluator::foldInlineAssignment(const std::string& name, const Expr& value, int32_t line,
                                                         const Program& program, std::vector<std::string>& errors) {
    if (!inlineEnv.contains(name)) {
        errors.push_back("line " + str(line) + ": inline assignment to undefined inline variable '" + name + "'");
        return {{}, false};
    }
    Exprs folded = foldCompileTimeExpr(value, program);
    if (!isConstant(folded.first)) {
        errors.push_back("line " + str(line) + ": inline assignment value must be a compile-time constant");
        return {{}, false};
    }
    inlineEnv.set(name, folded.first);
    return {{}, true};
}

// -- inline function body substitution -----------------------------------------

CtfeEvaluator::Env CtfeEvaluator::constTypeArguments(const FuncDecl& decl, const std::vector<TypeRef>& typeArgs) {
    Env out;
    for (size_t index = 0; index < decl.typeParams.size(); index++) {
        const std::string& parameter = decl.typeParams[index];
        if (!decl.constParams.contains(parameter)) continue;
        if (index >= typeArgs.size()) continue;
        const TypeRefConst* value = as<TypeRefConst>(typeArgs[index]);
        if (!value) continue;
        out.set(parameter, make(arena, ExprIntLiteral{value->value, decl.line, decl.column}));
    }
    return out;
}

std::optional<std::vector<Stmt>> CtfeEvaluator::tryInlineFuncCall(const Expr& expr, const Program& program) {
    const ExprCall* call = as<ExprCall>(expr);
    if (!call) return std::nullopt;
    const FuncDecl* funcDecl = functionNamed(program, call->callee, true);
    if (!funcDecl) return std::nullopt;
    // A discarded result is still the callee's result.
    if (funcDecl->body.size() == 1 && is<StmtReturn>(funcDecl->body[0])) {
        Expr expanded = inlineCallAsExpression(*call, call->args, program);
        if (!expanded) return std::nullopt;
        return std::vector<Stmt>{make(arena, StmtExprStmt{expanded, call->line, call->column})};
    }
    // Substitute parameters with arguments in the function body
    Env paramMap = constTypeArguments(*funcDecl, call->typeArgs);
    for (size_t i = 0; i < funcDecl->params.size(); i++) {
        Expr arg = i < call->args.size() ? call->args[i] : funcDecl->params[i].defaultValue;
        if (!arg) return std::nullopt;
        paramMap.set(funcDecl->params[i].name, asDeclaredLiteral(arg, funcDecl->params[i].type));
    }
    std::vector<Stmt> substituted;
    for (const Stmt& s : funcDecl->body) substituted.push_back(substituteInStmt(s, paramMap));
    // Wrap in a scope (scope block) to avoid variable name collisions
    StmtScope scope;
    scope.body = std::move(substituted);
    scope.line = call->line;
    return std::vector<Stmt>{make(arena, std::move(scope))};
}

Stmt CtfeEvaluator::substituteInStmt(const Stmt& stmt, const Env& paramMap) {
    auto sub = [&](const Expr& e) { return substituteInExpr(e, paramMap); };
    switch (stmt.kind()) {
        case StmtKind::Exchange:
            return edit<StmtExchange>(arena, stmt, [&](StmtExchange& c) {
                c.left = sub(c.left);
                c.right = sub(c.right);
            });
        case StmtKind::VarDecl: return edit<StmtVarDecl>(arena, stmt, [&](StmtVarDecl& c) { c.initializer = sub(c.initializer); });
        case StmtKind::FinDecl: return edit<StmtFinDecl>(arena, stmt, [&](StmtFinDecl& c) { c.initializer = sub(c.initializer); });
        case StmtKind::LetDecl: return edit<StmtLetDecl>(arena, stmt, [&](StmtLetDecl& c) { c.initializer = sub(c.initializer); });
        case StmtKind::Assignment: return edit<StmtAssignment>(arena, stmt, [&](StmtAssignment& c) { c.value = sub(c.value); });
        case StmtKind::Return: return edit<StmtReturn>(arena, stmt, [&](StmtReturn& c) { c.value = c.value ? sub(c.value) : c.value; });
        case StmtKind::ExprStmt: return edit<StmtExprStmt>(arena, stmt, [&](StmtExprStmt& c) { c.expr = sub(c.expr); });
        case StmtKind::If:
            return edit<StmtIf>(arena, stmt, [&](StmtIf& c) {
                c.condition = sub(c.condition);
                std::vector<Stmt> thenBranch;
                for (const Stmt& s : c.thenBranch) thenBranch.push_back(substituteInStmt(s, paramMap));
                c.thenBranch = std::move(thenBranch);
                if (c.elseBranch) {
                    std::vector<Stmt> elseBranch;
                    for (const Stmt& s : *c.elseBranch) elseBranch.push_back(substituteInStmt(s, paramMap));
                    c.elseBranch = std::move(elseBranch);
                }
            });
        case StmtKind::Assert:
            return edit<StmtAssert>(arena, stmt, [&](StmtAssert& c) {
                c.condition = sub(c.condition);
                c.message = sub(c.message);
            });
        case StmtKind::Trace:
            return edit<StmtTrace>(arena, stmt, [&](StmtTrace& c) {
                c.message = sub(c.message);
                c.level = c.level ? sub(c.level) : c.level;
            });
        case StmtKind::InlineTrace:
            return edit<StmtInlineTrace>(arena, stmt, [&](StmtInlineTrace& c) {
                c.message = sub(c.message);
                c.level = c.level ? sub(c.level) : c.level;
            });
        // inline constructs shouldn't appear in inline function bodies
        default: return stmt;
    }
}

Expr CtfeEvaluator::substituteInExpr(const Expr& expr, const Env& paramMap) {
    auto sub = [&](const Expr& e) { return substituteInExpr(e, paramMap); };
    auto subAll = [&](const std::vector<Expr>& values) {
        std::vector<Expr> out;
        out.reserve(values.size());
        for (const Expr& v : values) out.push_back(sub(v));
        return out;
    };
    switch (expr.kind()) {
        case ExprKind::Identifier: {
            const Expr* value = paramMap.get(cast<ExprIdentifier>(expr).name);
            return value ? *value : expr;
        }
        case ExprKind::Binary:
            return edit<ExprBinary>(arena, expr, [&](auto& c) {
                c.left = sub(c.left);
                c.right = sub(c.right);
            });
        case ExprKind::Unary: return edit<ExprUnary>(arena, expr, [&](auto& c) { c.operand = sub(c.operand); });
        case ExprKind::IncDec: return edit<ExprIncDec>(arena, expr, [&](auto& c) { c.target = sub(c.target); });
        case ExprKind::Call: return edit<ExprCall>(arena, expr, [&](auto& c) { c.args = subAll(c.args); });
        case ExprKind::TryPropagate: return edit<ExprTryPropagate>(arena, expr, [&](auto& c) { c.expr = sub(c.expr); });
        case ExprKind::Grouping: return edit<ExprGrouping>(arena, expr, [&](auto& c) { c.expr = sub(c.expr); });
        case ExprKind::IfExpr:
            return edit<ExprIfExpr>(arena, expr, [&](auto& c) {
                c.condition = sub(c.condition);
                c.thenExpr = sub(c.thenExpr);
                c.elseExpr = sub(c.elseExpr);
            });
        case ExprKind::Member:
            return edit<ExprMember>(arena, expr, [&](auto& c) {
                c.target = sub(c.target);
                c.nameExpr = c.nameExpr ? sub(c.nameExpr) : c.nameExpr;
            });
        case ExprKind::SafeMember: return edit<ExprSafeMember>(arena, expr, [&](auto& c) { c.target = sub(c.target); });
        case ExprKind::MethodCall:
            return edit<ExprMethodCall>(arena, expr, [&](auto& c) {
                c.target = sub(c.target);
                c.args = subAll(c.args);
            });
        case ExprKind::Index:
            return edit<ExprIndex>(arena, expr, [&](auto& c) {
                c.target = sub(c.target);
                c.index = sub(c.index);
            });
        case ExprKind::Cast: return edit<ExprCast>(arena, expr, [&](auto& c) { c.expr = sub(c.expr); });
        case ExprKind::IsCheck: return edit<ExprIsCheck>(arena, expr, [&](auto& c) { c.expr = sub(c.expr); });
        case ExprKind::InCheck:
            return edit<ExprInCheck>(arena, expr, [&](auto& c) {
                c.value = sub(c.value);
                c.collection = sub(c.collection);
            });
        case ExprKind::Range:
            return edit<ExprRange>(arena, expr, [&](auto& c) {
                c.from = sub(c.from);
                c.to = sub(c.to);
            });
        case ExprKind::ArrayLiteral: return edit<ExprArrayLiteral>(arena, expr, [&](auto& c) { c.elements = subAll(c.elements); });
        case ExprKind::SetLiteral: return edit<ExprSetLiteral>(arena, expr, [&](auto& c) { c.elements = subAll(c.elements); });
        case ExprKind::TupleLit: return edit<ExprTupleLit>(arena, expr, [&](auto& c) { c.elements = subAll(c.elements); });
        case ExprKind::VariantLit: return edit<ExprVariantLit>(arena, expr, [&](auto& c) { c.elements = subAll(c.elements); });
        case ExprKind::TupleAccess: return edit<ExprTupleAccess>(arena, expr, [&](auto& c) { c.target = sub(c.target); });
        case ExprKind::MapLit:
            return edit<ExprMapLit>(arena, expr, [&](auto& c) {
                for (auto& entry : c.entries) {
                    Expr key = sub(entry.first);
                    Expr value = sub(entry.second);
                    entry = {key, value};
                }
            });
        case ExprKind::NamedArg: return edit<ExprNamedArg>(arena, expr, [&](auto& c) { c.value = sub(c.value); });
        case ExprKind::NullCoalesce:
            return edit<ExprNullCoalesce>(arena, expr, [&](auto& c) {
                c.left = sub(c.left);
                c.right = sub(c.right);
            });
        case ExprKind::CatchExpr:
            return edit<ExprCatchExpr>(arena, expr, [&](auto& c) {
                c.expr = sub(c.expr);
                c.fallback = sub(c.fallback);
            });
        case ExprKind::InlineForArgs:
            return edit<ExprInlineForArgs>(arena, expr, [&](auto& c) {
                c.iterable = sub(c.iterable);
                c.body = sub(c.body);
            });
        case ExprKind::StringTemplate:
            return edit<ExprStringTemplate>(arena, expr, [&](auto& c) {
                for (StringTemplatePart& part : c.parts) {
                    if (!part.isLiteral) part = exprPart(sub(part.expr));
                }
            });
        case ExprKind::Deref: return edit<ExprDeref>(arena, expr, [&](auto& c) { c.target = sub(c.target); });
        case ExprKind::Await: return edit<ExprAwait>(arena, expr, [&](auto& c) { c.value = sub(c.value); });
        case ExprKind::Isolated: return edit<ExprIsolated>(arena, expr, [&](auto& c) { c.value = sub(c.value); });
        case ExprKind::Spread: return edit<ExprSpread>(arena, expr, [&](auto& c) { c.array = sub(c.array); });
        case ExprKind::Alloc: return edit<ExprAlloc>(arena, expr, [&](auto& c) { c.value = sub(c.value); });
        default: return expr;
    }
}

// -- inline if / for ------------------------------------------------------------

CtfeEvaluator::Stmts CtfeEvaluator::foldDeepInlineIf(const StmtDeepInlineIf& stmt, const Stmt& node, const Program& program,
                                                     std::vector<std::string>& errors) {
    Exprs cond = foldCompileTimeExpr(stmt.condition, program);
    const ExprBoolLiteral* b = as<ExprBoolLiteral>(cond.first);
    if (!b) return {{edit<StmtDeepInlineIf>(arena, node, [&](StmtDeepInlineIf& c) { c.condition = cond.first; })}, false};
    const std::vector<Stmt>* branch = b->value ? &stmt.thenBranch : (stmt.elseBranch ? &*stmt.elseBranch : nullptr);
    std::vector<Stmt> result;
    if (branch) {
        for (const Stmt& s : *branch) append(result, foldDeepInlineStmt(s, program, errors).first);
    }
    return {result, true};
}

CtfeEvaluator::Stmts CtfeEvaluator::foldInlineIf(const StmtInlineIf& stmt, const Stmt& node, const Program& program,
                                                 std::vector<std::string>& /*errors*/) {
    Exprs cond = foldCompileTimeExpr(stmt.condition, program);
    const ExprBoolLiteral* b = as<ExprBoolLiteral>(cond.first);
    // Not (yet) a compile-time constant: the fixed-point loop may resolve it later.
    if (!b) return {{edit<StmtInlineIf>(arena, node, [&](StmtInlineIf& c) { c.condition = cond.first; })}, false};
    if (b->value) return {stmt.thenBranch, true};
    return {stmt.elseBranch.value_or(std::vector<Stmt>{}), true};
}

CtfeEvaluator::Stmts CtfeEvaluator::foldInlineFor(const StmtInlineFor& stmt, const Stmt& node, const Program& program,
                                                  std::vector<std::string>& errors) {
    const ExprRange* range = as<ExprRange>(stmt.iterable);
    if (!range) {
        errors.push_back("line " + str(stmt.line) + ": inline for requires a literal range, got " + simpleKindName(stmt.iterable));
        return {{}, false};
    }
    Exprs startExpr = foldCompileTimeExpr(range->from, program);
    Exprs endExpr = foldCompileTimeExpr(range->to, program);
    const ExprIntLiteral* start = as<ExprIntLiteral>(startExpr.first);
    const ExprIntLiteral* end = as<ExprIntLiteral>(endExpr.first);
    // Bounds not yet constant - leave for the fixed-point loop / TypeResolver.
    if (!start || !end) return {{node}, false};
    Env savedEnv = inlineEnv;
    std::unordered_map<std::string, std::string> savedReflectionTypes = reflectionTypes;
    std::vector<Stmt> result;
    for (int64_t i : constantProgression(*range, start->value, end->value)) {
        inlineEnv.set(stmt.name, make(arena, ExprIntLiteral{i, stmt.line}));
        append(result, foldBody(stmt.body, program, errors).first);
    }
    // Restore the CTCE environment so the loop's bindings do not leak past it.
    inlineEnv = savedEnv;
    reflectionTypes = savedReflectionTypes;
    return {result, true};
}

}  // namespace azora
