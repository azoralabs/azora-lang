```
import std.dynamic
import std.io
import std.time

use std::{println, runScript, ScriptError}

func main(): Unit!ScriptError = loop by 1.seconds {
    fin result: Any = try runScript("script.az", entry: "h1")

    when result {
        is Int -> println(result) // Smart-cast to Int
        else -> println("Unknown return type")
    }
}
```

```
import std.io

func h1(): Int {
    std::println("Hi")
    return 7
}
```