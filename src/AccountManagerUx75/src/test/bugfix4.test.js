/**
 * Bug Fix Sprint #4 — Tests for Issues G, K, E, I, J, H, A
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));

describe('Issue G: MCP format mismatch', () => {

    it('ChatTokenRenderer uses XML <mcp:context> format, not bracket [MCP_CONTEXT]', () => {
        const src = readFileSync(resolve(__dirname, '..', 'chat', 'ChatTokenRenderer.js'), 'utf-8');
        expect(src).not.toContain('[MCP_CONTEXT');
        expect(src).not.toContain('[/MCP_CONTEXT]');
        expect(src).toContain('<mcp:context');
        expect(src).toContain('</mcp:context>');
    });

    it('MCP_BLOCK_RE regex matches backend XML format', async () => {
        const mod = await import('../chat/ChatTokenRenderer.js');
        const renderer = mod.ChatTokenRenderer;

        // Test with backend-format MCP block
        let content = 'Hello <mcp:context type="resource" uri="am7://test" ephemeral="true">{"data":"test"}</mcp:context> world';

        // Non-debug mode: should strip MCP blocks
        let stripped = renderer.processMcpTokens(content, false);
        expect(stripped).toBe('Hello  world');
        expect(stripped).not.toContain('mcp:context');

        // Debug mode: should render as collapsible details
        let debug = renderer.processMcpTokens(content, true);
        expect(debug).toContain('<details');
        expect(debug).toContain('resource');
    });

    it('MCP_BLOCK_RE handles uri before type attribute order', async () => {
        const mod = await import('../chat/ChatTokenRenderer.js');
        const renderer = mod.ChatTokenRenderer;

        let content = '<mcp:context uri="am7://mem/123" type="memory" ephemeral="true">gossip data</mcp:context>';
        let stripped = renderer.processMcpTokens(content, false);
        expect(stripped).toBe('');
    });

    it('stripTokens removes MCP XML blocks', async () => {
        const mod = await import('../chat/ChatTokenRenderer.js');
        const renderer = mod.ChatTokenRenderer;

        let content = 'text <mcp:context type="resource" uri="test">body</mcp:context> more';
        let stripped = renderer.stripTokens(content);
        expect(stripped).toBe('text  more');
    });

    it('processMcpTokens strips <mcp:resource /> self-closing tags', async () => {
        const mod = await import('../chat/ChatTokenRenderer.js');
        const renderer = mod.ChatTokenRenderer;

        let content = 'before <mcp:resource uri="am7://img/123" tags="portrait" /> after';
        let stripped = renderer.processMcpTokens(content, false);
        expect(stripped).toBe('before  after');
    });
});

describe('Issue K: analyzeModel default', () => {

    it('chatConfigModel.json does not default to dolphin-llama3', () => {
        const src = readFileSync(resolve(__dirname, '..', '..', '..', 'AccountManagerObjects7', 'src', 'main', 'resources', 'models', 'olio', 'llm', 'chatConfigModel.json'), 'utf-8');
        expect(src).not.toContain('dolphin-llama3');
    });

    it('modelDef.js does not default to dolphin-llama3', () => {
        const src = readFileSync(resolve(__dirname, '..', 'core', 'modelDef.js'), 'utf-8');
        expect(src).not.toContain('dolphin-llama3');
    });
});

describe('Issue E: Edit save textarea overwrite', () => {

    it('edit mode textarea uses oncreate instead of value attribute', () => {
        const src = readFileSync(resolve(__dirname, '..', 'features', 'chat.js'), 'utf-8');
        // Find the editMessage textarea section
        let editIdx = src.indexOf('id: "editMessage-"');
        expect(editIdx).toBeGreaterThan(-1);
        let block = src.substring(editIdx, editIdx + 500);
        // Should use oncreate for initial value, NOT Mithril-controlled value
        expect(block).toContain('oncreate');
        // Should NOT have value: content (which causes Mithril to overwrite user edits on redraw)
        expect(block).not.toMatch(/value:\s*content/);
    });
});

describe('Issue I: Character null resolution', () => {

    it('doPeek resolves systemCharacter and userCharacter individually via getFull', () => {
        const src = readFileSync(resolve(__dirname, '..', 'features', 'chat.js'), 'utf-8');
        // Should call getFull for olio.charPerson to resolve character stubs
        expect(src).toContain('getFull("olio.charPerson", fullCc.systemCharacter.objectId)');
        expect(src).toContain('getFull("olio.charPerson", fullCc.userCharacter.objectId)');
    });
});

describe('Issue J: LLM connection tracking', () => {

    it('LLMConnector exports connection tracking methods', async () => {
        const mod = await import('../chat/LLMConnector.js');
        const conn = mod.LLMConnector;
        expect(typeof conn.getActiveConnections).toBe('function');
        expect(typeof conn.getActiveConnectionCount).toBe('function');
        expect(typeof conn.abortAllConnections).toBe('function');
    });

    it('getActiveConnections returns empty array initially', async () => {
        const mod = await import('../chat/LLMConnector.js');
        const conn = mod.LLMConnector;
        let active = conn.getActiveConnections();
        expect(Array.isArray(active)).toBe(true);
        expect(active.length).toBe(0);
    });

    it('getActiveConnectionCount returns 0 initially', async () => {
        const mod = await import('../chat/LLMConnector.js');
        expect(mod.LLMConnector.getActiveConnectionCount()).toBe(0);
    });
});

describe('Issue H: promptTemplate in chatConfig form', () => {

    it('formDef chatConfig has promptTemplate field defined', () => {
        const src = readFileSync(resolve(__dirname, '..', 'core', 'formDef.js'), 'utf-8');
        // Find the chatConfig form section
        let cfgIdx = src.indexOf('forms.chatConfig');
        expect(cfgIdx).toBeGreaterThan(-1);
        // The chatConfig form is large; search in a wide window
        let nextFormIdx = src.indexOf('forms.', cfgIdx + 1);
        let cfgBlock = src.substring(cfgIdx, nextFormIdx > cfgIdx ? nextFormIdx : cfgIdx + 20000);
        expect(cfgBlock).toContain('promptTemplate');
        expect(cfgBlock).toContain('olio.llm.promptTemplate');
    });
});

describe('Issue A: Context attachment UX', () => {

    it('ContextPanel imports ObjectPicker', () => {
        const src = readFileSync(resolve(__dirname, '..', 'chat', 'ContextPanel.js'), 'utf-8');
        expect(src).toContain("import { ObjectPicker }");
    });

    it('ContextPanel has attachMenuView with attach types', () => {
        const src = readFileSync(resolve(__dirname, '..', 'chat', 'ContextPanel.js'), 'utf-8');
        expect(src).toContain('attachMenuView');
        expect(src).toContain('_attachTypes');
        expect(src).toContain('"data.data"');
        expect(src).toContain('"data.tag"');
        expect(src).toContain('"olio.charPerson"');
        expect(src).toContain('"olio.llm.chatConfig"');
        expect(src).toContain('"olio.llm.promptConfig"');
    });

    it('ContextPanel PanelView renders attach button', () => {
        const src = readFileSync(resolve(__dirname, '..', 'chat', 'ContextPanel.js'), 'utf-8');
        expect(src).toContain('attachMenuView()');
        expect(src).toContain('"Attach"');
    });

    it('openAttachPicker calls ObjectPicker.open with correct type', () => {
        const src = readFileSync(resolve(__dirname, '..', 'chat', 'ContextPanel.js'), 'utf-8');
        expect(src).toContain('ObjectPicker.open');
        expect(src).toContain('attach("context"');
    });
});

describe('MCP format in chat.js rendering pipeline', () => {

    it('getFormattedContent calls processMcpTokens before formatContent', () => {
        const src = readFileSync(resolve(__dirname, '..', 'features', 'chat.js'), 'utf-8');
        let mcpIdx = src.indexOf('processMcpTokens');
        let fmtIdx = src.indexOf('formatContent(processed)');
        expect(mcpIdx).toBeGreaterThan(-1);
        expect(fmtIdx).toBeGreaterThan(-1);
        // MCP processing must come BEFORE HTML escaping
        expect(mcpIdx).toBeLessThan(fmtIdx);
    });
});

describe('Issue B: Picker field styling', () => {

    it('picker renderer does not have bordered input styling', () => {
        const src = readFileSync(resolve(__dirname, '..', 'components', 'formFieldRenderers.js'), 'utf-8');
        let pickerIdx = src.indexOf('renderers.picker');
        expect(pickerIdx).toBeGreaterThan(-1);
        let pickerBlock = src.substring(pickerIdx, pickerIdx + 2000);
        // Should NOT have input-like border styling in the picker branch
        expect(pickerBlock).not.toContain('border rounded-md border-gray-300');
        expect(pickerBlock).not.toContain('bg-white dark:bg-black');
        // Should have plain text label
        expect(pickerBlock).toContain('cursor-pointer');
        expect(pickerBlock).toContain('"(none)"');
    });

    it('picker shows View and Clear buttons only when value is set', () => {
        const src = readFileSync(resolve(__dirname, '..', 'components', 'formFieldRenderers.js'), 'utf-8');
        let pickerIdx = src.indexOf('renderers.picker');
        let pickerBlock = src.substring(pickerIdx, pickerIdx + 2000);
        // View and Clear should be conditional on hasValue
        expect(pickerBlock).toContain('hasValue ? m("button"');
    });
});

describe('Issue C: Image gallery is pop-in dialog', () => {

    it('page.imageGallery opens a Dialog, not a form tab', () => {
        const src = readFileSync(resolve(__dirname, '..', 'core', 'pageClient.js'), 'utf-8');
        let galleryIdx = src.indexOf('imageGallery:');
        expect(galleryIdx).toBeGreaterThan(-1);
        let galleryBlock = src.substring(galleryIdx, galleryIdx + 8000);
        expect(galleryBlock).toContain('Dialog.open');
        expect(galleryBlock).toContain("'Gallery");
    });

    it('gallery has Set Profile, Auto-Tag, Delete buttons', () => {
        const src = readFileSync(resolve(__dirname, '..', 'core', 'pageClient.js'), 'utf-8');
        let galleryIdx = src.indexOf('imageGallery:');
        let galleryBlock = src.substring(galleryIdx, galleryIdx + 8000);
        expect(galleryBlock).toContain('Set Profile');
        expect(galleryBlock).toContain('Auto-Tag');
        expect(galleryBlock).toContain('Delete');
    });

    it('gallery has arrow key navigation', () => {
        const src = readFileSync(resolve(__dirname, '..', 'core', 'pageClient.js'), 'utf-8');
        let galleryIdx = src.indexOf('imageGallery:');
        let galleryBlock = src.substring(galleryIdx, galleryIdx + 8000);
        expect(galleryBlock).toContain('ArrowLeft');
        expect(galleryBlock).toContain('ArrowRight');
    });

    it('gallery sorts by modifiedDate descending', () => {
        const src = readFileSync(resolve(__dirname, '..', 'core', 'pageClient.js'), 'utf-8');
        let galleryIdx = src.indexOf('imageGallery:');
        let galleryBlock = src.substring(galleryIdx, galleryIdx + 8000);
        expect(galleryBlock).toContain("'modifiedDate'");
        expect(galleryBlock).toContain("'descending'");
    });

    it('gallery auto-opens after reimage completes', () => {
        const src = readFileSync(resolve(__dirname, '..', 'workflows', 'reimage.js'), 'utf-8');
        expect(src).toContain('openGalleryDialog(inst)');
        // Called after both single reimage and sequence
        let matches = src.match(/openGalleryDialog/g);
        expect(matches.length).toBeGreaterThanOrEqual(3); // definition + 2 calls
    });

    it('charPerson toolbar has photo_library button', () => {
        const src = readFileSync(resolve(__dirname, '..', 'views', 'object.js'), 'utf-8');
        expect(src).toContain("'photo_library'");
        expect(src).toContain('imageGallery');
    });
});

describe('Issue F: Firefox performance', () => {

    it('messages container has CSS containment', () => {
        const src = readFileSync(resolve(__dirname, '..', 'features', 'chat.js'), 'utf-8');
        expect(src).toContain('contain: layout style');
    });

    it('streaming redraw is throttled (not raw rAF)', () => {
        const src = readFileSync(resolve(__dirname, '..', 'features', 'chat.js'), 'utf-8');
        let fnIdx = src.indexOf('function scheduleStreamRedraw');
        expect(fnIdx).toBeGreaterThan(-1);
        let fnBlock = src.substring(fnIdx, fnIdx + 300);
        // Should use setTimeout for throttling, not raw requestAnimationFrame
        expect(fnBlock).toContain('setTimeout');
    });

    it('renderMessages limits visible messages during streaming', () => {
        const src = readFileSync(resolve(__dirname, '..', 'features', 'chat.js'), 'utf-8');
        expect(src).toContain('MAX_VISIBLE_MESSAGES_STREAMING');
        expect(src).toContain('renderMessages');
        // Should have the truncation logic
        expect(src).toContain('earlier messages hidden during streaming');
    });
});
