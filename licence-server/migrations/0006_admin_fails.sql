-- Wrong admin password attempts per IP address, for the 15-minute lock-out after 5 tries
CREATE TABLE IF NOT EXISTS admin_fails (
  ip TEXT PRIMARY KEY,
  count INTEGER NOT NULL DEFAULT 0,
  first_at INTEGER NOT NULL,
  locked_until INTEGER
);
