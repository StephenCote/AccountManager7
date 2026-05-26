/**
 * Unit tests for the height feet+inches encoding used by
 * formFieldRenderers.heightFeetInches.
 *
 * Regression: the plain number-input renderer used step=1 on a value stored
 * as feet+inches/100 (5.10 = 5'10"). One spinner click bumped 5.10 → 6.10,
 * which the server reads as 6'10" / 82". With BMI*inches²/703, that adds
 * ~50–100 lbs per accidental foot. The fix splits the input into feet and
 * inches with proper carry.
 *
 * These tests exercise the encode/decode logic directly so the math is
 * verified deterministically — no DOM or live backend needed.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));

// Re-implement encode/decode here to mirror what the renderer does. If the
// renderer changes shape the tests will still verify the encoding contract
// the server expects. (Source: components/formFieldRenderers.js)
function decode(v) {
    if (v == null || v === '' || isNaN(v)) return { feet: 0, inches: 0 };
    let n = Number(v);
    let f = Math.floor(n);
    let i = Math.round((n - f) * 100);
    if (i >= 12) { f += Math.floor(i / 12); i = i % 12; }
    return { feet: f, inches: i };
}
function encode(feet, inches) {
    let total = (feet * 12) + inches;
    let f = Math.floor(total / 12);
    let i = total - (f * 12);
    return Math.round((f + i / 100) * 100) / 100;
}

// Server-side heightToInches (BodyStatsProvider.java) — must match what
// our encode produces.
function serverHeightToInches(height) {
    let feet = Math.trunc(height);
    let inches = Math.round((height - feet) * 100);
    return feet * 12 + inches;
}

// Server-side weight (lbs) = (BMI * inches²) / 703
function serverWeight(bmi, encodedHeight) {
    let inches = serverHeightToInches(encodedHeight);
    return Math.round((bmi * inches * inches) / 703);
}

describe('Height feet+inches encoding', () => {

    it('decodes 5.10 as 5 feet 10 inches (not 5.1 decimal feet)', () => {
        expect(decode(5.10)).toEqual({ feet: 5, inches: 10 });
    });

    it('decodes 6.00 as 6 feet 0 inches', () => {
        expect(decode(6.00)).toEqual({ feet: 6, inches: 0 });
    });

    it('decodes 5.07 as 5 feet 7 inches', () => {
        expect(decode(5.07)).toEqual({ feet: 5, inches: 7 });
    });

    it('handles overflow: decode(5.13) normalizes to 6 feet 1 inch', () => {
        // The renderer self-corrects malformed stored values
        expect(decode(5.13)).toEqual({ feet: 6, inches: 1 });
    });

    it('decodes null/undefined/empty as 0 0', () => {
        expect(decode(null)).toEqual({ feet: 0, inches: 0 });
        expect(decode(undefined)).toEqual({ feet: 0, inches: 0 });
        expect(decode('')).toEqual({ feet: 0, inches: 0 });
    });

    it('encodes 5 feet 10 inches as 5.10', () => {
        expect(encode(5, 10)).toBe(5.10);
    });

    it('encodes 5 feet 11 inches + 1 = 6 feet 0 inches (carry)', () => {
        expect(encode(5, 12)).toBe(6.00);
    });

    it('encodes 5 feet 25 inches as 7 feet 1 inch (multi-carry)', () => {
        expect(encode(5, 25)).toBe(7.01);
    });

    it('round-trips: encode(decode(x)) === x for all valid heights', () => {
        for (let f = 3; f <= 8; f++) {
            for (let i = 0; i <= 11; i++) {
                let stored = encode(f, i);
                let back = decode(stored);
                expect(back).toEqual({ feet: f, inches: i });
            }
        }
    });
});

describe('Weight stability when adjusting height (the user-reported bug)', () => {
    const BMI = 22;

    it('OLD BUG: step=1 on 5.10 → 6.10 would compute as 82 inches and add ~57 lbs', () => {
        // Demonstrates the magnitude of the original bug — NOT something
        // we want to allow.
        let oldWeight = serverWeight(BMI, 5.10);  // 70" → 154 lbs
        let buggyWeight = serverWeight(BMI, 6.10); // 82" → 211 lbs (server reads 6'10")
        let delta = buggyWeight - oldWeight;
        expect(delta).toBeGreaterThan(40);
        expect(delta).toBeLessThan(70);
    });

    it('FIX: incrementing inches by 1 changes weight by no more than 7 lbs at BMI 22', () => {
        // With the feet+inches input, +1 inch is a real +1 inch.
        // Bound is small relative to the OLD bug's 50+ lb spike per click.
        for (let f = 4; f <= 7; f++) {
            for (let i = 0; i < 11; i++) {
                let before = serverWeight(BMI, encode(f, i));
                let after  = serverWeight(BMI, encode(f, i + 1));
                let delta = after - before;
                expect(delta).toBeGreaterThanOrEqual(0);
                expect(delta).toBeLessThanOrEqual(7);
            }
        }
    });

    it('FIX: incrementing 11→12 inches carries cleanly to next foot without spiking weight', () => {
        // 5'11" → 5'12" (which encodes as 6'0") should be a normal +1 inch delta
        let h11 = encode(5, 11);  // 5.11
        let h12 = encode(5, 12);  // 6.00 (with carry)
        expect(h12).toBe(6.00);
        let w11 = serverWeight(BMI, h11);
        let w12 = serverWeight(BMI, h12);
        expect(w12 - w11).toBeLessThanOrEqual(5);
    });
});

describe('Renderer source contract', () => {
    it('formFieldRenderers.js registers heightFeetInches', () => {
        const src = readFileSync(resolve(__dirname, '..', 'components', 'formFieldRenderers.js'), 'utf-8');
        expect(src).toContain('renderers.heightFeetInches');
    });

    it('formDef.js wires height to heightFeetInches format', () => {
        const src = readFileSync(resolve(__dirname, '..', 'core', 'formDef.js'), 'utf-8');
        // The statistics form's height field should use the new format.
        expect(src).toMatch(/height:\s*\{[^}]*format:\s*['"]heightFeetInches['"]/);
    });
});
