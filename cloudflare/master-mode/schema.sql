CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  firebase_uid TEXT NOT NULL,
  role TEXT NOT NULL CHECK(role IN ('approver','requester')),
  public_key_b64 TEXT,
  fcm_token TEXT,
  created_at INTEGER NOT NULL,
  revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS devices_uid_role ON devices(firebase_uid, role, revoked_at);
CREATE TABLE IF NOT EXISTS approval_requests (
  request_id TEXT PRIMARY KEY,
  nonce TEXT NOT NULL UNIQUE,
  challenge TEXT NOT NULL,
  requester_uid TEXT NOT NULL,
  requester_device_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('pending','approved','denied','expired','revoked')),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  approved_at INTEGER,
  session_id TEXT,
  signature_b64 TEXT
);
CREATE INDEX IF NOT EXISTS requests_requester ON approval_requests(requester_uid, created_at);
CREATE INDEX IF NOT EXISTS requests_pending ON approval_requests(status, expires_at);
CREATE TABLE IF NOT EXISTS master_sessions (
  session_id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL UNIQUE,
  requester_uid TEXT NOT NULL,
  requester_device_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS sessions_uid ON master_sessions(requester_uid, revoked_at);

CREATE TABLE IF NOT EXISTS live_token_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  firebase_uid TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS live_token_uid_time ON live_token_requests(firebase_uid, created_at);
