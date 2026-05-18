# Counter App - Multiplatform Demo

A single-file counter app that compiles to every UI target Azora supports. One `App.az` file, 7 platforms.

## The app

A counter with increment, decrement, and reset buttons. Uses reactive state (`rem`), declarative UI (`view`), animations (`.enterAnimation()`, `.hoverBrightness()`), and modifiers for styling.

## Build for any target

Each target outputs to its own `build/<target>/` directory.

```sh
cd examples/demo-multiplatform

azora build --js       # -> build/web-js/       React (browser)
azora build --wasm     # -> build/web-wasm/     WebAssembly (browser)
azora build --kmp      # -> build/kmp/          Kotlin Multiplatform (full Gradle project)
azora build --kt       # -> build/kotlin-jvm/   Kotlin JVM (plain)
azora build --cs       # -> build/csharp/       C# .NET (with .csproj)
azora build --py       # -> build/python/       Python Tkinter
azora build --swift    # -> build/swift/        SwiftUI
```

## Build all targets at once

```sh
azora build
```

Compiles every target listed in `azora.toml`.

## Run

```sh
# Web (starts dev server at http://localhost:8080)
azora run --js

# KMP desktop (Compose for Desktop)
azora run --kmp --desktop

# KMP Android (installs to emulator/device)
azora run --kmp --android

# KMP web (Compose for Web via Wasm)
azora run --kmp --web

# C# / .NET
azora run --cs

# Python / Tkinter
azora run --py

# Swift / SwiftUI
azora run --swift
```

## KMP project generation

`azora build --kmp` generates a full Compose Multiplatform Gradle project:

```
build/kmp/
├── gradlew, gradlew.bat                         Gradle wrapper
├── settings.gradle.kts                           TYPESAFE_PROJECT_ACCESSORS, :composeApp
├── build.gradle.kts                              Root (plugin aliases)
├── gradle.properties                             JVM args, config cache, Android
├── gradle/
│   ├── libs.versions.toml                        Kotlin 2.2.20, Compose 1.8.2, AGP, AndroidX
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties             Gradle 8.14.3
├── composeApp/
│   ├── build.gradle.kts                          KMP targets + Compose + distributions
│   └── src/
│       ├── commonMain/kotlin/.../App.kt          Compiled Azora UI
│       ├── desktopMain/kotlin/.../Main.kt        Window { App() }
│       ├── androidMain/
│       │   ├── kotlin/.../MainActivity.kt        setContent { App() }
│       │   └── AndroidManifest.xml
│       └── iosMain/kotlin/.../MainViewController.kt
└── iosApp/iosApp/
    ├── iOSApp.swift                              @main SwiftUI entry
    └── ContentView.swift                         ComposeView bridge
```

## What each target generates

| Target      | Flag      | Output Dir          | UI Framework    | Generated Project                         |
|-------------|-----------|---------------------|-----------------|-------------------------------------------|
| JavaScript  | `--js`    | `build/web-js/`     | React           | `app.js` + `index.html`                   |
| WebAssembly | `--wasm`  | `build/web-wasm/`   | React (WASI)    | `app.wasm` + `index.html`                 |
| KMP         | `--kmp`   | `build/kmp/`        | Jetpack Compose | Full Gradle project (desktop/Android/iOS) |
| Kotlin JVM  | `--kt`    | `build/kotlin-jvm/` | Compose (plain) | `app.kt`                                  |
| C#          | `--cs`    | `build/csharp/`     | MAUI / Console  | `app.cs` + `.csproj`                      |
| Python      | `--py`    | `build/python/`     | Tkinter         | `app.py`                                  |
| Swift       | `--swift` | `build/swift/`      | SwiftUI         | `app.swift`                               |
