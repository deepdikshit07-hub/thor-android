import test, { afterEach } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });

function makeEnv(uid = "master-uid") {
  const writes = [];
  const DB = {
    prepare(sql) {
      return {
        bind(...values) {
          return {
            async run() {
              writes.push({ sql, values });
              return { meta: { changes: 1 } };
            }
          };
        }
      };
    }
  };
  globalThis.fetch = async (url, options) => {
    assert.match(String(url), /identitytoolkit\.googleapis\.com/);
    assert.equal(options.method, "POST");
    return new Response(JSON.stringify({ users: [{ localId: uid }] }), {
      status: 200,
      headers: { "content-type": "application/json" }
    });
  };
  return { env: { MASTER_UID: "master-uid", FIREBASE_WEB_API_KEY: "test-key", DB }, writes };
}

function registerRequest(role, extra = {}) {
  return new Request("https://thor.test/v1/devices/register", {
    method: "POST",
    headers: { authorization: "Bearer test-id-token", "content-type": "application/json" },
    body: JSON.stringify({ deviceId: "device-test", role, ...extra })
  });
}

test("health endpoint is public and reports the service name", async () => {
  const response = await worker.fetch(new Request("https://thor.test/health"), {});
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true, service: "thor-master-approval" });
});

test("protected endpoints reject requests without a bearer token", async () => {
  const response = await worker.fetch(new Request("https://thor.test/v1/sessions/active"), {
    FIREBASE_WEB_API_KEY: "test-key"
  });
  assert.equal(response.status, 401);
});

test("only the configured master UID can register an approver device", async () => {
  const { env, writes } = makeEnv("master-uid");
  const response = await worker.fetch(registerRequest("approver", {
    publicKeyBase64: "test-public-key",
    fcmToken: "test-fcm-token"
  }), env);
  assert.equal(response.status, 200);
  assert.equal(writes.length, 1);
  assert.deepEqual(await response.json(), { ok: true, deviceId: "device-test", role: "approver" });
});

test("a non-master UID cannot register as the approver", async () => {
  const { env, writes } = makeEnv("requester-uid");
  const response = await worker.fetch(registerRequest("approver", {
    publicKeyBase64: "test-public-key",
    fcmToken: "test-fcm-token"
  }), env);
  assert.equal(response.status, 403);
  assert.equal(writes.length, 0);
});

test("the master UID cannot register as a requester", async () => {
  const { env, writes } = makeEnv("master-uid");
  const response = await worker.fetch(registerRequest("requester"), env);
  assert.equal(response.status, 403);
  assert.equal(writes.length, 0);
});

test("a separate Firebase UID can register as a requester", async () => {
  const { env, writes } = makeEnv("requester-uid");
  const response = await worker.fetch(registerRequest("requester"), env);
  assert.equal(response.status, 200);
  assert.equal(writes.length, 1);
  assert.deepEqual(await response.json(), { ok: true, deviceId: "device-test", role: "requester" });
});


function sessionCheckEnv(expectedDeviceId = "device-test") {
  const env = {
    MASTER_UID: "master-uid",
    FIREBASE_WEB_API_KEY: "test-key",
    DB: {
      prepare(sql) {
        return {
          bind(...values) {
            return {
              async first() {
                if (sql.includes("FROM devices")) {
                  const [deviceId, uid, role] = values;
                  return deviceId === expectedDeviceId && uid === "requester-uid" && role === "requester"
                    ? { device_id: deviceId, firebase_uid: uid, role, revoked_at: null }
                    : null;
                }
                if (sql.includes("FROM master_sessions")) {
                  const [sessionId] = values;
                  return sessionId === "session-test"
                    ? { requester_uid: "requester-uid", requester_device_id: expectedDeviceId, revoked_at: null }
                    : null;
                }
                return null;
              }
            };
          }
        };
      }
    }
  };
  globalThis.fetch = async () => new Response(JSON.stringify({ users: [{ localId: "requester-uid" }] }), {
    status: 200, headers: { "content-type": "application/json" }
  });
  return env;
}

test("session validation requires the registered requester device ID", async () => {
  const env = sessionCheckEnv();
  const response = await worker.fetch(new Request(
    "https://thor.test/v1/sessions/session-test/check?deviceId=device-test",
    { headers: { authorization: "Bearer test-id-token" } }
  ), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { active: true });
});

test("a different registered device cannot use another device's Master session", async () => {
  const env = sessionCheckEnv("other-device");
  const response = await worker.fetch(new Request(
    "https://thor.test/v1/sessions/session-test/check?deviceId=device-test",
    { headers: { authorization: "Bearer test-id-token" } }
  ), env);
  assert.equal(response.status, 403);
});

test("session validation fails closed when the device ID is omitted", async () => {
  const env = sessionCheckEnv();
  const response = await worker.fetch(new Request(
    "https://thor.test/v1/sessions/session-test/check",
    { headers: { authorization: "Bearer test-id-token" } }
  ), env);
  assert.equal(response.status, 403);
});

test("oversized POST bodies are rejected before route processing", async () => {
  const { env } = makeEnv("requester-uid");
  const response = await worker.fetch(new Request("https://thor.test/v1/devices/register", {
    method: "POST",
    headers: { authorization: "Bearer test-id-token", "content-type": "application/json" },
    body: JSON.stringify({ padding: "x".repeat(17000) })
  }), env);
  assert.equal(response.status, 413);
});


function liveTokenRequest(voiceName = "Charon") {
  return new Request("https://thor.test/v1/live-token", {
    method: "POST",
    headers: { authorization: "Bearer test-id-token", "content-type": "application/json" },
    body: JSON.stringify({ voiceName })
  });
}

test("Gemini Live token route requires a server-side API key", async () => {
  const { env } = makeEnv("requester-uid");
  const response = await worker.fetch(liveTokenRequest(), env);
  assert.equal(response.status, 503);
  assert.match((await response.json()).error, /not configured/i);
});

test("Gemini Live token route rejects voice names outside the allowlist", async () => {
  const { env, writes } = makeEnv("requester-uid");
  env.GEMINI_API_KEY = "server-only-test-key";
  const response = await worker.fetch(liveTokenRequest("UntrustedVoice"), env);
  assert.equal(response.status, 400);
  assert.equal(writes.length, 0);
});

test("Gemini Live token route returns a constrained short-lived token", async () => {
  const { env, writes } = makeEnv("requester-uid");
  env.GEMINI_API_KEY = "server-only-test-key";
  globalThis.fetch = async (url, options) => {
    if (String(url).includes("identitytoolkit.googleapis.com")) {
      return new Response(JSON.stringify({ users: [{ localId: "requester-uid" }] }), { status: 200 });
    }
    assert.match(String(url), /generativelanguage\.googleapis\.com\/v1beta\/auth_tokens/);
    assert.equal(options.headers["x-goog-api-key"], "server-only-test-key");
    const requestBody = JSON.parse(options.body);
    assert.equal(requestBody.uses, 1);
    assert.equal(requestBody.liveConnectConstraints.model, "models/gemini-3.8-live");
    assert.equal(requestBody.liveConnectConstraints.config.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName, "Charon");
    return new Response(JSON.stringify({ name: "short-lived-live-token" }), {
      status: 200, headers: { "content-type": "application/json" }
    });
  };
  const response = await worker.fetch(liveTokenRequest(), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { token: "short-lived-live-token", expiresInSeconds: 1800 });
  assert.equal(writes.length, 1);
  assert.match(writes[0].sql, /live_token_requests/);
});
