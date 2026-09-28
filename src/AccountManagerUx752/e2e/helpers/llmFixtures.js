/**
 * Read-only access to an LLM emulator fixture set on the HOST side of a Playwright run.
 *
 * A set directory (e.g. Objects7/src/test/resources/llm-fixtures/harlots-eight/) holds:
 *   - manifest.json               loose, always (the emulator's set descriptor)
 *   - fixtures.zip                the committed recordings: entries named <sha256>.json at the zip root
 *   - <sha256>.json               OPTIONAL loose recorder drops (LLM_EMULATOR_RECORD_DIR output), never
 *                                 committed (see src/.gitignore), honored ahead of a same-named zip entry
 *
 * The recordings are ~284 files of ~10 KB each; committing them loose bloated the tree, so they live in
 * one zip. No zip library is available in node_modules and the corporate proxy makes `npm install`
 * unreliable, so this is a minimal dependency-free reader: End-Of-Central-Directory record -> central
 * directory -> local file header -> data. Compression methods 0 (stored) and 8 (deflate, via
 * zlib.inflateRawSync) are supported; anything else, encryption, or ZIP64 fails loudly rather than
 * being silently skipped. Entry names that are not exactly `<64 hex>.json` — directories, notes,
 * `../` path tricks — are ignored.
 *
 * A corrupt zip THROWS from listFixtureNames/readFixture instead of yielding an empty set. Yielding
 * empty would flip the emulated spec into synth mode and pass the synth-mode assertions — a false
 * green. A missing zip is not an error: the set is then whatever loose files are present.
 *
 * This module never writes anything.
 */
import fs from 'fs';
import path from 'path';
import zlib from 'zlib';

export const FIXTURE_NAME_RE = /^[0-9a-f]{64}\.json$/;
export const FIXTURE_ZIP_NAME = 'fixtures.zip';

const SIG_LOCAL = 0x04034b50;   // "PK\x03\x04"
const SIG_CENTRAL = 0x02014b50; // "PK\x01\x02"
const SIG_EOCD = 0x06054b50;    // "PK\x05\x06"
const EOCD_MIN = 22;
const CENTRAL_MIN = 46;
const LOCAL_MIN = 30;
const METHOD_STORED = 0;
const METHOD_DEFLATE = 8;

// ─────────────────────────────────────────────────────────────────────────────────────────────
// CRC-32 (IEEE 802.3, the one zip uses). Table-driven; exported so a test can build a valid zip.
// ─────────────────────────────────────────────────────────────────────────────────────────────

const CRC_TABLE = (() => {
    const t = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        t[n] = c;
    }
    return t;
})();

/** CRC-32 of a Buffer/Uint8Array as an unsigned 32-bit number. */
export function crc32(buf) {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Zip parsing
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Parse the central directory of a zip held in `buf`. Returns Map<name, entry> for the entries whose
 * name is a fixture name; everything else is dropped here so callers never see it.
 * @param {Buffer} buf
 * @param {string} label for error messages
 */
export function parseZipEntries(buf, label) {
    const where = label || '<buffer>';
    if (!Buffer.isBuffer(buf) || buf.length < EOCD_MIN) {
        throw new Error(where + ': not a zip file (shorter than an End-Of-Central-Directory record)');
    }
    // The EOCD sits at the very end, optionally followed by a comment of up to 65535 bytes. Scan
    // backwards for the signature and accept it only where the comment length it declares lands
    // exactly on the end of the file — a signature byte pattern INSIDE a comment must not win.
    let eocd = -1;
    const lowest = Math.max(0, buf.length - EOCD_MIN - 0xFFFF);
    for (let i = buf.length - EOCD_MIN; i >= lowest; i--) {
        if (buf.readUInt32LE(i) !== SIG_EOCD) continue;
        const commentLen = buf.readUInt16LE(i + 20);
        if (i + EOCD_MIN + commentLen === buf.length) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error(where + ': not a zip file (End-Of-Central-Directory record not found)');

    const diskEntries = buf.readUInt16LE(eocd + 8);
    const totalEntries = buf.readUInt16LE(eocd + 10);
    const cdSize = buf.readUInt32LE(eocd + 12);
    const cdOffset = buf.readUInt32LE(eocd + 16);
    if (totalEntries === 0xFFFF || cdSize === 0xFFFFFFFF || cdOffset === 0xFFFFFFFF) {
        throw new Error(where + ': ZIP64 archives are not supported by this reader');
    }
    if (diskEntries !== totalEntries) {
        throw new Error(where + ': multi-disk archives are not supported by this reader');
    }
    if (cdOffset + cdSize > eocd) {
        throw new Error(where + ': central directory [' + cdOffset + ', ' + (cdOffset + cdSize)
            + ') runs past the End-Of-Central-Directory record at ' + eocd);
    }

    const entries = new Map();
    let p = cdOffset;
    for (let n = 0; n < totalEntries; n++) {
        if (p + CENTRAL_MIN > buf.length || buf.readUInt32LE(p) !== SIG_CENTRAL) {
            throw new Error(where + ': bad central directory entry #' + n + ' at offset ' + p);
        }
        const flags = buf.readUInt16LE(p + 8);
        const method = buf.readUInt16LE(p + 10);
        const crc = buf.readUInt32LE(p + 16);
        const compSize = buf.readUInt32LE(p + 20);
        const uncompSize = buf.readUInt32LE(p + 24);
        const nameLen = buf.readUInt16LE(p + 28);
        const extraLen = buf.readUInt16LE(p + 30);
        const commentLen = buf.readUInt16LE(p + 32);
        const localOffset = buf.readUInt32LE(p + 42);
        const nameEnd = p + CENTRAL_MIN + nameLen;
        if (nameEnd > buf.length) throw new Error(where + ': central directory entry #' + n + ' name runs past end of file');
        // Bit 11 = UTF-8 names; a fixture name is pure ASCII so either decoding reads it identically.
        const name = buf.toString('utf8', p + CENTRAL_MIN, nameEnd);
        p = nameEnd + extraLen + commentLen;
        if (!FIXTURE_NAME_RE.test(name)) continue;
        entries.set(name, { name, method, crc, compSize, uncompSize, localOffset, encrypted: (flags & 0x1) !== 0 });
    }
    return entries;
}

/**
 * Inflate/copy one entry's bytes out of `buf`. Sizes and CRC come from the central directory (the
 * local header's copies may be zero when the writer used a data descriptor, general-purpose bit 3);
 * only the local header's name/extra lengths are read from the local header, because they may
 * legitimately differ from the central directory's.
 */
export function extractZipEntry(buf, entry, label) {
    const where = (label || '<buffer>') + '!' + entry.name;
    if (entry.encrypted) throw new Error(where + ': encrypted entries are not supported');
    const lo = entry.localOffset;
    if (lo + LOCAL_MIN > buf.length || buf.readUInt32LE(lo) !== SIG_LOCAL) {
        throw new Error(where + ': local file header signature not found at offset ' + lo);
    }
    const nameLen = buf.readUInt16LE(lo + 26);
    const extraLen = buf.readUInt16LE(lo + 28);
    const start = lo + LOCAL_MIN + nameLen + extraLen;
    const end = start + entry.compSize;
    if (end > buf.length) throw new Error(where + ': compressed data [' + start + ', ' + end + ') runs past end of file');
    const raw = buf.subarray(start, end);
    let out;
    if (entry.method === METHOD_STORED) {
        out = Buffer.from(raw); // copy: callers must never alias the cached archive buffer
    } else if (entry.method === METHOD_DEFLATE) {
        out = zlib.inflateRawSync(raw);
    } else {
        throw new Error(where + ': unsupported compression method ' + entry.method + ' (only 0=stored and 8=deflate)');
    }
    if (out.length !== entry.uncompSize) {
        throw new Error(where + ': inflated to ' + out.length + ' bytes, central directory says ' + entry.uncompSize);
    }
    const actual = crc32(out);
    if (actual !== entry.crc) {
        throw new Error(where + ': CRC mismatch (computed ' + actual.toString(16) + ', central directory ' + entry.crc.toString(16) + ')');
    }
    return out;
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Fixture-set access
// ─────────────────────────────────────────────────────────────────────────────────────────────

// zipPath -> { mtimeMs, size, buf, entries }. The spec reads every recording (recordedSceneTitles),
// so the archive is parsed once per (path, mtime, size) and re-read if the file changes underneath.
const zipCache = new Map();

function loadZip(setDir) {
    const zipPath = path.join(setDir, FIXTURE_ZIP_NAME);
    let st;
    try { st = fs.statSync(zipPath); } catch (_) { return null; }
    if (!st.isFile()) return null;
    const cached = zipCache.get(zipPath);
    if (cached && cached.mtimeMs === st.mtimeMs && cached.size === st.size) return cached;
    const buf = fs.readFileSync(zipPath);
    const entries = parseZipEntries(buf, zipPath);
    const rec = { mtimeMs: st.mtimeMs, size: st.size, buf, entries };
    zipCache.set(zipPath, rec);
    return rec;
}

function looseFixturePath(setDir, name) {
    const p = path.join(setDir, name);
    try { return fs.statSync(p).isFile() ? p : null; } catch (_) { return null; }
}

/**
 * Names of every fixture in the set: loose `<sha256>.json` files in `setDir` plus the conforming
 * entries of `setDir/fixtures.zip`, de-duplicated and sorted. A set directory that does not exist
 * yields []. A zip that exists but cannot be parsed THROWS (see the header).
 * @param {string} setDir
 * @returns {string[]}
 */
export function listFixtureNames(setDir) {
    const names = new Set();
    let dirents = [];
    try { dirents = fs.readdirSync(setDir, { withFileTypes: true }); } catch (_) { dirents = []; }
    for (const d of dirents) {
        if (d.isFile() && FIXTURE_NAME_RE.test(d.name)) names.add(d.name);
    }
    const zip = loadZip(setDir);
    if (zip) for (const n of zip.entries.keys()) names.add(n);
    return [...names].sort();
}

/**
 * Bytes of one fixture. A loose file wins over a same-named zip entry (recorder drops are the
 * newer truth until they are folded into the zip). Mirrors fs.readFileSync: no `encoding` returns a
 * Buffer, `'utf8'` returns a string. Throws for a non-fixture name (including any path component)
 * or a name present neither loose nor in the zip.
 * @param {string} setDir
 * @param {string} name   exactly `<64 lowercase hex>.json`
 * @param {string} [encoding]
 * @returns {Buffer|string}
 */
export function readFixture(setDir, name, encoding) {
    if (typeof name !== 'string' || !FIXTURE_NAME_RE.test(name)) {
        throw new Error('not a fixture name: ' + JSON.stringify(name) + ' (want <64 hex>.json with no path)');
    }
    let out;
    const loose = looseFixturePath(setDir, name);
    if (loose) {
        out = fs.readFileSync(loose);
    } else {
        const zip = loadZip(setDir);
        const entry = zip ? zip.entries.get(name) : null;
        if (!entry) {
            throw new Error('fixture ' + name + ' not found in ' + setDir + ' (neither loose nor in ' + FIXTURE_ZIP_NAME
                + (zip ? '' : ', which is absent') + ')');
        }
        out = extractZipEntry(zip.buf, entry, path.join(setDir, FIXTURE_ZIP_NAME));
    }
    return encoding ? out.toString(encoding) : out;
}

/** True when `name` is present in the set (loose or zipped). Never throws for a bad name. */
export function hasFixture(setDir, name) {
    if (typeof name !== 'string' || !FIXTURE_NAME_RE.test(name)) return false;
    if (looseFixturePath(setDir, name)) return true;
    const zip = loadZip(setDir);
    return !!(zip && zip.entries.has(name));
}

/** Drop the parsed-archive cache (tests that rewrite a zip in place within the same millisecond). */
export function clearFixtureCache() {
    zipCache.clear();
}
