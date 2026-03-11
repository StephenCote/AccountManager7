// SQLite WASM cache layer for am7client
// Replaces the in-memory cache object with persistent, TTL-aware SQLite storage.
// Falls back gracefully to in-memory caching if sql.js fails to load.

let db = null;
let ready = false;
let failed = false;
let initPromise = null;
let metrics = { hits: 0, misses: 0, writes: 0, evictions: 0 };

const DEFAULT_TTL_MS = 5 * 60 * 1000; // 5 minutes

async function init() {
    if (initPromise) return initPromise;
    initPromise = _doInit();
    return initPromise;
}

async function _doInit() {
    if (ready || failed) return ready;
    try {
        const initSqlJs = (await import('sql.js')).default;
        const SQL = await initSqlJs();
        db = new SQL.Database();
        db.run(`CREATE TABLE IF NOT EXISTS cache (
            type TEXT NOT NULL,
            act TEXT NOT NULL,
            key TEXT NOT NULL,
            value TEXT NOT NULL,
            expires INTEGER NOT NULL,
            PRIMARY KEY (type, act, key)
        )`);
        db.run(`CREATE INDEX IF NOT EXISTS idx_cache_expires ON cache (expires)`);
        ready = true;
    } catch (e) {
        console.warn('[cacheDb] SQLite WASM failed to load, falling back to in-memory cache', e);
        failed = true;
        ready = false;
    }
    return ready;
}

function isReady() {
    return ready && db !== null;
}

function getMetrics() {
    return Object.assign({}, metrics);
}

function resetMetrics() {
    metrics.hits = 0;
    metrics.misses = 0;
    metrics.writes = 0;
    metrics.evictions = 0;
}

function get(type, act, key) {
    if (!isReady()) return undefined;
    try {
        let stmt = db.prepare('SELECT value, expires FROM cache WHERE type = ? AND act = ? AND key = ?');
        stmt.bind([type, act, key]);
        if (stmt.step()) {
            let row = stmt.get();
            stmt.free();
            let expires = row[1];
            if (expires > 0 && Date.now() > expires) {
                db.run('DELETE FROM cache WHERE type = ? AND act = ? AND key = ?', [type, act, key]);
                metrics.evictions++;
                metrics.misses++;
                return undefined;
            }
            metrics.hits++;
            try {
                return JSON.parse(row[0]);
            } catch (e) {
                return row[0];
            }
        }
        stmt.free();
        metrics.misses++;
        return undefined;
    } catch (e) {
        metrics.misses++;
        return undefined;
    }
}

function put(type, act, key, value, ttlMs) {
    if (!isReady()) return;
    try {
        let ttl = (ttlMs !== undefined && ttlMs !== null) ? ttlMs : DEFAULT_TTL_MS;
        let expires = ttl > 0 ? Date.now() + ttl : 0;
        let serialized = (typeof value === 'string') ? value : JSON.stringify(value);
        db.run(
            'INSERT OR REPLACE INTO cache (type, act, key, value, expires) VALUES (?, ?, ?, ?, ?)',
            [type, act, key, serialized, expires]
        );
        metrics.writes++;
    } catch (e) {
        console.warn('[cacheDb] Failed to write cache entry', e);
    }
}

function remove(type, key) {
    if (!isReady()) return;
    try {
        if (key) {
            // Remove specific key across all acts for this type
            db.run('DELETE FROM cache WHERE type = ? AND key = ?', [type, key]);
        } else {
            // Remove all entries for this type
            db.run('DELETE FROM cache WHERE type = ?', [type]);
        }
    } catch (e) {
        console.warn('[cacheDb] Failed to remove cache entry', e);
    }
}

function removeByType(type) {
    if (!isReady()) return;
    try {
        db.run('DELETE FROM cache WHERE type = ?', [type]);
    } catch (e) {
        console.warn('[cacheDb] Failed to clear type', e);
    }
}

function clearAll() {
    if (!isReady()) return;
    try {
        db.run('DELETE FROM cache');
        metrics.evictions = 0;
    } catch (e) {
        console.warn('[cacheDb] Failed to clear all', e);
    }
}

function evictExpired() {
    if (!isReady()) return 0;
    try {
        let now = Date.now();
        let countStmt = db.prepare('SELECT COUNT(*) FROM cache WHERE expires > 0 AND expires < ?');
        countStmt.bind([now]);
        let count = 0;
        if (countStmt.step()) count = countStmt.get()[0];
        countStmt.free();
        if (count > 0) {
            db.run('DELETE FROM cache WHERE expires > 0 AND expires < ?', [now]);
            metrics.evictions += count;
        }
        return count;
    } catch (e) {
        return 0;
    }
}

function entryCount() {
    if (!isReady()) return 0;
    try {
        let stmt = db.prepare('SELECT COUNT(*) FROM cache');
        let count = 0;
        if (stmt.step()) count = stmt.get()[0];
        stmt.free();
        return count;
    } catch (e) {
        return 0;
    }
}

function close() {
    if (db) {
        try { db.close(); } catch (e) { /* ignore */ }
        db = null;
    }
    ready = false;
    failed = false;
    initPromise = null;
}

export const cacheDb = {
    init,
    isReady,
    get,
    put,
    remove,
    removeByType,
    clearAll,
    evictExpired,
    entryCount,
    getMetrics,
    resetMetrics,
    close,
    DEFAULT_TTL_MS
};

export default cacheDb;
