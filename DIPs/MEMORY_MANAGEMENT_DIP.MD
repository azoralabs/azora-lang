Remove "deref" keyword, remove "drop" keyword, replace "drop" with "purge"

```azora

import std.memory::*

pack User {
    var name: String
    var age: Int
}

func main() {
    // stack allocation
    fin user1 = User("Andrew", 34)
    
    // unsafe heap allocation
    unsafe {
        fin user2: User* = alloc* User("Barni", 23) // fin user2 = alloc* User("Barni", 23)
        purge user2 // purge keyword to deallocate memory
        // cannot use here anymore
 }

    // c++ unique_ptr, make_unique / rust Box::new
    fin user3: Unique<User> = uniqueOf("Fred", 41)
    // or fin user3 = uniqueOf<User>("Fred", 41)

    // c++ shared_ptr, make_shared / rust Rc::new
    fin user4: Shared<User> = sharedOf("Fred", 41)
    // or fin user4 = sharedOf<User>("Fred", 41)

    // c++ shared_ptr, make_shared / rust Arc::new
    fin user5: SyncShared<User> = syncSharedOf("Fred", 41)
    // or fin user5 = syncSharedOf<User>("Fred", 41)
}
```

As smart pointers we have Unique, Shared, Atomic and Weak
And we also have Slice

// Const raw pointer.
```azora
unsafe func main() {
    fin x: Int* = alloc* Int(4)
    defer purge x

    println(x.*)         // ✅ read through pointer
    x.* = 10             // ❌ cannot mutate through Int*
    x = alloc* Int(8)    // ❌ `x` is a fin binding

    var y: Int* = alloc* Int(4)
    defer purge y

    println(y.*)         // ✅ read through pointer
    y.* = 10             // ❌ cannot mutate through Int*
    y = alloc* Int(8)    // ✅ ok, `y` is a var binding
}
```

// Mutable raw pointer.
```azora
unsafe func main() {
    fin x: Int^ = alloc^ Int(4)
    defer purge x

    println(x.^)         // ✅ read through pointer
    x.^ = 10             // ✅ ok, can mutate through Int^
    x = alloc^ Int(8)    // ❌ `x` is a fin binding

    var y: Int^ = alloc^ Int(4)
    defer purge y

    println(y.^)         // ✅ read through pointer
    y.^ = 10             // ✅ ok, can mutate through Int^
    y = alloc^ Int(8)    // ✅ ok, `y` is a var binding
}
```