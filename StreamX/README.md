# StreamX Android

`StreamX` is the Android client for the StreamX platform. It is built with Kotlin, Jetpack Compose, and Media3, and it connects to the StreamX backend for account-driven features while also supporting direct provider flows such as YouTube login inside the app.

## What The App Includes

- modern Compose-based player UI
- queue, shuffle, repeat, lyrics, and expanded player flows
- favourites, playlists, albums, downloads, and sharing
- collaborative jams, friends, and invite notifications
- custom deep links for playlist, album, track, and jam opens
- Firebase Cloud Messaging support for push notifications
- direct YouTube login for provider-native playback features

## Requirements

- Android Studio with Android SDK 35
- JDK 21
- Android device or emulator running Android 8.0+ because `minSdk = 26`
- Firebase project with an Android app entry for `com.xstream.music`

## First-Time Setup

### 1. Add `google-services.json`

This app applies the Google Services Gradle plugin, so the Firebase config file must exist before the first real build.

Get it from:

1. Firebase Console
2. Project settings
3. Your apps
4. Android app with package name `com.xstream.music`
5. Download `google-services.json`

Place it here:

```text
StreamX/app/google-services.json
```

### 2. Open the project

Open `StreamX` in Android Studio, let Gradle sync, and make sure the SDK / JDK versions line up with the project settings.

### 3. Build and install

From PowerShell:

```bash
cd StreamX
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

## Firebase In The Android App

The Android client uses Firebase for app-side messaging and analytics.

### What Firebase does here

- receives jam invites and other push notifications
- refreshes the device FCM token when Firebase rotates it
- registers that token with the StreamX backend once the user is logged in and an API URL is configured

### What you do not need to do manually

You do not need to fetch or paste an FCM device token yourself.

The app already does that at runtime:

- on startup
- when Firebase issues a new token

### Important distinction

The Android repo needs:

- `google-services.json`

The backend repo needs:

- Firebase Admin credentials such as `FIREBASE_CREDENTIALS` or `service_account.json`

Without backend Firebase Admin setup, the app can still build, but server-triggered notifications such as jam invites will not be delivered.

## Connecting To The Backend

The Android app can use direct provider functionality, but StreamX backend features depend on a configured API URL.

Backend-powered features include:

- login and account state
- playlists, favourites, and saved albums
- shared links
- friends and jams
- push token registration

For local backend testing:

- emulator: use something like `http://10.0.2.2:8000`
- physical device: use your machine's LAN IP or a tunnel URL such as `ngrok`

Make sure the backend is reachable from the device, not just from your laptop browser.

## YouTube Setup

The app supports direct YouTube login inside the client. That flow is separate from backend cookies.

What the app does:

- opens a WebView sign-in flow for YouTube
- stores the resulting cookies and metadata in app preferences
- uses those values for direct provider requests in the client

What this means:

- you do not need `cookies/yt.txt` for Android YouTube playback
- backend YouTube cookies are only for backend-side extraction paths

If you need account-specific YouTube or YouTube Music behavior, log in through the app's YouTube sign-in flow.

## Build Commands

### Debug

```bash
cd StreamX
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

### Release

```bash
cd StreamX
.\gradlew.bat :app:assembleRelease
```

## Release Signing

The release build is configured to look for:

```text
StreamX/../streamx-release-key.jks
```

It also reads these environment variables:

- `STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

If you plan to ship release builds, set those values in your environment before running the release task.

## Deployment

For this repo, "deploy" usually means producing an APK or app bundle and distributing it internally or through your preferred Android release process.

Typical flow:

1. configure Firebase with `google-services.json`
2. point the app at the correct backend URL
3. produce a signed release build
4. install it on test devices or upload it to your release channel

## Important Notes

- push notifications depend on both this repo and the backend Firebase Admin setup
- deep links and share links depend on the backend share routes being live
- jams and account features require a working backend URL
- the YouTube provider is client-side and does not need the backend for normal provider navigation

## Related Docs

- Platform overview: [../README.md](../README.md)
- Web client: [../StreamXWeb/README.md](../StreamXWeb/README.md)
