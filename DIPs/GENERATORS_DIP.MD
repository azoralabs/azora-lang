Remove flow, yield and launch keywords

Implement this in std.concurrency.generators - make it similar to kotlin:

and model streams as a standard-library builder and type, like Kotlin:
examples:

```azora
func countdown(from: Int): Flow<Int> = flow {
    reverse for n in from..0 {
        emit(n)
        delay(1.seconds) // seconds to be in std.time an extension to Int (prop) and be type Duration, something like this: "func seconds[self: Int]() = Duration(self)"
    }
}
```

The builder body is suspend-capable because flow {} accepts an asynchronous producer lambda.
Collection:

```azora
async func main() {
    countdown(5).collect { number ->
        io::println(number)
    }
}
```

That gives you the clean Kotlin distinction:

```azora
func values(): Sequence<Int> = sequence {
    yield(1)
    yield(2)
}

func events(): Flow<Event> = flow {
    emit(readEvent())
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
func observeMessages(): Flow<Message>
```

I would also keep Kotlin’s familiar builder vocabulary:

```azora
flow { emit(value) }
sequence { yield(value) }
```

This is clearer than adding a third declaration keyword and keeps asynchronous streams as a library abstraction rather than baking them deeply into the language.
