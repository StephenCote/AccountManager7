/**
 * Unit tests for show/hide thoughts behavior.
 *
 * Bugs being fixed:
 *   1. Cache key in getFormattedContent did not include hideThoughts, so
 *      clicking the toggle had no visible effect on already-rendered
 *      messages — the cached HTML was returned unchanged.
 *   2. When hideThoughts=false, <think>...</think> was passed through to
 *      marked.parse as raw unknown HTML. The browser renders <think> as
 *      an unstyled inline element, so the thought text blended invisibly
 *      into the message and looked like nothing happened.
 *
 * The show/hide transform now lives in ChatTokenRenderer.renderThoughts and is
 * exercised directly; chat.js is only checked for delegating to it. Handles
 * <think>, <thought> and <private> (character internal dialog).
 */
import { describe, it, expect, beforeAll } from 'vitest';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const chatJsSrc = readFileSync(resolve(__dirname, '..', 'features', 'chat.js'), 'utf-8');

describe('Show/Hide thoughts — chat.js source contract', () => {

    it('getFormattedContent cache key includes a hideThoughts marker', () => {
        // The cache key must vary with hideThoughts so toggling does not
        // return stale cached HTML. Look for "h" / "s" tag in the key.
        let m = chatJsSrc.match(/let\s+cacheKey\s*=\s*[^;\n]+;/);
        expect(m, 'cacheKey assignment should exist').toBeTruthy();
        expect(m[0], 'cacheKey must depend on hideThoughts').toMatch(/hideThoughts/);
    });

    it('streaming preview cache also depends on hideThoughts', () => {
        // Same regression in renderStreamingMessage — its _streamCache.text
        // tag must also include the hideThoughts state.
        let m = chatJsSrc.match(/let\s+cacheTag\s*=\s*[^;\n]+hideThoughts[^;\n]+;/);
        expect(m, 'stream cache tag should depend on hideThoughts').toBeTruthy();
    });

    it('both render paths delegate to ChatTokenRenderer.renderThoughts', () => {
        // getFormattedContent and renderStreamingMessage must share one implementation,
        // so a tag added there (e.g. <private>) applies to saved AND streaming messages.
        let calls = chatJsSrc.match(/ChatTokenRenderer\.renderThoughts\(\s*\w+\s*,\s*hideThoughts\s*\)/g) || [];
        expect(calls.length).toBe(2);
        expect(chatJsSrc, 'no leftover <think>-only regex in chat.js').not.toMatch(/\/<think>/);
    });

    it('toggle handler flips hideThoughts and triggers redraw', () => {
        expect(chatJsSrc, 'toggle handler should still flip hideThoughts')
            .toMatch(/hideThoughts\s*=\s*!hideThoughts/);
        expect(chatJsSrc, 'toggle handler should trigger m.redraw')
            .toMatch(/hideThoughts\s*=\s*!hideThoughts;?\s*m\.redraw\(\)/);
    });
});

describe('Show/Hide thoughts — ChatTokenRenderer.renderThoughts', () => {
    let R, L;
    beforeAll(async () => {
        R = (await import('../chat/ChatTokenRenderer.js')).ChatTokenRenderer;
        L = (await import('../chat/LLMConnector.js')).LLMConnector;
    });

    it('wraps a single <think> block in <details class="chat-thoughts">', () => {
        let result = R.renderThoughts('Before <think>secret reasoning</think> after.', false);
        expect(result).toContain('<details class="chat-thoughts');
        expect(result).toContain('>thinking</summary>');
        expect(result).toContain('secret reasoning');
        expect(result).not.toContain('<think>');
        expect(result).toContain('Before ');
        expect(result).toContain(' after.');
    });

    it('wraps <private> character dialog with a "private" label when shown', () => {
        let result = R.renderThoughts('"Hi Stephen!" <private>I am assessing him</private> *smiles*', false);
        expect(result).toContain('>private</summary>');
        expect(result).toContain('I am assessing him');
        expect(result).not.toContain('<private>');
        expect(result, '*emotes* stay visible').toContain('*smiles*');
    });

    it('wraps <thought> blocks too (previously passed through raw when shown)', () => {
        let result = R.renderThoughts('A <thought>hmm</thought> B', false);
        expect(result).toContain('chat-thoughts');
        expect(result).not.toContain('<thought>');
    });

    it('wraps multiple mixed blocks independently', () => {
        let result = R.renderThoughts('<think>one</think> middle <private>two</private>', false);
        expect((result.match(/chat-thoughts/g) || []).length).toBe(2);
        expect(result).toContain(' middle ');
    });

    it('does not pair mismatched tags', () => {
        let src = '<think>a</private>';
        expect(R.renderThoughts(src, false)).toBe(src);
        expect(R.renderThoughts(src, true)).toBe(src);
    });

    it('handles multiline content (the regex must span newlines)', () => {
        let result = R.renderThoughts('A<private>line1\nline2\nline3</private>B', false);
        expect(result).toContain('line1\nline2\nline3');
        expect(result).toContain('chat-thoughts');
    });

    it('escapes HTML inside the block to prevent injection', () => {
        let result = R.renderThoughts('<private>raw <script>alert(1)</script> & < ></private>', false);
        expect(result).not.toContain('<script>alert(1)</script>');
        expect(result).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
        expect(result).toContain('&amp;');
    });

    it('strips every block entirely when hidden, keeping *emotes*', () => {
        let result = R.renderThoughts('Before <think>x</think><private>secret</private> *waves* after.', true);
        expect(result).toBe('Before  *waves* after.');
    });

    it('pruneForDisplay(hide=true) strips <private> and keeps *emotes*', () => {
        let result = R.pruneForDisplay('"Hi!" <private>secret</private> *grins*', true);
        expect(result).not.toContain('secret');
        expect(result).toContain('*grins*');
    });

    it('pruneForDisplay(hide=false) leaves <private> for renderThoughts to wrap', () => {
        expect(R.pruneForDisplay('<private>secret</private>', false)).toContain('<private>secret</private>');
    });

    it('LLMConnector.pruneAll strips <private>', () => {
        expect(L.pruneAll('Hi <private>secret</private> there')).not.toContain('secret');
    });

    it('content without blocks is unchanged in either mode', () => {
        let plain = 'Just a normal message with *an emote*.';
        expect(R.renderThoughts(plain, false)).toBe(plain);
        expect(R.renderThoughts(plain, true)).toBe(plain);
    });
});

describe('Show/Hide thoughts — cache key behavior', () => {
    /// Mirrors the cache key formula in chat.js getFormattedContent.
    function makeKey(role, idx, hideThoughts, content) {
        return role + ":" + idx + ":" + (hideThoughts ? "h" : "s") + ":" + content;
    }

    it('cache key differs between hidden and shown for the same message', () => {
        let content = 'Plain <think>hidden</think> tail';
        let hiddenKey = makeKey('assistant', 0, true, content);
        let shownKey = makeKey('assistant', 0, false, content);
        expect(hiddenKey).not.toBe(shownKey);
    });

    it('cache key is stable for identical inputs', () => {
        let k1 = makeKey('assistant', 3, false, 'same content');
        let k2 = makeKey('assistant', 3, false, 'same content');
        expect(k1).toBe(k2);
    });
});
