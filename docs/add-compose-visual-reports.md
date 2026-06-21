# Add Compose Visual Reports

Apply the Gradle plugin:

```kotlin
plugins {
    id("com.droidagentkit.compose-visuals")
}

droidAgentVisuals {
    outputDir.set(layout.buildDirectory.dir("droidagentkit/visuals"))
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

Use the alpha test helper in JVM or Android-facing visual tests:

```kotlin
val rule = DroidAgentVisualRule()

val capture = rule.captureCompose(
    name = "home_screen",
    matrix = VisualMatrix.standard(),
    semantics = listOf("Button: Start")
) {
    "rendered-screen"
}
```

The alpha includes the report model, PNG diff engine, Gradle task registration, and deterministic capture metadata. Full Android instrumentation capture can be layered behind the same `DroidAgentVisualRule` API.
