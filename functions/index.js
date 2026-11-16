const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret, defineString } = require("firebase-functions/params");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, Timestamp } = require("firebase-admin/firestore");
const crypto = require("node:crypto");

initializeApp();

const db = getFirestore();
const RESEND_API_KEY = defineSecret("RESEND_API_KEY");
const OTP_HMAC_SECRET = defineSecret("OTP_HMAC_SECRET");
const RESEND_FROM_EMAIL = defineString("RESEND_FROM_EMAIL");

const MASTER_EMAIL = "deepdikshit07@gmail.com";
const OTP_TTL_MS = 5 * 60 * 1000;
const RESEND_COOLDOWN_MS = 60 * 1000;
const MAX_ATTEMPTS = 5;

function otpDocumentId(email) {
  return crypto.createHash("sha256").update(email.toLowerCase()).digest("hex");
}

function hashOtp(email, otp, secret) {
  return crypto.createHmac("sha256", secret)
    .update(email.toLowerCase() + ":" + otp)
    .digest("hex");
}

function safeEqualHex(left, right) {
  if (typeof left !== "string" || typeof right !== "string" ||
      !/^[a-f0-9]{64}$/.test(left) || !/^[a-f0-9]{64}$/.test(right)) {
    return false;
  }
  return crypto.timingSafeEqual(Buffer.from(left, "hex"), Buffer.from(right, "hex"));
}

exports.requestMasterOtp = onCall(
  {
    region: "asia-south1",
    enforceAppCheck: true,
    secrets: [RESEND_API_KEY, OTP_HMAC_SECRET],
  },
  async (request) => {
    if (!request.app) {
      throw new HttpsError("failed-precondition", "App verification is required.");
    }

    const email = MASTER_EMAIL;
    const docRef = db.collection("masterOtp").doc(otpDocumentId(email));
    const now = Date.now();
    const otp = crypto.randomInt(0, 1000000).toString().padStart(6, "0");
    const otpHash = hashOtp(email, otp, OTP_HMAC_SECRET.value());

    await db.runTransaction(async (tx) => {
      const snapshot = await tx.get(docRef);
      const data = snapshot.exists ? snapshot.data() : {};
      const lastSentAt = data.lastSentAt ? data.lastSentAt.toMillis() : 0;
      if (now - lastSentAt < RESEND_COOLDOWN_MS) {
        throw new HttpsError("resource-exhausted", "Please wait before requesting another code.");
      }
      tx.set(docRef, {
        otpHash,
        expiresAt: Timestamp.fromMillis(now + OTP_TTL_MS),
        lastSentAt: Timestamp.fromMillis(now),
        attempts: 0,
        consumed: false,
      });
    });

    const response = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        "Authorization": "Bearer " + RESEND_API_KEY.value(),
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        from: RESEND_FROM_EMAIL.value(),
        to: [email],
        subject: "Your THOR Master Mode verification code",
        text: "Your THOR Master Mode code is " + otp +
          ". It expires in 5 minutes. If you did not request this code, you can ignore this email.",
      }),
    });

    if (!response.ok) {
      await docRef.set({ consumed: true, otpHash: "", expiresAt: Timestamp.fromMillis(0) }, { merge: true });
      console.error("Resend delivery failed:", response.status);
      throw new HttpsError("unavailable", "Email delivery is temporarily unavailable.");
    }

    return { sent: true, destination: email.replace(/^(.).+(@.+)$/, "$1••••$2") };
  }
);

exports.verifyMasterOtp = onCall(
  {
    region: "asia-south1",
    enforceAppCheck: true,
    secrets: [OTP_HMAC_SECRET],
  },
  async (request) => {
    if (!request.app) {
      throw new HttpsError("failed-precondition", "App verification is required.");
    }

    const otp = typeof request.data?.code === "string" ? request.data.code.trim() : "";
    if (!/^\d{6}$/.test(otp)) {
      throw new HttpsError("invalid-argument", "Enter the six-digit code.");
    }

    const email = MASTER_EMAIL;
    const docRef = db.collection("masterOtp").doc(otpDocumentId(email));
    const now = Date.now();
    let accepted = false;

    await db.runTransaction(async (tx) => {
      const snapshot = await tx.get(docRef);
      if (!snapshot.exists) {
        throw new HttpsError("permission-denied", "No active verification code. Request a new one.");
      }
      const data = snapshot.data();
      if (data.consumed || !data.expiresAt || data.expiresAt.toMillis() <= now) {
        throw new HttpsError("deadline-exceeded", "That code has expired. Request a new one.");
      }
      if ((data.attempts || 0) >= MAX_ATTEMPTS) {
        throw new HttpsError("resource-exhausted", "Too many incorrect attempts. Request a new code.");
      }

      const candidateHash = hashOtp(email, otp, OTP_HMAC_SECRET.value());
      if (!safeEqualHex(data.otpHash, candidateHash)) {
        tx.update(docRef, { attempts: (data.attempts || 0) + 1 });
        return;
      }

      tx.update(docRef, { consumed: true, otpHash: "" });
      accepted = true;
    });

    if (!accepted) {
      throw new HttpsError("permission-denied", "That verification code is incorrect.");
    }

    const user = await getAuth().getUserByEmail(email).catch(async (error) => {
      if (error.code !== "auth/user-not-found") throw error;
      return getAuth().createUser({ email, emailVerified: true, displayName: "THOR Master" });
    });

    const customToken = await getAuth().createCustomToken(user.uid, { thorMaster: true });
    return { verified: true, customToken };
  }
);
