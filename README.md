# Turntable — Native Android App
## What's inside

- `MainActivity.kt` — the screen: player UI, file picker, tracklist
- `MusicService.kt` — the background service: actual playback, lock-screen controls,
  notification, keeps running when you leave the app
- `LibraryStore.kt` — saves your playlist between launches
- `TrackAdapter.kt` — the scrollable track list

# Install
## Step 1: Install Android Studio

1. Go to **developer.android.com/studio** and download it for your OS (Windows/Mac/Linux)
2. Install it and open it — first launch will download some SDK components, let it finish
   (this can take 10–20 minutes on the first run)

## Step 2: Open this project

1. Unzip this download
2. In Android Studio, choose **Open** (not "New Project")
3. Select the unzipped `TurntableAndroid` folder
4. Android Studio will "sync" the project — it downloads the specific Gradle/build tools this
   project needs. This can take a few minutes the first time. Just let it run; if it asks about
   using the Gradle wrapper, say yes.

## Step 3: Build the APK

1. Once the sync finishes with no red errors in the bottom bar, go to the top menu:
   **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. When it finishes, a small popup appears bottom-right — click **locate** to find the file.
   It'll be at:
   `TurntableAndroid/app/build/outputs/apk/debug/app-debug.apk`

That `app-debug.apk` file is your real, installable app.

## Step 4: Get it onto your phone and install it

Easiest way — email it to yourself or upload to Google Drive/Dropbox, then open it on your
phone:

1. Send `app-debug.apk` to your phone (email attachment, Drive, USB transfer, whatever's easiest)
2. Open it from your phone's Files app or notification
3. Android will warn about installing from an unknown source the first time — tap **Settings**
   in that prompt, then **Allow from this source**, then go back and tap **Install**
4. Done — Turntable now appears in your app drawer like any other app

## Using it

- **Add music** opens Android's file picker — choose MP3, FLAC, MP4/M4A, WAV, OGG, or AAC
  files from your phone, Google Drive, or wherever
- Tap a track to play it; shuffle and hide-track work the same as the desktop/web versions
- **Background playback**: leave the app, lock your phone, whatever — it keeps playing, with
  real play/pause/skip controls in your notification shade and on your lock screen, backed by
  an actual Android foreground service (not a browser trick this time)

## If the build fails

The most common cause is Android Studio not finishing its SDK setup. Check
**Tools → SDK Manager** and make sure "Android 14.0 (API 34)" is installed under SDK Platforms,
and a recent "Android SDK Build-Tools" version is installed under SDK Tools. Then try the build
again.
