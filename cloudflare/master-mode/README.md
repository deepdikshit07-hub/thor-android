# THOR Master Mode approval service (Cloudflare Worker + D1)

This is the server-side foundation for remote biometric approval on the free Cloudflare Workers/D1 plans. It is intentionally separate from the Android app and must be deployed and configured before the app can use it.

## Security model

- Device A (the trusted phone) creates a P-256 key in Android Keystore. Its private key must never leave the phone.
- The Worker stores only Device A's public key and FCM registration token.
- Device B requests a short-lived challenge. The Worker creates a random nonce, stores the exact challenge, and sends an FCM notification.
- Device A retrieves the pending challenge and uses Android BiometricPrompt to authorize a Keystore signature.
- The Worker verifies the signature, atomically consumes the pending request, and creates a server-side session.
- Only Device B's authenticated Firebase UID can retrieve a Firebase custom token carrying `thorMaster: true`.
- The session can be revoked from Device A. Device B must check the session with the Worker to detect remote revocation.
- The Worker validates Firebase ID tokens through Firebase Identity Toolkit's `accounts:lookup` endpoint before accepting the account UID.

A fingerprint image or fingerprint template is never uploaded. Biometrics are processed by Android.

## Current implementation boundary

The Android branch includes the Worker API client, Firebase Messaging notification receiver, Device A registration with a Keystore public key, Device B request/poll flow, biometric approval prompt, Firebase custom-token sign-in, session status checks, an "I quit" session-end request, and a Device A session-revocation command (`revoke master sessions`). The app has not yet been installed or tested on physical phones. Do not use the old email OTP flow as a security fallback; its Cloud Functions were not deployed.

## One-time Cloudflare setup

1. Install Node.js in an environment with Node/npm and run `npm install` in this directory.
2. The D1 database is `thor-master-approval`; its ID is configured in `wrangler.toml`.
3. Apply the schema with `npm run db:migrate:remote` if it has not already been applied.
4. In Firebase Console, identify the Firebase Authentication account used only by Device A. Copy its UID and set it as the Worker secret `MASTER_UID`.
5. Create a service account for the same Firebase project with permission to send Firebase Cloud Messaging messages and mint Firebase custom tokens. Download its JSON privately. Never commit it.
6. Set these Worker secrets privately in Cloudflare:
   - `MASTER_UID`
   - `FIREBASE_WEB_API_KEY`
   - `FIREBASE_SERVICE_ACCOUNT_JSON`
7. The Worker also needs the plain-text variable `FIREBASE_PROJECT_ID=thor-ai-2791b` and D1 binding `DB` pointing to `thor-master-approval`.
8. Deploy from this directory with `npm run deploy`.
9. Test `GET https://YOUR-WORKER.workers.dev/health`. It should return `{"ok":true,"service":"thor-master-approval"}`.

## Firebase account preparation

1. In Firebase Console for `thor-ai-2791b`, open Authentication > Sign-in method and enable **Google**.
2. In Project settings > Your apps, add the SHA-1 and SHA-256 fingerprints for the signing certificate used to build/install THOR.
3. Download the refreshed `google-services.json` and replace `app/google-services.json` in the repository. It must contain a Web OAuth client (`client_type: 3`), which generates `default_web_client_id` for Credential Manager.
4. Create or identify one Firebase Authentication user for Device A using the intended Google account. Configure only that Firebase UID as the Cloudflare `MASTER_UID` secret.
5. Use a different Google account on Device B. Its Firebase UID is the requester account that receives the custom-token Master session.
6. Never enter or share a Google password in THOR, chat, GitHub, or source files. THOR uses Android Credential Manager and exchanges a Google ID token for a Firebase session.

On Device A, open THOR > MASTER MODE SETUP, enter the Worker URL, choose **SET UP DEVICE A**, and select the Google account whose UID is configured as `MASTER_UID`. On Device B, open the same dialog, enter the Worker URL, choose **REQUEST ON DEVICE B**, and select a different Google account. Device A receives a notification; tap it and approve only if you initiated the request.

## Gemini Live token provisioning

The same authenticated Worker now exposes `POST /v1/live-token` for the Android Live tab. It validates the Firebase ID token, accepts only an allowlisted voice name, limits each Firebase UID to 12 token requests per hour using the D1 `live_token_requests` table, and requests a single-use 30-minute Gemini Live token constrained to the `gemini-3.8-live` model and audio output. The permanent Google AI Studio key remains a Cloudflare Worker secret; the app receives only the short-lived token.

Add the Worker secret `GEMINI_API_KEY` in Cloudflare. Apply the updated D1 schema before deploying so the token-rate-limit table exists. Do not place the key in `wrangler.toml`, GitHub Actions variables, Android resources, or the repository.

## API outline

All endpoints except `/health` require `Authorization: Bearer <Firebase ID token>`.

- `POST /v1/devices/register`: register Device A as `role=approver` with `deviceId`, `publicKeyBase64`, and `fcmToken`; register Device B as `role=requester` with `deviceId`.
- `POST /v1/requests`: Device B requests approval with its `deviceId`.
- `GET /v1/requests/pending?deviceId=...`: Device A lists pending approvals.
- `GET /v1/requests/{requestId}?deviceId=...`: Device A retrieves the exact challenge.
- `POST /v1/requests/{requestId}/approve`: Device A submits `deviceId` and `signatureBase64`.
- `GET /v1/requests/{requestId}/result`: Device B retrieves the server-issued custom token after approval.
- `POST /v1/sessions/{sessionId}/revoke`: Device A revokes a session.
- `GET /v1/sessions/active?deviceId=...`: Device A lists active sessions.
- `POST /v1/sessions/{sessionId}/end`: Device B ends its own session ("I quit").
- `GET /v1/sessions/{sessionId}/check`: Device B checks whether its session is still active.
- `POST /v1/live-token`: authenticated Android client requests a single-use constrained Gemini Live token (requires `GEMINI_API_KEY`).

## Deployment limitations and operational notes

- FCM approval alerts use a high-priority data-only message so `ThorMessagingService` can create a notification containing the request ID in its tap action while THOR is backgrounded. FCM is best-effort: a force-stopped app, disabled notifications, lost network, or aggressive battery management can delay or prevent notifications.
- The service-account JSON and master UID are secrets/configuration, not Android resources.
- The custom token is short-lived (one hour) and is exchanged by Firebase Auth for its normal persisted user session. The session itself has no fixed application timeout; remote revocation is enforced when the app checks the Worker.
- The Android client must check session revocation on app start/resume and before privileged actions. Offline operation cannot discover a new remote revocation until connectivity returns.
- Configure Firebase Authentication and FCM for the same project (`thor-ai-2791b`). The Worker uses the Firebase project ID in `wrangler.toml`.
- Before production use, add integration tests, abuse monitoring, and a second-device test matrix. A successful deployment and a passing health check do not replace real-device verification.
