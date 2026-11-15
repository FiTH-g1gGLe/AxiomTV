-- AxiomTV licence server: first schema

-- One row per box/phone. device_hash is a SHA-256 of the app's device ID, never the raw ID.
CREATE TABLE IF NOT EXISTS devices (
  device_hash  TEXT PRIMARY KEY,
  code         TEXT NOT NULL UNIQUE,          -- the code shown on screen, e.g. 7K3P-9QXD
  status       TEXT NOT NULL DEFAULT 'unpaid', -- unpaid | active | revoked
  source       TEXT,                          -- lemonsqueezy | manual
  order_id     TEXT,
  email        TEXT,
  note         TEXT,
  model        TEXT,
  app_version  TEXT,
  created_at   INTEGER NOT NULL,
  activated_at INTEGER,
  last_seen    INTEGER
);

CREATE INDEX IF NOT EXISTS devices_status ON devices(status);
CREATE INDEX IF NOT EXISTS devices_order ON devices(order_id);

-- Everything that changes a licence, for checking what happened later.
CREATE TABLE IF NOT EXISTS events (
  id     INTEGER PRIMARY KEY AUTOINCREMENT,
  at     INTEGER NOT NULL,
  code   TEXT,
  type   TEXT NOT NULL,
  detail TEXT
);

CREATE INDEX IF NOT EXISTS events_code ON events(code);

-- Settings you change from the admin page (checkout link, price text).
CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
