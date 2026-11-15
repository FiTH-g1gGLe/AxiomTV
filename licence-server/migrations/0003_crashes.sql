-- Crash reports sent by the app (stack trace + recent log, logins blanked out)
CREATE TABLE IF NOT EXISTS crashes (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  at          INTEGER NOT NULL,
  code        TEXT,
  model       TEXT,
  app_version TEXT,
  report      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS crashes_at ON crashes(at);
