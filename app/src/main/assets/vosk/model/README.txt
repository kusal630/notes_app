The Vosk speech model is downloaded at build time by the downloadVoskModel Gradle task (./gradlew downloadVoskModel). See app/build.gradle.kts. The app degrades gracefully when this folder is empty.
