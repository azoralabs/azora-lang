// Port of AstValidator.kt: structural checks after parsing and before
// semantic analysis (duplicate functions and parameters, missing returns,
// thread-unsafe globals, stability decorators, unknown decorators).
#pragma once

#include "base/ordered.h"
#include "frontend/ast.h"

#include <string>
#include <vector>

namespace azora {

/**
 * A decorator a library declares: the module declaring it, and whether that
 * module is in scope everywhere (an `exposed` module needs no import).
 */
struct LibraryDecorator {
    std::string module;
    bool alwaysInScope = false;
};

struct AstValidator {
    /**
     * The decorators the libraries the program was compiled against declare,
     * by name; null for a fragment validated on its own, where an undeclared
     * decorator is left to the full compilation.
     */
    explicit AstValidator(const OrderedMap<std::string, LibraryDecorator>* libraryDecorators = nullptr)
        : libraryDecorators(libraryDecorators) {}

    /** Human-readable errors for [program] (empty if the AST is valid). */
    std::vector<std::string> validate(const Program& program);

    const OrderedMap<std::string, LibraryDecorator>* libraryDecorators;
    /** Decorators declared in the program, its own and those injected from libraries. */
    OrderedSet<std::string> customDecos;
};

}  // namespace azora
