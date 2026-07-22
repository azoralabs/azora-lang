```
var a = 0
var b = 1
var c = 2
```

must be possible to be written in one line like this:

```
var [a, b, c] = [0, 1, 2]
var [a, b, c]: [Int, Int, Int] = [0, 1, 2]
```

[] is not tuple, is not array, is that compiler [] that we use on types
same to be available to fin, ref; [] is like a compiler grouping

Also make this possible
```
[var a, fin b, var c] = [0, 1, 2]
[var a, fin b, var c]: [Int, Int, Int] = [0, 1, 2]
```

And this
```
var [x, y] = [3, 4.6]
var [x, y]: [Int, Real] = [3, 4.6]
[x, y] = [2, 9.0]
[x, y] += [1, 4.5]
[x, y] += 1 // both increase by one, smart cast
[x, y]++ //both increase by one, smart cast
var [x, y, z] = 7 // they are all initialized with 7
var [x, y, z]: [Int, Real, Long] = 7 // All with 7, smart cast
```

------------

Add positional parameters the kotlin way but when calling use : instead of =

```
func x(a: Int, b: Real) {}

func main() {
    x(b: 3.4, a: 1)
    x(4, b: 5.6)
    x(a: 1, 8.5)
    x(a: 0, b: 3.2)
    x(9, 7.3)
}
```

------------

```
@Experimental(sinceAzora: "0.0.1")
func min<T>(a: T, b: T): T where T impl oper< {
    return if a < b { a } else { b }
}
```

```
This must be possible:

@Experimental(sinceAzora: "0.0.1")
func min<T>(a: T, b: T): T where T: oper< {
    return if a < b { a } else { b }
}

@Experimental(sinceAzora: "0.0.1")
func min<T>(a: T, b: T): T where T: oper< && T: oper> && T: oper<= && T: oper>= {
    return if a < b { a } else { b }
}

@Experimental(sinceAzora: "0.0.1")
func min<T>(a: T, b: T): T where T: [oper<, oper>, oper<=, oper>=] {
    return if a < b { a } else { b }
}

But we also want this in traits.az:

@SinceAzora("0.0.5")
typealias Comparable = [oper<, oper>, oper<=, oper>=]

@SinceAzora("0.0.5")
typealias Equtable = [oper==, oper!=]

@SinceAzora("0.0.5")
typealias Shiftable = [oper<<, oper>>]

@SinceAzora("0.0.5")
typealias Iterable = [oper.., oper..<, oper reverse.., oper reverse..<]

@Experimental(sinceAzora: "0.0.1")
func min<T>(a: T, b: T): T where T: Comparable {
    return if a < b { a } else { b }
}
```