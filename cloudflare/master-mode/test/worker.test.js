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
