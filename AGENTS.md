# GlyphHacker - Agent Instructions

Android app for glyph recognition using Kotlin and Jetpack Compose.

## Project Overview

- **Language**: Kotlin (100%)
- **Framework**: Android with Jetpack Compose UI
- **Build System**: Gradle with Kotlin DSL (`.gradle.kts`)
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 36
- **Java Compatibility**: Java 11 source, requires Java 17 to build

## Build Commands

```shell
# Debug build and install (primary workflow, should be done after every valuable code edit) (`adb install should be done immediately after build`)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk || true

# Release build
./gradlew :app:assembleRelease

# Clean build
./gradlew clean

# Run lint
./gradlew lint
```

## Test Commands

```shell
# Run all tests
./gradlew test

# Run unit tests only
./gradlew :app:testDebugUnitTest

# Run a single unit test class
./gradlew :app:testDebugUnitTest --tests "moe.lyniko.glyphhacker.ExampleUnitTest"

# Run a single test method
./gradlew :app:testDebugUnitTest --tests "moe.lyniko.glyphhacker.ExampleUnitTest.testMethod"

# Run instrumented tests (requires device/emulator)
./gradlew :app:connectedDebugAndroidTest

# Run a single instrumented test
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=moe.lyniko.glyphhacker.ExampleInstrumentedTest
```

## Project Structure

```
app/src/main/java/moe/lyniko/glyphhacker/
├── MainActivity.kt              # Main UI entry point (Compose)
├── accessibility/               # Accessibility service for gestures
├── capture/                     # Screen capture services
├── data/                        # Data models and repositories
├── debug/                       # Debug/analysis tools
├── glyph/                       # Core glyph recognition logic
├── overlay/                     # Overlay UI service
├── ui/                          # ViewModels and theme
└── util/                        # Utility functions
```

## Code Style Guidelines

This project uses `kotlin.code.style=official` (Kotlin official code style).

### Naming Conventions

| Element | Convention | Example |
|---------|------------|---------|
| Classes | PascalCase | `GlyphRecognitionEngine`, `MainViewModel` |
| Functions | camelCase | `processFrame`, `resetSession` |
| Variables | camelCase | `trackedGlyph`, `frameIntervalMs` |
| Constants | SCREAMING_SNAKE_CASE | `LOG_TAG`, `KEY_RECOGNITION_MODE` |
| Private backing fields | underscore prefix | `_message`, `_calibrating` |
| SharedPreferences keys | SCREAMING_SNAKE_CASE | `KEY_FRAME_INTERVAL_MS` |

### Import Ordering

Organize imports in this order:
1. Android SDK (`android.*`)
2. AndroidX (`androidx.*`)
3. Third-party libraries (`kotlinx.*`, `org.json.*`)
4. Project imports (`moe.lyniko.glyphhacker.*`)
5. Java standard library (`java.*`)
6. Kotlin standard library (`kotlin.*`)

### Error Handling Patterns

```kotlin
// For nullable results
runCatching { riskyOperation() }.getOrNull()

// For boolean success checks
runCatching { riskyOperation() }.isSuccess

// For operations that can fail, return Result<Unit>
suspend fun importFromJson(jsonText: String): Result<Unit> {
    return runCatching {
        // ... implementation
    }
}

// Use null-safe operators extensively
value?.let { process(it) } ?: defaultValue

// Use coerceIn() for value validation
value.coerceIn(minValue, maxValue)
```

### State Management

- Use `StateFlow`/`MutableStateFlow` for reactive state
- Use `data class` for immutable state objects
- Use `sealed class` or `enum class` for state machines
- Private backing fields with underscore prefix for MutableStateFlow:

```kotlin
private val _message = MutableStateFlow("")
val message: StateFlow<String> = _message.asStateFlow()
```

### Architecture Patterns

- **MVVM**: Use `AndroidViewModel` for ViewModels
- **Repository Pattern**: For data persistence (see `SettingsRepository`)
- **Bus Pattern**: For cross-component communication (`RuntimeStateBus`, `DrawCommandBus`)
- **Coroutines**: Use structured concurrency with `viewModelScope`, `serviceScope`

### Extension Functions

Place private extension functions at the bottom of the file:

```kotlin
private fun String?.toRecognitionMode(): RecognitionMode {
    if (this.isNullOrBlank()) return RecognitionMode.EDGE_SET
    return RecognitionMode.entries.firstOrNull { it.name == this } ?: RecognitionMode.EDGE_SET
}
```

### Companion Objects

Use for constants and factory methods. Place at the top of the class:

```kotlin
class GlyphRecognitionEngine {
    private companion object {
        private const val LOG_TAG = "GlyphHacker"
    }
    // ... rest of class
}
```

### Logging

Use structured log tags with context:
```kotlin
Log.d(LOG_TAG, "[ENGINE][F$frameId] Processing frame")
Log.d(LOG_TAG, "[DRAW] Starting gesture")
```

### Data Classes

Use for immutable state and configuration:

```kotlin
data class SessionState(
    val phase: GlyphPhase,
    val sequence: List<String>,
    val trackedGlyph: String?,
    // ... other fields
)
```

### Formatting

- Use trailing commas in multi-line parameter lists
- Use expression body for simple functions
- Prefer `when` over `if-else` chains for multiple conditions
- Use `apply` for object configuration blocks

```kotlin
prefs.edit().apply {
    putString(KEY_MODE, mode.name)
    putLong(KEY_INTERVAL, interval)
    apply()
}
```

## Dependencies

Key dependencies (see `app/build.gradle.kts` for versions):
- Jetpack Compose for UI
- AndroidX Lifecycle for ViewModel
- Kotlin Coroutines for async operations
- No external image processing libraries (uses Android Bitmap APIs)

## Key Concepts

- **标定帧 / "空白帧" / Blank Frame** = Ingress 中长按 HACK 后 command channel 刚打开时的截图。此时屏幕背景为纯黑，11 个 glyph 节点以高亮圆点显示，尚未开始绘制 glyph。这对应状态机中的 `COMMAND_OPEN` 阶段。`GlyphCalibration.calibrateFromBlankFrame()` 的输入就是这张截图，`CalibrationProfile` 中的坐标也基于该截图的分辨率。

## Notes for Agents

1. Always run the build command after making changes to verify compilation
2. The app uses accessibility services - test on a real device when possible
3. Screen capture functionality requires proper permissions
4. User-facing strings may be in Chinese - preserve existing language conventions
5. The glyph recognition engine is performance-critical - avoid unnecessary allocations
