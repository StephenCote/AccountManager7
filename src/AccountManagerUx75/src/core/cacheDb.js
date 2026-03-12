// localStorage cache layer for am7client
// Persistent, TTL-aware cache backed by localStorage.
// Key format: am7cache:{type}:{objectId}:{act}
// Falls back gracefully to in-memory if localStorage is unavailable.

const LS_PREFIX = 'am7cache:';
let metrics = { hits: 0, misses: 0, writes: 0, evictions: 0 };
let useMemory = false;
let memStore = {};

const DEFAULT_TTL_MS = 5 * 60 * 1000; // 5 minutes

function _lsAvailable() {
    try {
        localStorage.setItem('__am7test__', '1');
        localStorage.removeItem('__am7test__');
        return true;
    } catch (e) {
        return false;
    }
}

function init() {
    useMemory = !_lsAvailable();
    if (useMemory) {
        console.warn('[cacheDb] localStorage unavailable, using in-memory cache');
    } else {
        console.log('[am7client] localStorage cache active');
    }
    return Promise.resolve(true);
}

function isReady() {
    return true;
}

// Key format: am7cache:{type}:{objectId}:{act}
function _lsKey(type, act, key) {
    return LS_PREFIX + type + ':' + key + ':' + act;
}

function _allKeys() {
    if (useMemory) return Object.keys(memStore);
    try { return Object.keys(localStorage).filter(k => k.startsWith(LS_PREFIX)); } catch (e) { return []; }
}

function _getRaw(lk) {
    return useMemory ? memStore[lk] : localStorage.getItem(lk);
}

function _setRaw(lk, val) {
    if (useMemory) { memStore[lk] = val; return; }
    try {
        localStorage.setItem(lk, val);
    } catch (quota) {
        evictExpired();
        try { localStorage.setItem(lk, val); } catch (e2) { /* ignore */ }
    }
}

function _deleteRaw(lk) {
    if (useMemory) delete memStore[lk];
    else try { localStorage.removeItem(lk); } catch (e) { /* ignore */ }
}

function get(type, act, key) {
    try {
        let raw = _getRaw(_lsKey(type, act, key));
        if (raw == null) { metrics.misses++; return undefined; }
        let entry = JSON.parse(raw);
        if (entry.expires > 0 && Date.now() > entry.expires) {
            _deleteRaw(_lsKey(type, act, key));
            metrics.evictions++;
            metrics.misses++;
            return undefined;
        }
        metrics.hits++;
        return entry.value;
    } catch (e) {
        metrics.misses++;
        return undefined;
    }
}

function put(type, act, key, value, ttlMs) {
    try {
        let ttl = (ttlMs !== undefined && ttlMs !== null) ? ttlMs : DEFAULT_TTL_MS;
        let entry = JSON.stringify({ value, expires: ttl > 0 ? Date.now() + ttl : 0 });
        _setRaw(_lsKey(type, act, key), entry);
        metrics.writes++;
    } catch (e) {
        console.warn('[cacheDb] Failed to write cache entry', e);
    }
}

// remove(type, objectId) — deletes all acts for this type+objectId
// remove(type)           — deletes all entries for this type
function remove(type, objectId) {
    try {
        // Prefix: am7cache:{type}:{objectId}: or am7cache:{type}:
        let prefix = LS_PREFIX + type + ':' + (objectId ? objectId + ':' : '');
        _allKeys().filter(k => k.startsWith(prefix)).forEach(k => _deleteRaw(k));
    } catch (e) {
        console.warn('[cacheDb] Failed to remove cache entry', e);
    }
}

function removeByType(type) {
    remove(type);
}

function clearAll() {
    try {
        _allKeys().forEach(k => _deleteRaw(k));
        metrics.evictions = 0;
    } catch (e) {
        console.warn('[cacheDb] Failed to clear all', e);
    }
}

function evictExpired() {
    let count = 0;
    let now = Date.now();
    try {
        for (let k of _allKeys()) {
            try {
                let raw = _getRaw(k);
                if (!raw) continue;
                let entry = JSON.parse(raw);
                if (entry.expires > 0 && now > entry.expires) {
                    _deleteRaw(k);
                    count++;
                }
            } catch (e) { /* skip malformed */ }
        }
        metrics.evictions += count;
    } catch (e) { /* ignore */ }
    return count;
}

function entryCount() {
    try { return _allKeys().length; } catch (e) { return 0; }
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

function close() {
    // Clear cache entries and reset state (mirrors SQLite DB destruction for test isolation)
    clearAll();
    useMemory = false;
    memStore = {};
    metrics = { hits: 0, misses: 0, writes: 0, evictions: 0 };
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
