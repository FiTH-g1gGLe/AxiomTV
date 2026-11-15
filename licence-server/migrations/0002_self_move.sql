-- Lets buyers move their unlock to another box themselves

ALTER TABLE devices ADD COLUMN order_number TEXT;      -- the number on the Lemon Squeezy receipt
ALTER TABLE devices ADD COLUMN transfer_code TEXT;     -- 6-digit code shown on the old box
ALTER TABLE devices ADD COLUMN transfer_expires INTEGER;
ALTER TABLE devices ADD COLUMN last_self_move INTEGER; -- follows the licence, for the once-per-30-days limit

CREATE INDEX IF NOT EXISTS devices_transfer ON devices(transfer_code);
CREATE INDEX IF NOT EXISTS devices_order_number ON devices(order_number);
CREATE INDEX IF NOT EXISTS events_type_at ON events(type, at);
