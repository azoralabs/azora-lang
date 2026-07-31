```azora
mod main

use std.io

prot Area {
    func area[self: Self&](): Real
    prop areaP[self: Self&]: Real
}

pack Model {
    var _width = 0.0
    var _height = 0.0
}

impl Model {
    //typealias Self = Model - not necessary, injected automatically by compiler (keep pack Self in stdlib)
    
    // only available in impl block
    ctor [self: Self!](w: Real, h: Real) { // in this case can mutate
        self._width = w
        self._height = h
        std::println("Constructed")
    }

    // only available in impl block
    dtor [self: Self&] { // in this case only observes
        std::println("Destructed")
    }

    // we us it in string interpolation: std::println("$m")
    oper$ [self: Self&]: String { // operator toString
        return "${self._width}; ${self._height}"
    }

    prop width[self: Self&]: Real {
        return self._width
    }

    prop height[self: Self&] = self._height

    func incrementWidth[self: Self!]() {
        self._width++
    }
}

oper+ [self: Model&](rhs: Model): Model {
    // we have access to private fields because we are in the same file
    // this declared as a function extension in another file => do not have access to private fields
}

impl Area for Model { // we have access to private fields because we are in the same file
    func area[self: Self&]() { // Self will remain available in any Model impl scope
        return self._width * self._height
    }

    prop areaP[self: Self&] = self._width * self._height
}

func main() {
    fin m = Model(2.0, 3.0)
    std::println(m) // automatically use operator toString, or like this std::println("$m")
}

```