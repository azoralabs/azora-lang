This file is compiled to LLVM
```
import std.dynamic
import std.io
import std.time

use {println, runScript, ScriptError}

func main(): Unit ?! ScriptError = loop by 1.seconds {
    fin result: Any = try runScript("script.az", entry: "h1")

    when result {
        is Int -> println(result) // Smart-cast to Int
        else -> println("Unknown return type")
    }
}
```

This is an .az file, we are using it as a script for the interpreted injected in the LLVM
We should have an option if we inject the interpreter on not, that will give us access to std.dynamic
```
import std.io

func h1(): Int {
    println("Hi")
    return 7
}
```