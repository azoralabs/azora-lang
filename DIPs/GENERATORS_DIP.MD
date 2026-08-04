Remove flow, yield and launch keywords

Implement this in std.concurrency.generators - make it similar to kotlin:

and model streams as a standard-library builder and type, like Kotlin:
examples:

```azora
func countdown(from: Int): std::Flow<Int> = flow {
    reverse for n in from..0 {
        std::emit(n)
        std::delay(1.seconds) // seconds to be in std.time an extension to Int (prop) and be type Duration, something like this: "func seconds[self: Int]() = Duration(self)"
    }
}
```

The builder body is suspend-capable because flow {} accepts an asynchronous producer lambda.
Collection:

```azora
async func main() {
    countdown(5).collect { number ->
        std::println(number)
    }
}
```

That gives you the clean Kotlin distinction:

```azora
func values(): std::Sequence<Int> = std::sequence {
    std::yield(1)
    std::yield(2)
}

func events(): std::Flow<Event> = std::flow {
    std::emit(readEvent())
}
```

Notice that both declarations remain ordinary funcs. The returned type describes the abstraction:
* Sequence<T> — lazy synchronous values
* Flow<T> — cold asynchronous stream
* async func ...: T — asynchronous computation producing one value
  So the recommended Azora model is:

```azora
func calculate(): Int
async func download(): Bytes
func observeMessages(): std::Flow<Message>
```

I would also keep Kotlin’s familiar builder vocabulary:

```azora
std::flow { std::emit(value) }
std::sequence { std::yield(value) }
```

This is clearer than adding a third declaration keyword and keeps asynchronous streams as a library abstraction rather than baking them deeply into the language.

// new type form:  ([Flow<T>]() -> Unit)

For callback declaration we will use only this syntax build inside the compiler:
```azora
fin f1 = () -> Unit
fin f2 = (Double, Bool) -> Int
fin f3 = (...Double) -> Unit
fin f4 = (Int, ...Double) -> Bool
fin f5 = (T) -> R
fin f6 = [T](S) -> R
fin f7 = [T, Q](...S) -> R
```

```azora
fin f1 = async () -> Unit
fin f2 = async (Double, Bool) -> Int
fin f3 = async (...Double) -> Unit
fin f4 = async (Int, ...Double) -> Bool
fin f5 = async (T) -> R
fin f6 = async [T](S) -> R
fin f7 = async [T, Q](...S) -> R
```

I gave just some examples but all combinations should be possible:
And for callback implementation:

```azora
func main() {
    fin add1 = { x: Int, y: Int -> x + y }
    fin add2 = [self: Vec2&]{ self.x + self.y } // only use in Vec2 context
    fin add3 = [self: Vec2&]{ other: Vec2& -> self + other } // only use in Vec2 context
    fin add4: [Vec2&](Vec2&) -> Vec2 = [self: Vec2&]{ other: Vec2& -> self + other } // only use in Vec2 context
}
```