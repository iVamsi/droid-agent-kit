# Add Compose Visual Reports

Apply the Gradle plugin:

```kotlin
plugins {
    id("com.droidagentkit.compose-visuals")
}

droidAgentVisuals {
    outputDir.set(layout.buildDirectory.dir("droidagentkit/visuals"))
    goldensDir.set(layout.projectDirectory.dir("src/test/resources/droidagentkit/goldens"))
    packageName.set("com.example.app")
    failOnChangedGoldens.set(true)
    failOnAccessibilityWarnings.set(false)

    matrix {
        devices.add("phone_412x915")
        themes.addAll("light", "dark")
        fontScales.addAll(1.0f, 1.3f, 2.0f)
        locales.addAll("en", "ar")
    }

    tolerance {
        maxChangedPixelPercent.set(0.10)
        maxColorDistance.set(3)
    }
}
```

> **Current limitation:** the `matrix { }` block's `devices`/`themes`/`fontScales`/`locales` lists are
> not automatically expanded into a cross-product of captures. `DroidAgentVisualRule.captureCompose`
> only ever reads the *first* value of each list (`matrix.devices.firstOrNull()`,
> `matrix.themes.firstOrNull()`, and so on), so declaring multiple values above does not by itself
> produce multiple screenshots — it captures exactly one combination (here, `phone_412x915` /
> `light` / `1.0f` / `en`). If you need coverage across multiple themes, font scales, or locales,
> call `captureCompose` once per combination from your own test code, passing a `VisualMatrix` with
> single-element lists describing that specific combination (see below).

Use the visual test rule in your JVM test source set, rendering each case to real PNG bytes with a
renderer of your choice — for example [Paparazzi](https://github.com/cashapp/paparazzi), which renders
Compose/View screenshots on the JVM with no emulator required:

```kotlin
val rule = DroidAgentVisualRule()

val capture = rule.captureCompose(
    name = "home_screen",
    matrix = VisualMatrix.standard(),
    semantics = listOf("Button: Start"),
) {
    // Return PNG-encoded bytes from whatever renderer you use, e.g. Paparazzi's snapshot output.
    renderHomeScreenToPng()
}
```

To exercise more than one theme, font scale, or locale, call `captureCompose` multiple times — once
per combination — each with a `VisualMatrix` describing that single combination and a distinct
`name` (so captures don't overwrite each other):

```kotlin
val themes = listOf("light", "dark")
themes.forEach { theme ->
    rule.captureCompose(
        name = "home_screen_$theme",
        matrix = VisualMatrix(
            devices = listOf("phone_412x915"),
            themes = listOf(theme),
            fontScales = listOf(1.0f),
            locales = listOf("en"),
        ),
        semantics = listOf("Button: Start"),
    ) {
        renderHomeScreenToPng(theme = theme)
    }
}
```

`captureCompose` persists the PNG and a semantics sidecar under `outputDir`. Run
`./gradlew droidAgentVisualsReport` to diff fresh captures against `goldensDir` and produce a real
markdown report, or `./gradlew droidAgentVisualsUpdateGoldens` to accept the current captures as the new
baseline. The equivalent CLI commands are `droidagent visuals report --project <path>` and
`droidagent visuals update-goldens --project <path>` (both also accept `--output-dir`/`--goldens-dir` to
override the defaults).

droid-agent-kit does not depend on Paparazzi, Robolectric, or the Android SDK itself — any renderer that
produces PNG bytes works. Accessibility-based findings (contrast, touch targets, missing semantics
labels) are not yet detected; only pixel-diff regression is implemented today.
