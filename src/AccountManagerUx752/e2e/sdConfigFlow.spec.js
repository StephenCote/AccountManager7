/**
 * Reproduces the "Invalid type" failure in the cardgame deckView SD Config panel
 * and the chatConfig system-button auto-load expectation.
 *
 * Both paths run against the live backend with ensureSharedTestUser() per
 * AccountManagerUx752/CLAUDE.md rules (NEVER admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, apiLogout, ensurePath, createObject } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

// PLAYWRIGHT_BASE_URL is mandatory for every run; no local-dev fallback.
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL;

/**
 * Provision a charPerson the shared user OWNS, in its own ~/Characters (group-scoped). A fresh
 * shared user has no characters, and an org-wide `olio.charPerson` list with no groupId/organizationId
 * condition is denied by PBAC ("Group could not be found"), so the old "find any charPerson" probe
 * never had a record to work with. createObject searches by name first, so this is idempotent.
 */
async function provisionSharedCharPerson(testInfo) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let lr = await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
        if (!(lr.ok() || lr.status() === 204)) throw new Error('login as ' + testInfo.testUserName + ' failed: HTTP ' + lr.status());
        let charDir = await ensurePath(ctx, 'auth.group', 'data', '~/Characters');
        if (!charDir || !charDir.id) throw new Error('could not ensure ~/Characters for ' + testInfo.testUserName);
        const name = testInfo.testUserName + '_sdcfg_char';
        let rec = await createObject(ctx, 'olio.charPerson', {
            name,
            firstName: 'Sdcfg',
            middleName: 'E2E',
            lastName: 'Character',
            gender: 'female',
            alignment: 'neutralgood',
            groupId: charDir.id,
            groupPath: charDir.path
        });
        if (!rec || !rec.objectId) throw new Error('could not create olio.charPerson ' + name + ' in ~/Characters');
        await apiLogout(ctx);
        // Create returns identity fields only (model-api.md "Create response pattern") — keep the name/group.
        return Object.assign({}, rec, { name, groupId: charDir.id, groupPath: charDir.path });
    } finally {
        await ctx.dispose();
    }
}

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
        await page.waitForFunction(() => window.__cardGameCtx, null, { timeout: 25000 });

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

        await page.waitForFunction(() => window.__cardGameCtx?.screen === 'deckView', null, { timeout: 30000 });

        // Click the SD Config panel header to expand it — that triggers
        // getSdOverrideInst → prepareInstance(olio.sd.config) → render of
        // the freeForm object view which (per the bug) calls getModel(undefined).
        const sdHeader = page.locator('.cg2-sd-panel-header:has-text("SD Config")').first();
        await expect(sdHeader).toBeVisible({ timeout: 15000 });
        await sdHeader.click();
        // The panel body (deckView.js:284-324) must actually render: type tabs + the freeForm
        // olio.sd.config view. Without this the error checks below pass vacuously on a no-op click.
        const sdBody = page.locator('.cg2-sd-panel-body').first();
        await expect(sdBody, 'SD Config panel body expands').toBeVisible({ timeout: 15000 });
        await expect(sdBody.locator('.cg2-sd-tab-active').first()).toHaveText('Default');
        await expect(sdBody.locator('button', { hasText: 'Save to Deck' })).toBeVisible();
        // The freeForm object view renders olio.sd.config fields as real inputs.
        await expect(sdBody.locator('input, select, textarea').first(), 'olio.sd.config form fields render').toBeVisible({ timeout: 15000 });
        await page.waitForTimeout(1000); // let any deferred redraw surface console errors before we read the sink

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

    // refreshAllCharacters (cardGame/services/characters.js:804) -> refreshCharacterCard (:169) ->
    // fetchCharPerson (:152): viewQuery('olio.charPerson') + objectId + request(profile, store, statistics).
    // The user-reported 4xx was on that exact /rest/model/search POST. This test owns its input — a
    // charPerson the shared user created in its own ~/Characters — and calls the product's
    // fetchCharPerson on it, then runs refreshAllCharacters over a deck whose card has that sourceId.
    test('viewDeck refreshAllCharacters: charPerson search succeeds', async ({ page }) => {
        test.setTimeout(120000);
        const charPerson = await provisionSharedCharPerson(testInfo);
        console.log('[charPerson] ' + charPerson.name + ' ' + charPerson.objectId + ' in ' + charPerson.groupPath);

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
        await page.waitForFunction(() => window.__cardGameCtx, null, { timeout: 25000 });

        // 1. The product's own fetchCharPerson on the owned record.
        const probe = await page.evaluate(async (oid) => {
            // characters.js:819 exports `characters` (ESM named export)
            const api = (await import('/src/cardGame/services/characters.js')).characters;
            const am7v = (await import('/src/core/view.js')).am7view;
            const out = {};
            // Mirror the request body fetchCharPerson sends (characters.js:153-156) for the log.
            const q = am7v.viewQuery('olio.charPerson');
            q.field('objectId', oid);
            q.range(0, 1);
            q.entity.request.push('profile', 'store', 'statistics');
            out.requestBody = JSON.stringify(q.entity);
            try {
                const rec = await api.fetchCharPerson(oid);
                out.found = !!rec;
                out.name = rec && rec.name;
                out.objectId = rec && rec.objectId;
                out.keys = rec ? Object.keys(rec).slice(0, 20) : [];
            } catch (e) {
                out.threw = e.message;
            }
            return out;
        }, charPerson.objectId);
        console.log('[fetchCharPerson probe]', JSON.stringify(probe, null, 2));
        expect(probe.threw, 'fetchCharPerson did not throw').toBeUndefined();
        expect(probe.found, 'fetchCharPerson found the owned charPerson').toBe(true);
        expect(probe.objectId).toBe(charPerson.objectId);
        expect(probe.name).toBe(charPerson.name);

        // 2. refreshAllCharacters over an in-memory deck whose card points at that charPerson by sourceId
        //    (the path deckView.js:167 runs on "Refresh").
        const refreshed = await page.evaluate(async (cp) => {
            // characters.js:819 exports `characters` (ESM named export)
            const api = (await import('/src/cardGame/services/characters.js')).characters;
            const deck = {
                deckName: 'RefreshProbe', themeId: 'high-fantasy',
                cards: [{ type: 'character', name: 'stale name', sourceId: cp.objectId }],
                cardCount: 1
            };
            const out = {};
            try {
                await api.refreshAllCharacters(deck);
                out.cardName = deck.cards[0].name;
                out.cardRace = deck.cards[0].race;
                out.hasStats = !!deck.cards[0].stats;
            } catch (e) {
                out.threw = e.message;
            }
            return out;
        }, charPerson);
        console.log('[refreshAllCharacters]', JSON.stringify(refreshed));
        expect(refreshed.threw, 'refreshAllCharacters did not throw').toBeUndefined();
        expect(refreshed.cardName, 'card name refreshed from the charPerson record').toBe(charPerson.name);

        const failedCp = charPersonReqs.filter(r => r.status >= 400);
        console.log('[charPerson reqs] count=' + charPersonReqs.length + ' failed=' + failedCp.length);
        failedCp.slice(0, 3).forEach((r, i) => {
            console.log('[failed req #' + i + ' status=' + r.status + ']');
            console.log('  body: ' + r.reqBody);
            console.log('  resp: ' + r.respBody);
        });
        if (failedReqs.length) console.log('[failed reqs all]\n' + failedReqs.slice(0, 5).map(r => r.status + ' ' + r.url + '\n  ' + r.body).join('\n'));

        expect(charPersonReqs.length, 'at least one charPerson /rest/model/search POST was issued').toBeGreaterThan(0);
        expect(failedCp, 'no charPerson search returned 4xx/5xx').toHaveLength(0);
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
            return { hasGroup: !!grp, groupName: grp?.name, groupPath: grp?.path, groupObjectId: grp?.objectId };
        });
        console.log('[lib status after click]', JSON.stringify(libStatus));
        console.log('[wizardVisible=' + wizardVisible + ' sysLibToast=' + sysLibToast + ' navUrl=' + (afterUrl !== beforeUrl) + ']');
        // Dump openSystemLibrary trace from the page console
        const osLogs = sink.all.filter(l => l.includes('[openSystemLibrary]'));
        console.log('[openSystemLibrary trace]\n' + osLogs.join('\n'));

        // openSystemLibrary (views/list.js:641-696) for olio.llm.chatConfig: a resolved library group
        // navigates to /list/olio.llm.chatConfig/<grp.objectId> (navigateToPathId, :449); no group opens
        // ChatSetupWizard (:668, "Chat Library Setup" in a `.fixed.inset-0` overlay, ChatSetupWizard.js:159-166).
        // The pre-fix bug fell through to "System library not initialized" without offering either path.
        expect(sysLibToast, 'fell through to fallback toast instead of opening wizard or auto-loading').toBe(false);
        if (wizardVisible) {
            expect(wizardDiag.isVisible, 'ChatSetupWizard._visible is set while the wizard shows').toBe(true);
            expect(wizardDiag.domDialogCount, 'wizard overlay is mounted in the render tree').toBeGreaterThan(0);
            await expect(page.locator('.fixed.inset-0 h3', { hasText: 'Chat Library Setup' })).toBeVisible();
        } else {
            expect(libStatus.hasGroup, 'no wizard shown, so the chat library group must have resolved (auto-load)').toBe(true);
            expect(libStatus.groupPath, 'chat library group lives under /Library').toMatch(/^\/Library\//);
            // Auto-load must actually land the user in the library group list.
            await expect(page).toHaveURL(new RegExp('/list/olio\\.llm\\.chatConfig/' + libStatus.groupObjectId), { timeout: 10000 });
            await expect(page.locator('.result-nav-outer').first()).toBeVisible({ timeout: 15000 });
        }
    });
});
