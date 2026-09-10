/**
 * Regression tests for the WebSocket chirp routing + evaluation/progress handlers.
 *
 * Bug: pageClient.routeMessage collapsed policyEvent / evalProgress / autotuneEvent /
 * interactionEvent into a single handlePolicyEvent call that dropped chirps[2]. Memory-extraction
 * progress (evalProgress: memoryExtract/memoryExtractDone) therefore surfaced as a spurious
 * "Policy violation detected" toast, and every progress event was recorded as a policy violation.
 *
 * These tests exercise the REAL routeMessage dispatch and the REAL LLMConnector handlers — not a
 * reimplementation of their logic — so a regression that re-collapses the branches fails here.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { routeMessage } from '../core/pageClient.js';
import { page } from '../core/pageClient.js';
import { LLMConnector } from '../chat/LLMConnector.js';

// routeMessage dispatches via `import('../chat/LLMConnector.js').then(...)`, so give the microtask
// queue a tick to resolve the (already-cached) dynamic import before asserting.
const flush = () => new Promise(r => setTimeout(r, 20));

describe('routeMessage chirp dispatch', () => {
    let spies;
    beforeEach(() => {
        spies = {
            evalProgress: vi.spyOn(LLMConnector, 'handleEvalProgress').mockImplementation(() => {}),
            policy: vi.spyOn(LLMConnector, 'handlePolicyEvent').mockImplementation(() => {}),
            autotune: vi.spyOn(LLMConnector, 'handleAutotuneEvent').mockImplementation(() => {}),
            interaction: vi.spyOn(LLMConnector, 'handleInteractionEvent').mockImplementation(() => {})
        };
    });
    afterEach(() => { vi.restoreAllMocks(); });

    it('routes evalProgress to handleEvalProgress with phase + detail, NOT to the policy handler', async () => {
        routeMessage({ chirps: ['evalProgress', 'memoryExtractDone', '2 memories extracted'] });
        await flush();
        expect(spies.evalProgress).toHaveBeenCalledWith('memoryExtractDone', '2 memories extracted');
        expect(spies.policy).not.toHaveBeenCalled();
    });

    it('routes a genuine policyEvent to handlePolicyEvent, preserving chirps[2] as data', async () => {
        const details = JSON.stringify({ requestId: 'r1', details: 'identity mismatch' });
        routeMessage({ chirps: ['policyEvent', 'violation', details] });
        await flush();
        expect(spies.policy).toHaveBeenCalledWith({ type: 'violation', data: details });
        expect(spies.evalProgress).not.toHaveBeenCalled();
    });

    it('routes autotuneEvent to handleAutotuneEvent with type + detail', async () => {
        routeMessage({ chirps: ['autotuneEvent', 'promptSuggestion', 'revise'] });
        await flush();
        expect(spies.autotune).toHaveBeenCalledWith('promptSuggestion', 'revise');
        expect(spies.policy).not.toHaveBeenCalled();
    });

    it('routes interactionEvent to handleInteractionEvent with the raw json payload', async () => {
        const payload = JSON.stringify({ interactionType: 'GOSSIP', summary: 'x' });
        routeMessage({ chirps: ['interactionEvent', payload] });
        await flush();
        expect(spies.interaction).toHaveBeenCalledWith(payload);
        expect(spies.policy).not.toHaveBeenCalled();
    });
});

describe('LLMConnector.handleEvalProgress behavior', () => {
    let toastCalls;
    beforeEach(() => {
        toastCalls = [];
        vi.spyOn(page, 'toast').mockImplementation((level, msg, dur) => {
            toastCalls.push({ level, msg, dur });
        });
        LLMConnector.setBgActivity(null, null);
    });
    afterEach(() => { vi.restoreAllMocks(); LLMConnector.setBgActivity(null, null); });

    it('keyframe drives the bgActivity indicator and does NOT toast', () => {
        LLMConnector.handleEvalProgress('keyframe', '');
        expect(LLMConnector.bgActivity).toEqual({ icon: 'psychology', label: 'Memorizing conversation…' });
        expect(toastCalls.filter(t => t.level === 'warn')).toHaveLength(0);
        LLMConnector.handleEvalProgress('keyframeDone', '');
        expect(LLMConnector.bgActivity).toBeNull();
    });

    it('memoryExtract sets bgActivity(neurology); it never emits a policy-violation warn', () => {
        LLMConnector.handleEvalProgress('memoryExtract', '');
        expect(LLMConnector.bgActivity).toEqual({ icon: 'neurology', label: 'Forming memories…' });
        expect(toastCalls.some(t => t.level === 'warn')).toBe(false);
    });

    it('memoryExtractDone with a result clears bgActivity and shows an info toast (not a warning)', async () => {
        LLMConnector.handleEvalProgress('memoryExtractDone', '3 memories extracted');
        expect(LLMConnector.bgActivity).toBeNull();
        expect(toastCalls).toContainEqual({ level: 'info', msg: '3 memories extracted', dur: 3000 });
        expect(toastCalls.some(t => t.level === 'warn')).toBe(false);
        await flush(); // let the MemoryPanel dynamic-import refresh settle without throwing
    });

    it('memoryExtractDone with error shows a warn toast', () => {
        LLMConnector.handleEvalProgress('memoryExtractDone', 'error');
        expect(toastCalls).toContainEqual({ level: 'warn', msg: 'Memory extraction failed', dur: 3000 });
    });

    it('memoryExtractDone triggers MemoryPanel.refresh via dynamic import', async () => {
        const mp = await import('../chat/MemoryPanel.js');
        const refreshSpy = vi.spyOn(mp.MemoryPanel, 'refresh').mockImplementation(() => {});
        LLMConnector.handleEvalProgress('memoryExtractDone', '1 memory extracted');
        await flush();
        expect(refreshSpy).toHaveBeenCalled();
    });

    it('midStreamViolation shows a warn toast prefixed "Policy:" with a 5s duration', () => {
        LLMConnector.handleEvalProgress('midStreamViolation', 'Character identity mismatch');
        expect(toastCalls).toContainEqual({ level: 'warn', msg: 'Policy: Character identity mismatch', dur: 5000 });
    });

    it('an unknown phase is a no-op (no toast, no bgActivity) — never a default policy warning', () => {
        LLMConnector.handleEvalProgress('somethingNew', 'detail');
        expect(toastCalls).toHaveLength(0);
        expect(LLMConnector.bgActivity).toBeNull();
    });
});

describe('LLMConnector.handleAutotuneEvent / handleInteractionEvent behavior', () => {
    let toastCalls;
    beforeEach(() => {
        toastCalls = [];
        vi.spyOn(page, 'toast').mockImplementation((level, msg, dur) => {
            toastCalls.push({ level, msg, dur });
        });
    });
    afterEach(() => { vi.restoreAllMocks(); });

    it('promptSuggestion → info toast and records lastAutotuneEvent', () => {
        LLMConnector.handleAutotuneEvent('promptSuggestion', '');
        expect(toastCalls.some(t => t.level === 'info')).toBe(true);
        expect(LLMConnector.lastAutotuneEvent).toEqual({ type: 'promptSuggestion', data: '' });
    });

    it('complianceViolation → warn toast', () => {
        LLMConnector.handleAutotuneEvent('complianceViolation', 'bias flagged');
        expect(toastCalls).toContainEqual({ level: 'warn', msg: 'Compliance: bias flagged', dur: 6000 });
    });

    it('handleInteractionEvent parses json and records lastInteractionEvent', () => {
        LLMConnector.handleInteractionEvent(JSON.stringify({ interactionType: 'GOSSIP', summary: 'chatter' }));
        expect(LLMConnector.lastInteractionEvent).toEqual({ interactionType: 'GOSSIP', summary: 'chatter' });
        expect(toastCalls.some(t => t.level === 'info' && /gossip/.test(t.msg))).toBe(true);
    });
});
