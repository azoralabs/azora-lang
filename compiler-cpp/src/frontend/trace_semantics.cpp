// Port of TraceSemantics.kt: replaces free uses of a trace body's implicit
// `it` receiver.
#include "frontend/trace_semantics.h"

namespace azora {

namespace {

Expr bind(Arena& arena, const Expr& e, const Expr& receiver, bool interpolation);

std::vector<Expr> bindAll(Arena& arena, const std::vector<Expr>& items, const Expr& receiver, bool interpolation) {
    std::vector<Expr> out;
    for (const Expr& item : items) out.push_back(bind(arena, item, receiver, interpolation));
    return out;
}

template <class K>
Expr remake(Arena& arena, K copy) {
    return make(arena, std::move(copy));
}

Expr bind(Arena& arena, const Expr& e, const Expr& receiver, bool interpolation) {
    auto b = [&](const Expr& inner) { return bind(arena, inner, receiver, interpolation); };
    auto bOpt = [&](const Expr& inner) { return inner ? b(inner) : inner; };
    switch (e.kind()) {
        case ExprKind::None: return e;
        case ExprKind::InferredMember: return e;
        case ExprKind::MapEntryArg: {
            ExprMapEntryArg c = cast<ExprMapEntryArg>(e);
            c.key = b(c.key);
            c.value = b(c.value);
            return remake(arena, c);
        }
        case ExprKind::Identifier: {
            const ExprIdentifier& id = cast<ExprIdentifier>(e);
            if (id.name != "it") return e;
            if (!interpolation) return receiver;
            TypeRefNamed string;
            string.name = "String";
            ExprCast castExpr;
            castExpr.expr = receiver;
            castExpr.targetType = make(arena, string);
            castExpr.line = id.line;
            castExpr.column = id.column;
            return make(arena, castExpr);
        }
        case ExprKind::IntLiteral:
        case ExprKind::DoubleLiteral:
        case ExprKind::CharLiteral:
        case ExprKind::StringLiteral:
        case ExprKind::BoolLiteral:
        case ExprKind::NullLiteral:
        case ExprKind::UpperScopeAccess:
        case ExprKind::Inject:
            return e;
        case ExprKind::Binary: {
            ExprBinary c = cast<ExprBinary>(e);
            c.left = b(c.left);
            c.right = b(c.right);
            return remake(arena, c);
        }
        case ExprKind::Unary: {
            ExprUnary c = cast<ExprUnary>(e);
            c.operand = b(c.operand);
            return remake(arena, c);
        }
        case ExprKind::IncDec: {
            ExprIncDec c = cast<ExprIncDec>(e);
            c.target = b(c.target);
            return remake(arena, c);
        }
        case ExprKind::Call: {
            ExprCall c = cast<ExprCall>(e);
            c.args = bindAll(arena, c.args, receiver, interpolation);
            return remake(arena, c);
        }
        case ExprKind::Grouping: {
            ExprGrouping c = cast<ExprGrouping>(e);
            c.expr = b(c.expr);
            return remake(arena, c);
        }
        case ExprKind::Range: {
            ExprRange c = cast<ExprRange>(e);
            c.from = b(c.from);
            c.to = b(c.to);
            return remake(arena, c);
        }
        case ExprKind::ArrayLiteral: {
            ExprArrayLiteral c = cast<ExprArrayLiteral>(e);
            c.elements = bindAll(arena, c.elements, receiver, interpolation);
            return remake(arena, c);
        }
        case ExprKind::SetLiteral: {
            ExprSetLiteral c = cast<ExprSetLiteral>(e);
            c.elements = bindAll(arena, c.elements, receiver, interpolation);
            return remake(arena, c);
        }
        case ExprKind::Index: {
            ExprIndex c = cast<ExprIndex>(e);
            c.target = b(c.target);
            c.index = b(c.index);
            return remake(arena, c);
        }
        case ExprKind::Member: {
            ExprMember c = cast<ExprMember>(e);
            c.target = b(c.target);
            return remake(arena, c);
        }
        case ExprKind::MethodCall: {
            ExprMethodCall c = cast<ExprMethodCall>(e);
            c.target = b(c.target);
            c.args = bindAll(arena, c.args, receiver, interpolation);
            return remake(arena, c);
        }
        case ExprKind::StringTemplate: {
            ExprStringTemplate c = cast<ExprStringTemplate>(e);
            for (StringTemplatePart& part : c.parts) {
                if (!part.isLiteral) part = exprPart(bind(arena, part.expr, receiver, true));
            }
            return remake(arena, c);
        }
        case ExprKind::TupleLit: {
            ExprTupleLit c = cast<ExprTupleLit>(e);
            c.elements = bindAll(arena, c.elements, receiver, interpolation);
            return remake(arena, c);
        }
        case ExprKind::VariantLit: {
            ExprVariantLit c = cast<ExprVariantLit>(e);
            c.elements = bindAll(arena, c.elements, receiver, interpolation);
            return remake(arena, c);
        }
        case ExprKind::TupleAccess: {
            ExprTupleAccess c = cast<ExprTupleAccess>(e);
            c.target = b(c.target);
            return remake(arena, c);
        }
        case ExprKind::CatchExpr: {
            ExprCatchExpr c = cast<ExprCatchExpr>(e);
            c.expr = b(c.expr);
            c.fallback = b(c.fallback);
            return remake(arena, c);
        }
        case ExprKind::TryPropagate: {
            ExprTryPropagate c = cast<ExprTryPropagate>(e);
            c.expr = b(c.expr);
            return remake(arena, c);
        }
        case ExprKind::Seal: {
            ExprSeal c = cast<ExprSeal>(e);
            c.value = b(c.value);
            return remake(arena, c);
        }
        case ExprKind::IfExpr: {
            ExprIfExpr c = cast<ExprIfExpr>(e);
            c.condition = b(c.condition);
            c.thenExpr = b(c.thenExpr);
            c.elseExpr = b(c.elseExpr);
            return remake(arena, c);
        }
        // A nested lambda owns its own implicit receiver.
        case ExprKind::Lambda: return e;
        case ExprKind::NamedArg: {
            ExprNamedArg c = cast<ExprNamedArg>(e);
            c.value = b(c.value);
            return remake(arena, c);
        }
        case ExprKind::NullCoalesce: {
            ExprNullCoalesce c = cast<ExprNullCoalesce>(e);
            c.left = b(c.left);
            c.right = b(c.right);
            return remake(arena, c);
        }
        case ExprKind::SafeMember: {
            ExprSafeMember c = cast<ExprSafeMember>(e);
            c.target = b(c.target);
            return remake(arena, c);
        }
        case ExprKind::Cast: {
            ExprCast c = cast<ExprCast>(e);
            c.expr = b(c.expr);
            return remake(arena, c);
        }
        case ExprKind::InlineForArgs: {
            ExprInlineForArgs c = cast<ExprInlineForArgs>(e);
            c.iterable = b(c.iterable);
            c.body = b(c.body);
            return remake(arena, c);
        }
        case ExprKind::IsCheck: {
            ExprIsCheck c = cast<ExprIsCheck>(e);
            c.expr = b(c.expr);
            return remake(arena, c);
        }
        case ExprKind::InCheck: {
            ExprInCheck c = cast<ExprInCheck>(e);
            c.value = b(c.value);
            c.collection = b(c.collection);
            return remake(arena, c);
        }
        case ExprKind::MapLit: {
            ExprMapLit c = cast<ExprMapLit>(e);
            for (auto& entry : c.entries) {
                Expr key = b(entry.first);
                Expr value = b(entry.second);
                entry = {key, value};
            }
            return remake(arena, c);
        }
        case ExprKind::Alloc: {
            ExprAlloc c = cast<ExprAlloc>(e);
            c.value = b(c.value);
            return remake(arena, c);
        }
        case ExprKind::Deref: {
            ExprDeref c = cast<ExprDeref>(e);
            c.target = b(c.target);
            return remake(arena, c);
        }
        case ExprKind::Isolated: {
            ExprIsolated c = cast<ExprIsolated>(e);
            c.value = b(c.value);
            return remake(arena, c);
        }
        case ExprKind::Await: {
            ExprAwait c = cast<ExprAwait>(e);
            c.value = b(c.value);
            return remake(arena, c);
        }
        case ExprKind::Spread: {
            ExprSpread c = cast<ExprSpread>(e);
            c.array = b(c.array);
            return remake(arena, c);
        }
        case ExprKind::MetaInvoke: {
            ExprMetaInvoke c = cast<ExprMetaInvoke>(e);
            c.args = bindAll(arena, c.args, receiver, interpolation);
            return remake(arena, c);
        }
        case ExprKind::Slice: {
            ExprSlice c = cast<ExprSlice>(e);
            c.target = b(c.target);
            c.start = bOpt(c.start);
            c.stop = bOpt(c.stop);
            c.step = bOpt(c.step);
            return remake(arena, c);
        }
    }
    AZORA_UNREACHABLE();
}

}  // namespace

Expr bindTraceReceiver(Arena& arena, const Expr& expr, const Expr& receiver) { return bind(arena, expr, receiver, false); }

}  // namespace azora
