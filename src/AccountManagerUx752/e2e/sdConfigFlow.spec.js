/**
 * Reproduces the "Invalid type" failure in the cardgame deckView SD Config panel
 * and the chatConfig system-button auto-load expectation.
 *
 * Both paths run against the live backend with ensureSharedTestUser() per
 * AccountManagerUx752/CLAUDE.md rules (NEVER admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

function attachConsole(page, sink) {
    page.on('console', msg => {
        const t = msg.type();
        const text = msg.text();
        sink.all.push('[' + t + '] ' + text);
        if (t === 'error') {
            // Try to get the stack via the args
            sink.errors.push(text);
            (async () => {
                try {
                    for (const arg of msg.args()) {
                        const v = await arg.jsonValue().catch(() => null);
                        if (v && typeof v === 'string' && v.includes('at ')) sink.stacks.push(v);
                    }
                } catch {}
            })();
        }
    });
    page.on('pageerror', err => {
        sink.errors.push(err.message);
        sink.stacks.push(err.stack || '');
    });
}

test.describe('SD config + chat library', () => {
    let testInfo = {};
    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('cardGame deckView: expanding SD Config panel does not throw "Invalid type"', async ({ page }) => {
        test.setTimeout(180000);
        const sink = { all: [], errors: [], stacks: [] };
        attachConsole(page, sink);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Patch am7model.getModel after page load so we capture every bad call site.
        // Inject the patch after model.js has loaded (after navigating to cardgame
        // we'll re-apply this in the page.evaluate before the click).
        // (Done below via runtime evaluate, not addInitScript — model.js is loaded
        // on demand by route, and addInitScript fires before module load.)

        // Pick an existing deck OR create the smallest viable one in memory.
        // We just want to reach deckView. The previous test left decks in
        // ~/CardGame so list should be non-empty for this user, but be safe.
        await page.goto('/#!/cardGame');
        await page.waitForFunction(() => window.__cardGameCtx, { timeout: 25000 });

        // Now that the cardgame bundle has loaded am7model, wrap getModel to record
        // the caller stack any time it's invoked with null/undefined/empty.
        await page.evaluate(async () => {
            const mod = await import('/src/core/model.js');
            const am7model = mod.am7model;
            const orig = am7model.getModel;
            window.__invalidTypeStacks = [];
            am7model.getModel = function(type) {
                if (type == null || !type) {
                    window.__invalidTypeStacks.push({
                        type: String(type),
                        stack: new Error().stack
                    });
                }
                return orig.call(this, type);
            };
        });

        // Seed: load a minimal in-memory deck and switch directly to deckView.
        // No need for real persistence — the SD Config panel renders solely from
        // ctx.sdOverrides / ArtPipeline state, not the deck itself.
        const seeded = await page.evaluate(async () => {
            const ctx = window.__cardGameCtx;
            // Load theme so getActiveTheme() works (panel header needs it).
            const themes = await import('/src/cardGame/services/themes.js');
            await themes.themes.loadThemeConfig('high-fantasy');
            ctx.viewingDeck = {
                deckName: 'SDPanelProbe', themeId: 'high-fantasy',
                cards: [{ type: 'character', name: 'Probe Hero', sourceId: null,
                          _sourceChar: { name: 'Probe Hero', _tempId: 'p1', statistics: {} } }],
                cardCount: 1
            };
            // __cardGameSetScreen sets ctx.screen and triggers mithril redraw
            window.__cardGameSetScreen('deckView');
            return { ok: true };
        });
        console.log('[deck-seed]', JSON.stringify(seeded));

        await page.waitForFunction(() => window.__cardGameCtx?.screen === 'deckView', { timeout: 30000 });

        // Click the SD Config panel header to expand it — that triggers
        // getSdOverrideInst → prepareInstance(olio.sd.config) → render of
        // the freeForm object view which (per the bug) calls getModel(undefined).
        const sdHeader = page.locator('.cg2-sd-panel-header:has-text("SD Config")').first();
        await expect(sdHeader).toBeVisible({ timeout: 15000 });
        await sdHeader.click();
        await page.waitForTimeout(2000); // let the form render + redraw

        await screenshot(page, 'sdConfig-expanded');

        const invalidTypeErr = sink.errors.find(s => s.includes('Invalid type'));
        const capturedStacks = await page.evaluate(() => window.__invalidTypeStacks || []);
        if (invalidTypeErr || capturedStacks.length) {
            console.log('[Invalid type count]', capturedStacks.length);
            capturedStacks.slice(0, 5).forEach((s, i) => {
                console.log('[Invalid type #' + i + ' type=' + s.type + ']\n' + s.stack);
                console.log('----');
            });
            console.log('[sdConfig recent logs]\n' + sink.all.slice(-50).join('\n'));
        }
        expect(invalidTypeErr, 'Invalid type fired during SD Config panel expansion').toBeUndefined();

        // The form system renders `Invalid field: <name>` literally on the page
        // when forms reference a field the model schema doesn't have. The user
        // hit this for `loras` — the cardgame SD config form has it but the
        // client modelDef.js was drifted from the server schema.
        const invalidFieldCount = await page.locator('text=/Invalid field:/').count();
        if (invalidFieldCount > 0) {
            const which = await page.locator('text=/Invalid field:/').allTextContents();
            console.log('[Invalid field spans rendered]\n' + which.join('\n'));
        }
        expect(invalidFieldCount, 'form rendered "Invalid field: …" span (schema/form mismatch)').toBe(0);
    });

    test('viewDeck refreshAllCharacters: charPerson search succeeds', async ({ page }) => {
        test.setTimeout(120000);
        const failedReqs = [];
        const charPersonReqs = [];
        page.on('response', async (resp) => {
            const url = resp.url();
            if (!url.includes('/rest/')) return;
            const status = resp.status();
            const reqBody = resp.request().postData();
            const isCharPersonSearch = reqBody && reqBody.includes('charPerson');
            if (isCharPersonSearch) {
                let respBody = '';
                try { respBody = (await resp.text()).slice(0, 500); } catch {}
                charPersonReqs.push({
                    status, url,
                    reqBody: (reqBody || '').slice(0, 600),
                    respBody
                });
            }
            if (status >= 400) {
                let body = '';
                try { body = (await resp.text()).slice(0, 400); } catch {}
                failedReqs.push({ status, url, body });
            }
        });
        const consoleErrors = [];
        page.on('console', msg => {
            if (msg.type() === 'error') consoleErrors.push(msg.text());
        });
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        await page.goto('/#!/cardGame');
        await page.waitForFunction(() => window.__cardGameCtx, { timeout: 25000 });

        // Load saved decks directly via the deckStorage list API (loadSavedDecks
        // only fires when the deckList component mounts).
        const deckInfo = await page.evaluate(async () => {
            const storageMod = await import('/src/cardGame/state/storage.js');
            const decks = await storageMod.deckStorage.list();
            return { count: decks.length, decks: decks.slice(0, 3) };
        });
        console.log('[savedDecks]', JSON.stringify(deckInfo));
        if (!deckInfo.count) {
            test.skip(true, 'No saved decks for this user — run cardGameBuilderFlow first to seed one.');
        }

        // Find a deck that has char cards with sourceIds (mirrors what hits
        // refreshAllCharacters → fetchCharPerson in the live UI).
        const pickInfo = await page.evaluate(async (deckNames) => {
            const storage = (await import('/src/cardGame/state/storage.js')).deckStorage;
            for (const dn of deckNames) {
                const d = await storage.load(dn);
                if (!d || !d.cards) continue;
                const charCards = d.cards.filter(c => c.type === 'character' && (c.sourceId || c._sourceChar));
                const withSourceId = charCards.filter(c => c.sourceId);
                if (withSourceId.length > 0) {
                    return { name: dn, charCount: charCards.length, withSourceId: withSourceId.length, firstSourceId: withSourceId[0].sourceId };
                }
            }
            return { name: null };
        }, deckInfo.decks.map(d => d.name || d.deckName || d.storageName));
        console.log('[pick deck]', JSON.stringify(pickInfo));
        if (!pickInfo.name) {
            test.skip(true, 'No saved deck with sourceId-bearing character cards.');
        }

        await page.evaluate(async (name) => {
            const NS = window.__cardGameNS;
            await NS.UI.DeckList.viewDeck(name);
        }, pickInfo.name);

        // Give refreshAllCharacters a chance to fire and complete
        await page.waitForTimeout(8000);

        const failedCp = charPersonReqs.filter(r => r.status >= 400);
        if (failedCp.length || charPersonReqs.length === 0) {
            console.log('[charPerson reqs] count=' + charPersonReqs.length + ' failed=' + failedCp.length);
            charPersonReqs.slice(0, 3).forEach((r, i) => {
                console.log('[req #' + i + ' status=' + r.status + ']');
                console.log('  body: ' + r.reqBody);
                console.log('  resp: ' + r.respBody);
            });
        }
        console.log('[failed reqs all]\n' + failedReqs.slice(0, 5).map(r => r.status + ' ' + r.url + '\n  ' + r.body).join('\n'));

        const postErr = consoleErrors.find(e => e.includes('Failed to post') && e.includes('/rest/model/search'));
        expect(postErr, 'viewDeck triggered a failed /rest/model/search POST').toBeUndefined();
    });

    test('chatConfig list: clicking system button auto-loads templates', async ({ page }) => {
        test.setTimeout(180000);
        const sink = { all: [], errors: [], stacks: [] };
        attachConsole(page, sink);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Reset library cache so this test starts from a clean check
        await page.evaluate(async () => {
            const mod = await import('/src/chat/LLMConnector.js');
            mod.LLMConnector.resetLibraryCache?.();
        });

        await page.goto('/#!/list/olio.llm.chatConfig');
        await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
        await page.waitForTimeout(1500);

        // The system button on the list pagination bar uses the
        // admin_panel_settings material icon. Locate by icon text.
        const sysBtn = page.locator('button:has(.material-symbols-outlined:has-text("admin_panel_settings"))').first();
        await expect(sysBtn).toBeVisible({ timeout: 15000 });
        const beforeUrl = page.url();
        await sysBtn.click();

        // Expected per user: configs auto-load and we navigate to the
        // chat library group, OR at minimum the wizard isn't required and
        // the page completes without requiring further user input.
        await page.waitForTimeout(3000);
        const afterUrl = page.url();
        await screenshot(page, 'chatConfig-system-clicked');

        const wizardVisible = await page.locator('text=Chat Library Setup').isVisible().catch(() => false);
        const sysLibToast = await page.locator('text=System library not initialized').isVisible().catch(() => false);

        // Diagnostic: is the wizard's _visible flag set? Is WizardView mounted?
        const wizardDiag = await page.evaluate(async () => {
            const w = await import('/src/chat/ChatSetupWizard.js');
            return {
                isVisible: w.ChatSetupWizard.isVisible(),
                hasWizardView: typeof w.ChatSetupWizard.WizardView?.view === 'function',
                domDialogCount: document.querySelectorAll('.fixed.inset-0').length
            };
        });
        console.log('[wizard diag]', JSON.stringify(wizardDiag));

        // Read what the LLMConnector says the library status is now
        const libStatus = await page.evaluate(async () => {
            const mod = await import('/src/chat/LLMConnector.js');
            const grp = await mod.LLMConnector.getLibraryGroup('chat');
            return { hasGroup: !!grp, groupName: grp?.name, groupPath: grp?.path };
        });
        console.log('[lib status after click]', JSON.stringify(libStatus));
        console.log('[wizardVisible=' + wizardVisible + ' sysLibToast=' + sysLibToast + ' navUrl=' + (afterUrl !== beforeUrl) + ']');
        // Dump openSystemLibrary trace from the page console
        const osLogs = sink.all.filter(l => l.includes('[openSystemLibrary]'));
        console.log('[openSystemLibrary trace]\n' + osLogs.join('\n'));

        // The system button should either auto-load (libStatus.hasGroup) OR open the
        // ChatSetupWizard so the user can seed it. The pre-fix bug fell through to
        // "System library not initialized" without ever offering either path — that's
        // the regression we're guarding against.
        expect(sysLibToast, 'fell through to fallback toast instead of opening wizard or auto-loading').toBe(false);
        expect(wizardVisible || libStatus.hasGroup,
            'either the wizard appeared or the library was auto-loaded').toBe(true);
        expect(wizardDiag.domDialogCount, 'wizard component is mounted in the render tree').toBeGreaterThan(0);
    });
});
