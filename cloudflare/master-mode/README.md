# THOR Master Mode approval service (Cloudflare Worker + D1)

This is the server-side foundation for remote biometric approval on the free Cloudflare Workers/D1 plans. It is intentionally separate from the Android app and must be deployed before the app can use it.

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

The Android branch includes the Worker API client, Firebase Messaging notification receiver, Device A registration with a Keystore public key, Device B request/poll flow, biometric approval prompt, Firebase custom-token sign-in, session status checks, an "I quit" session-end request, and a Device A session-revocation command (`revoke master sessions`). The latest Android CI build on this branch passed, but the app has not yet been installed or tested on physical phones. The Worker must be deployed and configured before the approval flow can work. Do not use the old email OTP flow as a security fallback; its Cloud Functions were not deployed.

## One-time Cloudflare setup

1. Install Node.js on a computer (or use an environment with Node/npm) and run `npm install` in this directory.
2. Create the D1 database: `npm run db:create`. Copy the returned database ID into `wrangler.toml`.
3. Apply the schema: `npm run db:migrate:remote`.
4. In Firebase Console, create/identify the Firebase Authentication account used only by Device A. Copy its UID and set it as the Worker secret `MASTER_UID`.
5. Create a service account for the same Firebase project with permission to send Firebase Cloud Messaging messages and mint Firebase custom tokens. Download its JSON privately. Never commit it.
6. Set secrets with Wrangler:
   - `npx wrangler secret put MASTER_UID`
   - `npx wrangler secret put FIREBASE_WEB_API_KEY` (use the Firebase project's Web API key from Project settings; it is an app identifier, not a password)
   - `npx wrangler secret put FIREBASE_SERVICE_ACCOUNT_JSON` (paste the complete service-account JSON when prompted)
7. Deploy: `npm run deploy`.
8. Test `GET https://YOUR-WORKER.workers.dev/health`. It should return `{"ok":true,"service":"thor-master-approval"}`.

## Firebase account preparation

1. In Firebase Console for `thor-ai-2791`, confirm Email/Password sign-in is enabled.
2. Create one Firebase Authentication user for Device A using the trusted master email. Copy that user's **UID** and configure it as the Cloudflare `MASTER_UID` secret.
3. Create a separate Firebase Authentication user for Device B. Device B's Firebase UID is the account that receives the custom-token Master session.
4. Never send either account password or the service-account JSON in this chat, GitHub, or a source file. Enter account passwords only in THOR's setup dialog on the relevant phone.

On Device A, open THOR > MASTER MODE SETUP, enter the Worker URL and the trusted Firebase account, then choose **SET UP DEVICE A**. On Device B, open the same dialog, enter the Worker URL and Device B Firebase account, then choose **REQUEST ON DEVICE B**. Device A receives a notification; tap it and approve only if you initiated the request.

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

## Deployment limitations and operational notes

- FCM approval alerts use a high-priority data-only message so `ThorMessagingService` can create a notification containing the request ID in its tap action while THOR is backgrounded. FCM is best-effort: a force-stopped app, disabled notifications, lost network, or aggressive battery management can delay or prevent notifications.
- The service-account JSON and master UID are secrets/configuration, not Android resources.
- The custom token is short-lived (one hour) and is exchanged by Firebase Auth for its normal persisted user session. The session itself has no fixed application timeout; remote revocation is enforced when the app checks the Worker.
- The Android client must check session revocation on app start/resume and before privileged actions. Offline operation cannot discover a new remote revocation until connectivity returns.
- Configure Firebase Authentication and FCM for the same project (`thor-ai-2791`). The Worker uses the Firebase project ID in `wrangler.toml`.
- Before production use, add automated integration tests, abuse monitoring, and a second-device test matrix. This backend source is not proof of a successful deployment.
