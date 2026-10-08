/**
 * core/pageClient.js wssSend — string recipient lookup (was a "not yet implemented in Ux75" warn stub
 * until 2026-10-07; now the Ux7 pageClient.js:692 port: resolve the user name via
 * am7client.getByName('system.user', null, name) and refuse to send when it does not resolve).
 *
 * The socket is a fake (records what is sent) and getByName is spied; the REST contract behind it
 * (GET /rest/model/system.user/null/{name}: 200 for a name the caller may read, 404 otherwise) was
 * checked live against the Docker stack as the shared user on 2026-10-07.
 */
import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest';

let sent = [];
class FakeWebSocket {
    static OPEN = 1;
    static CLOSED = 3;
    constructor(url) {
        this.url = url;
        this.readyState = FakeWebSocket.OPEN;
        setTimeout(() => { if (this.onopen) this.onopen({}); }, 0);
    }
    send(d) { sent.push(JSON.parse(d)); }
    close() { this.readyState = FakeWebSocket.CLOSED; if (this.onclose) this.onclose({ code: 1000 }); }
}

let page, am7client, uwm;

beforeAll(async () => {
    globalThis.WebSocket = FakeWebSocket;
    globalThis.window = globalThis.window || { location: { protocol: 'https:', host: 'localhost:8899', port: '8899' } };
    ({ page } = await import('../core/pageClient.js'));
    ({ am7client, uwm } = await import('../core/am7client.js'));
    page.token = 'tok-123';
    await page.wss.connect();
});

afterAll(() => {
    // Detach the reconnect path before closing so the fake's onclose does not schedule a reconnect.
    page.wss.close();
});

describe('page.wss.send recipient resolution', () => {
    it('a string recipient is resolved to the user id through am7client.getByName(system.user, null, name)', async () => {
        sent = [];
        let spy = vi.spyOn(am7client, 'getByName').mockImplementation((type, id, name, fh) => {
            let o = (name === 'alice') ? { id: 77, objectId: 'A-1', name: 'alice' } : undefined;
            if (fh) fh(o);
            return Promise.resolve(o);
        });
        let ok = await page.wss.send('chat', '{"hello":1}', 'alice', 'olio.llm.chatRequest');
        expect(ok).not.toBe(false);
        expect(spy).toHaveBeenCalledWith('system.user', null, 'alice', expect.any(Function));
        expect(sent.length).toBe(1);
        let env = sent[0];
        expect(env.schema).toBe('message.socketMessage');
        expect(env.token).toBe('tok-123');
        expect(env.message.name).toBe('chat');
        expect(env.message.modelType).toBe('olio.llm.chatRequest');
        expect(env.message.recipientId).toBe(77);
        expect(env.message.recipientType).toBe('USER');
        expect(env.message.data).toBe(uwm.base64Encode('{"hello":1}'));
        spy.mockRestore();
    });

    it('a string recipient that does not resolve is refused — nothing is sent (no silent broadcast)', async () => {
        sent = [];
        let warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
        let spy = vi.spyOn(am7client, 'getByName').mockImplementation((type, id, name, fh) => {
            if (fh) fh(undefined);
            return Promise.resolve(undefined);
        });
        let ok = await page.wss.send('chat', '{}', 'nobody', 'olio.llm.chatRequest');
        expect(ok).toBe(false);
        expect(sent.length).toBe(0);
        expect(warn).toHaveBeenCalledWith(expect.stringContaining('nobody'));
        spy.mockRestore();
        warn.mockRestore();
    });

    it('a numeric recipient is used as-is', async () => {
        sent = [];
        let spy = vi.spyOn(am7client, 'getByName');
        await page.wss.send('game', '{}', 42, 'game.action.x');
        expect(spy).not.toHaveBeenCalled();
        expect(sent.length).toBe(1);
        expect(sent[0].message.recipientId).toBe(42);
        expect(sent[0].message.recipientType).toBe('USER');
        spy.mockRestore();
    });

    it('no recipient (how LLMConnector / audio / gameStream call it) still sends with recipientType UNKNOWN', async () => {
        sent = [];
        let spy = vi.spyOn(am7client, 'getByName');
        await page.wss.send('chat', '{}', undefined, 'olio.llm.chatRequest');
        expect(spy).not.toHaveBeenCalled();
        expect(sent.length).toBe(1);
        expect(sent[0].message.recipientId).toBeNull();
        expect(sent[0].message.recipientType).toBe('UNKNOWN');
        spy.mockRestore();
    });
});
