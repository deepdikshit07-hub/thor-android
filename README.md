# THOR Android Assistant

THOR is an experimental Android personal assistant project. The active development branch is
`experiment/boss-speaker-verification`; keep it separate from the main branch while features are
being validated.

## Current project features

- Dark, blue-accented assistant interface with five bottom-navigation sections.
- Animated holographic reactor HUD and startup animation.
- Custom THOR launcher artwork.
- Text commands, Android speech-recognition handoff, and hybrid multilingual Text-to-Speech (prefers installed network voices online and installed local voices offline).
- English, Hindi, and Bengali response selection.
- Local notes, tasks, user-taught lessons, and reminders.
- App launching, selected Android settings shortcuts, dialer/SMS composer handoff, and media-key controls.
- Optional on-device text generation using the bundled Qwen LiteRT model.
- Experimental WeSpeaker embedding enrollment; automatic speaker identification is not enabled.
- Remote Master Mode foundation using Cloudflare Worker/D1, Firebase Authentication, FCM, and
  Device A biometric signing.

## Build and download

GitHub Actions builds a debug APK when commits are pushed to `experiment/**`.

1. Open the repository's **Actions** tab.
2. Select the latest successful **Build THOR APK** run for the experimental branch.
3. Download the `THOR-debug-apk` artifact and extract `app-debug.apk`.
4. Install it on an Android device after reviewing Android's installation prompt.

The APK includes the offline model assets downloaded by CI and can therefore be very large.

## Important limitations

- A successful CI build is not a physical-device test.
- Android's speech-recognition service may require internet; requesting offline recognition does not guarantee offline speech-to-text.
- Hybrid TTS selects from voices installed in the Android speech engine; online voices are used only when the device reports validated internet, with local voices preferred offline. Voice quality and language coverage depend on the installed engine and voice data; it does not use or reproduce ChatGPT Live's proprietary voice.
- Automatic Boss voice recognition is not active; the speaker embedding work is experimental and not authentication.
- Remote Master Mode needs the Cloudflare Worker, D1 schema, Firebase secrets/accounts, FCM, and two-device testing.
- The current app does not yet enforce Master Mode as a gate for additional protected commands.
- Accessibility navigation requires the user to enable THOR Navigation manually in Android settings.
- Android battery management and notification permissions can affect reminders and FCM delivery.

## Security and configuration

The Android Firebase configuration file identifies project `thor-ai-2791b` (project number
`418315674236`). Keep the Worker `FIREBASE_PROJECT_ID`, Firebase Web API key, and service-account
project consistent with that project. Never commit service-account JSON, private keys, passwords,
or App Check debug tokens.
