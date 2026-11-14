# THOR Android Assistant

THOR is an experimental Android personal assistant project. The active development branch is
`feature/thor-live-mode`; its draft pull request targets `experiment/boss-speaker-verification`. Keep it separate from the main branch while features are being validated.

## Current project features

- Dark, blue-accented assistant interface with six bottom-navigation sections.
- Dedicated THOR LIVE bottom-navigation section with animated energy core, bidirectional Gemini Live audio, voice interruption support, and session-only captions (no local chat transcript storage).
- Animated holographic reactor HUD and startup animation.
- Custom THOR launcher artwork.
- Text commands, Android speech-recognition handoff, foreground-service Background Wake with an ongoing microphone notification, and hybrid multilingual Text-to-Speech (prefers installed network voices online and installed local voices offline).
- English, Hindi, and Bengali response selection.
- Local notes, tasks, user-taught lessons, and reminders.
- App launching, selected Android settings shortcuts, dialer/SMS composer handoff, and media-key controls.
- On-device text generation using the bundled Qwen LiteRT model (about 586 MB). The model is included in the APK so offline AI is ready without a separate model download.
- Per-device AI Provider Manager: choose Firebase Gemini, direct Gemini API with your own key, OpenAI / ChatGPT API, Groq, OpenRouter, Cloudflare Workers AI, a custom OpenAI-compatible endpoint, or local-only AI. Each provider keeps its own model, endpoint, and encrypted API credential on that installation.
- Experimental WeSpeaker embedding enrollment; automatic speaker identification is not enabled.
- Remote Master Mode foundation using Cloudflare Worker/D1, Firebase Authentication, FCM, and
  Device A biometric signing.

## Build and download

GitHub Actions builds a debug APK for the active pull request targeting `experiment/boss-speaker-verification` and for supported workflow branches.

1. Open the repository's **Actions** tab.
2. Select the latest successful **Build THOR APK** run for the experimental branch.
3. Download the `THOR-APK-DOWNLOAD` artifact and extract `app-debug.apk`.
4. Install it on an Android device after reviewing Android's installation prompt.

The APK includes the offline model assets downloaded by CI and can therefore be very large. To keep startup responsive and avoid loading a large model into RAM unnecessarily, THOR loads Offline AI only when you tap its button or explicitly request an offline answer.

## THOR Live Mode setup

THOR Live uses Google's bidirectional Gemini Live API over a WebSocket. It reuses the same Google Gemini API key saved on that installation under **Settings → AI Provider → Google Gemini (own API key)**; there is no second Live-only key, Worker URL, or Firebase sign-in step for this direct-key mode. The key is encrypted at rest with Android Keystore and is never committed to source control. As with any client-side BYOK app, a determined attacker with control of a compromised device may still inspect a key while the app is using it; use a key with appropriate API restrictions and quota limits.

1. In Google AI Studio, create a Gemini API key with access to a model that supports the Gemini Live API.
2. In THOR, open **Settings → AI Provider**, select **Google Gemini (own API key)**, paste the key once, and save.
3. Open **LIVE** and start a session. THOR reuses that saved key for the Live WebSocket connection.
4. Grant microphone permission when Android asks. Headphones are recommended to reduce echo.

Live Mode requires an internet connection while active and may incur Gemini API usage charges or quota limits. Ending the session closes the socket, stops microphone capture and playback, and clears the temporary caption. The optional Cloudflare /v1/live-token endpoint remains available for a future server-proxied deployment, but it is not required by the current Android Live client.


## AI provider setup

Open **Settings → AI Provider** to select the provider for that installation. Firebase Gemini uses THOR's managed Firebase AI Logic connection. Direct Gemini API accepts a Google AI Studio key; OpenAI / ChatGPT API accepts an OpenAI API key; Groq and OpenRouter have preset endpoints; Cloudflare Workers AI requires the account-specific endpoint; and Custom accepts an OpenAI-compatible chat-completions API. Each provider has its own model and key slot. Users can replace or remove saved keys in the app. Keys are encrypted at rest with Android Keystore AES-GCM and remain on that installation. API usage is billed separately from consumer subscriptions (for example, ChatGPT Plus does not include OpenAI API credits). Free tiers, quotas, and terms are controlled by each provider. A normal model API key does not automatically enable live web search or guarantee current affairs are up to date; that requires a provider's search/grounding feature or a separate search integration. Selecting Local-only keeps THOR's AI prompts on-device.

Credentials are encrypted at rest using an Android Keystore AES-GCM key. Android backup is disabled for this app. Credentials are not embedded in the APK or shared between devices. Never paste API keys into chat, source control, screenshots, or issue reports. A provider's free tier, rate limits, model names, and availability are controlled by that provider and can change. A 429 response triggers a one-hour per-provider cooldown and THOR falls back to local AI. This is a client-side BYOK design; a production multi-user service should proxy requests through a trusted backend rather than distribute a shared secret.

## Master Mode Google sign-in setup

Google sign-in for remote Master Mode requires a correctly configured Firebase Android OAuth client. The checked-in `app/google-services.json` currently has an empty `oauth_client` array, so this repository alone cannot complete Google sign-in until the Firebase project configuration is refreshed.

1. In Android Studio, run the Gradle task `app → Tasks → android → signingReport` (or run `./gradlew signingReport`) and record the SHA-1 and SHA-256 fingerprints for the debug keystore. Repeat for the release signing key used to distribute the app.
2. In Firebase Console, open project `thor-ai-2791b` → Project settings → Your apps → Android app `com.thor.assistant`. Add the debug SHA-1 and SHA-256 fingerprints; add the release fingerprints too before distributing a release build.
3. In Firebase Console → Authentication → Sign-in method, enable **Google** and select the project support email.
4. In Google Cloud Console for the same Firebase project, complete the OAuth consent screen / Google Auth Platform branding and audience setup. If the app is in Testing mode, add each tester's Google account as a test user.
5. Return to Firebase Project settings and download a fresh `google-services.json`. Replace `app/google-services.json` with that downloaded file; do not manually invent or edit OAuth client IDs. Confirm the file contains a Web OAuth client (client type 3) and that the Google Services Gradle plugin generates a non-empty `default_web_client_id`.
6. Rebuild and install the new APK. Test Google sign-in on Device A and Device B. Confirm Firebase Authentication → Users shows the signed-in account on each device.
7. Separately configure the Cloudflare Worker secrets, D1 schema, FCM credentials, and registered approver device for Master Mode approval. Google sign-in alone does not grant Master Mode; the Worker approval and device verification checks must also pass.

Do not share OAuth secrets, service-account JSON, API keys, or debug tokens in chat or commit them to Git. The Android Firebase config is a client configuration file, but it must still come from the correct Firebase project and app registration.

## Important limitations

- A successful CI build is not a physical-device test. The repository currently has no Android instrumentation test suite, so lint and the unit-test task do not prove real microphone, speaker, notification, or device compatibility.
- Android's speech-recognition service may require internet; requesting offline recognition does not guarantee offline speech-to-text.
- Hybrid TTS selects from voices installed in the Android speech engine; online voices are used only when the device reports validated internet, with local voices preferred offline. Voice quality and language coverage depend on the installed engine and voice data; it does not use or reproduce ChatGPT Live's proprietary voice.
- Automatic Boss voice recognition is not active; the speaker embedding work is experimental and not authentication.
- Remote Master Mode needs the Cloudflare Worker, D1 schema, Firebase secrets/accounts, FCM, and two-device testing.
- Remote Master Mode session administration is enforced by the Cloudflare Worker using the configured master UID and registered approver device. It is not a general app lock: ordinary phone controls remain available to anyone who can unlock and use this installation.
- Accessibility navigation requires the user to enable THOR Navigation manually in Android settings.
- Background Wake requires microphone and notification permissions and uses an Android foreground service; battery management and speech-provider behavior can still affect availability. Reminder alarms and FCM delivery can also be delayed by Android power and notification settings.

## Security and configuration

The Android Firebase configuration file identifies project `thor-ai-2791b` (project number
`418315674236`). Keep the Worker `FIREBASE_PROJECT_ID`, Firebase Web API key, and service-account
project consistent with that project. Never commit service-account JSON, private keys, passwords,
or App Check debug tokens.
