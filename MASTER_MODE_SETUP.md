# THOR Master Mode deployment status

The active Android implementation uses the Cloudflare Worker and D1 approval flow described in
[cloudflare/master-mode/README.md](cloudflare/master-mode/README.md).

## Active implementation

- Firebase project configured in the Android app: `thor-ai-2791b` (project number `418315674236`).
- Device A signs a one-time challenge with a P-256 Android Keystore key after BiometricPrompt approval.
- Cloudflare Worker verifies the signature and issues a Firebase custom token for Device B.
- Device B checks the server-side session and can end it with **I quit**; Device A can revoke it.
- The Worker project ID, Firebase Web API key, and service-account JSON must all belong to the same Firebase project.
- Keep service-account credentials and Worker secrets out of source control and chat.

## Legacy email OTP code

The repository still contains an older Firebase callable email-OTP implementation under `functions/`.
It is not called by the current Android app and is not a fallback for remote biometric approval.
Do not deploy it as part of the current Master Mode setup. The active implementation is the Cloudflare
Worker; follow its README and complete the two-device tests before relying on it.

## Verification boundary

A successful Android CI build verifies compilation and APK packaging only. It does not verify
Cloudflare deployment, FCM delivery, biometric hardware, Firebase account configuration, or
two-device session behavior. Those require deployment and tests on both physical phones.
