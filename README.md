# Premium Notes

A private, fully offline handwriting notes application for Android with a
software palm-rejection pipeline at its core.

Write naturally with a stylus or your finger. Raw touch input is analyzed in
real time to reliably ignore palm rests so only your pen or writing finger
leaves ink — no proprietary or cloud palm-rejection service required.

## Features

- Handwriting notes with low-latency stroke rendering
- Software palm rejection for passive styluses and fingers
- Pen, highlighter, eraser, and selection tools
- Multi-page notebooks with a page navigator
- Infinite canvas with pinch-to-zoom and pan
- Export notes to PDF and share them with other apps
- Light and dark themes
- Input diagnostics for calibrating your device

## Privacy

- 100% offline: all notes are stored on-device in a local Room database
- No internet permission, no accounts, no tracking, no ads, no analytics
- No Android permissions requested
- `allowBackup` is disabled so notes are never uploaded to cloud backups

## Requirements

- JDK 17+
- Android SDK (compileSdk 35, minSdk 26, targetSdk 35)
- Gradle 8.13 (use the bundled wrapper; no system Gradle required)

## Building

The Gradle wrapper downloads the pinned, checksummed Gradle 8.13 distribution
from the official Gradle service. Set `ANDROID_HOME` (or provide
`local.properties` with `sdk.dir`), then:

```sh
# Release APK (unsigned; F-Droid signs its own builds)
./gradlew :app:assembleRelease

# Debug APK
./gradlew :app:assembleDebug

# Unit tests
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintRelease
```

Release builds are reproducible: two clean builds from the same commit
produce byte-for-byte identical APKs.

## License

Copyright (C) 2026 codeRed

Premium Notes is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See the [LICENSE](LICENSE) file for details.
