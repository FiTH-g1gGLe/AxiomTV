-- Crash reports also hold AxiomTV error reports (e.g. error 1005): 'crash' or 'error'
ALTER TABLE crashes ADD COLUMN kind TEXT NOT NULL DEFAULT 'crash';
