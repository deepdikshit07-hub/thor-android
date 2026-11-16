# THOR Remote Biometric Master Mode

## Agreed behavior

- Device A: the trusted Oppo A16 (Android 10).
- Device B: the other Android phone that requests Master Mode.
- Device B requests access; Device A receives an FCM notification and must approve with its enrolled fingerprint.
- The fingerprint template never leaves Device A. Android Keystore holds a P-256 private key; only the public key is registered with the Worker.
- No fixed 12-hour or other app-level session timer. Closing THOR, swiping it away, or restarting Device B does not itself end the Firebase-authenticated session.
- Saying **I quit** signs out Device B and asks the Worker to end that session.
- Device A can say **revoke master sessions** to list and revoke active sessions.
- Remote revocation is enforced the next time Device B checks in. It cannot be detected while Device B is offline.
- FCM delivery is best-effort. Force-stop, disabled notifications, battery restrictions, or no network can delay or prevent the notification.

## Code now present on the experimental branch

- `ThorDeviceApproval.kt`: P-256 Android Keystore signing with BiometricPrompt.
- `ThorMasterApprovalClient.kt`: HTTPS client for the Cloudflare Worker API.
- `ThorMessagingService.kt`: FCM notification handling and notification tap routing.
- `MainActivity.kt`: Device A registration, Device B request/poll, fingerprint approval, custom-token sign-in, session check, local sign-out, and Device A session revocation command.
- `cloudflare/master-mode/src/index.js`: Worker endpoints for device registration, short-lived approval requests, FCM sending, ECDSA signature verification, Firebase custom-token issuance, session checking and revocation.
- `cloudflare/master-mode/schema.sql`: D1 tables for registered devices, one-time approval requests and sessions.
- `cloudflare/master-mode/README.md`: deployment and Firebase account setup.

The legacy email OTP client flow has been removed. Firebase Cloud Functions are not used by this implementation. The Worker uses Firebase Identity Toolkit to validate Firebase ID tokens and a private service-account key kept only in Cloudflare Worker secrets.

## Not yet a production-ready release

The source is prepared, but the system is **not ready to use** until all of these are complete:

1. Deploy the Cloudflare Worker and create/apply its D1 database schema.
2. Configure `MASTER_UID`, `FIREBASE_WEB_API_KEY`, and `FIREBASE_SERVICE_ACCOUNT_JSON` as Worker secrets.
3. Create the Device A Firebase Email/Password account and a separate Device B Firebase account.
4. Register Device A and Device B from the app using the deployed Worker URL.
5. Build the current experimental branch successfully.
6. Test the real phones for valid/invalid fingerprint, expired request, replay attempt, notification delivery, app close/restart, `I quit`, and remote revocation.

The latest Android build must be checked before installing. A successful CI build alone does not prove that FCM, Worker deployment, biometric hardware, or two-device session behavior works.

## Important boundaries

- The Worker and Android app must use the same Firebase project: `thor-ai-2791`.
- Device A must sign in with the Firebase account whose UID is configured as `MASTER_UID`.
- Device B must use a different Firebase account. The Worker rejects registering the master account as a requester.
- Never put the service-account JSON/private key in GitHub, Android code, or this chat.
- The custom token is short-lived and exchanged by Firebase Auth for its persisted session. The session has no fixed app-level expiry; the Worker can revoke it.
- If Device B is offline, it cannot learn about remote revocation until it reconnects.
- Voice matching is still experimental and is not used as the security factor for Master Mode.
- This feature does not yet gate any additional THOR commands; it establishes and validates the Master Mode identity/session state for later protected features.
