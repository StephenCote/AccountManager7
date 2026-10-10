/**
 * Regression test: cleanupTestUserFull() must not leave orphaned content records behind.
 *
 * Root cause this guards against: deleting an auth.group never cascades to its contents in AM7
 * (deliberate design — see aiDocs/KnownIssues.md KI-20/21). cleanupTestUser() deletes the ~/Notes
 * (and sibling) group containers but not the data.note rows inside them, leaving them behind with a
 * now-dangling groupId. Those orphans later tripped PolicyUtil ("Group could not be found") and
 * PathProvider (groupPath degrading to "/" + name) when anything touched them — observed live via
 * leftover "LC4Note Note N" rows from listControl.spec.js runs, which called plain cleanupTestUser().
 * Fix: use cleanupTestUserFull(), which deletes the content records inside ~/Notes and ~/Data
 * before the group deletes. (It previously ran the server-side sweep via GET /rest/model/cleanup
 * instead; that route also runs a database-wide `vacuum(full)` and deadlocked parallel workers —
 * see the helper's comment, 2026-10-10.)
 */
import { test, expect } from '@playwright/test';
import { setupTestUser, cleanupTestUserFull, apiLogin, searchByField } from './helpers/api.js';

test.describe('cleanupTestUserFull orphan prevention', () => {
    test('notes created under ~/Notes do not survive cleanupTestUserFull', async ({ request }) => {
        const testInfo = await setupTestUser(request, {
            suffix: 'orphanchk' + Date.now().toString(36),
            noteCount: 1,
            notePrefix: 'OrphanCheck'
        });
        expect(testInfo.notes.length).toBeGreaterThan(0);
        const createdNote = testInfo.notes[0];

        await cleanupTestUserFull(request, testInfo.user?.objectId, { userName: testInfo.testUserName });

        // Verify via a fresh admin-authenticated read-only lookup (the test user itself is now
        // deleted, so it can't be used to check its own former data) that the note record is
        // genuinely gone, not merely orphaned with a dangling groupId.
        await apiLogin(request, { org: '/Development' });
        const stillExists = await searchByField(request, 'data.note', 'objectId', createdNote.objectId, ['id', 'objectId', 'name']);
        expect(stillExists).toBeNull();
    });
});
