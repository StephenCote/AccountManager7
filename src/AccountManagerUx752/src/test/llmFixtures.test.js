/**
 * e2e/helpers/llmFixtures.js — the dependency-free zip reader the emulated PictureBook spec uses to
 * see the committed LLM recordings (`<set>/fixtures.zip`, entries `<sha256>.json` at the zip root)
 * alongside any loose recorder drops.
 *
 * The zips here are BUILT IN THE TEST, byte by byte (local file header / central directory / EOCD),
 * so what is exercised is the reader's parsing of a real archive layout — a stored entry, a deflated
 * entry (zlib.deflateRawSync), an archive comment the EOCD scan must skip over, a local header whose
 * extra field differs from the central directory's, and the path-trick / non-conforming names that
 * must be ignored. Every case reads real bytes back through the public API; nothing is mocked.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import os from 'os';
import path from 'path';
import zlib from 'zlib';
import {
    listFixtureNames, readFixture, hasFixture, crc32, parseZipEntries, extractZipEntry,
    clearFixtureCache, FIXTURE_NAME_RE, FIXTURE_ZIP_NAME
} from '../../e2e/helpers/llmFixtures.js';

const HEX_A = 'a'.repeat(64);
const HEX_B = '0123456789abcdef'.repeat(4);
const HEX_C = 'c0ffee'.padEnd(64, '0');
const HEX_TRICK = 'deadbeef'.padEnd(64, 'd');
const NAME_A = HEX_A + '.json';
const NAME_B = HEX_B + '.json';
const NAME_C = HEX_C + '.json';

function fixtureJson(key, kind, content) {
    return JSON.stringify({
        key, kind, model: 'goekdenizguelmez/JOSIEFIED-Qwen3:8b',
        request: { model: 'goekdenizguelmez/JOSIEFIED-Qwen3:8b', messages: [{ role: 'user', content: 'TEXT SEGMENT:\nChapter 9\n...' }] },
        response: { content },
        recordedAt: '2026-09-26T10:00:00Z'
    }, null, 2);
}

/**
 * Hand-built zip writer. `entries`: [{ name, data, method (0|8, default 8), crc (override),
 * localExtra (Buffer, written ONLY to the local header) }]. `opts.comment` appends an archive comment.
 */
function buildZip(entries, opts) {
    const comment = Buffer.from((opts && opts.comment) || '', 'utf8');
    const parts = [];
    const centrals = [];
    let offset = 0;
    for (const e of entries) {
        const nameBuf = Buffer.from(e.name, 'utf8');
        const data = Buffer.isBuffer(e.data) ? e.data : Buffer.from(e.data, 'utf8');
        const method = e.method === undefined ? 8 : e.method;
        const comp = method === 8 ? zlib.deflateRawSync(data) : data;
        const crc = e.crc !== undefined ? e.crc : crc32(data);
        const extra = e.localExtra || Buffer.alloc(0);

        const local = Buffer.alloc(30 + nameBuf.length + extra.length);
        local.writeUInt32LE(0x04034b50, 0);
        local.writeUInt16LE(20, 4);            // version needed to extract
        local.writeUInt16LE(0, 6);             // general purpose flags
        local.writeUInt16LE(method, 8);
        local.writeUInt16LE(0, 10);            // mod time
        local.writeUInt16LE(0x21, 12);         // mod date (1980-01-01)
        local.writeUInt32LE(crc, 14);
        local.writeUInt32LE(comp.length, 18);
        local.writeUInt32LE(data.length, 22);
        local.writeUInt16LE(nameBuf.length, 26);
        local.writeUInt16LE(extra.length, 28);
        nameBuf.copy(local, 30);
        extra.copy(local, 30 + nameBuf.length);
        parts.push(local, comp);

        const central = Buffer.alloc(46 + nameBuf.length);
        central.writeUInt32LE(0x02014b50, 0);
        central.writeUInt16LE(20, 4);          // version made by
        central.writeUInt16LE(20, 6);          // version needed
        central.writeUInt16LE(0, 8);           // flags
        central.writeUInt16LE(method, 10);
        central.writeUInt16LE(0, 12);
        central.writeUInt16LE(0x21, 14);
        central.writeUInt32LE(crc, 16);
        central.writeUInt32LE(comp.length, 20);
        central.writeUInt32LE(data.length, 24);
        central.writeUInt16LE(nameBuf.length, 28);
        central.writeUInt16LE(0, 30);          // extra length (central copy: none)
        central.writeUInt16LE(0, 32);          // comment length
        central.writeUInt16LE(0, 34);          // disk number start
        central.writeUInt16LE(0, 36);          // internal attributes
        central.writeUInt32LE(0, 38);          // external attributes
        central.writeUInt32LE(offset, 42);     // relative offset of local header
        nameBuf.copy(central, 46);
        centrals.push(central);
        offset += local.length + comp.length;
    }
    const cd = Buffer.concat(centrals);
    const eocd = Buffer.alloc(22 + comment.length);
    eocd.writeUInt32LE(0x06054b50, 0);
    eocd.writeUInt16LE(0, 4);                  // this disk
    eocd.writeUInt16LE(0, 6);                  // disk with the central directory
    eocd.writeUInt16LE(entries.length, 8);
    eocd.writeUInt16LE(entries.length, 10);
    eocd.writeUInt32LE(cd.length, 12);
    eocd.writeUInt32LE(offset, 16);
    eocd.writeUInt16LE(comment.length, 20);
    comment.copy(eocd, 22);
    return Buffer.concat([...parts, cd, eocd]);
}

let setDir;
beforeEach(() => {
    clearFixtureCache();
    setDir = fs.mkdtempSync(path.join(os.tmpdir(), 'llm-fixtures-test-'));
});
afterEach(() => {
    clearFixtureCache();
    fs.rmSync(setDir, { recursive: true, force: true });
});

describe('crc32', () => {
    it('matches the IEEE reference values zip files carry', () => {
        expect(crc32(Buffer.from(''))).toBe(0x00000000);
        expect(crc32(Buffer.from('123456789'))).toBe(0xCBF43926);   // the canonical check value
        expect(crc32(Buffer.from('The quick brown fox jumps over the lazy dog'))).toBe(0x414FA339);
    });
    it('agrees with zlib.crc32 when Node provides it', () => {
        if (typeof zlib.crc32 !== 'function') return; // Node < 22.2 has no zlib.crc32; the reference values above still hold
        const buf = Buffer.from(fixtureJson(HEX_A, 'extract-chunk', '{"additions":[]}'));
        expect(crc32(buf)).toBe(zlib.crc32(buf));
    });
});

describe('FIXTURE_NAME_RE', () => {
    it('accepts exactly <64 lowercase hex>.json and nothing else', () => {
        expect(FIXTURE_NAME_RE.test(NAME_A)).toBe(true);
        expect(FIXTURE_NAME_RE.test(HEX_A.toUpperCase() + '.json')).toBe(false);
        expect(FIXTURE_NAME_RE.test(HEX_A.slice(1) + '.json')).toBe(false);
        expect(FIXTURE_NAME_RE.test('../' + NAME_A)).toBe(false);
        expect(FIXTURE_NAME_RE.test('sub/' + NAME_A)).toBe(false);
        expect(FIXTURE_NAME_RE.test('manifest.json')).toBe(false);
        expect(FIXTURE_NAME_RE.test('notes.txt')).toBe(false);
    });
});

describe('listFixtureNames / readFixture with a hand-built fixtures.zip', () => {
    const jsonA = fixtureJson(HEX_A, 'extract-chunk', '{"additions":[{"title":"The Fairy Court Convenes"}],"revisions":[],"removals":[]}');
    const jsonB = fixtureJson(HEX_B, 'reduce-character', '{"name":"Nessa","gender":"female"}');
    const trickJson = fixtureJson(HEX_TRICK, 'extract-chunk', '{"additions":[{"title":"MUST NOT BE VISIBLE"}]}');

    function writeStandardZip(extraOpts) {
        const zip = buildZip([
            { name: NAME_A, data: jsonA, method: 8 },                                 // deflate
            { name: NAME_B, data: jsonB, method: 0 },                                 // stored
            { name: 'notes.txt', data: 'operator notes, not a fixture', method: 8 },  // non-conforming name
            { name: '../' + HEX_TRICK + '.json', data: trickJson, method: 8 },       // 64 hex but path-prefixed
            { name: 'sub/' + HEX_TRICK + '.json', data: trickJson, method: 0 },      // directory prefix
            { name: HEX_TRICK + '.JSON', data: trickJson, method: 0 }                // wrong-case extension
        ], extraOpts);
        fs.writeFileSync(path.join(setDir, FIXTURE_ZIP_NAME), zip);
        return zip;
    }

    it('lists only the conforming root entries, sorted, and ignores notes/path-trick/case-variant names', () => {
        writeStandardZip();
        expect(listFixtureNames(setDir)).toEqual([NAME_B, NAME_A].sort());
        expect(hasFixture(setDir, NAME_A)).toBe(true);
        expect(hasFixture(setDir, '../' + HEX_TRICK + '.json')).toBe(false);
        expect(hasFixture(setDir, HEX_TRICK + '.json')).toBe(false);
    });

    it('reads a deflated entry back as the exact JSON that was zipped', () => {
        writeStandardZip();
        const text = readFixture(setDir, NAME_A, 'utf8');
        expect(text).toBe(jsonA);
        const fx = JSON.parse(text);
        expect(fx.key).toBe(HEX_A);
        expect(fx.kind).toBe('extract-chunk');
        expect(fx.response.content).toContain('The Fairy Court Convenes');
    });

    it('reads a stored entry back as a Buffer when no encoding is given', () => {
        writeStandardZip();
        const buf = readFixture(setDir, NAME_B);
        expect(Buffer.isBuffer(buf)).toBe(true);
        expect(buf.equals(Buffer.from(jsonB, 'utf8'))).toBe(true);
        expect(JSON.parse(buf.toString('utf8')).kind).toBe('reduce-character');
    });

    it('skips an archive comment when locating the End-Of-Central-Directory record', () => {
        // The comment deliberately contains the EOCD signature bytes so a naive "last occurrence"
        // scan would land on the wrong offset.
        writeStandardZip({ comment: 'PK\x05\x06 decoy signature inside the comment PK\x05\x06' });
        expect(listFixtureNames(setDir)).toEqual([NAME_B, NAME_A].sort());
        expect(readFixture(setDir, NAME_A, 'utf8')).toBe(jsonA);
    });

    it('uses the LOCAL header name/extra lengths to find the data (they may differ from the central directory)', () => {
        const zip = buildZip([
            { name: NAME_A, data: jsonA, method: 8, localExtra: Buffer.from([0x55, 0x54, 0x05, 0x00, 0x03, 0x01, 0x02, 0x03, 0x04]) },
            { name: NAME_B, data: jsonB, method: 0, localExtra: Buffer.alloc(13, 0xAB) }
        ]);
        fs.writeFileSync(path.join(setDir, FIXTURE_ZIP_NAME), zip);
        expect(readFixture(setDir, NAME_A, 'utf8')).toBe(jsonA);
        expect(readFixture(setDir, NAME_B, 'utf8')).toBe(jsonB);
    });

    it('a loose file with the same name overrides the zip entry, and a loose-only file is listed too', () => {
        writeStandardZip();
        const looseA = fixtureJson(HEX_A, 'extract-chunk', '{"additions":[{"title":"RE-RECORDED loose drop wins"}]}');
        const looseC = fixtureJson(HEX_C, 'guess-apparel', '[{"name":"cloak"}]');
        fs.writeFileSync(path.join(setDir, NAME_A), looseA);
        fs.writeFileSync(path.join(setDir, NAME_C), looseC);
        fs.writeFileSync(path.join(setDir, 'manifest.json'), '{"strict":false}');
        fs.writeFileSync(path.join(setDir, 'README.txt'), 'not a fixture');
        fs.mkdirSync(path.join(setDir, 'f'.repeat(64) + '.json')); // a DIRECTORY with a fixture-shaped name

        const names = listFixtureNames(setDir);
        expect(names).toEqual([NAME_A, NAME_B, NAME_C].sort());
        expect(names).not.toContain('manifest.json');
        expect(names).not.toContain('f'.repeat(64) + '.json');

        expect(readFixture(setDir, NAME_A, 'utf8')).toBe(looseA);      // loose wins
        expect(readFixture(setDir, NAME_A, 'utf8')).not.toBe(jsonA);
        expect(readFixture(setDir, NAME_B, 'utf8')).toBe(jsonB);       // still from the zip
        expect(readFixture(setDir, NAME_C, 'utf8')).toBe(looseC);      // loose only
    });

    it('throws for a name that is not a fixture name, including path tricks, before touching the filesystem', () => {
        writeStandardZip();
        expect(() => readFixture(setDir, '../' + NAME_A)).toThrow(/not a fixture name/);
        expect(() => readFixture(setDir, 'manifest.json')).toThrow(/not a fixture name/);
        expect(() => readFixture(setDir, HEX_TRICK + '.JSON')).toThrow(/not a fixture name/);
        expect(() => readFixture(setDir, null)).toThrow(/not a fixture name/);
    });

    it('throws for a well-formed name that is neither loose nor in the zip', () => {
        writeStandardZip();
        expect(() => readFixture(setDir, HEX_TRICK + '.json')).toThrow(/not found in .*neither loose nor in fixtures\.zip/);
    });

    it('re-reads the zip when it changes on disk', () => {
        writeStandardZip();
        expect(listFixtureNames(setDir)).toEqual([NAME_B, NAME_A].sort());
        const zip2 = buildZip([{ name: NAME_C, data: 'only C now', method: 8 }]);
        fs.writeFileSync(path.join(setDir, FIXTURE_ZIP_NAME), zip2);
        // size differs from the first archive, so the (mtime,size) cache key misses even within one ms
        expect(listFixtureNames(setDir)).toEqual([NAME_C]);
        expect(readFixture(setDir, NAME_C, 'utf8')).toBe('only C now');
    });
});

describe('degenerate sets', () => {
    it('a set directory with no zip lists only its loose fixtures', () => {
        fs.writeFileSync(path.join(setDir, NAME_B), '{"k":1}');
        fs.writeFileSync(path.join(setDir, 'manifest.json'), '{}');
        expect(listFixtureNames(setDir)).toEqual([NAME_B]);
        expect(readFixture(setDir, NAME_B, 'utf8')).toBe('{"k":1}');
        expect(() => readFixture(setDir, NAME_A)).toThrow(/fixtures\.zip, which is absent/);
    });

    it('a set directory that does not exist yields [] (synth mode), not an error', () => {
        expect(listFixtureNames(path.join(setDir, 'no-such-set'))).toEqual([]);
        expect(hasFixture(path.join(setDir, 'no-such-set'), NAME_A)).toBe(false);
    });

    it('an empty zip yields [] and a real zip with zero conforming entries yields []', () => {
        fs.writeFileSync(path.join(setDir, FIXTURE_ZIP_NAME), buildZip([]));
        expect(listFixtureNames(setDir)).toEqual([]);
        fs.writeFileSync(path.join(setDir, FIXTURE_ZIP_NAME), buildZip([{ name: 'notes.txt', data: 'x' }, { name: 'a/b.json', data: '{}' }]));
        clearFixtureCache();
        expect(listFixtureNames(setDir)).toEqual([]);
    });
});

describe('corruption is loud, never an empty set', () => {
    it('a fixtures.zip that is not a zip throws from listFixtureNames', () => {
        fs.writeFileSync(path.join(setDir, FIXTURE_ZIP_NAME), Buffer.from('this is not a zip archive, it is prose'.repeat(3)));
        expect(() => listFixtureNames(setDir)).toThrow(/End-Of-Central-Directory record not found/);
    });

    it('a truncated zip (EOCD lost) throws rather than listing nothing', () => {
        const zip = buildZip([{ name: NAME_A, data: fixtureJson(HEX_A, 'extract-chunk', '{}') }]);
        fs.writeFileSync(path.join(setDir, FIXTURE_ZIP_NAME), zip.subarray(0, zip.length - 10));
        expect(() => listFixtureNames(setDir)).toThrow(/not a zip file/);
    });

    it('a CRC mismatch on an entry throws from readFixture', () => {
        const zip = buildZip([{ name: NAME_A, data: fixtureJson(HEX_A, 'extract-chunk', '{}'), crc: 0x12345678 }]);
        fs.writeFileSync(path.join(setDir, FIXTURE_ZIP_NAME), zip);
        expect(listFixtureNames(setDir)).toEqual([NAME_A]); // the directory is fine
        expect(() => readFixture(setDir, NAME_A)).toThrow(/CRC mismatch/);
    });

    it('an unsupported compression method throws from readFixture', () => {
        // method 12 = bzip2; the bytes are irrelevant because the reader must refuse before inflating
        const zip = buildZip([{ name: NAME_A, data: 'irrelevant', method: 12 }]);
        fs.writeFileSync(path.join(setDir, FIXTURE_ZIP_NAME), zip);
        expect(() => readFixture(setDir, NAME_A)).toThrow(/unsupported compression method 12/);
    });

    it('parseZipEntries/extractZipEntry work on a bare buffer and reject an encrypted entry', () => {
        const zip = buildZip([{ name: NAME_A, data: 'abc', method: 0 }]);
        // flip general-purpose bit 0 (encryption) in the central directory copy of the flags
        const cdOffset = zip.readUInt32LE(zip.length - 22 + 16);
        zip.writeUInt16LE(0x0001, cdOffset + 8);
        const entries = parseZipEntries(zip, 'in-memory');
        expect([...entries.keys()]).toEqual([NAME_A]);
        expect(() => extractZipEntry(zip, entries.get(NAME_A), 'in-memory')).toThrow(/encrypted entries are not supported/);
    });
});
