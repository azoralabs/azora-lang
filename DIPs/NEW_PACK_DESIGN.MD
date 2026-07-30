```azora
mod main

use std.io

prot Area {
    func area[self&](): Real
}

pack Model {
    var _width = 0.0
    var _height = 0.0
}

ctor [self: Model&](w: Real, h: Real) {
    self._width = w
    self._heigh = h
    std::println("Constructed")
}

dtor [self: Model&] {
    std::println("Destructed")
}

oper$ [self: Model&]: String { // operator toString
    return "${self._width}; ${self._height}"
}

prop width[self: Model&]: Real {
    return self._width
}

prop height[self: Model&] = self._height

func incrementWidht(self: Model!) {
    self._width++
}

impl Area for Model {
    func area[self&]() {
        return self._width * self._height
    }
}

func main() {
    fin m = Model(2.0, 3.0)
    std::println(m) // automatically use operator toString, or like this std::println("$m")
}

```