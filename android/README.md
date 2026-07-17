# GoShelf Android App

Android audiobook client for [GoShelf](https://github.com/aleksclark/goshelf).

## Features

- Connect to a GoShelf server (configurable URL)
- Login with username/password (session cookie persisted securely)
- Browse audiobook library with cover art grid
- Download books as ZIP with automatic audio extraction
- Background downloads with progress tracking
- Settings for server URL and download directory

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Hilt** for dependency injection
- **OkHttp** for networking
- **Coil** for image loading
- **WorkManager** for background downloads
- **EncryptedSharedPreferences** for secure credential storage

## Building

```bash
cd android
./gradlew assembleDebug
```

## Running Tests

```bash
# Unit tests
./gradlew testDebugUnitTest

# Instrumented tests (requires emulator or device)
./gradlew connectedDebugAndroidTest
```

## Configuration

On first launch, configure:
1. **Server URL** - Your GoShelf server address (default: https://books.clark.team)
2. **Username/Password** - Your GoShelf credentials

After login, the download directory can be configured in Settings.

## Requirements

- Android 8.0+ (API 26)
- Network access to GoShelf server
