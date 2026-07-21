Add "use zone" and local "import" and custom path import and custom "use zone"

Example 1:

```
module m1

zone x {
    fin a = 5
}

zone y {
    fin b = "Hi"
}

```

Use 1:

```
module main

import m1 // Everything top level decl from m1 is imported (in this case x and y zones), equivalent to "import m1.{x, y}"

func main() {
    fin f1 = x::a
    fin f2 = y::b
}

```

Use 2:

```
module main

import m1.x // Only zone x is imported, zone y wont be visible and accessible

func main() {
    fin f1 = x::a
    fin f2 = m1.y::b
}

```

Use 2:

```
module main

import m1.x // Only zone x is imported, zone y wont be visible and accessible

func main() {
    fin f1 = x::a
    fin f2 = m1.y::b
}

```

Use 3:

```
module main

func main() {
    import m1 // local imports possible, here we cannot have "export import" ofc

    fin f1 = x::a
    fin f2 = y::b
}

```

Example 2:

```
module m2

zone x {
    fin a = 5
}

zone y {
    fin b = "Hi"
    
    zone z {
        fin c = true
        fin d = false
    }
}

```

Use 1:

```
module main

import m2

use x
use y::z // similar to "use y::z::{c, d}" if you want to use just some symbols, not all

func main() {
    fin f1 = a     // we can directly access "a" because we "use x"
    fin f2 = y::b  // we do not have "use y" so "b" is not visible directly"
    fin f3 = c     // we can directly access "c" because we "use y::z"
}

```

Use 2:

```
module main

import m2

use y::z::c

func main() {
    fin f1 = c  // only "c" is visible from "zone z", "d" is not
    fin f2 = y::z::d // we must write the full zone path to access it
}

```

Use 3:

```
module main

import m2

func main() {
    use y::z::d  // local use
    fin f1 = d
}

```

Use 4:

```
module main

import m2

func main() {
    with y::z {  // c and d will be available only inside this scope
        fin f1 = c
        fin f2 = d
    }
    
    fin f3 = y::z::c // here we need again the full path
}

```

"with" can also be used globally like this:

```
with y::z {
    inline fin f0 = c
    func f1() {}
    func f2() {}
}
```

make sure it works with "friend zone"s to and "use friend zone" which will automatically use it