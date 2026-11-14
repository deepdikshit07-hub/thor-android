const json = (value, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
  });

const b64url = (bytes) => {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
};
const utf8 = (value) => new TextEncoder().encode(value);
const derEcdsaToRaw = (der) => {
  // Android Signature returns ASN.1 DER; WebCrypto ECDSA verify expects fixed-width r||s.
  if (der.length < 8 || der[0] !== 0x30) throw new Error("BAD_SIGNATURE");
  let offset = 2;
  if (der[1] & 0x80) {
    const count = der[1] & 0x7f;
    if (count < 1 || count > 2 || der.length < 2 + count) throw new Error("BAD_SIGNATURE");
    offset = 2 + count;
  }
  if (der[offset++] !== 0x02) throw new Error("BAD_SIGNATURE");
  const rLen = der[offset++];
  const r = der.slice(offset, offset + rLen); offset += rLen;
  if (der[offset++] !== 0x02) throw new Error("BAD_SIGNATURE");
  const sLen = der[offset++];
  const s = der.slice(offset, offset + sLen);
  const trim = (v) => v.length > 32 && v[0] === 0 ? v.slice(1) : v;
  const rr = trim(r), ss = trim(s);
  if (rr.length > 32 || ss.length > 32) throw new Error("BAD_SIGNATURE");
  const raw = new Uint8Array(64);
  raw.set(rr, 32 - rr.length);
  raw.set(ss, 64 - ss.length);
  return raw;
};
const randomId = (bytes = 24) => {
  const data = crypto.getRandomValues(new Uint8Array(bytes));
  return b64url(data);
};
const pemBytes = (pem) => {
  const body = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const raw = atob(body);
  return Uint8Array.from(raw, c => c.charCodeAt(0));
};
const signJwt = async (claims, privateKeyPem) => {
  const header = b64url(utf8(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const payload = b64url(utf8(JSON.stringify(claims)));
  const unsigned = header + "." + payload;
  const key = await crypto.subtle.importKey(
    "pkcs8", pemBytes(privateKeyPem),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, utf8(unsigned));
  return unsigned + "." + b64url(new Uint8Array(signature));
};

async function authenticate(request, env) {
  const auth = request.headers.get("authorization") || "";
  if (!auth.startsWith("Bearer ")) throw new Error("AUTH_REQUIRED");
  const token = auth.slice(7);
  if (token.length > 8192 || !env.FIREBASE_WEB_API_KEY) throw new Error("BAD_TOKEN");
  // Firebase Identity Toolkit validates the Firebase ID token and returns its account UID.
  const response = await fetch(
    "https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=" + encodeURIComponent(env.FIREBASE_WEB_API_KEY),
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ idToken: token })
    }
  );
  if (!response.ok) throw new Error("BAD_TOKEN");
  const payload = await response.json();
  const account = payload.users?.[0];
  if (!account?.localId || account.disabled === true) throw new Error("BAD_TOKEN");
  return { uid: account.localId, claims: account };
}

async function sendFcm(env, token, data) {
  const account = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT_JSON);
  if (!account.client_email || !account.private_key || !account.project_id ||
      account.project_id !== env.FIREBASE_PROJECT_ID) throw new Error("FCM_CONFIG");
  const now = Math.floor(Date.now() / 1000);
  const assertion = await signJwt({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  }, account.private_key);
  const oauth = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion
    })
  });
  if (!oauth.ok) throw new Error("FCM_OAUTH_FAILED");
  const { access_token } = await oauth.json();
  const sent = await fetch("https://fcm.googleapis.com/v1/projects/" + account.project_id + "/messages:send", {
    method: "POST",
    headers: { authorization: "Bearer " + access_token, "content-type": "application/json" },
    body: JSON.stringify({
      message: {
        token,
        // Data-only messages are required so FirebaseMessagingService receives
        // the message in the background and can attach the request ID to our
        // custom notification's PendingIntent.
        data: { type: "thor_master_approval", requestId: data.requestId },
        android: { priority: "high" }
      }
    })
  });
  if (!sent.ok) throw new Error("FCM_SEND_FAILED");
}

async function issueFirebaseCustomToken(env, uid, sessionId) {
  const account = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT_JSON);
  const now = Math.floor(Date.now() / 1000);
  return signJwt({
    iss: account.client_email,
    sub: account.client_email,
    aud: "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit",
    iat: now,
    exp: now + 3600,
    uid,
    claims: { thorMaster: true, thorSessionId: sessionId }
  }, account.private_key);
}

async function requireDevice(env, deviceId, uid, role) {
  const row = await env.DB.prepare(
    "SELECT * FROM devices WHERE device_id=? AND firebase_uid=? AND role=? AND revoked_at IS NULL"
  ).bind(deviceId, uid, role).first();
  if (!row) throw new Error("DEVICE_NOT_REGISTERED");
  return row;
}

async function handle(request, env) {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/health") return json({ ok: true, service: "thor-master-approval" });
  if (url.pathname.startsWith("/v1/") && request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization,content-type", "access-control-allow-methods": "GET,POST,OPTIONS" } });
  }
  let identity;
  try { identity = await authenticate(request, env); }
  catch (_) { return json({ error: "Authentication required." }, 401); }

  let body = {};
  if (request.method === "POST") {
    const declaredLength = Number(request.headers.get("content-length") || 0);
    if (declaredLength > 16384) return json({ error: "Request body is too large." }, 413);
    let rawBody = "";
    try { rawBody = await request.text(); } catch (_) { return json({ error: "Invalid request body." }, 400); }
    if (rawBody.length > 16384) return json({ error: "Request body is too large." }, 413);
    try { body = rawBody ? JSON.parse(rawBody) : {}; } catch (_) { return json({ error: "Invalid JSON." }, 400); }
    if (!body || typeof body !== "object" || Array.isArray(body)) return json({ error: "JSON object required." }, 400);
  }

  try {
    if (request.method === "POST" && url.pathname === "/v1/live-token") {
      if (!env.GEMINI_API_KEY) return json({ error: "Live Mode is not configured on the server." }, 503);
      const voiceName = body.voiceName;
      const allowedVoices = ["Charon", "Aoede", "Kore", "Puck"];
      if (typeof voiceName !== "string" || !allowedVoices.includes(voiceName)) {
        return json({ error: "Unsupported THOR voice selection." }, 400);
      }
      const now = Math.floor(Date.now() / 1000);
      const usage = await env.DB.prepare(
        "INSERT INTO live_token_requests(firebase_uid,created_at) SELECT ?,? WHERE (SELECT COUNT(*) FROM live_token_requests WHERE firebase_uid=? AND created_at>?) < 12"
      ).bind(identity.uid, now, identity.uid, now - 3600).run();
      if (!usage?.meta?.changes) return json({ error: "Live Mode token limit reached. Try again later." }, 429);

      const tokenResponse = await fetch("https://generativelanguage.googleapis.com/v1beta/auth_tokens", {
        method: "POST",
        headers: {
          "x-goog-api-key": env.GEMINI_API_KEY,
          "content-type": "application/json"
        },
        body: JSON.stringify({
          uses: 1,
          expireTime: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
          newSessionExpireTime: new Date(Date.now() + 60 * 1000).toISOString(),
          liveConnectConstraints: {
            model: "models/gemini-3.8-live",
            config: {
              sessionResumption: {},
              responseModalities: ["AUDIO"],
              speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName } } },
              inputAudioTranscription: {},
              outputAudioTranscription: {}
            }
          }
        })
      });
      const tokenPayload = await tokenResponse.json().catch(() => ({}));
      if (!tokenResponse.ok || typeof tokenPayload.name !== "string") {
        console.error("Gemini Live token provisioning failed", tokenResponse.status);
        return json({ error: "Gemini Live could not issue a session token. Check the server API key and Live API access." }, 502);
      }
      return json({ token: tokenPayload.name, expiresInSeconds: 1800 });
    }

    if (request.method === "POST" && url.pathname === "/v1/devices/register") {
      const { deviceId, role, publicKeyBase64, fcmToken } = body;
      if (typeof deviceId !== "string" || !/^[A-Za-z0-9_-]{8,80}$/.test(deviceId) ||
          !["approver", "requester"].includes(role)) return json({ error: "Invalid device registration." }, 400);
      if (role === "approver" && identity.uid !== env.MASTER_UID) return json({ error: "Only the configured master account can register the trusted phone." }, 403);
      if (role === "requester" && identity.uid === env.MASTER_UID) return json({ error: "Use a separate Firebase account on Device B." }, 403);
      if (role === "approver" && (typeof publicKeyBase64 !== "string" || publicKeyBase64.length > 2048 || typeof fcmToken !== "string" || fcmToken.length > 4096)) return json({ error: "Trusted phone requires its public key and FCM token." }, 400);
      if (role === "requester" && fcmToken !== undefined && (typeof fcmToken !== "string" || fcmToken.length > 4096)) return json({ error: "Invalid FCM token." }, 400);
      const now = Math.floor(Date.now() / 1000);
      await env.DB.prepare(
        "INSERT INTO devices(device_id,firebase_uid,role,public_key_b64,fcm_token,created_at) VALUES(?,?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET firebase_uid=excluded.firebase_uid,role=excluded.role,public_key_b64=excluded.public_key_b64,fcm_token=excluded.fcm_token,revoked_at=NULL"
      ).bind(deviceId, identity.uid, role, role === "approver" ? publicKeyBase64 : null, fcmToken || null, now).run();
      return json({ ok: true, deviceId, role });
    }

    if (request.method === "POST" && url.pathname === "/v1/requests") {
      const { deviceId } = body;
      if (typeof deviceId !== "string" || !/^[A-Za-z0-9_-]{8,80}$/.test(deviceId)) return json({ error: "Valid device ID required." }, 400);
      await requireDevice(env, deviceId, identity.uid, "requester");
      const approver = await env.DB.prepare(
        "SELECT device_id,fcm_token FROM devices WHERE firebase_uid=? AND role='approver' AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1"
      ).bind(env.MASTER_UID).first();
      if (!approver?.fcm_token) return json({ error: "Trusted phone is not registered or has no notification token." }, 409);
      const now = Math.floor(Date.now() / 1000);
      const recent = await env.DB.prepare(
        "SELECT COUNT(*) AS n FROM approval_requests WHERE requester_uid=? AND created_at>?"
      ).bind(identity.uid, now - 3600).first();
      if ((recent?.n || 0) >= 5) return json({ error: "Too many requests. Try again later." }, 429);
      const requestId = randomId(18), nonce = randomId(32), expiresAt = now + 180;
      const challenge = JSON.stringify({
        purpose: "THOR_MASTER_APPROVAL_V1",
        requestId, nonce, requesterUid: identity.uid,
        requesterDeviceId: deviceId, expiresAt
      });
      await env.DB.prepare(
        "INSERT INTO approval_requests(request_id,nonce,challenge,requester_uid,requester_device_id,status,created_at,expires_at) VALUES(?,?,?,?,?,'pending',?,?)"
      ).bind(requestId, nonce, challenge, identity.uid, deviceId, now, expiresAt).run();
      try { await sendFcm(env, approver.fcm_token, { requestId }); }
      catch (_) { await env.DB.prepare("UPDATE approval_requests SET status='expired' WHERE request_id=?").bind(requestId).run(); return json({ error: "Notification could not be delivered. Check the trusted phone's connection and FCM setup." }, 502); }
      return json({ ok: true, requestId, expiresAt, expiresInSeconds: 180 });
    }

    if (request.method === "GET" && url.pathname === "/v1/requests/pending") {
      if (identity.uid !== env.MASTER_UID) return json({ error: "Forbidden." }, 403);
      const deviceId = url.searchParams.get("deviceId") || "";
      await requireDevice(env, deviceId, identity.uid, "approver");
      const rows = await env.DB.prepare(
        "SELECT request_id,requester_uid,requester_device_id,created_at,expires_at FROM approval_requests WHERE status='pending' AND expires_at>? ORDER BY created_at DESC LIMIT 10"
      ).bind(Math.floor(Date.now() / 1000)).all();
      return json({ requests: rows.results || [] });
    }

    const detailMatch = url.pathname.match(/^\/v1\/requests\/([A-Za-z0-9_-]+)$/);
    if (request.method === "GET" && detailMatch) {
      if (identity.uid !== env.MASTER_UID) return json({ error: "Forbidden." }, 403);
      const deviceId = url.searchParams.get("deviceId") || "";
      await requireDevice(env, deviceId, identity.uid, "approver");
      const row = await env.DB.prepare(
        "SELECT request_id,challenge,requester_uid,requester_device_id,expires_at,status FROM approval_requests WHERE request_id=?"
      ).bind(detailMatch[1]).first();
      if (!row || row.status !== "pending" || row.expires_at <= Math.floor(Date.now() / 1000)) return json({ error: "Request is no longer pending." }, 410);
      return json(row);
    }

    const denyMatch = url.pathname.match(/^\/v1\/requests\/([A-Za-z0-9_-]+)\/deny$/);
    if (request.method === "POST" && denyMatch) {
      if (identity.uid !== env.MASTER_UID) return json({ error: "Forbidden." }, 403);
      const deviceId = body.deviceId;
      await requireDevice(env, deviceId, identity.uid, "approver");
      const now = Math.floor(Date.now() / 1000);
      const result = await env.DB.prepare(
        "UPDATE approval_requests SET status='denied' WHERE request_id=? AND status='pending' AND expires_at>?"
      ).bind(denyMatch[1], now).run();
      if (!result?.meta?.changes) return json({ error: "Request is expired or already handled." }, 410);
      return json({ ok: true, denied: true });
    }

    const approveMatch = url.pathname.match(/^\/v1\/requests\/([A-Za-z0-9_-]+)\/approve$/);
    if (request.method === "POST" && approveMatch) {
      if (identity.uid !== env.MASTER_UID) return json({ error: "Forbidden." }, 403);
      const { deviceId, signatureBase64 } = body;
      const device = await requireDevice(env, deviceId, identity.uid, "approver");
      if (typeof signatureBase64 !== "string" || signatureBase64.length > 1024) return json({ error: "Invalid signature." }, 400);
      const row = await env.DB.prepare(
        "SELECT * FROM approval_requests WHERE request_id=?"
      ).bind(approveMatch[1]).first();
      const now = Math.floor(Date.now() / 1000);
      if (!row || row.status !== "pending" || row.expires_at <= now) return json({ error: "Request expired or already used." }, 410);
      const publicKey = await crypto.subtle.importKey(
        "spki", Uint8Array.from(atob(device.public_key_b64), c => c.charCodeAt(0)),
        { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]
      );
      const derSignature = Uint8Array.from(atob(signatureBase64), c => c.charCodeAt(0));
      const signature = derEcdsaToRaw(derSignature);
      const valid = await crypto.subtle.verify(
        { name: "ECDSA", hash: "SHA-256" }, publicKey, signature, utf8(row.challenge)
      );
      if (!valid) return json({ error: "Fingerprint approval signature did not verify." }, 403);
      const sessionId = randomId(24);
      const results = await env.DB.batch([
        env.DB.prepare(
          "UPDATE approval_requests SET status='approved',approved_at=?,session_id=?,signature_b64=? WHERE request_id=? AND status='pending' AND expires_at>?"
        ).bind(now, sessionId, signatureBase64, row.request_id, now),
        env.DB.prepare(
          "INSERT INTO master_sessions(session_id,request_id,requester_uid,requester_device_id,created_at) SELECT ?,?,?,?,? WHERE EXISTS (SELECT 1 FROM approval_requests WHERE request_id=? AND status='approved' AND session_id=?)"
        ).bind(sessionId, row.request_id, row.requester_uid, row.requester_device_id, now, row.request_id, sessionId)
      ]);
      if (!results[0]?.meta?.changes || !results[1]?.meta?.changes) return json({ error: "Request was already consumed." }, 409);
      return json({ ok: true, sessionId });
    }

    const resultMatch = url.pathname.match(/^\/v1\/requests\/([A-Za-z0-9_-]+)\/result$/);
    if (request.method === "GET" && resultMatch) {
      const deviceId = url.searchParams.get("deviceId") || "";
      if (!/^[A-Za-z0-9_-]{8,80}$/.test(deviceId)) return json({ error: "Valid device ID required." }, 400);
      await requireDevice(env, deviceId, identity.uid, "requester");
      const row = await env.DB.prepare(
        "SELECT r.status,r.requester_uid,r.requester_device_id,r.session_id,s.revoked_at FROM approval_requests r LEFT JOIN master_sessions s ON s.session_id=r.session_id WHERE r.request_id=?"
      ).bind(resultMatch[1]).first();
      if (!row || row.requester_uid !== identity.uid || row.requester_device_id !== deviceId) return json({ error: "Request not found." }, 404);
      if (row.status !== "approved" || !row.session_id || row.revoked_at) return json({ status: row.status });
      const token = await issueFirebaseCustomToken(env, identity.uid, row.session_id);
      return json({ status: "approved", customToken: token, sessionId: row.session_id });
    }

    const sessionMatch = url.pathname.match(/^\/v1\/sessions\/([A-Za-z0-9_-]+)\/revoke$/);
    if (request.method === "POST" && sessionMatch) {
      if (identity.uid !== env.MASTER_UID) return json({ error: "Forbidden." }, 403);
      const deviceId = body.deviceId;
      await requireDevice(env, deviceId, identity.uid, "approver");
      const now = Math.floor(Date.now() / 1000);
      await env.DB.prepare("UPDATE master_sessions SET revoked_at=? WHERE session_id=? AND revoked_at IS NULL").bind(now, sessionMatch[1]).run();
      await env.DB.prepare("UPDATE approval_requests SET status='revoked' WHERE session_id=?").bind(sessionMatch[1]).run();
      return json({ ok: true, revoked: true });
    }

    const endMatch = url.pathname.match(/^\/v1\/sessions\/([A-Za-z0-9_-]+)\/end$/);
    if (request.method === "POST" && endMatch) {
      const deviceId = body.deviceId;
      if (typeof deviceId !== "string" || !/^[A-Za-z0-9_-]{8,80}$/.test(deviceId)) return json({ error: "Valid device ID required." }, 400);
      await requireDevice(env, deviceId, identity.uid, "requester");
      const now = Math.floor(Date.now() / 1000);
      await env.DB.prepare(
        "UPDATE master_sessions SET revoked_at=? WHERE session_id=? AND requester_uid=? AND requester_device_id=? AND revoked_at IS NULL"
      ).bind(now, endMatch[1], identity.uid, deviceId).run();
      await env.DB.prepare(
        "UPDATE approval_requests SET status='revoked' WHERE session_id=? AND requester_uid=? AND requester_device_id=?"
      ).bind(endMatch[1], identity.uid, deviceId).run();
      return json({ ok: true, ended: true });
    }

    const activeMatch = url.pathname === "/v1/sessions/active";
    if (request.method === "GET" && activeMatch) {
      if (identity.uid !== env.MASTER_UID) return json({ error: "Forbidden." }, 403);
      const deviceId = url.searchParams.get("deviceId") || "";
      await requireDevice(env, deviceId, identity.uid, "approver");
      const rows = await env.DB.prepare(
        "SELECT session_id,requester_uid,requester_device_id,created_at FROM master_sessions WHERE revoked_at IS NULL ORDER BY created_at DESC LIMIT 25"
      ).all();
      return json({ sessions: rows.results || [] });
    }

    const checkMatch = url.pathname.match(/^\/v1\/sessions\/([A-Za-z0-9_-]+)\/check$/);
    if (request.method === "GET" && checkMatch) {
      const deviceId = url.searchParams.get("deviceId") || "";
      if (!/^[A-Za-z0-9_-]{8,80}$/.test(deviceId)) return json({ active: false }, 403);
      await requireDevice(env, deviceId, identity.uid, "requester");
      const row = await env.DB.prepare(
        "SELECT requester_uid,requester_device_id,revoked_at FROM master_sessions WHERE session_id=?"
      ).bind(checkMatch[1]).first();
      if (!row || row.requester_uid !== identity.uid || row.requester_device_id !== deviceId || row.revoked_at) return json({ active: false }, 403);
      return json({ active: true });
    }

    return json({ error: "Not found." }, 404);
  } catch (error) {
    const known = String(error?.message || "");
    const status = known === "DEVICE_NOT_REGISTERED" ? 403 : 400;
    return json({ error: status === 403 ? "Device is not registered." : "Request could not be completed." }, status);
  }
}

export default {
  async fetch(request, env) {
    try { return await handle(request, env); }
    catch (_) { return json({ error: "Service temporarily unavailable." }, 500); }
  }
};
