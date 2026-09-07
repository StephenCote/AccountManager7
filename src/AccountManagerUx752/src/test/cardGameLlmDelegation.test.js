/**
 * CardGame LLM base-class delegation.
 *
 * Regression guard for the Ux752 wiring gap: llmBase.js / chatManager.js / director.js
 * previously reached the LLM plumbing via `page.components.llmConnector` and
 * `page.components.chat`, slots that are NEVER registered in Ux752, so every CardGame
 * LLM feature was silently disabled. They now resolve the directly-imported LLMConnector
 * (matching magic8/SessionDirector and the chat feature).
 *
 * These tests exercise the real static delegation with actual values — no REST call and
 * no mocking of the connector (per repo rule). extractContent / cleanJsonResponse are pure;
 * they route straight into LLMConnector.extractContent / parseDirective.
 */
import { describe, it, expect } from 'vitest';
import { CardGameLLM } from '../cardGame/ai/llmBase.js';
import { LLMConnector } from '../chat/LLMConnector.js';

describe('CardGameLLM delegates to the canonical LLMConnector', () => {
    it('extractContent pulls the assistant message via LLMConnector (was "[object Object]" when unwired)', () => {
        const response = { messages: [{ role: 'assistant', content: 'HELLO' }] };
        // Proves the fix: with the connector unregistered this returned String(response) = "[object Object]".
        expect(CardGameLLM.extractContent(response)).toBe('HELLO');
        // And it matches the module it now delegates to.
        expect(CardGameLLM.extractContent(response)).toBe(LLMConnector.extractContent(response));
    });

    it('extractContent preserves the empty-string contract for a null response', () => {
        expect(CardGameLLM.extractContent(null)).toBe('');
    });

    it('cleanJsonResponse repairs markdown-fenced JSON via LLMConnector.parseDirective (was null when unwired)', () => {
        const fenced = '```json\n{"stacks":[{"position":2,"coreCard":"Attack"}]}\n```';
        const cleaned = CardGameLLM.cleanJsonResponse(fenced);
        expect(cleaned).not.toBeNull();
        expect(JSON.parse(cleaned)).toEqual({ stacks: [{ position: 2, coreCard: 'Attack' }] });
    });

    it('cleanJsonResponse returns null for content with no JSON object', () => {
        expect(CardGameLLM.cleanJsonResponse('no json here')).toBeNull();
        expect(CardGameLLM.cleanJsonResponse('')).toBeNull();
    });
});
