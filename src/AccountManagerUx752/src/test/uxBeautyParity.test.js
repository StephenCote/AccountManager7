/**
 * Parity guard for the beauty selector's client-side copy of the server composite chain.
 *
 * formDef.js duplicates olio.statistics' composite chain in JS so the beauty selector can solve for a
 * band on unsaved state without a round trip — the same tradeoff bodyShapeMidpoints already makes
 * against BodyStatsProvider's classifier. Duplication is only safe if something goes red when the two
 * drift apart.
 *
 * This test and the JUnit test TestUxBeautyParity read the SAME fixture:
 *   AccountManagerObjects7/src/test/resources/olio/beautyParityFixture.json
 * The JUnit side runs each stat block through the real provider chain and asserts the fixture is what
 * the server actually computes. This side asserts formDef.js reproduces it. Changing either
 * implementation alone turns one of them red.
 */
import { describe, it, expect, beforeAll } from 'vitest';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const FIXTURE = resolve(__dirname,
    '../../../AccountManagerObjects7/src/test/resources/olio/beautyParityFixture.json');

let fixture;
let am7model;

beforeAll(async () => {
    fixture = JSON.parse(readFileSync(FIXTURE, 'utf8'));
    /// formDef.js registers the chain on am7model as a side effect of import
    await import('../core/formDef.js');
    am7model = (await import('../core/model.js')).am7model;
});

describe('Ux beauty selector / server composite parity', () => {

    it('exposes the chain formDef.js uses for the selector', () => {
        expect(typeof am7model.computeBeautyFromStats).toBe('function');
        expect(typeof am7model.beautyLabelForStat).toBe('function');
        expect(Array.isArray(am7model.beautyBands)).toBe(true);
    });

    it('the fixture is usable and spans every band', () => {
        expect(fixture.cases.length).toBeGreaterThan(0);
        const labels = new Set(fixture.cases.map(c => c.label));
        for (const band of ['hideous', 'homely', 'bland', 'comely', 'pretty', 'beautiful', 'gorgeous']) {
            expect(labels.has(band), `fixture never exercises the '${band}' band`).toBe(true);
        }
    });

    it('reproduces the server beauty statistic for every fixture stat block', () => {
        const drift = [];
        for (const c of fixture.cases) {
            const beauty = am7model.computeBeautyFromStats(c.stats);
            if (beauty !== c.beauty) {
                drift.push(`${c.name}: server ${c.beauty}, client ${beauty}`);
            }
        }
        expect(drift, `client chain drifted from the server composite:\n  ${drift.join('\n  ')}`).toEqual([]);
    });

    it('reproduces the server beauty label for every fixture stat block', () => {
        const drift = [];
        for (const c of fixture.cases) {
            const label = am7model.beautyLabelForStat(am7model.computeBeautyFromStats(c.stats));
            if (label !== c.label) {
                drift.push(`${c.name}: server '${c.label}', client '${label}'`);
            }
        }
        expect(drift, `client banding drifted from NarrativeUtil.getLooksPrettyUgly:\n  ${drift.join('\n  ')}`).toEqual([]);
    });

    /// The bands must tile 0..20 with no gap and no overlap, or a real beauty statistic can fall
    /// through to the 'indescribable' fallback.
    it('bands tile the whole 0-20 range', () => {
        const seen = [];
        for (let stat = 0; stat <= 20; stat++) {
            const label = am7model.beautyLabelForStat(stat);
            expect(label, `beauty ${stat} has no band`).not.toBe('indescribable');
            if (!seen.includes(label)) seen.push(label);
        }
        expect(seen).toEqual(['hideous', 'homely', 'bland', 'comely', 'pretty', 'beautiful', 'gorgeous']);
    });
});
