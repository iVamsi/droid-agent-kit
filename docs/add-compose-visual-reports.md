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

`captureCompose` persists the PNG and a semantics sidecar under `outputDir`. Run
`./gradlew droidAgentVisualsReport` to diff fresh captures against `goldensDir` and produce a real
markdown report, or `./gradlew droidAgentVisualsUpdateGoldens` to accept the current captures as the new
baseline. The equivalent CLI commands are `droidagent visuals report --project <path>` and
`droidagent visuals update-goldens --project <path>` (both also accept `--output-dir`/`--goldens-dir` to
override the defaults).

droid-agent-kit does not depend on Paparazzi, Robolectric, or the Android SDK itself — any renderer that
produces PNG bytes works. Accessibility-based findings (contrast, touch targets, missing semantics
labels) are not yet detected; only pixel-diff regression is implemented today.
