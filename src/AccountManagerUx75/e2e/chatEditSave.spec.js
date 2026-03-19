/**
 * Chat edit save — tests that editing messages persists to the server.
 * Uses REST API to create session, send message via /rest/chat/text, then test edit.
 */
import { test as base, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { captureConsole } from './helpers/console.js';
import { ensureSharedTestUser } from './helpers/api.js';

const test = base;

test.describe('Chat edit save', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('edit message content persists after save', async ({ page }) => {
        test.setTimeout(300000);

        let capture = captureConsole(page, { verbose: true });
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Navigate to chat to load modules
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        // Dismiss wizard if present
        let wizardTitle = page.locator('text=Chat Library Setup');
        if (await wizardTitle.isVisible({ timeout: 3000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
            await expect(wizardTitle).not.toBeVisible({ timeout: 5000 });
        }

        // Create a session and send a test message via REST API
        let setup = await page.evaluate(async () => {
            let { am7client } = await import('/src/core/am7client.js');
            let { am7model } = await import('/src/core/model.js');
            let { am7view } = await import('/src/core/view.js');
            let { page } = await import('/src/core/pageClient.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let { ConversationManager } = await import('/src/chat/ConversationManager.js');
            let { applicationPath } = await import('/src/core/config.js');
            let log = [];

            // Check library status and init if needed
            let libOk = await LLMConnector.ensureLibrary();
            log.push('Library status: ' + libOk);

            if (!libOk) {
                // Try to init library
                try {
                    await LLMConnector.initLibrary();
                    libOk = await LLMConnector.ensureLibrary();
                    log.push('Library init result: ' + libOk);
                } catch (e) {
                    log.push('Library init error: ' + e.message);
                }
            }

            if (!libOk) {
                return { error: 'Chat library not available', log };
            }

            // Create a session via ConversationManager
            try {
                await ConversationManager.refresh();
                let sessions = ConversationManager.getSessions() || [];
                log.push('Existing sessions: ' + sessions.length);

                let sess = null;
                // Use existing session with messages if available
                for (let s of sessions) {
                    if (s.session && s.session.id && s.sessionType) {
                        let sq = am7view.viewQuery(am7model.newInstance(s.sessionType));
                        sq.field('id', s.session.id);
                        sq.cache(false);
                        sq.entity.request.push('messages');
                        let sqr = await page.search(sq);
                        if (sqr && sqr.results && sqr.results.length) {
                            let sr = sqr.results[0];
                            if (sr.messages && sr.messages.length > 0) {
                                let editIdx = sr.messages.findIndex(m => m.role !== 'system');
                                if (editIdx >= 0) {
                                    sess = s;
                                    log.push('Found existing session with ' + sr.messages.length + ' msgs');
                                    break;
                                }
                            }
                        }
                    }
                }

                // If no session with messages, create one and send a message via /chat/text
                if (!sess) {
                    log.push('Creating new session...');
                    sess = await ConversationManager.createSession('EditTest-' + Date.now());
                    if (!sess) {
                        return { error: 'Failed to create session', log };
                    }
                    log.push('Created session: ' + sess.objectId);

                    // Send a test message via buffered chat
                    log.push('Sending test message...');
                    try {
                        let chatBody = Object.assign({}, sess);
                        chatBody.message = 'Hello, this is a test message for edit verification.';
                        chatBody.uid = page.uid();
                        let resp = await fetch(applicationPath + '/rest/chat/text', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            credentials: 'include',
                            body: JSON.stringify(chatBody)
                        });
                        let respOk = resp.ok;
                        log.push('Chat response: ' + resp.status + ' ok=' + respOk);
                    } catch (e) {
                        log.push('Chat send error: ' + e.message);
                        return { error: 'Failed to send chat message: ' + e.message, log };
                    }
                }

                // Now fetch the session's messages
                if (!sess.session || !sess.session.id || !sess.sessionType) {
                    return { error: 'Session missing session ref or sessionType', log };
                }

                return {
                    sessionId: sess.session.id,
                    sessionType: sess.sessionType,
                    objectId: sess.objectId,
                    log
                };
            } catch (e) {
                return { error: 'Setup error: ' + e.message, log };
            }
        });

        console.log('Setup:');
        setup.log.forEach(l => console.log('  ' + l));

        if (setup.error) {
            console.log('SETUP ERROR: ' + setup.error);
            // This is a real failure — not a skip
            expect(setup.error).toBeNull();
            return;
        }

        // Step 2: Edit a message and verify persistence
        let result = await page.evaluate(async (info) => {
            let { am7client } = await import('/src/core/am7client.js');
            let { am7model } = await import('/src/core/model.js');
            let { am7view } = await import('/src/core/view.js');
            let { page } = await import('/src/core/pageClient.js');
            let log = [];
            let marker = ' [EDIT-' + Date.now() + ']';

            // Fetch session with messages
            let q = am7view.viewQuery(am7model.newInstance(info.sessionType));
            q.field('id', info.sessionId);
            q.cache(false);
            q.entity.request.push('messages');
            let qr = await page.search(q);
            if (!qr || !qr.results || !qr.results.length) {
                return { error: 'Session fetch failed', log };
            }
            let sess = qr.results[0];
            log.push('Messages: ' + (sess.messages ? sess.messages.length : 0));

            if (!sess.messages || !sess.messages.length) {
                return { error: 'No messages found', log };
            }

            // Find editable message
            let editIdx = sess.messages.findIndex(m => m.role !== 'system');
            if (editIdx < 0) {
                return { error: 'No non-system messages', log };
            }

            let original = sess.messages[editIdx].content;
            log.push('Editing idx ' + editIdx + ' role=' + sess.messages[editIdx].role);
            log.push('Original (first 60): ' + original.substring(0, 60));

            // Edit
            sess.messages[editIdx].content = original + marker;

            // Patch
            try {
                await page.patchObject(sess);
                log.push('Patch OK');
            } catch (e) {
                return { error: 'Patch failed: ' + e.message, log };
            }

            // Verify
            am7client.clearCache();
            let vq = am7view.viewQuery(am7model.newInstance(info.sessionType));
            vq.field('id', info.sessionId);
            vq.cache(false);
            vq.entity.request.push('messages');
            let vqr = await page.search(vq);
            if (!vqr || !vqr.results || !vqr.results.length) {
                return { error: 'Verify fetch failed', log };
            }
            let v = vqr.results[0];
            let vc = v.messages[editIdx].content;
            let persisted = vc.includes(marker);
            log.push('Verified includes marker: ' + persisted);

            // Restore
            if (persisted) {
                v.messages[editIdx].content = original;
                await page.patchObject(v);
                am7client.clearCache();
                log.push('Restored original');
            }

            return { persisted, log };
        }, setup);

        console.log('Result:');
        result.log.forEach(l => console.log('  ' + l));
        if (result.error) {
            console.log('ERROR: ' + result.error);
        }

        expect(result.persisted).toBe(true);
    });
});
