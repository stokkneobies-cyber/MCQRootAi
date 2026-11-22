# MCQ Root AI

Android Kotlin starter app for multiple-choice study assistance.

## Features
- Manual question input
- Screenshot import with on-device ML Kit OCR (Latin script)
- OpenAI API analysis using `gpt-4o-mini`
- Optional floating result overlay (Android overlay permission)
- API key stored in app-private SharedPreferences

## Build
1. Open this folder in Android Studio (Hedgehog or newer).
2. Install Android SDK Platform 35 and a compatible JDK (17).
3. Let Gradle sync.
4. Build > Build APK(s), or run:
   `./gradlew assembleDebug`

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## Setup
1. Install the APK.
2. Enter your own OpenAI API key in the app.
3. Type a question or choose a screenshot.
4. Review OCR text before asking AI.
5. To show the result over another app, grant the Android "Display over other apps" permission.

## Important
- This is a normal Android app; root is not required for OCR, AI requests, or overlay.
- KernelSU is not used by this MVP. No stealth, anti-detection, or game/app memory modification features are included.
- The API key is stored locally in app-private preferences, but a rooted device or compromised app can expose locally stored secrets. Do not share your key.
- OpenAI API usage may incur charges. The app does not include an API subscription.
- OCR quality depends on image clarity and language; inspect extracted text and choices.
- Build status: source project generated; APK not built or runtime-tested in this environment.
