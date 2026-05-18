# PawTrack - Pet Tracker Demo

A multi-page pet tracker web app built with Azora and compiled to JavaScript (React). Demonstrates views, reactive state, SVG icons, form inputs, animations, and client-side routing.

## How it was generated

```sh
azora create demo-website --js
```

## Project structure

```
demo-website/
├── azora.toml                  Project config (web-js target, port 8080)
├── .gitignore
├── src/
│   ├── App.az                  Root view with NavHost routing (4 pages)
│   └── Pages/
│       ├── Shared.az           Reusable BackButton, PageTitle components
│       ├── HomePage.az         Landing with feature cards and SVG icons
│       ├── PetsPage.az         Pet list with emoji avatars and stats
│       ├── AddPetPage.az       Form with pet type selector and inputs
│       └── HealthPage.az       Health log timeline with typed entries
└── tests/
    └── test_main.az
```

## How to run

```sh
cd examples/demo-website
azora run --js
```

Opens at [http://localhost:8080](http://localhost:8080). Supports browser refresh on any route (SPA fallback).

## How to build only

```sh
azora build --js
```

Output: `build/app.js` and `build/index.html`.

## Project config

```toml
[project]
name = "demo-website"
version = "0.1.0"
target = "web-js"
entry = "App.az"
src = "src"

[web-js]
title = "PawTrack - Pet Tracker"
port = 8080

[test]
src = "tests"
```

## Features demonstrated

### Routing

`NavHost` provides client-side routing with browser URL sync and history support:

```
view App() {
    rem page: Int = 0

    NavHost(page, ["/", "/pets", "/add", "/health"]) {
        HomePage(onPets: { page = 1 }, onAdd: { page = 2 }, onHealth: { page = 3 })
        PetsPage(onBack: { page = 0 })
        AddPetPage(onBack: { page = 0 })
        HealthPage(onBack: { page = 0 })
    }
}

func main() {
    render(App)
}
```

- `rem page` is reactive state - changing it switches the visible page
- The URL array maps each index to a path (`/`, `/pets`, `/add`, `/health`)
- Browser back/forward buttons work via `popstate` listener
- Refreshing a sub-route (e.g. `/pets`) serves `index.html` (SPA fallback)

### Reactive state and forms

`rem` declares reactive state variables that trigger re-renders when mutated:

```
view AddPetPage(onBack: () -> Unit) {
    rem petKind: String = ""
    rem petName: String = ""
    rem petAge: String = ""

    // Input binds to state via value + onInput
    Input(
        value: petName,
        onInput: { v -> petName = v },
        placeholder: "e.g. Luna",
        modifier: Modifier()
            .padding(12, 16)
            .borderRadius(8)
            .border(1, "#e2e8f0")
    )

    // Number-only input
    Input(
        value: petAge,
        onInput: { v -> petAge = v },
        inputType: "number",
        placeholder: "e.g. 3"
    )
}
```

### Animations and transitions

Azora provides a Compose-inspired, fully declarative animation system. Every animation is explicitly declared in Azora source files via modifier methods - nothing is implicit or hardcoded.

**Entry animations** - play once when the element mounts:

```
// Page fades in on navigation
Column(
    modifier: Modifier()
        .enterAnimation("azFadeIn", 400)
) { ... }
```

Built-in keyframe animations: `azFadeIn`, `azSlideIn`, `azSlideUp`, `azScaleIn`, `azBounceIn`.

**Hover: lift** - card lifts up with shadow on hover:

```
// Used on FeatureCard, PetCard, HealthEntry
Row(
    modifier: Modifier()
        .padding(20)
        .borderRadius(12)
        .hoverLift(2)
) { ... }
```

**Hover: scale** - element scales up on hover:

```
// Used on PetTypeOption buttons
Button(
    modifier: Modifier()
        .hoverScale(1.05)
) { ... }
```

**Hover: brightness** - element darkens/lightens on hover:

```
// Used on navigation buttons and BackButton
Button(
    modifier: Modifier()
        .backgroundColor("#f97316")
        .hoverBrightness(0.9)
) { ... }
```

**Hover: glow** - element shows a focus ring on hover:

```
Input(
    modifier: Modifier()
        .hoverGlow(3)
)
```

**Transition any property** - animate changes to a specific CSS property:

```
Column(
    modifier: Modifier()
        .transition("opacity", 300, "ease-out")
        .opacity(if visible { 1.0 } else { 0.0 })
) { ... }

// Or animate all properties at once
Box(
    modifier: Modifier()
        .animateAll(300)
)
```

**Transform modifiers:**

```
Modifier().scale(1.05)          // transform: scale(1.05)
Modifier().translateY(-4)       // transform: translateY(-4px)
Modifier().translateX(10)       // transform: translateX(10px)
Modifier().rotate(45)           // transform: rotate(45deg)
```

**AnimatedVisibility** - enter/exit animation wrapper:

```
AnimatedVisibility(visible: showMenu, duration: 300) {
    Column { ... }
}
```

**Crossfade** - cross-fade between children by index:

```
Crossfade(target: selectedTab, duration: 250) {
    TabOne()
    TabTwo()
    TabThree()
}
```

**How it works under the hood:**

Hover modifiers (`.hoverLift()`, `.hoverScale()`, etc.) set CSS custom variables (`--az-lift`, `--az-scale`, etc.) on the element via inline styles. A small generic runtime in the generated HTML matches these via attribute selectors (`[style*="--az-lift"]:hover`) and applies the effects. This keeps the system fully declarative - the developer controls exactly which elements animate and how.

### Inline SVG icons

Full SVG support with `Svg`, `SvgPath`, `SvgCircle`, `SvgLine`, `SvgRect`, `SvgPolyline`:

```
// Arrow-left icon for back button
Svg(width: 16, height: 16, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor") {
    SvgPath(d: "M19 12H5")
    SvgPath(d: "M12 19l-7-7 7-7")
}

// Checkmark in circle
Svg(width: 20, height: 20, viewBox: "0 0 24 24", fill: "none", stroke: "#3b82f6") {
    SvgRect(x: 3, y: 3, width: 18, height: 18, rx: 4)
    SvgPath(d: "M9 12l2 2 4-4")
}
```

SVG `stroke` can be dynamic (bound to a prop like `color` or `tagColor`).

### Reusable components

Views are composable. Shared components live in `Shared.az` and are used across pages:

```
// Shared.az - reusable back button with arrow icon and hover effect
view BackButton(onClick: () -> Unit) {
    Button(
        onClick: onClick,
        modifier: Modifier()
            .padding(10, 20)
            .backgroundColor("#f1f5f9")
            .color("#334155")
            .borderRadius(8)
            .fontSize(14)
            .hoverBrightness(0.92)
    ) {
        Row(modifier: Modifier().gap(6).align("center")) {
            Svg(width: 16, height: 16, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor") {
                SvgPath(d: "M19 12H5")
                SvgPath(d: "M12 19l-7-7 7-7")
            }
            Text("Back")
        }
    }
}
```

Used in any page with just `BackButton(onClick: onBack)`.

### Conditional styling

Modifier values can use inline `if` expressions for dynamic styles:

```
view PetTypeOption(emoji: String, label: String, selected: String, onClick: () -> Unit) {
    Button(
        onClick: onClick,
        modifier: Modifier()
            .backgroundColor(if selected == label { "#fff7ed" } else { "#fff" })
            .border(if selected == label { 2 } else { 1 }, if selected == label { "#f97316" } else { "#e2e8f0" })
    ) { ... }
}
```

### Button children

Buttons support rich content via lambda children (icon + text):

```
Button(
    onClick: onPets,
    modifier: Modifier()
        .padding(14, 28)
        .backgroundColor("#f97316")
        .color("#fff")
        .borderRadius(10)
) {
    Row(modifier: Modifier().gap(8).align("center")) {
        Svg(width: 18, height: 18, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor") {
            SvgPath(d: "M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2")
            SvgCircle(cx: 12, cy: 7, r: 4)
        }
        Text("My Pets")
    }
}
```

### Wildcard imports

`use pages.*` imports all `.az` files from the `Pages/` directory, making every view available without individual imports.
