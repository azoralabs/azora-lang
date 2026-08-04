1. Replace "zone" keyword with "realm" keyword
2. Replace "Real" type with "Double" type
3. Replace "deco" keyword with "annot" keyword
4. Replace "meta" keyword with "macro" keyword
5. Replace "kind" keyword with "variant" keyword
6. Replace "mod" keyword with "module" keyword
7. Replace "use" keyword with "import" keyword
8. Add "scope" keyword and we will use it like this:
```azora
func add() in {} out {} scope {} // scope here, no realm
// we will use realm only in global scope (similar to c++ namespace)
func main() {
    fin x = 4
    scope { // here we use "scope", not "realm"
        fin x = "Hello"
        std::println(x) // Hello
        std::println(::x) // 4
    }
    // We already have this implemented but we need to use "scope" keyword in this case
}
```
9. Replace "fail" keyword with "error" keyword
10. Add "union" keyword and will be similar to C untagged unions
11. Remove "type" keyword and we raplace:
```azora
@Since("0.0.1")
type promote(T: ...Type) where T.length >= 2 && T is Number {
    let Result: Type = T.0
    for Candidate in T[1...] {
        Result = if Candidate::rank > Result::rank { Candidate } else { Result }
    }
    return Result
}
```
with
```azora
// deepinline functions will be always starting with big letter
// also deepinline func to be possible if we have parameters
@Since("0.0.5")
deepinline prop Promote<...T>: Type where T.length >= 2 && T is Number {
    var result: Type = T.0 // we never infer type, we alyaws write it explicitely
    for candidate: Type in T[1...] { // again, we do not auto infer
        if candidate::rank > result::rank {
            result = candidate
        }
    }
    return result
    
}
//where the language defines:
//T → the type varargs.
//T.length → number of types.
//T.0 → first type.
//T[1...] → slice of the remaining types.
//T is Number → every type in the varargs satisfies Number.
Then usage is very natural:
func add<T, U>(x: T, y: U): Promote<T, U> {} // no more ! syntax like it was in type, so remove that
// We already have bridge pack Type in core.az, and when we use it as return Type then we must return a Type, not a valie
// We cannot use Type as a parameter ever
// We cannot use "var x: Type = Int" outside an inline scope, here we are inside deepinline so everything gets evaluated at compile time so it works
```
12. We must use "var", "let", "val", "fin" keywords, all usable in inline scope and runtime scope:
var - mutable binding - mutable value - supports mutable borrow (!) - supports immutable borrow (&)
let - immutable binding - mutable value - supports mutable borrow (!) - supports immutable borrow (&)
val - mutable binding - immutable value - does not support mutable borrow (!) - supports immutable borrow (&)
fin - immutable binding - immutable value - does not support mutable borrow (!) - supports immutable borrow (&)