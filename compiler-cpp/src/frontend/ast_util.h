// Functions Ast.kt declares beside the node classes: TypeRef's toString(),
// the encodings of type-function and named type-macro calls, and the small
// Kotlin text helpers the frontend uses on names.
#pragma once

#include "frontend/ast.h"

#include <string>
#include <utility>
#include <vector>

namespace azora {

/** Kotlin `TypeRef.toString()` (every TypeRef kind overrides it). */
std::string toString(const TypeRef& type);

/** TypeRef.displayName(): the simple name for Named, toString() otherwise. */
std::string displayName(const TypeRef& type);

/** TypeAnnotation.Explicit.name. */
inline std::string annotationName(const TypeAnnotation& a) { return displayName(a.ref); }

/** TypeRef.Failable's constructor, with its `require` checks. */
TypeRefFailable* makeFailable(Arena& arena, const TypeRef& ok, const std::vector<std::string>& errSets);

/** TypeRef.HOLE and isHole: `_`, an argument left to inference. */
inline const char* const TYPE_HOLE = "_";
bool isHole(const TypeRef& type);

/** Kotlin `Expr.Range.constantProgression(from, to)`, as its values. */
std::vector<int64_t> constantProgression(const ExprRange& range, int64_t from, int64_t to);

/** Kotlin `NumericLiteral.value.toString()`. */
std::string numericValueText(const NumericLiteral& literal);

/** Kotlin `name.firstOrNull()?.isUpperCase() == true`. */
bool firstIsUpperCase(const std::string& name);
/** Kotlin `name.firstOrNull()?.isLowerCase() == true`. */
bool firstIsLowerCase(const std::string& name);

/** Kotlin `name.replaceFirstChar { it.uppercase() }`. */
std::string capitalizeFirst(const std::string& name);

/** Kotlin `name.replaceFirstChar { it.lowercase() }`. */
std::string decapitalizeFirst(const std::string& name);

/** `.(args) * count` - the construction and the count it is repeated by, or none. */
std::optional<std::pair<Expr, Expr>> asRepeatedConstruction(const ExprBinary& expr);

/** The name given to a contextual receiver that a lambda inherits rather than declares. */
std::string lambdaReceiverName(int32_t line, int32_t column, int32_t index);

/** Internal encoding for a deferred `Name<...>` type-property call inside a TypeRef. */
namespace TypeFunctionCall {
TypeRefNamed* create(Arena& arena, const std::string& name, const std::vector<TypeRef>& args,
                     const std::vector<Expr>& valueArgs = {});
bool isCall(const TypeRefNamed& ref);
std::string name(const TypeRefNamed& ref);
}  // namespace TypeFunctionCall

/** Internal encoding for a library-defined named type-macro invocation. */
namespace NamedTypeMacroCall {
enum class Form : uint8_t { Prefix, List, Infix };
const char* formName(Form form);
TypeRefNamed* create(Arena& arena, const std::string& name, const std::vector<TypeRef>& args,
                     const std::string& modifier, Form form);
bool isCall(const TypeRefNamed& ref);
std::string modifier(const TypeRefNamed& ref);
Form form(const TypeRefNamed& ref);
std::string name(const TypeRefNamed& ref);
}  // namespace NamedTypeMacroCall

/** Encodes a source path containing both an owning module and a scope-qualified symbol. */
namespace ModuleQualifiedSymbol {
std::string create(const std::string& module, const std::string& symbol);
bool isQualified(const std::string& name);
std::string module(const std::string& name);
std::string symbol(const std::string& name);
}  // namespace ModuleQualifiedSymbol

/** Literals.kt: the two literals the language writes numbers as. */
namespace Literals {
inline const char* const INT = "__int";
inline const char* const UINT = "__uint";
inline const char* const REAL = "__float";
inline bool isKind(const std::string& kind) { return kind == INT || kind == UINT || kind == REAL; }
inline const char* const DEFAULT_INT = "Int";
inline const char* const DEFAULT_FLOAT = "Float";
}  // namespace Literals

/** `Array<T>(a, b)` as the literal it stands for (Literals.kt arrayCallLiteral). */
ExprArrayLiteral* arrayCallLiteral(Arena& arena, const ExprCall& call);
/** `Tuple<A, B>(a, b)` as the tuple literal it stands for, or null for fewer than two elements. */
ExprTupleLit* tupleCallLiteral(Arena& arena, const ExprCall& call);

}  // namespace azora
