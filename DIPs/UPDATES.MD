```azora
func first(a: String&, b: String&): String&[a]
```
It immediately reads as:
"Returns a String& borrowed from a."
Instead of:
"Returns a String& with lifetime 'a."
Those are not the same abstraction. The former is what the programmer cares about.

```azora
func first(a: String&, b: String&): String&[a] {
    return a
}
func first(a: String&, b: String&): String&[a]
func get(map: Map<K, V>&, key: K&): V&[map]
func value[self: Self&](): Int&[self]
func value[self: Self!](): Int![self]
```

It becomes part of the API contract
Someone reading only the signature immediately knows:
```azora
func first(a: String&, b: String&): String&[a]
```
cannot return a reference to b.
That's valuable documentation as well as compiler information.
My only suggestion
I'd make it optional.
Most functions don't need it:
```azora
func size[self: Self&](): Int
```
No borrow is returned, so nothing to annotate.
Only functions returning borrows would use it:
```azora
func front[self: Self&](): T&[self]
func data[self: Self!](): Byte![self]
func first(a: String&, b: String&): String&[a]
```
I actually think this is a stronger abstraction than Rust's lifetime parameters because it exposes the borrow origin, which is what users reason about, while leaving lifetime inference entirely inside the compiler.

If you return both borrows independently, I would keep each origin attached to its own type.
For example:
```azora
func pair(a: String&, b: String&): std::tupleOf(String&[a], String&[b])
```

This is very explicit:
first element borrows from a
second element borrows from b
I think that's the cleanest solution.

If you return one borrow that may come from either, then you need to express that relationship.
For example:
```azora
func choose(a: String&, b: String&): String&[a, b]
```
meaning:
The returned borrow originates from either a or b.
The compiler would internally compute the appropriate lifetime.meaning:
