# THOR Remote Biometric Approval — implementation notes

## Agreed device roles
- Device A: user's Oppo A16 (Android 10), trusted approval phone.
- Device B: the other Android phone requesting Master Mode.
- Expected use: usually zero or one approval request per day.

## Stage 1 committed
`ThorDeviceApproval.kt` provides the device-local cryptographic primitive:
- Generates a P-256 ECDSA key pair in Android Keystore.
- Requires biometric authentication for every signing operation.
- Exports only the public key for a future trusted pairing flow.
- Signs a supplied one-time challenge after BiometricPrompt succeeds.
- Includes public-key signature verification for tests/integration.
- Supports deleting the local key.

No fingerprint image, template, or biometric data is read or transmitted. The private key is not exportable from Android Keystore.

## Not yet implemented — do not treat Master Mode as enabled
This stage does NOT pair phones, send push notifications, validate requests remotely, prevent server-side replay, or unlock Master Mode. The existing email OTP client flow remains unchanged and still depends on the previously planned callable Cloud Functions; it is not deployed on Firebase Spark.

## Remaining stages
1. Pairing protocol: authenticated setup, device identity, public-key registration, explicit confirmation on both phones.
2. Trusted approval service: validate signed-in device identity, request expiry, one-time nonce consumption, and rate limits.
3. FCM: add Firebase Messaging receiver/token registration and a server-side sender. A Cloudflare Worker can be evaluated for the free-tier sender, but its credentials must remain in Worker secrets, never in the Android app or repository.
4. Device B verification: verify the server's approval result and issue a short-lived session only after backend validation.
5. Test on both actual phones, including cancellation, wrong fingerprint, expired request, replay, network loss, and app force-stop.

## Free-tier note
The expected low request volume is well below typical free request quotas, but quotas and provider terms can change. Push delivery is best-effort; force-stopped apps, disabled notifications, battery restrictions, or lack of network can delay or prevent delivery.
