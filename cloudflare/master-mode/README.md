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
- The Worker validates Firebase ID tokens using Google's tokeninfo endpoint and enforces the configured Firebase project audience/issuer.

A fingerprint image or fingerprint template is never uploaded. Biometrics are processed by Android.

## Important current boundary

This directory provides the backend service and D1 schema. The Android UI, FCM token registration/service, Firebase sign-in screens, pairing/setup flow, pending-request approval screen, result polling, and remote-revocation checks still need to be connected to these endpoints. Until that Android integration is complete and tested on both phones, **Master Mode is not ready to use**. Do not enable the old email OTP flow as a security fallback; its Cloud Functions were not deployed.

## One-time Cloudflare setup

1. Install Node.js on a computer (or use an environment with Node/npm) and run `npm install` in this directory.
2. Create the D1 database: `npm run db:create`. Copy the returned database ID into `wrangler.toml`.
3. Apply the schema: `npm run db:migrate:remote`.
4. In Firebase Console, create/identify the Firebase Authentication account used only by Device A. Copy its UID and set it as the Worker secret `MASTER_UID`.
5. Create a service account for the same Firebase project with permission to send Firebase Cloud Messaging messages and mint Firebase custom tokens. Download its JSON privately. Never commit it.
6. Set secrets with Wrangler:
   - `npx wrangler secret put MASTER_UID`
   - `npx wrangler secret put FIREBASE_SERVICE_ACCOUNT_JSON` (paste the complete service-account JSON when prompted)
7. Deploy: `npm run deploy`.
8. Test `GET https://YOUR-WORKER.workers.dev/health`. It should return `{"ok":true,"service":"thor-master-approval"}`.

## API outline

All endpoints except `/health` require `Authorization: Bearer <Firebase ID token>`.

- `POST /v1/devices/register`: register Device A as `role=approver` with `deviceId`, `publicKeyBase64`, and `fcmToken`; register Device B as `role=requester` with `deviceId`.
- `POST /v1/requests`: Device B requests approval with its `deviceId`.
- `GET /v1/requests/pending?deviceId=...`: Device A lists pending approvals.
- `GET /v1/requests/{requestId}?deviceId=...`: Device A retrieves the exact challenge.
- `POST /v1/requests/{requestId}/approve`: Device A submits `deviceId` and `signatureBase64`.
- `GET /v1/requests/{requestId}/result`: Device B retrieves the server-issued custom token after approval.
- `POST /v1/sessions/{sessionId}/revoke`: Device A revokes a session.
- `GET /v1/sessions/{sessionId}/check`: Device B checks whether its session is still active.

## Deployment limitations and operational notes

- FCM is best-effort. A force-stopped app, disabled notifications, lost network, or aggressive battery management can delay notifications.
- The service-account JSON and master UID are secrets/configuration, not Android resources.
- The custom token is short-lived (one hour) and is exchanged by Firebase Auth for its normal persisted user session. The session itself has no fixed application timeout; remote revocation is enforced when the app checks the Worker.
- The Android client must check session revocation on app start/resume and before privileged actions. Offline operation cannot discover a new remote revocation until connectivity returns.
- Configure Firebase Authentication and FCM for the same project (`thor-ai-2791`). The Worker uses the Firebase project ID in `wrangler.toml`.
- Before production use, add automated integration tests, abuse monitoring, and a second-device test matrix. This backend source is not proof of a successful deployment.
