We add to module std/functional/core the following:

```
/**
 * @generic PARAMS: a list with function parameters
 * @generic RETURN_TYPE: the function return type
 * @generic RECEIVERS: a list with function contextual parameters
 */
@SinceAzora("0.0.4")
bridge pack Func<[...PARAMS], RETURN_TYPE, [...RECEIVERS]>

/**
 * @generic PARAMS: a list with task parameters
 * @generic RETURN_TYPE: the task return type
 * @generic RECEIVERS: a list with task contextual parameters
 */
@SinceAzora("0.0.4")
bridge pack Task<[...PARAMS], RETURN_TYPE, [...RECEIVERS]>

/**
 * @generic PARAMS: a list with flow parameters
 * @generic RETURN_TYPE: the flow return type
 * @generic RECEIVERS: a list with flow contextual parameters
 */
@SinceAzora("0.0.4")
bridge pack Flow<[...PARAMS], RETURN_TYPE, [...RECEIVERS]>
```

They are all implemented in compiler and with bridge we only make use of them in our code, then we define:

```
meta type {
    Func[...$receivers](...$params) -> $returnType
        => Func<[...$params], $returnType, [...$receivers]>

    Func(...$params) -> $returnType
        => Func<[...$params], $returnType, []>

    Func[...$receivers] -> $returnType
        => Func<[], $returnType, [...$receivers]>
}
```

same for Task and Flow

So we can use like this:

```
pack P {
    fin add: Func[Int, Int] -> Int =
        func { x: Int, y: Int -> x + y }

    fin sub: Func(Int, Int) -> Int =
        func(x: Int, y: Int) {
            return x - y
        }

    fin compute: Func[Logger](Int, Int) -> Unit =
        func(x: Int, y: Int) { logger ->
            logger.log(x * y)
        }
}
```

and we can call them like this:

```
func main() {
    fin p = P()
    fin logger = Logger()
    
    with [2, 3] {
        fin r1 = p.add()
        println(r1) // prints 5
    }
    
    fin r2 = p.add(2, 3) // also ok, we can use contextual params as normal ones in call
    
    fin r3 = p.sub(9, 7) // r3 will be 2
    
    with logger {
        fin r4 = p.compute(2, 5)
    }
    
    fin r5 = p.compute(2, 5, logger)
}
```

or if we are in that scope already:

```
impl Logger {
    func log(p: P) { ref self ->
        p.computer(2, 3) // self is automatically passed
    }
}
```