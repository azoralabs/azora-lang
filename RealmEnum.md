Create realm inside enum like this:

```
enum X {
    A
    realm z1 {
        B
        C
    }
    D
    realm z2 {
        E
        realm z3 {
            F
        }
    }
    use realm z4 {
        G
        realm z5 {
            H
        }
    }
}
```

this can be called like this:

```
X.A
X.z1::B
X.z1::C
X.D
X.z2::E
x.z2::z3::F
x.z4::G
x.G
x.z4::G.z5::H
x.G.z5::H
```

Also add support for 'use realm' and 'use use realm like:

```
enum Y {
    use realm z1 {
        A
    }
    B
    use realm z1 {
        C
    }
    import use realm z2 {
        D
    }
    use realm z2 {
        E
    }
}
```

this can be called like this:

```
Y.z1::A
Y.B
Y.z1::C
Y.D
Y.z2::D
Y.z2::E
```

implement tests and make sure to show how to use in 'when' too
of course we can say "realm z1::z2" and so on
make sure it works with "use" and "with", both global and local

the same thing implement for "slot" and "fail" too