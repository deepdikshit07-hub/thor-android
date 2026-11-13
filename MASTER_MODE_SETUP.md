# THOR Master Mode email OTP — deployment checklist

The Android client and callable Cloud Functions are staged on
`experiment/boss-speaker-verification`. The configured Master email is
`deepdikshit07@gmail.com`.

## Security model
- The client never generates or checks OTP values.
- Cloud Functions generates a cryptographically random six-digit code.
- Firestore stores an HMAC digest, expiry, attempt count, resend timestamp, and consumed flag.
- Codes expire after five minutes, are single-use, and allow at most five verification attempts.
- Requests are rate-limited to one per minute per configured Master email.
- Callable functions require Firebase App Check.
- Firestore rules deny client access to OTP records.
- Email API credentials and OTP HMAC secret are server-side Firebase secrets only.

## Required one-time setup (Firebase project owner)
1. Confirm Firebase project `thor-ai-2791b` is the intended project and that billing is enabled for Cloud Functions.
2. In Firebase Console, enable **Authentication → Sign-in method → Email/Password** (the app signs in with a server-created custom token; this is required for Auth user sessions).
3. Create or select the Firestore database in the same project.
4. Configure Firebase App Check for Android. For debug builds, register the local debug token in the App Check console; never commit or share that token. For production, configure Play Integrity and use a Play-distributed app.
5. Create a Resend account, verify a sending domain, and choose a sender address on that verified domain. The verified sender is not necessarily the Master recipient.
6. Install Node.js 20 and Firebase CLI in a trusted computer or Termux environment. From the repository root:
   ```sh
   npm --prefix functions install
   firebase login
   firebase use thor-ai-2791b
   firebase functions:secrets:set RESEND_API_KEY
   firebase functions:secrets:set OTP_HMAC_SECRET
   firebase functions:config:set # not needed; set the sender as a parameter below
   ```
   For `OTP_HMAC_SECRET`, use a long random value (at least 32 random bytes). Keep both secrets private.
7. Set the sender parameter in the Functions environment to a verified sender, e.g. `THOR <no-reply@your-verified-domain>`. Do not use the Master recipient as the sender unless that domain is verified.
8. Deploy:
   ```sh
   firebase deploy --only functions,firestore:rules
   ```
9. Verify the deployed functions are in region `asia-south1`, then run an end-to-end test on the app: request OTP, receive email, enter code, confirm Firebase Auth sign-in, and test wrong/expired/reused code behavior.
10. Before distributing, test App Check enforcement with the intended release signing certificate and confirm Play Integrity works. Debug App Check tokens must not be used in a release.

## Important status
The repository contains the implementation, but it is **not deployed or end-to-end verified**. A repository commit or successful Android CI build does not configure Firebase services, secrets, sender-domain verification, or App Check. Do not treat Master Mode as production-secure until the above steps and device tests pass.
