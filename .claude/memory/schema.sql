-- Memory store schema. Store of record for memories; MEMORY.md is generated from this.
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS memories (
  name        TEXT PRIMARY KEY,                 -- short-kebab-case slug
  description TEXT NOT NULL,                    -- one-line summary, used for recall relevance
  type        TEXT NOT NULL CHECK (type IN ('user','feedback','project','reference')),
  body        TEXT NOT NULL,
  created_at  TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at  TEXT NOT NULL DEFAULT (datetime('now')),

  -- Lifecycle. A memory that stops being true is usually not deleted: it is
  -- marked and pointed at whatever replaced it, so the reversal stays legible.
  status        TEXT NOT NULL DEFAULT 'active'
                CHECK (status IN ('active','deprecated','superseded')),
  superseded_by TEXT REFERENCES memories(name) ON DELETE SET NULL ON UPDATE CASCADE,

  -- Provenance. Which session/file produced this, so a memory can be traced back.
  origin_session_id TEXT,
  source_path       TEXT,
  -- Any frontmatter keys this schema does not model, kept as JSON rather than
  -- dropped on import. Prevents silent data loss when the format grows.
  extra_metadata    TEXT
);

-- Which Claude Code session transcripts have been mined, so re-running the miner
-- skips work already done instead of duplicating memories.
CREATE TABLE IF NOT EXISTS mined_sessions (
  session_id       TEXT PRIMARY KEY,
  transcript_path  TEXT,
  transcript_bytes INTEGER,
  model            TEXT,
  memories_created INTEGER NOT NULL DEFAULT 0,
  summary          TEXT,
  mined_at         TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Files a memory is about, so "what do we know about this file?" is answerable.
CREATE TABLE IF NOT EXISTS memory_files (
  name      TEXT NOT NULL REFERENCES memories(name) ON DELETE CASCADE ON UPDATE CASCADE,
  file_path TEXT NOT NULL,
  PRIMARY KEY (name, file_path)
);
CREATE INDEX IF NOT EXISTS memory_files_path ON memory_files(file_path);

CREATE INDEX IF NOT EXISTS memories_status  ON memories(status);
CREATE INDEX IF NOT EXISTS memories_session ON memories(origin_session_id);

-- [[wikilinks]] parsed out of body. dst may not exist yet: an unresolved link marks
-- something worth writing later, so this is deliberately not a foreign key.
CREATE TABLE IF NOT EXISTS links (
  src TEXT NOT NULL REFERENCES memories(name) ON DELETE CASCADE ON UPDATE CASCADE,
  dst TEXT NOT NULL,
  PRIMARY KEY (src, dst)
);
CREATE INDEX IF NOT EXISTS links_dst ON links(dst);

-- Raw embeddings, stored as plain JSON so memory.db needs no extension to stay
-- readable. The sqlite-vec index (vectors.db) is derived from this table and can
-- always be rebuilt; see vec.ps1 and SETUP.md.
CREATE TABLE IF NOT EXISTS embeddings (
  name           TEXT PRIMARY KEY REFERENCES memories(name) ON DELETE CASCADE ON UPDATE CASCADE,
  model          TEXT NOT NULL,              -- embedding model that produced this vector
  dim            INTEGER NOT NULL,
  vec            TEXT NOT NULL,              -- JSON array of floats
  norm           REAL NOT NULL,              -- precomputed L2 norm, for cosine without the extension
  content_sha256 TEXT NOT NULL,              -- hash of the embedded text; detects stale vectors
  created_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TRIGGER IF NOT EXISTS memories_touch
AFTER UPDATE OF description, type, body ON memories
BEGIN
  UPDATE memories SET updated_at = datetime('now') WHERE name = NEW.name;
END;

-- Full-text search over description + body.
CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
  name, description, body,
  content = 'memories',
  content_rowid = 'rowid',
  tokenize = 'porter unicode61'
);

CREATE TRIGGER IF NOT EXISTS memories_fts_ai AFTER INSERT ON memories BEGIN
  INSERT INTO memories_fts(rowid, name, description, body)
  VALUES (NEW.rowid, NEW.name, NEW.description, NEW.body);
END;

CREATE TRIGGER IF NOT EXISTS memories_fts_ad AFTER DELETE ON memories BEGIN
  INSERT INTO memories_fts(memories_fts, rowid, name, description, body)
  VALUES ('delete', OLD.rowid, OLD.name, OLD.description, OLD.body);
END;

CREATE TRIGGER IF NOT EXISTS memories_fts_au AFTER UPDATE ON memories BEGIN
  INSERT INTO memories_fts(memories_fts, rowid, name, description, body)
  VALUES ('delete', OLD.rowid, OLD.name, OLD.description, OLD.body);
  INSERT INTO memories_fts(rowid, name, description, body)
  VALUES (NEW.rowid, NEW.name, NEW.description, NEW.body);
END;
