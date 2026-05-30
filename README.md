# PlateAuth

NFC behavioral biometric authentication for Android.

## Concept

PlateAuth is a novel authentication method that leverages NFC event patterns as a behavioral biometric. Instead of reading tag data, it analyzes *how* a user physically interacts with NFC tags at different positions relative to their body.

Three-point enrollment captures distinct interaction profiles:

- **Plate-side** -- phone held flat against an NFC-embedded plate
- **Bare-side** -- phone held against the opposite surface (no plate)
- **Air baseline** -- phone held in open air (control measurement)

Behavioral signatures are derived from event rate, inter-event gap analysis, total event count, and NFC technology type distribution. These features vary measurably between positions and between users due to differences in grip, angle, pressure, and device proximity.

## How It Works

1. **Enrollment**: The user performs three 15-second NFC capture sessions (plate, bare, air). The plate-side capture is saved as the enrolled behavioral profile.
2. **Authentication**: A new capture session is compared against the enrolled profile using a scoring system that evaluates event rate similarity, gap timing, and event count deviation. A score of 2/3 or higher results in a match.

Capture data (timestamped NFC tag events with technology metadata) is saved as JSON for offline analysis.

## Tech Stack

- **Language**: Kotlin
- **Platform**: Android (NFC ReaderMode API)
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (Android 15)
- **Dependencies**: None beyond Android SDK (JUnit 4 for unit tests only)

## Status

Research prototype / proof of concept. This project explores whether NFC interaction patterns carry enough biometric signal to distinguish users or positions. It is not intended for production security use.

## Building

1. Open the project in Android Studio.
2. Sync Gradle.
3. Build and run on a physical device with NFC hardware (emulators do not support NFC).

Or from the command line:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Testing

The statistical core (timing/gap/rate statistics, profile construction, and the
authentication scoring rule) lives in [`SignatureAnalysis`](app/src/main/java/com/plateauth/SignatureAnalysis.kt),
a framework-free Kotlin object that `MainActivity` delegates to. It has JVM unit
tests that run with no device or emulator:

```bash
./gradlew test
```

NFC capture and the UI are Android-framework bound and are not covered by these
unit tests (they require a physical NFC device).

## Continuous Integration

`.github/workflows/ci.yml` runs `./gradlew assembleDebug test lint` on every push
and pull request (JDK 17, Temurin). This is the project's first automated build.

## License

MIT
