// @vitest-environment jsdom
/**
 * PictureBook V3 composition board (P4-9, design §26) — the pure helpers of features/pictureBookCompose.js.
 *
 * Why: the canvas geometry (percent boxes, z from depth, drag clamping), the compose-job counter text, the
 * configOverride parse/delta and the headline-prompt pick from the /plan `compiled` stage map are the pieces
 * a Playwright run cannot pin down value-by-value. Nothing here touches the network.
 *
 * pictureBookCompose.js imports pageClient before dialogCore, so importing the feature first is safe.
 */
import { describe, it, expect } from 'vitest';
import * as cmp from '../features/pictureBookCompose.js';
import am7model from '../core/model.js';

describe('key lists', () => {
    it('COMPOSITION_KEYS are the camera/setting fields the board saves as one PATCH', () => {
        expect(cmp.COMPOSITION_KEYS).toEqual(['settingText', 'action', 'mood', 'shotType', 'viewpoint', 'cameraText', 'props', 'focalActorObjectId']);
        expect(cmp.PROMPT_KEYS).toEqual(['promptOverride', 'promptLocked']);
        expect(cmp.ACTOR_KEYS).toContain('referencePolicy');
        expect(cmp.ACTOR_KEYS).toContain('count');
        expect(cmp.ELEMENT_KEYS).toEqual(['elementType', 'description', 'x', 'y', 'w', 'h', 'depth', 'importance']);
        expect(cmp.COMPOSITION_KEYS).not.toContain('promptOverride');
        expect(cmp.COMPOSITION_KEYS).not.toContain('configOverride');
    });
});

describe('canvasAspect', () => {
    it('is width/height from the plan canvas', () => {
        expect(cmp.canvasAspect({ canvas: { width: 1024, height: 768 } })).toBeCloseTo(4 / 3, 6);
        expect(cmp.canvasAspect({ canvas: { width: '1024', height: '1024' } })).toBe(1);
    });
    it('falls back to 4:3 when the canvas is absent or degenerate', () => {
        expect(cmp.canvasAspect(null)).toBeCloseTo(4 / 3, 6);
        expect(cmp.canvasAspect({})).toBeCloseTo(4 / 3, 6);
        expect(cmp.canvasAspect({ canvas: {} })).toBeCloseTo(4 / 3, 6);
        expect(cmp.canvasAspect({ canvas: { width: 0, height: 768 } })).toBeCloseTo(4 / 3, 6);
        expect(cmp.canvasAspect({ canvas: { width: 'x', height: 768 } })).toBeCloseTo(4 / 3, 6);
    });
});

describe('boxStyle', () => {
    it('maps fractions to percent and the int depth to z (0 = front-most = highest; each layer back is one lower)', () => {
        let s = cmp.boxStyle({ x: 0.25, y: 0.5, w: 0.3, h: 0.2, depth: 2 });
        expect(s).toEqual({ position: 'absolute', left: '25.00%', top: '50.00%', width: '30.00%', height: '20.00%', zIndex: '998' });
        expect(cmp.boxStyle({ depth: 0 }).zIndex).toBe('1000');
        expect(cmp.boxStyle({ depth: 1 }).zIndex).toBe('999');
        expect(cmp.boxStyle({ depth: 7 }).zIndex).toBe('993');
        expect(Number(cmp.boxStyle({ depth: 1 }).zIndex) < Number(cmp.boxStyle({ depth: 0 }).zIndex), 'front-most actor stacks above the one behind it').toBe(true);
    });
    it('a fractional or negative depth rounds / floors to a layer (legacy 0..1 rows land in front), huge depths bottom out at 0', () => {
        expect(cmp.boxStyle({ depth: 0.4 }).zIndex).toBe('1000');
        expect(cmp.boxStyle({ depth: 2.6 }).zIndex).toBe('997');
        expect(cmp.boxStyle({ depth: -3 }).zIndex).toBe('1000');
        expect(cmp.boxStyle({ depth: 'nope' }).zIndex).toBe('1000');
        expect(cmp.boxStyle({ depth: 5000 }).zIndex).toBe('0');
    });
    it('clamps the box into 0..1 and gives empty/invalid boxes a 10% footprint', () => {
        let s = cmp.boxStyle({ x: -1, y: 2, w: 0, h: 'nope', depth: 7 });
        expect(s.left).toBe('0.00%');
        expect(s.top).toBe('100.00%');
        expect(s.width).toBe('10.00%');
        expect(s.height).toBe('10.00%');
        expect(s.zIndex).toBe('993');
        expect(cmp.boxStyle(null).width).toBe('10.00%');
    });
});

describe('dragMove', () => {
    it('adds the delta and rounds to 3 dp', () => {
        expect(cmp.dragMove({ x: 0.1, y: 0.2, w: 0.2, h: 0.2 }, 0.1234, -0.05)).toEqual({ x: 0.223, y: 0.15 });
    });
    it('keeps the box inside the page', () => {
        expect(cmp.dragMove({ x: 0.1, y: 0.1, w: 0.3, h: 0.4 }, 5, 5)).toEqual({ x: 0.7, y: 0.6 });
        expect(cmp.dragMove({ x: 0.1, y: 0.1, w: 0.3, h: 0.4 }, -5, -5)).toEqual({ x: 0, y: 0 });
        expect(cmp.dragMove({ x: 0.5, y: 0.5, w: 2, h: 2 }, 0.1, 0.1)).toEqual({ x: 0, y: 0 });
    });
    it('tolerates garbage', () => {
        expect(cmp.dragMove(null, 'a', undefined)).toEqual({ x: 0, y: 0 });
        expect(cmp.dragMove({ x: 'x', y: 'y', w: -1, h: -1 }, 0.5, 0.5)).toEqual({ x: 0.5, y: 0.5 });
    });
});

describe('sceneLabel', () => {
    it('is "index · title", index alone without a title, ? without an index', () => {
        expect(cmp.sceneLabel(null)).toBe('');
        expect(cmp.sceneLabel({ sceneIndex: 3, title: 'Quay' })).toBe('3 · Quay');
        expect(cmp.sceneLabel({ sceneIndex: 0 })).toBe('0');
        expect(cmp.sceneLabel({ title: 'Untitled' })).toBe('? · Untitled');
    });
});

describe('composeCounterText', () => {
    it('reports submit, progress and the terminal outcomes', () => {
        expect(cmp.composeCounterText(null)).toBe('Submitting…');
        expect(cmp.composeCounterText({ terminal: false, current: 3, total: 12, phase: 'scene 3' })).toBe('3 of 12 · scene 3');
        expect(cmp.composeCounterText({ terminal: false })).toBe('0 of 0');
        expect(cmp.composeCounterText({ terminal: true, cancelled: true })).toBe('Cancelled');
        expect(cmp.composeCounterText({ terminal: true, error: 'boom' })).toBe('Failed: boom');
        expect(cmp.composeCounterText({ terminal: true })).toBe('Done');
        expect(cmp.composeCounterText({ terminal: true, result: 'text' })).toBe('Done');
    });
    it('summarises the compose job result', () => {
        expect(cmp.composeCounterText({ terminal: true, result: { composed: 4 } })).toBe('Done: composed 4');
        expect(cmp.composeCounterText({ terminal: true, result: {} })).toBe('Done: composed 0');
        expect(cmp.composeCounterText({ terminal: true, result: {
            composed: 4, current: 2, skippedUserEdited: 1, migrated: 3, hardFailures: 1, pagesSkipped: 2, quickStartRendered: 1, quickStartFailed: 1, interrupted: true
        } })).toBe('Done: composed 4, current 2, kept 1 hand-edited, migrated 3, failed 1, pages skipped 2, drafts rendered 1, drafts failed 1 (interrupted)');
    });
    it('"migrated N" is the loop count plus the up-front migration pass, and staled renders are counted (P4-12 h2/c1)', () => {
        expect(cmp.composeCounterText({ terminal: true, result: { composed: 2, migrated: 0, migration: { created: 5 } } })).toBe('Done: composed 2, migrated 5');
        expect(cmp.composeCounterText({ terminal: true, result: { composed: 2, migrated: 1, migration: { created: 5, failed: 1 } } })).toBe('Done: composed 2, migrated 6');
        expect(cmp.composeCounterText({ terminal: true, result: { composed: 2, migration: { created: 0 } } })).toBe('Done: composed 2');
        expect(cmp.composeCounterText({ terminal: true, result: { composed: 3, staled: 3, hardFailures: 1 } })).toBe('Done: composed 3, failed 1, renders staled 3');
    });
});

describe('composeSceneText', () => {
    it('reports a plain compose; source and counts only when asked (the re-compose toast)', () => {
        expect(cmp.composeSceneText(null, 'composed')).toBe('Scene composed');
        expect(cmp.composeSceneText({ written: true, source: 'LLM', actors: [1, 2], elements: [1] }, 'composed')).toBe('Scene composed');
        expect(cmp.composeSceneText({ written: true, source: 'LLM', actors: [1, 2], elements: [1] }, 're-composed', true)).toBe('Scene re-composed (LLM, 2 actors, 1 elements)');
        expect(cmp.composeSceneText({ written: true }, 're-composed', true)).toBe('Scene re-composed (llm, 0 actors, 0 elements)');
    });
    it('reports the failure reason when nothing was written', () => {
        expect(cmp.composeSceneText({ written: false, failureReason: 'LLM unavailable' }, 'composed')).toBe('Scene was not composed: LLM unavailable');
        expect(cmp.composeSceneText({ written: false }, 're-composed')).toBe('Scene was not re-composed');
    });
    it('names migrate-first, the migrated fallback and the stale render mark, then the warnings (P4-12 h1/c1)', () => {
        expect(cmp.composeSceneText({ written: true, source: 'LLM', actors: [1], elements: [], migratedFirst: true, nodeStale: true }, 'composed'))
            .toBe('Scene composed — legacy prompt migrated first; render marked stale');
        expect(cmp.composeSceneText({ written: true, source: 'MIGRATED', actors: [], elements: [], migratedFirst: true, migratedFallback: true, nodeStale: false, warnings: ['LLM unavailable; the MIGRATED composition written first from the scene\'s own fields stands'] }, 'composed'))
            .toBe('Scene composed — legacy prompt migrated; the LLM compose did not land, so it stands — LLM unavailable; the MIGRATED composition written first from the scene\'s own fields stands');
        expect(cmp.composeSceneText({ written: true, source: 'LLM', actors: [], elements: [], nodeStale: true, warnings: ['w1', 'w2'] }, 're-composed', true))
            .toBe('Scene re-composed (LLM, 0 actors, 0 elements) — render marked stale — w1; w2');
    });
});

describe('parseConfigOverride', () => {
    it('parses a sparse object and rejects blanks, arrays, scalars and bad JSON', () => {
        expect(cmp.parseConfigOverride('{"steps":20,"schema":"olio.sd.config"}')).toEqual({ steps: 20, schema: 'olio.sd.config' });
        expect(cmp.parseConfigOverride('  ')).toBeNull();
        expect(cmp.parseConfigOverride(null)).toBeNull();
        expect(cmp.parseConfigOverride('[1]')).toBeNull();
        expect(cmp.parseConfigOverride('7')).toBeNull();
        expect(cmp.parseConfigOverride('{nope')).toBeNull();
    });
});

describe('computeOverrideDelta', () => {
    it('merges the stored keys with the instance changes and stamps the schema', () => {
        let inst = { changes: ['steps', 'cfg'], entity: { steps: 30, cfg: 6.5, name: 'ignored' } };
        expect(cmp.computeOverrideDelta('{"schema":"olio.sd.config","width":512}', inst)).toEqual({ width: 512, steps: 30, cfg: 6.5, [am7model.jsonModelKey]: 'olio.sd.config' });
    });
    it('drops identity keys and treats blank changes as deletions', () => {
        let inst = { changes: { steps: true, width: true }, entity: { steps: '', width: null, id: 9, objectId: 'x' } };
        expect(cmp.computeOverrideDelta('{"steps":20,"width":512,"id":9,"objectId":"x","groupId":3}', inst)).toBeNull();
        let inst2 = { changes: ['steps', 'groupId', 'objectId'], entity: { steps: 12, groupId: 3, objectId: 'x' } };
        expect(cmp.computeOverrideDelta('', inst2)).toEqual({ steps: 12, [am7model.jsonModelKey]: 'olio.sd.config' });
    });
    it('is null when nothing is set', () => {
        expect(cmp.computeOverrideDelta('', null)).toBeNull();
        expect(cmp.computeOverrideDelta('{"schema":"olio.sd.config"}', { changes: [], entity: {} })).toBeNull();
    });
});

describe('overrideUnchanged', () => {
    it('is true when the delta carries exactly the stored keys and values (schema and identity ignored)', () => {
        expect(cmp.overrideUnchanged('', null)).toBe(true);
        expect(cmp.overrideUnchanged('{"schema":"olio.sd.config"}', null)).toBe(true);
        expect(cmp.overrideUnchanged('{"steps":20,"width":512,"id":9,"schema":"olio.sd.config"}', { width: 512, steps: 20, [am7model.jsonModelKey]: 'olio.sd.config' })).toBe(true);
        expect(cmp.overrideUnchanged('{"loras":[{"name":"a","weight":0.5}]}', { loras: [{ name: 'a', weight: 0.5 }], [am7model.jsonModelKey]: 'olio.sd.config' })).toBe(true);
    });
    it('is false when a key is added, removed or changed — including a blanked stored override', () => {
        expect(cmp.overrideUnchanged('{"steps":20}', null)).toBe(false);
        expect(cmp.overrideUnchanged('', { steps: 12, [am7model.jsonModelKey]: 'olio.sd.config' })).toBe(false);
        expect(cmp.overrideUnchanged('{"steps":20}', { steps: 30, [am7model.jsonModelKey]: 'olio.sd.config' })).toBe(false);
        expect(cmp.overrideUnchanged('{"steps":20}', { steps: 20, cfg: 7, [am7model.jsonModelKey]: 'olio.sd.config' })).toBe(false);
        expect(cmp.overrideUnchanged('{"steps":20,"cfg":7}', { steps: 20, [am7model.jsonModelKey]: 'olio.sd.config' })).toBe(false);
    });
});

describe('userEditedWarning', () => {
    it('warns only for hand-edited compositions', () => {
        expect(cmp.userEditedWarning({ userEdited: true })).toBe(cmp.USER_EDITED_WARNING);
        expect(cmp.userEditedWarning({ userEdited: false })).toBeNull();
        expect(cmp.userEditedWarning(null)).toBeNull();
    });
});

describe('planHeadlinePrompt', () => {
    it('picks the first compiled stage with a prompt (compiled is a stage map on the wire)', () => {
        let p = { compiled: { base: { prompt: '', negative: 'n0' }, refine: { prompt: 'a quay at dusk', negative: 'blurry', estimatedTokens: 12 }, upscale: { prompt: 'later' } } };
        expect(cmp.planHeadlinePrompt(p)).toEqual({ stage: 'refine', prompt: 'a quay at dusk', negative: 'blurry' });
    });
    it('tolerates a flat {prompt, negative}', () => {
        expect(cmp.planHeadlinePrompt({ compiled: { prompt: 'flat', negative: 'neg' } })).toEqual({ stage: null, prompt: 'flat', negative: 'neg' });
        expect(cmp.planHeadlinePrompt({ compiled: { prompt: 'flat' } })).toEqual({ stage: null, prompt: 'flat', negative: '' });
    });
    it('is null when nothing was compiled — never a JSON dump of the stage map', () => {
        expect(cmp.planHeadlinePrompt(null)).toBeNull();
        expect(cmp.planHeadlinePrompt({})).toBeNull();
        expect(cmp.planHeadlinePrompt({ compiled: {} })).toBeNull();
        expect(cmp.planHeadlinePrompt({ compiled: { base: { prompt: '  ' } } })).toBeNull();
        expect(cmp.planHeadlinePrompt({ compiled: { base: 'text' } })).toBeNull();
    });
});
