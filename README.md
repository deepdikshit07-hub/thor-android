# THOR Android Assistant

THOR is an experimental Android personal assistant project. The active development branch is
`experiment/boss-speaker-verification`; keep it separate from the main branch while features are
being validated.

## Current project features

- Dark, blue-accented assistant interface with six bottom-navigation sections.
- Dedicated THOR LIVE bottom-navigation section with animated energy core, bidirectional Gemini Live audio, voice interruption support, and session-only captions (no local chat transcript storage).
- Animated holographic reactor HUD and startup animation.
- Custom THOR launcher artwork.
- Text commands, Android speech-recognition handoff, and hybrid multilingual Text-to-Speech (prefers installed network voices online and installed local voices offline).
- English, Hindi, and Bengali response selection.
- Local notes, tasks, user-taught lessons, and reminders.
- App launching, selected Android settings shortcuts, dialer/SMS composer handoff, and media-key controls.
- Optional on-device text generation using the bundled Qwen LiteRT model.
- Per-device AI Provider Manager: choose Firebase Gemini, Groq, OpenRouter, Cloudflare Workers AI, a custom OpenAI-compatible endpoint, or local-only AI. Each provider keeps its own model, endpoint, and encrypted API credential on that installation.
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

## THOR Live Mode setup

THOR Live uses Google's bidirectional Gemini Live API rather than the normal text-chat request path. It streams microphone PCM audio directly to Gemini over a WebSocket and plays native model audio; the selected THOR voice style maps to a supported Gemini prebuilt voice. Live captions are transient UI text only. THOR does not append Live turns to the chat screen or write them to the app's chat/history storage. Ending the session closes the socket, stops microphone capture and playback, and clears the latest caption.

Live Mode uses the Cloudflare Worker URL already configured in **Master Mode Setup**. It also requires:
1. Enable **Anonymous** sign-in in Firebase Authentication (or sign in through an existing THOR Firebase account).
2. In Cloudflare Worker secrets, add `GEMINI_API_KEY` containing a Google AI Studio API key with Gemini Live API access. Never put this key in the APK, GitHub, or chat.
3. Apply the updated D1 schema from `cloudflare/master-mode/schema.sql` using `npm run db:migrate:remote`, then deploy the updated Worker using `npm run deploy`.
4. Keep the Firebase Web API key configured as `FIREBASE_WEB_API_KEY` Worker secret. The Worker authenticates Firebase ID tokens and limits token issuance to 12 requests per account per hour. Each issued Live token is single-use and short-lived.
5. Open THOR's **LIVE** tab and start a session. The app uses the Worker URL configured on that device.

Live Mode uses network data continuously while active and may incur Gemini API usage charges or quota limits. Use Wi-Fi or review the provider's pricing/quota before long sessions. Headphones are recommended to reduce speaker-to-microphone echo. The app stops the live microphone session when it leaves the foreground.

## AI provider setup

Open **Settings → AI Provider** to select the provider for that installation. Gemini uses the existing Firebase AI Logic integration. Other hosted providers require the user's own API key, model ID, and (where applicable) HTTPS chat-completions endpoint. Groq and OpenRouter have preset endpoints; Cloudflare Workers AI requires the account-specific endpoint. The generic adapter supports OpenAI-compatible chat-completions APIs only.

Credentials are encrypted at rest using an Android Keystore AES-GCM key. Android backup is disabled for this app. Credentials are not embedded in the APK or shared between devices. Never paste API keys into chat, source control, screenshots, or issue reports. A provider's free tier, rate limits, model names, and availability are controlled by that provider and can change. A 429 response triggers a one-hour per-provider cooldown and THOR falls back to local AI. This is a client-side BYOK design; a production multi-user service should proxy requests through a trusted backend rather than distribute a shared secret.

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
