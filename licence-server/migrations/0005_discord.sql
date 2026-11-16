-- Discord: which Discord user each box is linked to, the short code shown on the box to link it, and beta testers
ALTER TABLE devices ADD COLUMN discord_user TEXT;
ALTER TABLE devices ADD COLUMN discord_link_code TEXT;
ALTER TABLE devices ADD COLUMN discord_link_expires INTEGER;
ALTER TABLE devices ADD COLUMN beta INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS devices_discord_user ON devices(discord_user);
CREATE INDEX IF NOT EXISTS devices_discord_link ON devices(discord_link_code);
