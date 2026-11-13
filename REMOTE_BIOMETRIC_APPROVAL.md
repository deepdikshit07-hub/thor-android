# THOR Remote Biometric Approval — implementation notes

## Agreed device roles
- Device A: user's Oppo A16 (Android 10), trusted approval phone.
- Device B: the other Android phone requesting Master Mode.
- Expected use: usually zero or one approval request per day.

## Session lifetime decision
- No 12-hour or other app-level timeout.
- Closing THOR, swiping it away, force-stopping it, or restarting Device B must not by itself end an approved session.
- The Android Firebase Authentication SDK persists a signed-in user across app restarts. THOR now restores Master Mode only when the restored ID token contains the server-issued `thorMaster: true` claim.
- Saying **I quit** signs out on Device B and clears its locally persisted Firebase session.
- Clearing THOR's app data, uninstalling it, explicit sign-out, or server-side revocation can end the session. Device A remote revocation still requires the trusted approval backend and is not implemented yet.
- Firebase ID tokens are refreshed by the SDK; the session has no fixed 12-hour application timer. This does not mean credentials can never be revoked.

## Existing device-local biometric primitive
`ThorDeviceApproval.kt` contains the P-256 Android Keystore signing primitive. It keeps the private key on Device A and uses BiometricPrompt for each signing operation. It is not yet connected to the app's UI or a pairing/approval server.

## Session persistence groundwork committed
- MainActivity now uses FragmentActivity, so the AndroidX BiometricPrompt helper can be integrated.
- THOR listens for Firebase Auth state changes and restores Master Mode only after checking the `thorMaster` custom claim.
- The `I quit` command signs out and therefore clears the persisted session.
- This does not activate remote biometric approval. The existing email OTP flow is still present and its callable Cloud Functions are not deployed on the Spark plan.

## Not yet implemented — do not treat remote biometric Master Mode as enabled
This stage does NOT pair phones, send push notifications, validate approval requests remotely, prevent server-side replay, or issue a Master credential after Device A fingerprint approval.

## Remaining stages
1. Secure pairing: authenticate both devices, register Device A's public key and Device B's identity, and confirm pairing on both phones.
2. Trusted approval service: validate signed-in device identity, short request expiry, one-time nonce consumption, rate limits, and explicit revocation.
3. FCM: add token registration and notification handling plus a trusted sender. A Cloudflare Worker can be used as a free-tier sender if its service-account credential is kept only in Worker secrets.
4. Device A approval UI: fetch the pending challenge, display the requesting device, require fingerprint approval, and send the ECDSA signature to the trusted service.
5. Device B session issuance: only the trusted service may issue the custom token with `thorMaster: true` after signature verification and replay checks.
6. Test both actual phones, including wrong fingerprint, expiry, replay, revocation, network loss, app close, force-stop, and device restart.

## Free-tier and delivery note
Cloudflare currently documents 100,000 Worker requests per day on its Free plan. Provider quotas and terms can change. FCM notification delivery is best-effort; force-stop, disabled notifications, battery restrictions, or lack of network can delay or prevent delivery. Never put service-account private keys or other server credentials in Android code or GitHub.
