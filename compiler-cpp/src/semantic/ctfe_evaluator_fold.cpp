// Port of semantic/CtfeEvaluator.kt, second half: expression folding, the
// constant helpers, `reflect` scope queries, and the interpreter that runs a
// function body on constant arguments.
#include "semantic/ctfe_evaluator.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/kotlin_math.h"
#include "base/numbers.h"
#include "base/text.h"
#include "frontend/ast_rewriter.h"
#include "frontend/ast_util.h"
#include "ir/ir_type.h"
#include "semantic/decorator_metadata.h"

#include <algorithm>
#include <functional>

namespace azora {

namespace {

template <class K, class H, class F>
K* edit(Arena& arena, const H& h, F&& f) {
    K c = cast<K>(h);
    f(c);
    return make(arena, std::move(c));
}

const std::unordered_set<std::string>& numericTypes() {
    static const std::unordered_set<std::string> names = {
        "Byte", "Short", "Int", "Long", "Cent", "UByte", "UShort", "UInt", "ULong", "UCent", "Half", "Float", "Double", "Quad",
    };
    return names;
}

/** Intrinsics.RUNTIME: runtime functions the backends supply despite their ordinary std bodies. */
bool isRuntimeIntrinsic(const std::string& name) { return name == "toString" || name == "concurrency_cancel"; }

/** The implicit top-level scope reported for globally declared names. */
const ScopeMeta& globalScope() {
    static const ScopeMeta scope{std::string("global"), false, nullptr};
    return scope;
}

ExprIntLiteral* intLiteral(Arena& arena, int64_t value, int32_t line) { return make(arena, ExprIntLiteral{value, line}); }
ExprBoolLiteral* boolLiteral(Arena& arena, bool value, int32_t line) { return make(arena, ExprBoolLiteral{value, line}); }
ExprDoubleLiteral* doubleLiteral(Arena& arena, double value, int32_t line) { return make(arena, ExprDoubleLiteral{value, line}); }
ExprStringLiteral* stringLiteral(Arena& arena, std::string value, int32_t line) {
    return make(arena, ExprStringLiteral{std::move(value), line});
}

/** Kotlin `String.repeat(n)`. */
std::string repeated(const std::string& text, int32_t count) {
    if (count < 0) failArgument("Count 'n' must be non-negative, but was " + str(count) + ".");
    return repeat(text, count);
}

}  // namespace

// -- Reflection bookkeeping and conformance -------------------------------------

void CtfeEvaluator::recordReflectionType(const std::string& name, const TypeAnnotation& annotation, const Expr& initializer) {
    const TypeRef& explicitRef = annotation.ref;
    std::optional<std::string> type;
    if (const TypeRefReference* reference = as<TypeRefReference>(explicitRef)) {
        type = displayName(reference->inner);
    } else if (!explicitRef) {
        if (const ExprCall* call = as<ExprCall>(initializer)) type = call->callee;
    } else {
        type = displayName(explicitRef);
    }
    if (type) reflectionTypes[name] = *type;
}

std::optional<bool> CtfeEvaluator::conformanceOf(const std::string& typeName, const std::string& specName, const Program& program) {
    // Named integer widths are aliases of Int<N>/UInt<N>: consult the family's conformances.
    std::optional<IrType> primitive;
    if (IrTypes::isPrimitiveName(typeName)) {
        try {
            primitive = IrTypes::fromName(typeName);
        } catch (const CompileFailure&) {
            primitive = std::nullopt;
        }
    }
    const IrTypeInteger* integer = primitive ? irAs<IrTypeInteger>(*primitive) : nullptr;
    if (integer && table.lookupSpec(specName)) {
        return table.conformsTo(typeName, specName) || table.conformsTo(integer->isSigned ? "Int" : "UInt", specName);
    }
    bool declared = false;
    for (const TopLevel& item : program.items) {
        const TopLevelPack* pack = as<TopLevelPack>(item);
        const TopLevelEnum* e = as<TopLevelEnum>(item);
        const TopLevelSpec* spec = as<TopLevelSpec>(item);
        if ((pack && pack->name == typeName) || (e && e->name == typeName) || (spec && spec->name == typeName)) {
            declared = true;
            break;
        }
    }
    if (!declared) return std::nullopt;
    std::unordered_set<std::string> seen;
    // A spec may itself refine another, so the question is reachability.
    std::function<bool(const std::string&)> conforms = [&](const std::string& from) -> bool {
        if (!seen.insert(from).second) return false;
        std::vector<std::string> specs;
        for (const TopLevel& item : program.items) {
            const TopLevelImpl* impl = as<TopLevelImpl>(item);
            if (impl && impl->typeName == from && impl->traitName) specs.push_back(*impl->traitName);
        }
        for (const std::string& s : specs) {
            if (s == specName || conforms(s)) return true;
        }
        return false;
    };
    return conforms(typeName);
}

std::pair<std::vector<Expr>, bool> CtfeEvaluator::spliceInlineForArgs(const std::vector<Expr>& args, const Program& program) {
    if (std::none_of(args.begin(), args.end(), [](const Expr& a) { return is<ExprInlineForArgs>(a); })) return {args, false};
    bool changed = false;
    std::vector<Expr> result;
    for (const Expr& arg : args) {
        const ExprInlineForArgs* loop = as<ExprInlineForArgs>(arg);
        std::optional<std::vector<int64_t>> values;
        if (loop) values = constantRangeOf(loop->iterable, program);
        if (!loop || !values) {
            result.push_back(arg);
            continue;
        }
        changed = true;
        for (int64_t value : *values) {
            Env bound;
            bound.set(loop->name, make(arena, ExprIntLiteral{value, loop->line, loop->column}));
            result.push_back(substituteInExpr(loop->body, bound));
        }
    }
    return {result, changed};
}

std::optional<std::vector<int64_t>> CtfeEvaluator::constantRangeOf(const Expr& iterable, const Program& program) {
    const ExprRange* range = as<ExprRange>(iterable);
    if (!range) return std::nullopt;
    Expr from = foldExpr(range->from, program).first;
    const ExprIntLiteral* fromValue = as<ExprIntLiteral>(from);
    if (!fromValue) return std::nullopt;
    Expr to = foldExpr(range->to, program).first;
    const ExprIntLiteral* toValue = as<ExprIntLiteral>(to);
    if (!toValue) return std::nullopt;
    return constantProgression(*range, fromValue->value, toValue->value);
}

Expr CtfeEvaluator::inlineCallAsExpression(const ExprCall& call, const std::vector<Expr>& args, const Program& program) {
    const FuncDecl* decl = functionNamed(program, call.callee, true);
    if (!decl) return Expr{};
    for (const Param& p : decl->params) {
        if (p.variadic) return Expr{};
    }
    if (decl->body.size() != 1) return Expr{};
    const StmtReturn* ret = as<StmtReturn>(decl->body[0]);
    if (!ret || !ret->value) return Expr{};
    Expr returned = foldTypeNames(ret->value, decl->typeParams, call.typeArgs, call);
    Env bindings = constTypeArguments(*decl, call.typeArgs);
    for (size_t index = 0; index < decl->params.size(); index++) {
        const Param& param = decl->params[index];
        Expr argument = index < args.size() ? args[index] : param.defaultValue;
        if (!argument) return Expr{};
        bindings.set(param.name, asDeclaredLiteral(argument, param.type));
    }
    return foldExpr(substituteInExpr(returned, bindings), program).first;
}

Expr CtfeEvaluator::foldTypeNames(const Expr& expr, const std::vector<std::string>& typeParams, const std::vector<TypeRef>& typeArgs,
                                  const ExprCall& call) {
    if (typeParams.empty() || typeArgs.empty()) return expr;
    OrderedMap<std::string, TypeRef> bound;
    for (size_t index = 0; index < typeParams.size(); index++) {
        if (index < typeArgs.size()) bound.set(typeParams[index], typeArgs[index]);
    }
    if (bound.empty()) return expr;
    auto bareOf = [](TypeRef ref) {
        while (const TypeRefReference* r = as<TypeRefReference>(ref)) ref = r->inner;
        return ref;
    };
    // Bound to a parameter of the erased generic being folded, `T.typeName` has no name to fold to.
    for (const auto& [param, ref] : bound) {
        TypeRef bare = bareOf(ref);
        const TypeRefNamed* erased = as<TypeRefNamed>(bare);
        if (!erased || !erased->args.empty() || !erasedTypeParams.count(erased->name)) continue;
        if (!readsTypeName(expr, param)) continue;
        if (activeErrors) {
            activeErrors->push_back("line " + str(call.line) + ": '" + call.callee + "<" + erased->name + ">' needs the name of '" +
                                    erased->name + "', but generics are erased, so inside a generic function '" + erased->name +
                                    "' has none at run time - take what is looked up by name (such as the Storage<" +
                                    erased->name + ">) as a parameter from a caller that names the type");
        }
    }
    // `T.typeName` names the *type*, not how it is borrowed.
    OrderedMap<std::string, std::string> names;
    for (const auto& [param, ref] : bound) names.set(param, displayName(bareOf(ref)));

    std::function<TypeRef(const TypeRef&)> substitute = [&](const TypeRef& ref) -> TypeRef {
        switch (ref.kind()) {
            case TypeRefKind::Named: {
                const TypeRefNamed& n = cast<TypeRefNamed>(ref);
                if (n.args.empty()) {
                    const TypeRef* value = bound.get(n.name);
                    return value ? *value : ref;
                }
                std::vector<TypeRef> args;
                for (const TypeRef& a : n.args) args.push_back(substitute(a));
                return edit<TypeRefNamed>(arena, ref, [&](TypeRefNamed& c) { c.args = std::move(args); });
            }
            case TypeRefKind::Array: return edit<TypeRefArray>(arena, ref, [&](TypeRefArray& c) { c.element = substitute(c.element); });
            case TypeRefKind::Set: return edit<TypeRefSet>(arena, ref, [&](TypeRefSet& c) { c.element = substitute(c.element); });
            case TypeRefKind::Map:
                return edit<TypeRefMap>(arena, ref, [&](TypeRefMap& c) {
                    c.key = substitute(c.key);
                    c.value = substitute(c.value);
                });
            case TypeRefKind::Tuple:
                return edit<TypeRefTuple>(arena, ref, [&](TypeRefTuple& c) {
                    std::vector<TypeRef> elements;
                    for (const TypeRef& e : c.elements) elements.push_back(substitute(e));
                    c.elements = std::move(elements);
                });
            case TypeRefKind::Nullable: return edit<TypeRefNullable>(arena, ref, [&](TypeRefNullable& c) { c.inner = substitute(c.inner); });
            case TypeRefKind::Reference: return edit<TypeRefReference>(arena, ref, [&](TypeRefReference& c) { c.inner = substitute(c.inner); });
            default: return ref;
        }
    };

    std::function<Expr(const Expr&)> fold = [&](const Expr& e) -> Expr {
        auto all = [&](const std::vector<Expr>& values) {
            std::vector<Expr> out;
            for (const Expr& v : values) out.push_back(fold(v));
            return out;
        };
        switch (e.kind()) {
            case ExprKind::Member: {
                const ExprMember& m = cast<ExprMember>(e);
                if (m.name == "typeName") {
                    if (const ExprIdentifier* id = as<ExprIdentifier>(m.target)) {
                        if (const std::string* name = names.get(id->name)) return make(arena, ExprStringLiteral{*name, m.line, m.column});
                    }
                }
                return edit<ExprMember>(arena, e, [&](auto& c) { c.target = fold(c.target); });
            }
            case ExprKind::Call:
                return edit<ExprCall>(arena, e, [&](auto& c) {
                    c.args = all(c.args);
                    c.receiver = c.receiver ? fold(c.receiver) : c.receiver;
                    std::vector<TypeRef> typeArgsOut;
                    for (const TypeRef& t : c.typeArgs) typeArgsOut.push_back(substitute(t));
                    c.typeArgs = std::move(typeArgsOut);
                });
            case ExprKind::MethodCall:
                return edit<ExprMethodCall>(arena, e, [&](auto& c) {
                    c.target = fold(c.target);
                    c.args = all(c.args);
                });
            case ExprKind::Binary:
                return edit<ExprBinary>(arena, e, [&](auto& c) {
                    c.left = fold(c.left);
                    c.right = fold(c.right);
                });
            case ExprKind::Unary: return edit<ExprUnary>(arena, e, [&](auto& c) { c.operand = fold(c.operand); });
            case ExprKind::IncDec: return edit<ExprIncDec>(arena, e, [&](auto& c) { c.target = fold(c.target); });
            case ExprKind::Grouping: return edit<ExprGrouping>(arena, e, [&](auto& c) { c.expr = fold(c.expr); });
            case ExprKind::IfExpr:
                return edit<ExprIfExpr>(arena, e, [&](auto& c) {
                    c.condition = fold(c.condition);
                    c.thenExpr = fold(c.thenExpr);
                    c.elseExpr = fold(c.elseExpr);
                });
            case ExprKind::Index:
                return edit<ExprIndex>(arena, e, [&](auto& c) {
                    c.target = fold(c.target);
                    c.index = fold(c.index);
                });
            case ExprKind::Isolated: return edit<ExprIsolated>(arena, e, [&](auto& c) { c.value = fold(c.value); });
            case ExprKind::Cast:
                return edit<ExprCast>(arena, e, [&](auto& c) {
                    c.expr = fold(c.expr);
                    c.targetType = substitute(c.targetType);
                });
            case ExprKind::StringTemplate:
                return edit<ExprStringTemplate>(arena, e, [&](auto& c) {
                    for (StringTemplatePart& part : c.parts) {
                        if (!part.isLiteral) part = exprPart(fold(part.expr));
                    }
                });
            default: return e;
        }
    };
    return fold(expr);
}

bool CtfeEvaluator::readsTypeName(const Expr& expr, const std::string& param) {
    bool found = false;
    AstRewriter finder(arena);
    finder.onExpr = [&](AstRewriter& self, const Expr& e) -> Expr {
        if (const ExprMember* m = as<ExprMember>(e); m && m->name == "typeName") {
            const ExprIdentifier* id = as<ExprIdentifier>(m->target);
            if (id && id->name == param) found = true;
        }
        return self.baseExpr(e);
    };
    finder.expr(expr);
    return found;
}

std::optional<std::string> CtfeEvaluator::spliceName(const std::string& name) {
    if (!contains(name, '$')) return std::nullopt;
    std::string result = name;
    bool replaced = false;
    // Longest name first, so `$row` is not read as `$r` followed by `ow`.
    std::vector<std::string> keys;
    for (const auto& [key, _] : inlineEnv) keys.push_back(key);
    std::stable_sort(keys.begin(), keys.end(), [](const std::string& a, const std::string& b) { return u16len(a) > u16len(b); });
    for (const std::string& binding : keys) {
        std::string marker = "$" + binding;
        if (!contains(result, marker)) continue;
        std::optional<std::string> text = constantText(*inlineEnv.get(binding));
        if (!text) continue;
        result = replaceAll(result, marker, *text);
        replaced = true;
    }
    if (!replaced) return std::nullopt;
    return result;
}

std::string CtfeEvaluator::kotlinToString(const Expr& expr) { return structuralKey(expr); }

// -- Expression folding ---------------------------------------------------------

CtfeEvaluator::Exprs CtfeEvaluator::foldExpr(const Expr& expr, const Program& program) {
    auto unchanged = Exprs{expr, false};
    switch (expr.kind()) {
        // `.(args)` carries ordinary expressions; they fold as any call's arguments do.
        case ExprKind::InferredMember: {
            const ExprInferredMember& m = cast<ExprInferredMember>(expr);
            if (!m.ctorArgs) return unchanged;
            bool changed = false;
            std::vector<Expr> folded;
            for (const Expr& argument : *m.ctorArgs) {
                Exprs result = foldExpr(argument, program);
                changed = changed || result.second;
                folded.push_back(result.first);
            }
            if (!changed) return unchanged;
            return {edit<ExprInferredMember>(arena, expr, [&](auto& c) { c.ctorArgs = std::move(folded); }), true};
        }
        case ExprKind::MapEntryArg:
        case ExprKind::InlineForArgs: return unchanged;
        case ExprKind::InCheck: {
            const ExprInCheck& c = cast<ExprInCheck>(expr);
            Exprs value = foldExpr(c.value, program);
            Exprs collection = foldExpr(c.collection, program);
            return {edit<ExprInCheck>(arena, expr, [&](auto& n) {
                        n.value = value.first;
                        n.collection = collection.first;
                    }),
                    value.second || collection.second};
        }
        case ExprKind::Binary: {
            const ExprBinary& b = cast<ExprBinary>(expr);
            Exprs left = foldExpr(b.left, program);
            Exprs right = foldExpr(b.right, program);
            // Runtime arithmetic needs its resolved width: do not fold it untyped.
            bool arithmetic = b.op == TokenType::PLUS || b.op == TokenType::MINUS || b.op == TokenType::STAR ||
                              b.op == TokenType::SLASH || b.op == TokenType::PERCENT;
            bool untypedRuntimeArithmetic =
                compileTimeDepth == 0 && is<ExprIntLiteral>(left.first) && is<ExprIntLiteral>(right.first) && arithmetic;
            Expr folded = untypedRuntimeArithmetic ? Expr{} : tryFoldBinary(left.first, b.op, right.first, b.line);
            if (folded) return {folded, true};
            return {edit<ExprBinary>(arena, expr, [&](auto& n) {
                        n.left = left.first;
                        n.right = right.first;
                    }),
                    left.second || right.second};
        }
        case ExprKind::Unary: {
            const ExprUnary& u = cast<ExprUnary>(expr);
            Exprs operand = foldExpr(u.operand, program);
            Expr folded = tryFoldUnary(u.op, operand.first, u.line);
            if (folded) return {folded, true};
            return {edit<ExprUnary>(arena, expr, [&](auto& n) { n.operand = operand.first; }), operand.second};
        }
        case ExprKind::IncDec: return unchanged;
        case ExprKind::SetLiteral: {
            bool changed = false;
            std::vector<Expr> elements;
            for (const Expr& e : cast<ExprSetLiteral>(expr).elements) {
                Exprs folded = foldExpr(e, program);
                changed = changed || folded.second;
                elements.push_back(folded.first);
            }
            return {edit<ExprSetLiteral>(arena, expr, [&](auto& n) { n.elements = std::move(elements); }), changed};
        }
        case ExprKind::Call: return foldCall(cast<ExprCall>(expr), expr, program);
        case ExprKind::Grouping: {
            Exprs inner = foldExpr(cast<ExprGrouping>(expr).expr, program);
            if (isConstant(inner.first)) return {inner.first, true};
            return {edit<ExprGrouping>(arena, expr, [&](auto& n) { n.expr = inner.first; }), inner.second};
        }
        // Substitute inline fin bindings
        case ExprKind::Identifier: {
            const ExprIdentifier& id = cast<ExprIdentifier>(expr);
            const Expr* inlineValue = inlineEnv.get(id.name);
            if (inlineValue) return {typedForRuntime(id.name, *inlineValue), true};
            return unchanged;
        }
        case ExprKind::UpperScopeAccess:
        case ExprKind::IntLiteral:
        case ExprKind::DoubleLiteral:
        case ExprKind::StringLiteral:
        case ExprKind::BoolLiteral:
        case ExprKind::CharLiteral:
        case ExprKind::TupleLit:
        case ExprKind::TupleAccess:
        case ExprKind::VariantLit: return unchanged;
        // Folding must not drop the seal.
        case ExprKind::Seal: {
            Exprs value = foldExpr(cast<ExprSeal>(expr).value, program);
            if (!value.second) return unchanged;
            return {edit<ExprSeal>(arena, expr, [&](auto& n) { n.value = value.first; }), true};
        }
        case ExprKind::IfExpr: {
            const ExprIfExpr& i = cast<ExprIfExpr>(expr);
            Exprs condition = foldExpr(i.condition, program);
            Exprs thenExpr = foldExpr(i.thenExpr, program);
            Exprs elseExpr = foldExpr(i.elseExpr, program);
            if (const ExprBoolLiteral* b = as<ExprBoolLiteral>(condition.first)) return {b->value ? thenExpr.first : elseExpr.first, true};
            return {edit<ExprIfExpr>(arena, expr, [&](auto& n) {
                        n.condition = condition.first;
                        n.thenExpr = thenExpr.first;
                        n.elseExpr = elseExpr.first;
                    }),
                    condition.second || thenExpr.second || elseExpr.second};
        }
        case ExprKind::CatchExpr: {
            const ExprCatchExpr& c = cast<ExprCatchExpr>(expr);
            Exprs inner = foldExpr(c.expr, program);
            Exprs fallback = foldExpr(c.fallback, program);
            if (!inner.second && !fallback.second) return unchanged;
            return {edit<ExprCatchExpr>(arena, expr, [&](auto& n) {
                        n.expr = inner.first;
                        n.fallback = fallback.first;
                    }),
                    true};
        }
        // A lambda body is a block like any other.
        case ExprKind::Lambda: {
            std::vector<std::string> discarded;
            Stmts body = foldScopedBody(cast<ExprLambda>(expr).body, program, activeErrors ? *activeErrors : discarded);
            if (!body.second) return unchanged;
            return {edit<ExprLambda>(arena, expr, [&](auto& n) { n.body = std::move(body.first); }), true};
        }
        case ExprKind::SafeMember: {
            const ExprSafeMember& s = cast<ExprSafeMember>(expr);
            // `(reflect X).scope?.label` / `?.isInline` - fold the safe terminal.
            if (Expr folded = foldScopeTerminal(s.target, s.name, program, s.line)) return {folded, true};
            return unchanged;
        }
        case ExprKind::NullCoalesce: {
            const ExprNullCoalesce& n = cast<ExprNullCoalesce>(expr);
            Exprs left = foldExpr(n.left, program);
            if (is<ExprNullLiteral>(left.first)) return {foldExpr(n.right, program).first, true};
            if (isConstant(left.first)) return {left.first, true};
            if (!left.second) return unchanged;
            return {edit<ExprNullCoalesce>(arena, expr, [&](auto& c) { c.left = left.first; }), true};
        }
        case ExprKind::Cast: {
            const ExprCast& c = cast<ExprCast>(expr);
            Exprs inner = foldExpr(c.expr, program);
            Expr candidate = inner.second ? Expr(edit<ExprCast>(arena, expr, [&](auto& n) { n.expr = inner.first; })) : expr;
            if (std::optional<std::string> text = constantText(candidate)) {
                return {make(arena, ExprStringLiteral{*text, c.line, c.column, c.length}), true};
            }
            return {candidate, inner.second};
        }
        // `T is Number` where `T` is bound to a concrete type: conformance decides it here.
        case ExprKind::IsCheck: {
            const ExprIsCheck& c = cast<ExprIsCheck>(expr);
            const ExprIdentifier* subject = as<ExprIdentifier>(c.expr);
            std::optional<bool> decided = subject ? conformanceOf(subject->name, c.typeName, program) : std::nullopt;
            if (decided) return {make(arena, ExprBoolLiteral{*decided, c.line, c.column}), true};
            return unchanged;
        }
        case ExprKind::NamedArg: {
            Exprs value = foldExpr(cast<ExprNamedArg>(expr).value, program);
            if (!value.second) return unchanged;
            return {edit<ExprNamedArg>(arena, expr, [&](auto& n) { n.value = value.first; }), true};
        }
        case ExprKind::MapLit: {
            bool changed = false;
            std::vector<std::pair<Expr, Expr>> entries;
            for (const auto& [k, v] : cast<ExprMapLit>(expr).entries) {
                Exprs key = foldExpr(k, program);
                Exprs value = foldExpr(v, program);
                changed = changed || key.second || value.second;
                entries.emplace_back(key.first, value.first);
            }
            if (!changed) return unchanged;
            return {edit<ExprMapLit>(arena, expr, [&](auto& n) { n.entries = std::move(entries); }), true};
        }
        case ExprKind::Alloc: {
            Exprs v = foldExpr(cast<ExprAlloc>(expr).value, program);
            if (!v.second) return unchanged;
            return {edit<ExprAlloc>(arena, expr, [&](auto& n) { n.value = v.first; }), true};
        }
        case ExprKind::Deref: {
            Exprs v = foldExpr(cast<ExprDeref>(expr).target, program);
            if (!v.second) return unchanged;
            return {edit<ExprDeref>(arena, expr, [&](auto& n) { n.target = v.first; }), true};
        }
        case ExprKind::Isolated: {
            Exprs v = foldExpr(cast<ExprIsolated>(expr).value, program);
            if (!v.second) return unchanged;
            return {edit<ExprIsolated>(arena, expr, [&](auto& n) { n.value = v.first; }), true};
        }
        case ExprKind::Await: {
            Exprs v = foldExpr(cast<ExprAwait>(expr).value, program);
            if (!v.second) return unchanged;
            return {edit<ExprAwait>(arena, expr, [&](auto& n) { n.value = v.first; }), true};
        }
        case ExprKind::Spread: {
            Exprs v = foldExpr(cast<ExprSpread>(expr).array, program);
            if (!v.second) return unchanged;
            return {edit<ExprSpread>(arena, expr, [&](auto& n) { n.array = v.first; }), true};
        }
        case ExprKind::NullLiteral:
        case ExprKind::Inject: return unchanged;
        case ExprKind::TryPropagate: {
            Exprs inner = foldExpr(cast<ExprTryPropagate>(expr).expr, program);
            if (!inner.second) return unchanged;
            return {edit<ExprTryPropagate>(arena, expr, [&](auto& n) { n.expr = inner.first; }), true};
        }
        case ExprKind::Range: {
            const ExprRange& r = cast<ExprRange>(expr);
            Exprs from = foldExpr(r.from, program);
            Exprs to = foldExpr(r.to, program);
            if (!from.second && !to.second) return unchanged;
            return {edit<ExprRange>(arena, expr, [&](auto& n) {
                        n.from = from.first;
                        n.to = to.first;
                    }),
                    true};
        }
        case ExprKind::ArrayLiteral: {
            bool changed = false;
            std::vector<Expr> elements;
            for (const Expr& e : cast<ExprArrayLiteral>(expr).elements) {
                Exprs folded = foldExpr(e, program);
                changed = changed || folded.second;
                elements.push_back(folded.first);
            }
            if (!changed) return unchanged;
            return {edit<ExprArrayLiteral>(arena, expr, [&](auto& n) { n.elements = std::move(elements); }), true};
        }
        case ExprKind::Index: {
            const ExprIndex& i = cast<ExprIndex>(expr);
            Exprs target = foldExpr(i.target, program);
            Exprs index = foldExpr(i.index, program);
            if (!target.second && !index.second) return unchanged;
            return {edit<ExprIndex>(arena, expr, [&](auto& n) {
                        n.target = target.first;
                        n.index = index.first;
                    }),
                    true};
        }
        case ExprKind::Member: return foldMember(cast<ExprMember>(expr), expr, program);
        case ExprKind::MethodCall: {
            const ExprMethodCall& m = cast<ExprMethodCall>(expr);
            Exprs target = foldExpr(m.target, program);
            bool changed = target.second;
            std::vector<Expr> args;
            for (const Expr& a : m.args) {
                Exprs folded = foldExpr(a, program);
                changed = changed || folded.second;
                args.push_back(folded.first);
            }
            if (!changed) return unchanged;
            return {edit<ExprMethodCall>(arena, expr, [&](auto& n) {
                        n.target = target.first;
                        n.args = std::move(args);
                    }),
                    true};
        }
        case ExprKind::StringTemplate: {
            const ExprStringTemplate& t = cast<ExprStringTemplate>(expr);
            bool changed = false;
            std::vector<StringTemplatePart> parts;
            for (const StringTemplatePart& part : t.parts) {
                if (part.isLiteral) {
                    parts.push_back(part);
                    continue;
                }
                Exprs folded = foldExpr(part.expr, program);
                if (folded.second) changed = true;
                parts.push_back(folded.second ? exprPart(folded.first) : part);
            }
            Expr candidate = changed ? Expr(edit<ExprStringTemplate>(arena, expr, [&](auto& n) { n.parts = std::move(parts); })) : expr;
            if (std::optional<std::string> text = constantText(candidate)) {
                return {make(arena, ExprStringLiteral{*text, t.line, t.column, t.length}), true};
            }
            return {candidate, changed};
        }
        // Macros are expanded before CTCE; unreachable.
        case ExprKind::MetaInvoke:
        case ExprKind::Slice: return unchanged;
        default: AZORA_UNREACHABLE();
    }
}

CtfeEvaluator::Exprs CtfeEvaluator::foldCall(const ExprCall& call, const Expr& expr, const Program& program) {
    if (compileTimeDepth > 0 && call.callee == "__hasAnnot") {
        std::optional<std::string> decoratorName;
        if (call.typeArgs.size() == 1) decoratorName = displayName(call.typeArgs[0]);
        if (!decoratorName || call.args.size() != 1) {
            if (activeErrors) activeErrors->push_back("line " + str(call.line) + ": hasAnnot requires exactly one receiver and decorator type");
            return {make(arena, ExprBoolLiteral{false, call.line, call.column}), true};
        }
        std::optional<DecoratorMetadata::Site> site = DecoratorMetadata::findSite(call.args[0], reflectionTypes, program);
        if (!site) {
            if (activeErrors) activeErrors->push_back("line " + str(call.line) + ": hasAnnot receiver is not compile-time declaration metadata");
            return {make(arena, ExprBoolLiteral{false, call.line, call.column}), true};
        }
        bool knownDecorator = false;
        for (const TopLevel& item : program.items) {
            const TopLevelDeco* deco = as<TopLevelDeco>(item);
            if (deco && deco->name == *decoratorName) {
                knownDecorator = true;
                break;
            }
        }
        if (!knownDecorator) {
            if (activeErrors) activeErrors->push_back("line " + str(call.line) + ": unknown decorator '" + *decoratorName + "' in hasAnnot");
            return {make(arena, ExprBoolLiteral{false, call.line, call.column}), true};
        }
        bool applied = DecoratorMetadata::findApplied(*site, *decoratorName, program).has_value();
        return {make(arena, ExprBoolLiteral{applied, call.line, call.column}), true};
    }
    // `Vec(inline for i in 0..<N { … })` supplies one argument per iteration.
    std::pair<std::vector<Expr>, bool> spliced = spliceInlineForArgs(call.args, program);
    bool anyChanged = spliced.second;
    std::vector<Expr> newArgs;
    for (const Expr& arg : spliced.first) {
        Exprs folded = foldExpr(arg, program);
        anyChanged = anyChanged || folded.second;
        newArgs.push_back(folded.first);
    }
    // An `inline func` used as a value is inlined here; this is what binds its const type parameters.
    if (Expr inlined = inlineCallAsExpression(call, newArgs, program)) {
        table.semanticFacts.folded(inlined, call);
        return {inlined, true};
    }
    // Try to evaluate the call at compile time if all args are constants
    bool allConst = std::all_of(newArgs.begin(), newArgs.end(), [&](const Expr& a) { return isConstant(a); });
    if (allConst) {
        if (Expr value = tryEvalCall(call.callee, newArgs, program, call.line, call.typeArgs)) {
            Expr result = typedCallResult(call.callee, value, program);
            table.semanticFacts.folded(result, call);
            return {result, true};
        }
    }
    return {edit<ExprCall>(arena, expr, [&](auto& n) { n.args = std::move(newArgs); }), anyChanged};
}

CtfeEvaluator::Exprs CtfeEvaluator::foldMember(const ExprMember& member, const Expr& expr, const Program& program) {
    // `m.m$r$c` - a member name may splice compile-time values.
    if (std::optional<std::string> spliced = spliceName(member.name)) {
        Exprs target = foldExpr(member.target, program);
        return {edit<ExprMember>(arena, expr, [&](auto& n) {
                    n.target = target.first;
                    n.name = *spliced;
                }),
                true};
    }
    if (Expr folded = foldScopeTerminal(member.target, member.name, program, member.line)) return {folded, true};
    const ExprCall* metadataQuery = as<ExprCall>(member.target);
    if (compileTimeDepth > 0 && metadataQuery && metadataQuery->callee == "__annotMeta") {
        std::optional<std::string> decoratorName;
        if (metadataQuery->typeArgs.size() == 1) decoratorName = displayName(metadataQuery->typeArgs[0]);
        std::optional<DecoratorMetadata::Site> site;
        if (metadataQuery->args.size() == 1) site = DecoratorMetadata::findSite(metadataQuery->args[0], reflectionTypes, program);
        std::optional<DecoratorMetadata::Applied> applied;
        if (site && decoratorName) applied = DecoratorMetadata::findApplied(*site, *decoratorName, program);
        std::string shownName = decoratorName ? *decoratorName : "null";
        if (!applied) {
            if (activeErrors) activeErrors->push_back("line " + str(member.line) + ": decorator '" + shownName + "' is not applied to this declaration");
            return {make(arena, ExprBoolLiteral{false, member.line, member.column}), true};
        }
        Expr value = DecoratorMetadata::fieldValue(arena, *applied, member.name);
        if (!value) {
            if (activeErrors) {
                activeErrors->push_back("line " + str(member.line) + ": decorator '" + shownName + "' has no field '" + member.name +
                                        "' or the field has no value");
            }
            return {make(arena, ExprBoolLiteral{false, member.line, member.column}), true};
        }
        return {foldCompileTimeExpr(value, program).first, true};
    }
    Exprs target = foldExpr(member.target, program);
    if (!target.second) return {expr, false};
    return {edit<ExprMember>(arena, expr, [&](auto& n) { n.target = target.first; }), true};
}

// -- Constant folding helpers -----------------------------------------------------

Expr CtfeEvaluator::typedForRuntime(const std::string& name, const Expr& value) {
    if (compileTimeDepth > 0) return value;
    const TypeRef* written = inlineTypes.get(name);
    const TypeRefNamed* declared = written ? as<TypeRefNamed>(*written) : nullptr;
    if (!declared) return value;
    if (!declared->args.empty() || !numericTypes().count(declared->name)) return value;
    if (is<ExprIntLiteral>(value) && declared->name == "Int") return value;
    if (!is<ExprIntLiteral>(value) && !is<ExprDoubleLiteral>(value)) return value;
    ExprCast c;
    c.expr = value;
    c.targetType = *written;
    c.line = lineOf(value);
    return make(arena, std::move(c));
}

Expr CtfeEvaluator::typedCallResult(const std::string& name, const Expr& value, const Program& program) {
    if (compileTimeDepth > 0) return value;
    const FuncDecl* decl = functionNamed(program, name);
    if (!decl || !decl->returnType.isExplicit()) return value;
    const TypeRefNamed* declared = as<TypeRefNamed>(decl->returnType.ref);
    if (!declared) return value;
    if (!declared->args.empty() || !numericTypes().count(declared->name)) return value;
    if (is<ExprIntLiteral>(value) && declared->name == "Int") return value;
    if (!is<ExprIntLiteral>(value) && !is<ExprDoubleLiteral>(value)) return value;
    ExprCast c;
    c.expr = value;
    c.targetType = decl->returnType.ref;
    c.line = lineOf(value);
    return make(arena, std::move(c));
}

bool CtfeEvaluator::isConstant(const Expr& expr) {
    switch (expr.kind()) {
        case ExprKind::IntLiteral:
        case ExprKind::DoubleLiteral:
        case ExprKind::StringLiteral:
        case ExprKind::BoolLiteral:
        case ExprKind::CharLiteral: return true;
        case ExprKind::Member: {
            const ExprMember& m = cast<ExprMember>(expr);
            const ExprIdentifier* target = as<ExprIdentifier>(m.target);
            if (!target) return false;
            const std::vector<std::string>* variants = table.lookupEnum(target->name);
            return variants && contains(*variants, m.name);
        }
        case ExprKind::Call: {
            const ExprCall& c = cast<ExprCall>(expr);
            return c.callee == "__defaultLogLevel" && c.args.empty();
        }
        default: return false;
    }
}

Expr CtfeEvaluator::traceLevel(const Expr& level, int32_t line) {
    if (level) return level;
    const std::vector<std::string>* variants = table.lookupEnum("LogLevel");
    std::string first = variants && !variants->empty() ? variants->front() : "Debug";
    Expr target = make(arena, ExprIdentifier{"LogLevel", line});
    return make(arena, ExprMember{target, first, line});
}

std::optional<std::string> CtfeEvaluator::traceLevelName(const Expr& level) {
    const std::vector<std::string>* variants = table.lookupEnum("LogLevel");
    switch (level.kind()) {
        case ExprKind::Member: {
            const ExprMember& m = cast<ExprMember>(level);
            const ExprIdentifier* target = as<ExprIdentifier>(m.target);
            if (target && target->name == "LogLevel" && variants && contains(*variants, m.name)) return m.name;
            return std::nullopt;
        }
        case ExprKind::StringLiteral: {
            const std::string& value = cast<ExprStringLiteral>(level).value;
            if (variants && contains(*variants, value)) return value;
            return std::nullopt;
        }
        case ExprKind::Call: {
            const ExprCall& c = cast<ExprCall>(level);
            if (c.callee == "__defaultLogLevel" && c.args.empty() && variants && !variants->empty()) return variants->front();
            return std::nullopt;
        }
        default: return std::nullopt;
    }
}

std::optional<std::string> CtfeEvaluator::enumQualifiedName(const Expr& expr) {
    const ExprMember* member = as<ExprMember>(expr);
    if (!member) return std::nullopt;
    const ExprIdentifier* target = as<ExprIdentifier>(member->target);
    if (!target) return std::nullopt;
    const std::vector<std::string>* variants = table.lookupEnum(target->name);
    if (variants && contains(*variants, member->name)) return target->name + "." + member->name;
    return std::nullopt;
}

std::optional<std::string> CtfeEvaluator::constantText(const Expr& expr) {
    switch (expr.kind()) {
        case ExprKind::StringLiteral: return cast<ExprStringLiteral>(expr).value;
        case ExprKind::IntLiteral: return str((long long)cast<ExprIntLiteral>(expr).value);
        case ExprKind::DoubleLiteral: return javaDoubleToString(cast<ExprDoubleLiteral>(expr).value);
        case ExprKind::BoolLiteral: return str(cast<ExprBoolLiteral>(expr).value);
        case ExprKind::CharLiteral: {
            std::string out;
            appendCodePoint(out, (uint32_t)cast<ExprCharLiteral>(expr).value);
            return out;
        }
        case ExprKind::Member: return enumQualifiedName(expr);
        case ExprKind::Call: {
            const ExprCall& c = cast<ExprCall>(expr);
            if (c.callee != "__defaultLogLevel" || !c.args.empty()) return std::nullopt;
            const std::vector<std::string>* variants = table.lookupEnum("LogLevel");
            if (!variants || variants->empty()) return std::nullopt;
            return "LogLevel." + variants->front();
        }
        case ExprKind::Grouping: return constantText(cast<ExprGrouping>(expr).expr);
        case ExprKind::Cast: {
            const ExprCast& c = cast<ExprCast>(expr);
            if (displayName(c.targetType) == "String") return constantText(c.expr);
            return std::nullopt;
        }
        case ExprKind::StringTemplate: {
            std::string out;
            for (const StringTemplatePart& part : cast<ExprStringTemplate>(expr).parts) {
                if (part.isLiteral) {
                    out += part.text;
                } else {
                    std::optional<std::string> text = constantText(part.expr);
                    if (!text) return std::nullopt;
                    out += *text;
                }
            }
            return out;
        }
        default: return std::nullopt;
    }
}

// -- Scope reflection (`(reflect X).enclosingScope.label` / `.isInline`) ----------

std::optional<CtfeEvaluator::ReflectNode> CtfeEvaluator::evalReflectNode(const Expr& expr, const Program& program) {
    switch (expr.kind()) {
        case ExprKind::Grouping: return evalReflectNode(cast<ExprGrouping>(expr).expr, program);
        case ExprKind::Call: {
            const ExprCall& c = cast<ExprCall>(expr);
            if (c.callee != "__reflect" || c.args.size() != 1) return std::nullopt;
            const ExprIdentifier* id = as<ExprIdentifier>(c.args[0]);
            if (!id) return std::nullopt;
            return ReflectNode{false, id->name, nullptr};
        }
        case ExprKind::Member: {
            const ExprMember& m = cast<ExprMember>(expr);
            return stepReflectScope(evalReflectNode(m.target, program), m.name, program);
        }
        case ExprKind::SafeMember: {
            const ExprSafeMember& m = cast<ExprSafeMember>(expr);
            return stepReflectScope(evalReflectNode(m.target, program), m.name, program);
        }
        default: return std::nullopt;
    }
}

std::optional<CtfeEvaluator::ReflectNode> CtfeEvaluator::stepReflectScope(const std::optional<ReflectNode>& base, const std::string& member,
                                                                         const Program& program) {
    // `reflect<T>.enclosingScope` (`scope` is a keyword, so the member is spelled out).
    if (member != "enclosingScope") return std::nullopt;
    if (!base) return std::nullopt;
    if (!base->isScope) {
        const ScopeMeta* meta = program.scopes.get(base->name);
        return ReflectNode{true, "", meta ? meta : &globalScope()};
    }
    return ReflectNode{true, "", base->meta ? base->meta->parent : nullptr};
}

Expr CtfeEvaluator::foldScopeTerminal(const Expr& target, const std::string& member, const Program& program, int32_t line) {
    if (member != "label" && member != "isInline") return Expr{};
    std::optional<ReflectNode> node = evalReflectNode(target, program);
    if (!node || !node->isScope) return Expr{};
    if (member == "label") {
        if (node->meta && node->meta->label) return stringLiteral(arena, *node->meta->label, line);
        return nullLiteral();
    }
    if (node->meta) return boolLiteral(arena, node->meta->isInline, line);
    return nullLiteral();
}

Expr CtfeEvaluator::tryFoldBinary(const Expr& left, TokenType op, const Expr& right, int32_t line) {
    // Int op Int
    if (const ExprIntLiteral* l = as<ExprIntLiteral>(left)) {
        if (const ExprIntLiteral* r = as<ExprIntLiteral>(right)) {
            // A literal states no width; the fold holds only while the value is exact.
            if (l->magnitude || l->text || r->magnitude || r->text) return Expr{};
            int64_t a = l->value;
            int64_t b = r->value;
            switch (op) {
                case TokenType::PLUS: {
                    int64_t sum = kadd(a, b);
                    return ((a ^ sum) & (b ^ sum)) >= 0 ? Expr(intLiteral(arena, sum, line)) : Expr{};
                }
                case TokenType::MINUS: {
                    int64_t diff = ksub(a, b);
                    return ((a ^ b) & (a ^ diff)) >= 0 ? Expr(intLiteral(arena, diff, line)) : Expr{};
                }
                case TokenType::STAR: {
                    int64_t product = kmul(a, b);
                    bool exact = a == 0 || (kdiv(product, a) == b && !(a == -1 && b == INT64_MIN));
                    return exact ? Expr(intLiteral(arena, product, line)) : Expr{};
                }
                case TokenType::SLASH:
                    return b != 0 && !(a == INT64_MIN && b == -1) ? Expr(intLiteral(arena, kdiv(a, b), line)) : Expr{};
                case TokenType::PERCENT: return b != 0 ? Expr(intLiteral(arena, krem(a, b), line)) : Expr{};
                case TokenType::EQUAL_EQUAL: return boolLiteral(arena, a == b, line);
                case TokenType::BANG_EQUAL: return boolLiteral(arena, a != b, line);
                case TokenType::LESS: return boolLiteral(arena, a < b, line);
                case TokenType::LESS_EQUAL: return boolLiteral(arena, a <= b, line);
                case TokenType::GREATER: return boolLiteral(arena, a > b, line);
                case TokenType::GREATER_EQUAL: return boolLiteral(arena, a >= b, line);
                default: return Expr{};
            }
        }
    }
    // Double op Double
    if (const ExprDoubleLiteral* l = as<ExprDoubleLiteral>(left)) {
        if (const ExprDoubleLiteral* r = as<ExprDoubleLiteral>(right)) {
            double a = l->value;
            double b = r->value;
            switch (op) {
                case TokenType::PLUS: return doubleLiteral(arena, a + b, line);
                case TokenType::MINUS: return doubleLiteral(arena, a - b, line);
                case TokenType::STAR: return doubleLiteral(arena, a * b, line);
                case TokenType::SLASH: return doubleLiteral(arena, a / b, line);
                case TokenType::EQUAL_EQUAL: return boolLiteral(arena, a == b, line);
                case TokenType::BANG_EQUAL: return boolLiteral(arena, a != b, line);
                case TokenType::LESS: return boolLiteral(arena, a < b, line);
                case TokenType::LESS_EQUAL: return boolLiteral(arena, a <= b, line);
                case TokenType::GREATER: return boolLiteral(arena, a > b, line);
                case TokenType::GREATER_EQUAL: return boolLiteral(arena, a >= b, line);
                default: return Expr{};
            }
        }
    }
    // Bool op Bool
    if (const ExprBoolLiteral* l = as<ExprBoolLiteral>(left)) {
        if (const ExprBoolLiteral* r = as<ExprBoolLiteral>(right)) {
            switch (op) {
                case TokenType::AND_AND: return boolLiteral(arena, l->value && r->value, line);
                case TokenType::OR_OR: return boolLiteral(arena, l->value || r->value, line);
                case TokenType::EQUAL_EQUAL: return boolLiteral(arena, l->value == r->value, line);
                case TokenType::BANG_EQUAL: return boolLiteral(arena, l->value != r->value, line);
                default: return Expr{};
            }
        }
    }
    // String op String
    if (const ExprStringLiteral* l = as<ExprStringLiteral>(left)) {
        if (const ExprStringLiteral* r = as<ExprStringLiteral>(right)) {
            switch (op) {
                case TokenType::PLUS: return stringLiteral(arena, l->value + r->value, line);
                case TokenType::EQUAL_EQUAL: return boolLiteral(arena, l->value == r->value, line);
                case TokenType::BANG_EQUAL: return boolLiteral(arena, l->value != r->value, line);
                default: return Expr{};
            }
        }
    }
    // String * Int, Int * String
    if (op == TokenType::STAR) {
        const ExprStringLiteral* ls = as<ExprStringLiteral>(left);
        const ExprIntLiteral* ri = as<ExprIntLiteral>(right);
        if (ls && ri) return stringLiteral(arena, repeated(ls->value, ktoInt(ri->value)), line);
        const ExprIntLiteral* li = as<ExprIntLiteral>(left);
        const ExprStringLiteral* rs = as<ExprStringLiteral>(right);
        if (li && rs) return stringLiteral(arena, repeated(rs->value, ktoInt(li->value)), line);
    }
    return Expr{};
}

Expr CtfeEvaluator::tryFoldUnary(TokenType op, const Expr& operand, int32_t line) {
    // The suffix is the literal's declared width: a 128-bit literal is negated on its digits.
    if (op == TokenType::MINUS) {
        if (const ExprIntLiteral* i = as<ExprIntLiteral>(operand)) {
            ExprIntLiteral n;
            n.value = kneg(i->value);
            n.line = line;
            if (i->text) n.text = "-" + *i->text;
            if (i->magnitude) n.magnitude = "-" + *i->magnitude;
            return make(arena, std::move(n));
        }
        if (const ExprDoubleLiteral* d = as<ExprDoubleLiteral>(operand)) return doubleLiteral(arena, -d->value, line);
    }
    if (op == TokenType::BANG) {
        if (const ExprBoolLiteral* b = as<ExprBoolLiteral>(operand)) return boolLiteral(arena, !b->value, line);
    }
    return Expr{};
}

Expr CtfeEvaluator::asDeclaredLiteral(const Expr& arg, const TypeRef& declared) {
    const TypeRefNamed* named = as<TypeRefNamed>(declared);
    if (!named) return arg;
    if (named->name != "Double" && named->name != "Float" && named->name != "Quad") return arg;
    if (const ExprIntLiteral* i = as<ExprIntLiteral>(arg)) return doubleLiteral(arena, (double)i->value, i->line);
    if (const ExprUnary* u = as<ExprUnary>(arg)) {
        const ExprIntLiteral* i = as<ExprIntLiteral>(u->operand);
        if (u->op == TokenType::MINUS && i) return doubleLiteral(arena, -(double)i->value, u->line);
    }
    return arg;
}

Expr CtfeEvaluator::tryEvalCall(const std::string& name, const std::vector<Expr>& args, const Program& program, int32_t line,
                                const std::vector<TypeRef>& typeArgs /*= {}*/) {
    const FuncDecl* funcDecl = functionNamed(program, name);
    if (!funcDecl) return Expr{};
    // A `scope test` member may only be used from a test.
    if (program.testScopeMembers.contains(name)) return Expr{};
    // Tasks are execution boundaries, not pure value expressions.
    if (funcDecl->isTask || funcDecl->isUnsafe) return Expr{};
    // A failable function can fail, and failure is not a value this evaluator models.
    if (funcDecl->returnType.isExplicit() && is<TypeRefFailable>(funcDecl->returnType.ref)) return Expr{};
    if (isRuntimeIntrinsic(name)) return Expr{};
    // A variadic parameter is bound at runtime to an array of the trailing arguments.
    for (const Param& p : funcDecl->params) {
        if (p.variadic) return Expr{};
    }
    // arg count mismatch - let TypeResolver report it
    if (args.size() != funcDecl->params.size()) return Expr{};
    // A const type parameter is part of the environment.
    Env env;
    Env constArguments = constTypeArguments(*funcDecl, typeArgs);
    if (constArguments.size() != funcDecl->constParams.size()) return Expr{};
    for (const auto& [k, v] : constArguments) env.set(k, v);
    for (size_t i = 0; i < funcDecl->params.size(); i++) {
        env.set(funcDecl->params[i].name, asDeclaredLiteral(args[i], funcDecl->params[i].type));
    }
    BodyOutcome outcome = interpretBody(funcDecl->body, env, program, line);
    return outcome.kind == BodyOutcome::Kind::Returned ? outcome.value : Expr{};
}

CtfeEvaluator::BodyOutcome CtfeEvaluator::interpretBody(const std::vector<Stmt>& body, Env& env, const Program& program, int32_t line) {
    auto bind = [&](const std::string& name, const Expr& initializer) -> bool {
        Expr value = evalExpr(initializer, env, program);
        if (!value) return false;
        env.set(name, value);
        return true;
    };
    auto branchOf = [&](const Expr& condition, const std::vector<Stmt>& thenBranch, const std::optional<std::vector<Stmt>>& elseBranch,
                        std::optional<BodyOutcome>& out) -> bool {
        // Returns false to continue with the next statement; sets [out] to return.
        Expr cond = evalExpr(condition, env, program);
        const ExprBoolLiteral* b = as<ExprBoolLiteral>(cond);
        if (!cond || !b) {
            out = BodyOutcome::unknown();
            return true;
        }
        const std::vector<Stmt>* branch = b->value ? &thenBranch : (elseBranch ? &*elseBranch : nullptr);
        if (!branch) return false;
        BodyOutcome result = interpretBody(*branch, env, program, line);
        if (result.kind != BodyOutcome::Kind::FellThrough) {
            out = result;
            return true;
        }
        return false;
    };
    for (const Stmt& stmt : body) {
        std::optional<BodyOutcome> out;
        switch (stmt.kind()) {
            case StmtKind::Import: break;
            case StmtKind::VarDecl:
                if (!bind(cast<StmtVarDecl>(stmt).name, cast<StmtVarDecl>(stmt).initializer)) return BodyOutcome::unknown();
                break;
            case StmtKind::FinDecl:
                if (!bind(cast<StmtFinDecl>(stmt).name, cast<StmtFinDecl>(stmt).initializer)) return BodyOutcome::unknown();
                break;
            case StmtKind::LetDecl:
                if (!bind(cast<StmtLetDecl>(stmt).name, cast<StmtLetDecl>(stmt).initializer)) return BodyOutcome::unknown();
                break;
            case StmtKind::InlineFin:
                if (!bind(cast<StmtInlineFin>(stmt).name, cast<StmtInlineFin>(stmt).initializer)) return BodyOutcome::unknown();
                break;
            case StmtKind::InlineLet:
                if (!bind(cast<StmtInlineLet>(stmt).name, cast<StmtInlineLet>(stmt).initializer)) return BodyOutcome::unknown();
                break;
            case StmtKind::InlineVar:
                if (!bind(cast<StmtInlineVar>(stmt).name, cast<StmtInlineVar>(stmt).initializer)) return BodyOutcome::unknown();
                break;
            case StmtKind::Assignment:
                if (!bind(cast<StmtAssignment>(stmt).name, cast<StmtAssignment>(stmt).value)) return BodyOutcome::unknown();
                break;
            case StmtKind::InlineAssignment:
                if (!bind(cast<StmtInlineAssignment>(stmt).name, cast<StmtInlineAssignment>(stmt).value)) return BodyOutcome::unknown();
                break;
            case StmtKind::Return: {
                const StmtReturn& s = cast<StmtReturn>(stmt);
                Expr value = s.value ? evalExpr(s.value, env, program) : Expr{};
                return value ? BodyOutcome::returned(value) : BodyOutcome::unknown();
            }
            case StmtKind::If: {
                const StmtIf& s = cast<StmtIf>(stmt);
                if (branchOf(s.condition, s.thenBranch, s.elseBranch, out)) return *out;
                break;
            }
            case StmtKind::InlineIf: {
                const StmtInlineIf& s = cast<StmtInlineIf>(stmt);
                if (branchOf(s.condition, s.thenBranch, s.elseBranch, out)) return *out;
                break;
            }
            case StmtKind::DeepInlineIf: {
                const StmtDeepInlineIf& s = cast<StmtDeepInlineIf>(stmt);
                if (branchOf(s.condition, s.thenBranch, s.elseBranch, out)) return *out;
                break;
            }
            case StmtKind::InlineFor: {
                const StmtInlineFor& s = cast<StmtInlineFor>(stmt);
                const ExprRange* range = as<ExprRange>(s.iterable);
                if (!range) return BodyOutcome::unknown();
                const ExprIntLiteral* from = as<ExprIntLiteral>(evalExpr(range->from, env, program));
                if (!from) return BodyOutcome::unknown();
                int64_t start = from->value;
                const ExprIntLiteral* to = as<ExprIntLiteral>(evalExpr(range->to, env, program));
                if (!to) return BodyOutcome::unknown();
                for (int64_t i : constantProgression(*range, start, to->value)) {
                    env.set(s.name, intLiteral(arena, i, s.line));
                    BodyOutcome result = interpretBody(s.body, env, program, line);
                    if (result.kind != BodyOutcome::Kind::FellThrough) return result;
                }
                break;
            }
            case StmtKind::InlineBlock:
            case StmtKind::DeepInlineBlock:
            case StmtKind::Scope: {
                const std::vector<Stmt>& inner = is<StmtInlineBlock>(stmt)       ? cast<StmtInlineBlock>(stmt).body
                                                 : is<StmtDeepInlineBlock>(stmt) ? cast<StmtDeepInlineBlock>(stmt).body
                                                                                 : cast<StmtScope>(stmt).body;
                BodyOutcome result = interpretBody(inner, env, program, line);
                if (result.kind != BodyOutcome::Kind::FellThrough) return result;
                break;
            }
            // In CTCE interpretation, noinline is just pass-through
            case StmtKind::NoInline: {
                BodyOutcome result = interpretBody({cast<StmtNoInline>(stmt).stmt}, env, program, line);
                if (result.kind != BodyOutcome::Kind::FellThrough) return result;
                break;
            }
            case StmtKind::InlineAssert: {
                Expr cond = evalExpr(cast<StmtInlineAssert>(stmt).condition, env, program);
                if (!cond) return BodyOutcome::unknown();
                const ExprBoolLiteral* b = as<ExprBoolLiteral>(cond);
                if (b && !b->value) return BodyOutcome::unknown();
                break;
            }
            // no-op during interpretation
            case StmtKind::InlineTrace: break;
            // Side effects, runtime storage, control flow: not evaluable at compile time.
            default: return BodyOutcome::unknown();
        }
    }
    return BodyOutcome::fellThrough();
}

Expr CtfeEvaluator::evalExpr(const Expr& expr, const Env& env, const Program& program) {
    auto lookup = [&](const std::string& name) -> Expr {
        const Expr* value = env.get(name);
        return value ? *value : Expr{};
    };
    switch (expr.kind()) {
        case ExprKind::InferredMember: {
            const ExprInferredMember& m = cast<ExprInferredMember>(expr);
            return m.scopeRead ? lookup(m.scopeRead->name) : Expr{};
        }
        case ExprKind::IntLiteral:
        case ExprKind::DoubleLiteral:
        case ExprKind::StringLiteral:
        case ExprKind::BoolLiteral:
        case ExprKind::CharLiteral: return expr;
        case ExprKind::Identifier: return lookup(cast<ExprIdentifier>(expr).name);
        case ExprKind::UpperScopeAccess: return lookup(cast<ExprUpperScopeAccess>(expr).name);
        case ExprKind::Grouping: return evalExpr(cast<ExprGrouping>(expr).expr, env, program);
        case ExprKind::TryPropagate: return evalExpr(cast<ExprTryPropagate>(expr).expr, env, program);
        // A seal says who may write the field; the value is what it wraps.
        case ExprKind::Seal: return evalExpr(cast<ExprSeal>(expr).value, env, program);
        case ExprKind::IfExpr: {
            const ExprIfExpr& i = cast<ExprIfExpr>(expr);
            Expr condition = evalExpr(i.condition, env, program);
            const ExprBoolLiteral* b = as<ExprBoolLiteral>(condition);
            if (!b) return Expr{};
            return b->value ? evalExpr(i.thenExpr, env, program) : evalExpr(i.elseExpr, env, program);
        }
        case ExprKind::Unary: {
            const ExprUnary& u = cast<ExprUnary>(expr);
            Expr operand = evalExpr(u.operand, env, program);
            if (!operand) return Expr{};
            return tryFoldUnary(u.op, operand, u.line);
        }
        case ExprKind::Binary: {
            const ExprBinary& b = cast<ExprBinary>(expr);
            Expr left = evalExpr(b.left, env, program);
            if (!left) return Expr{};
            Expr right = evalExpr(b.right, env, program);
            if (!right) return Expr{};
            return tryFoldBinary(left, b.op, right, b.line);
        }
        case ExprKind::Call: {
            const ExprCall& c = cast<ExprCall>(expr);
            std::vector<Expr> evalArgs;
            for (const Expr& a : c.args) {
                Expr value = evalExpr(a, env, program);
                if (!value) return Expr{};
                evalArgs.push_back(value);
            }
            return tryEvalCall(c.callee, evalArgs, program, c.line);
        }
        // Everything else is not a compile-time value.
        default: return Expr{};
    }
}

}  // namespace azora
