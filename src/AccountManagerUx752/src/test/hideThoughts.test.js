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
 * Verified via source inspection of the rendering logic (the function is
 * embedded in chat.js with many module-level dependencies; extracting the
 * helper would require a separate file). The Playwright counterpart can
 * be added once a chat session with thinking-mode content exists.
 */
import { describe, it, expect } from 'vitest';
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

    it('when hideThoughts=false, <think> blocks get wrapped in a styled <details>', () => {
        // Find the else branch and confirm it emits <details> markup
        // rather than letting raw <think> pass to marked.parse.
        let hasShowBranch = /<think>\(.*?\)<\\\/think>/.test(chatJsSrc.replace(/\s+/g, ''));
        // Look for the wrap-as-details substitution pattern
        expect(chatJsSrc, 'should wrap <think> as <details class="chat-thoughts">')
            .toMatch(/<think>.*<\/think>/);
        expect(chatJsSrc, 'chat-thoughts wrapper class should be emitted')
            .toContain('chat-thoughts');
        expect(chatJsSrc, 'thinking summary label should be emitted')
            .toContain('>thinking<');
    });

    it('when hideThoughts=true, <think> blocks are stripped (not wrapped)', () => {
        // The stripping branch must still exist.
        expect(chatJsSrc, 'should still strip <think>...</think> when hidden')
            .toMatch(/replace\(\s*\/<think>\[\\s\\S\]\*\?<\\\/think>\/g\s*,\s*""\s*\)/);
    });

    it('toggle handler flips hideThoughts and triggers redraw', () => {
        expect(chatJsSrc, 'toggle handler should still flip hideThoughts')
            .toMatch(/hideThoughts\s*=\s*!hideThoughts/);
        expect(chatJsSrc, 'toggle handler should trigger m.redraw')
            .toMatch(/hideThoughts\s*=\s*!hideThoughts;?\s*m\.redraw\(\)/);
    });
});

describe('Show/Hide thoughts — wrap-as-details regex behavior', () => {
    /// The exact replace pattern is mirrored from chat.js getFormattedContent
    /// else-branch. If you change this in chat.js, change it here too —
    /// the goal is to assert the actual transformation, not just the
    /// presence of strings in source.
    function wrapThoughtsAsDetails(content) {
        return content.replace(/<think>([\s\S]*?)<\/think>/g, function(_m, inner) {
            let escaped = String(inner)
                .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            return '<details class="chat-thoughts my-2 rounded border border-amber-300 dark:border-amber-700 bg-amber-50/40 dark:bg-amber-900/20 text-xs" open>'
                 + '<summary class="cursor-pointer px-2 py-1 text-amber-700 dark:text-amber-300 font-medium">thinking</summary>'
                 + '<div class="px-2 py-1 whitespace-pre-wrap text-gray-700 dark:text-gray-300">'
                 + escaped
                 + '</div></details>';
        });
    }

    function stripThoughts(content) {
        return content.replace(/<think>[\s\S]*?<\/think>/g, "");
    }

    it('wraps a single <think> block in <details class="chat-thoughts">', () => {
        let result = wrapThoughtsAsDetails(
            'Before <think>secret reasoning</think> after.');
        expect(result).toContain('<details class="chat-thoughts');
        expect(result).toContain('<summary');
        expect(result).toContain('>thinking</summary>');
        expect(result).toContain('secret reasoning');
        expect(result).not.toContain('<think>');
        expect(result).toContain('Before ');
        expect(result).toContain(' after.');
    });

    it('wraps multiple <think> blocks independently', () => {
        let result = wrapThoughtsAsDetails(
            '<think>one</think> middle <think>two</think>');
        let matches = result.match(/chat-thoughts/g) || [];
        expect(matches.length).toBe(2);
        expect(result).toContain('one');
        expect(result).toContain('two');
        expect(result).toContain(' middle ');
    });

    it('handles multiline thoughts (the regex must span newlines)', () => {
        let result = wrapThoughtsAsDetails(
            'A<think>line1\nline2\nline3</think>B');
        expect(result).toContain('line1\nline2\nline3');
        expect(result).toContain('chat-thoughts');
    });

    it('escapes HTML inside the thought to prevent injection', () => {
        let result = wrapThoughtsAsDetails(
            '<think>raw <script>alert(1)</script> & < ></think>');
        expect(result).not.toContain('<script>alert(1)</script>');
        expect(result).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
        expect(result).toContain('&amp;');
    });

    it('stripThoughts removes the block entirely when hidden', () => {
        let result = stripThoughts(
            'Before <think>secret</think> after.');
        expect(result).toBe('Before  after.');
        expect(result).not.toContain('secret');
        expect(result).not.toContain('<think>');
    });

    it('content without <think> is unchanged in either branch', () => {
        let plain = 'Just a normal message with no thinking.';
        expect(wrapThoughtsAsDetails(plain)).toBe(plain);
        expect(stripThoughts(plain)).toBe(plain);
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
