/**
 * PB2 character-list regression proof.
 *
 * Original bug: after /extract completed, the "Manage Characters" list came back empty
 * because CharacterUtil.randomPerson spun forever on an empty basis-world name pool and the
 * extract never returned. This test drives the WHOLE pipeline through the request API context
 * only (no page.evaluate / no /src/ dev-server imports), so it runs against the Docker prod
 * build on :9443, and hard-asserts that GET /{bookObjectId}/characters is NON-EMPTY.
 *
 * Uses setupWorkflowTestData — NEVER admin.
 */
import { test, expect } from '@playwright/test';
import {
    setupWorkflowTestData, apiLogin, apiLogout,
    ensurePath, createNote, createObject
} from './helpers/api.js';

const REST = '/AccountManagerService7/rest';

const AIME_TEXT = `AIME

Valentines Day singles events were blissfully epicaricacious. Schadenfreudian. Delightfully delectable. Lonely adults trodding out a lifetime's worth of emotional baggage casually dressed to such fantasy as trophies enshrined behind the thin plate glass of a secondhand curio; Of course one or more cats sprayed the back, and occasionally a few exotic bugs crept from the darkened hollows of the rear right leg.

The AI And Me singles event promised to be unlike any other; perhaps had they simply spoke plain, for the price of a geriatric rock star's off-Broadway casino theatre ticket, one could spend a Valentines Day evening taking the very same date they'd been on every day for as long as they could remember; This special evening your phone and you will immerse in a peaceful atmosphere and draw reverie from the curated bar.

Introverts slouching in their date's romantic glow formed a wilted bouquet, long ago plucked buds desperately clutching leathery petals against stems thickened with unnatural fertilizers. An eery somber echo rang for every clink against rectangular glass. And, as if scripted to specific times or events, sometimes in a rippling sequence, lips momentarily touched glass and a tear gleamed silver as it melted through heavy foundation.

With Rejects popping off in a slow though consistent rolling thunder, Break-Ups were streaks and bolts and chains of lightning that when striking close set the spine to shiver. An unsettling crash and crunch, a sudden hush making audible room for the imminent gasp of a dejected soliloquy. Quick-change service staff carry the remains upon a silver platter hoisted betwixt outstretched hands, pallbearers leading a somber procession towards the always available cry room.

Outside, the rain began to fall, light and misty, fog churning like a smoldering fire through the streets. Bathed in the bright neon lights advertising the very explicit fantasies so secretly craved, the walk across the slick street through choking fog appeared programmatic, hypnotic, and the way the doors whisper open and greet with a pleasant warm puff of air is resplendent, only to be greeted by a solemn faced caretaker who prepares an arrangement of new vessels into which you must pour your soul.`;

test('PB2 extract yields a non-empty character list', async ({ request }) => {
    test.setTimeout(1800000); // LLM extraction + Olio seed can take many minutes

    const testInfo = await setupWorkflowTestData(request, { suffix: 'pbchars' + Date.now().toString(36) });
    console.log('=== user=' + testInfo.testUserName + ' ===');

    await apiLogin(request, { user: testInfo.testUserName, password: testInfo.testPassword });

    const ts = Date.now().toString(36);
    const note = await createNote(request, '~/Data', 'AIME-chars-' + ts, AIME_TEXT);
    expect(note && note.objectId, 'AIME note created').toBeTruthy();
    const workObjectId = note.objectId;
    console.log('=== AIME note: ' + workObjectId + ' ===');

    // chatConfig + connection (URL lives on the system.connection FK, not on chatConfig)
    const chatDir = await ensurePath(request, 'auth.group', 'data', '~/Chat');
    expect(chatDir && chatDir.id, 'chat dir').toBeTruthy();
    const conn = await createObject(request, 'system.connection', {
        name: 'PBchars-conn-' + ts,
        groupId: chatDir.id, groupPath: chatDir.path,
        serverUrl: 'http://192.168.1.42:11434', requestTimeout: 300
    });
    expect(conn && conn.id, 'connection').toBeTruthy();
    const cfgName = 'PBchars-cfg-' + ts;
    const cfg = await createObject(request, 'olio.llm.chatConfig', {
        name: cfgName,
        groupId: chatDir.id, groupPath: chatDir.path,
        serviceType: 'ollama', model: 'qwen3-vl:8b-instruct', analyzeModel: 'qwen3-vl:8b-instruct',
        connection: { schema: 'system.connection', id: conn.id, objectId: conn.objectId }
    });
    expect(cfg && cfg.objectId, 'chatConfig').toBeTruthy();
    console.log('=== chatConfig: ' + cfgName + ' ===');

    // Full extract — this is the path that used to hang in the character loop.
    const extractResp = await request.post(REST + '/olio/picture-book/' + workObjectId + '/extract', {
        data: {
            schema: 'olio.pictureBookRequest',
            count: 3, genre: 'contemporary',
            chatConfig: cfgName, bookName: 'AIME Chars Book'
        },
        timeout: 1500000
    });
    console.log('=== extract HTTP ' + extractResp.status() + ' ===');
    expect(extractResp.status(), 'extract returns 200 (did not hang)').toBe(200);
    const meta = await extractResp.json();
    const bookObjectId = meta.bookObjectId;
    const scenes = Array.isArray(meta.scenes) ? meta.scenes : [];
    console.log('=== bookObjectId=' + bookObjectId + ' scenes=' + scenes.length + ' ===');
    scenes.forEach((s, i) => console.log('  scene ' + i + ': "' + (s.title || '') + '"'));
    expect(bookObjectId, 'extract returns a bookObjectId').toBeTruthy();
    expect(scenes.length, 'extract produced scenes').toBeGreaterThan(0);

    // THE ORIGINAL BUG: the character list must be non-empty.
    const charsResp = await request.get(REST + '/olio/picture-book/' + bookObjectId + '/characters');
    console.log('=== GET /characters HTTP ' + charsResp.status() + ' ===');
    expect(charsResp.status(), 'characters endpoint 200').toBe(200);
    const characters = await charsResp.json();
    console.log('=== character count: ' + (Array.isArray(characters) ? characters.length : 'N/A') + ' ===');
    if (Array.isArray(characters)) {
        characters.forEach((c, i) => console.log('  ' + i + ': "' + (c.name || '(unnamed)') + '" gender=' + c.gender + ' portrait=' + c.hasPortrait));
    }

    expect(Array.isArray(characters), 'characters is an array').toBe(true);
    expect(characters.length, 'character list is NON-EMPTY (the original bug)').toBeGreaterThan(0);
    for (const c of characters) {
        expect(c.objectId, 'each character has an objectId').toBeTruthy();
        expect((c.name || '').trim().length, 'each character has a non-empty name').toBeGreaterThan(0);
    }

    await apiLogout(request);
    console.log('VERIFIED: extract completed and character list has ' + characters.length + ' entries');
});
