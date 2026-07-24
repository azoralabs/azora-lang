```
// will make code (function call, pack instantiation) callable in safe code even if it contains unsafe code
// add in std.core
@Experimental(sinceAzora: "0.0.4")
bridge deco Trusted for [.Pack, .Func] // add more
```

```
// pointers should not be allowed to be used in safe context so we have to declare the pack as unsafe
@Experimental(sinceAzora: "0.0.4")
@Trusted
bridge unsafe pack Array<T, N: Int> {
    /**
     * Pointer to the first contiguous element.
     *
     * The pointer is stable for the lifetime of a fixed array. Bounds are
     * not carried by the pointer itself; use `size` before pointer indexing.
     */
    fin data: T*
}

impl zone for Array {
    /**
     * Pre-allocates an array of `count` slots.
     *
     * Compiler-provided (`bridge`): each backend supplies the storage allocation
     * directly, so there is no Azora body. Used as a low-level building block by
     * the functional combinators that grow result arrays.
     *
     * @param T The element type.
     * @param count The number of slots to allocate; must be non-negative.
     * @return An `Array<T>` with `count` slots.
     */
    @Experimental(sinceAzora: "0.0.4")
    @Trusted
    bridge unsafe func fill<T>(count: Int): Array<T>
}
```

"out" keyword must be used only in contracts and generics covariant, no more use inside function parameters

// Structure
@[DECORATORS]
VISIBILITY SAFETY pack NAME<...GENERICS> where [...CLAUSE] { ...RECEIVERS -> ...FIELDS }

// Function
@[DECORATORS]
VISIBILITY SAFETY func NAME<...GENERICS>(...PARAMS)[...RECEIVERS]: RETURN_TYPE ?! [...FAILS]
where [...CLAUSE]

// Function with body
@[DECORATORS]
VISIBILITY SAFETY func NAME<...GENERICS>(...PARAMS): RETURN_TYPE ?! [...FAILS]
where [...CLAUSE] { ...RECEIVERS -> }

// Function with body and contracts
@[DECORATORS]
VISIBILITY SAFETY func NAME<...GENERICS>(...PARAMS): RETURN_TYPE ?! [...FAILS]
where [...CLAUSE]
in { ...RECEIVERS -> }
out { it, ...RECEIVERS -> }
zone { ...RECEIVERS -> }