```azora
pack Size {
    var width: Int
    var height: Int
}

// All this things that applies to constructors must also apply to function calls
func main() {
    fin size1 = Size(2, 3)
    fin size2 = Size(width: 2, height: 3)
    fin size3 = Size(width: 2, 3)
    fin size4 = Size(2, height: 3)
    fin size5 = Size(height: 3, width: 2)
    
    // no need for comma if we use new line (actually comma is not allowed at all in this case)
    // this will enable inline if/for/while/when/loop inside constructor call
    fin size6 = Size(
        2
        3
    )

    fin size7 = Size(
        width: 2
        height: 3
    )

    fin size8 = Size(
        width: 2
        3
    )

    fin size9 = Size(
        2
        height: 3
    )

    fin size10 = Size(
        height: 3
        width: 2
    )
}
```