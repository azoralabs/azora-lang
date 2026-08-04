Here the return type if not specified must be Unit

```azora
func hello() {
    std::println("Hello!")
}
```

```azora
func div(x: Int, y: Int) ?! MathError {
    if y == 0 { return .DivisionByZero }
    std::println(x / y)
}
```

Here it must be specified because we return Int

```azora
func div(x: Int, y: Int): Int ?! MathError {
    return if y == 0 { .DivisionByZero } else { x / y }
}
```

Same rule applies on prop (so on func and prop we do not infer return type).

On lambdas, we infer return type.
