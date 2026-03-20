/**
 * Chat utility — backward-compatible API for chat operations (ESM port).
 * Wraps LLMConnector for convenience.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { applicationPath } from '../core/config.js';
import { LLMConnector } from './LLMConnector.js';

async function getChatRequest(requestName, chatCfg, promptCfg) {
    let grp = await page.findObject("auth.group", "data", "~/ChatRequests");
    let q = am7view.viewQuery(am7model.newInstance("olio.llm.chatRequest"));
    q.field("groupId", grp.id);
    q.field("name", requestName);
    q.cache(false);
    q.entity.request.push("session", "sessionType", "chatConfig", "promptConfig", "objectId");
    let req;
    let qr = await page.search(q);
    if (!qr || !qr.results || qr.results.length === 0) {
        let chatReq = {
            schema: "olio.llm.chatRequest",
            name: requestName,
            chatConfig: { objectId: chatCfg.objectId },
            promptConfig: { objectId: promptCfg.objectId },
            uid: page.uid()
        };
        req = await m.request({
            method: 'POST',
            url: applicationPath + "/rest/chat/new",
            withCredentials: true,
            body: chatReq
        });
    } else {
        req = qr.results[0];
    }
    return req;
}

async function makeChat(sName, sModel, sServerUrl, sServiceType) {
    let grp = await page.findObject("auth.group", "data", "~/Chat");
    let q = am7view.viewQuery(am7model.newInstance("olio.llm.chatConfig"));
    q.field("groupId", grp.id);
    q.field("name", sName);
    q.cache(false);
    let cfg;
    let qr = await page.search(q);
    if (!qr || !qr.results || qr.results.length === 0) {
        let icfg = am7model.newInstance("olio.llm.chatConfig");
        icfg.api.groupId(grp.id);
        icfg.api.groupPath(grp.path);
        icfg.api.name(sName);
        icfg.api.model(sModel);
        icfg.api.messageTrim(6);
        icfg.api.serverUrl(sServerUrl);
        icfg.api.serviceType(sServiceType);
        await page.createObject(icfg.entity);
        qr = await page.search(q);
    }
    if (qr?.results.length > 0) {
        cfg = qr.results[0];
    }
    if (!cfg) {
        page.toast("error", "Could not create chat config");
    }
    return cfg;
}

async function makePrompt(sName, aSystem) {
    let grp = await page.findObject("auth.group", "data", "~/Chat");
    let q = am7view.viewQuery(am7model.newInstance("olio.llm.promptConfig"));
    q.field("groupId", grp.id);
    q.field("name", sName);
    q.cache(false);
    let cfg;
    let qr = await page.search(q);
    if (!qr || !qr.results || qr.results.length === 0) {
        let icfg = am7model.newInstance("olio.llm.promptConfig");
        icfg.api.groupId(grp.id);
        icfg.api.groupPath(grp.path);
        icfg.api.name(sName);
        icfg.entity.system = aSystem;
        await page.createObject(icfg.entity);
        qr = await page.search(q);
    }
    if (qr?.results.length > 0) {
        cfg = qr.results[0];
    }
    if (!cfg) {
        page.toast("error", "Could not create prompt config");
    }
    return cfg;
}

async function chat(session, msg) {
    return LLMConnector.chat(session, msg);
}

async function deleteChat(session, force, fHandler) {
    return LLMConnector.deleteSession(session, force, fHandler);
}

const am7chat = {
    makePrompt,
    makeChat,
    getChatRequest,
    deleteChat,
    chat,
    getHistory: (session) => LLMConnector.getHistory(session),
    extractContent: (response) => LLMConnector.extractContent(response),
    streamChat: (session, message, callbacks) => LLMConnector.streamChat(session, message, callbacks),
    cancelStream: (session) => LLMConnector.cancelStream(session),
    parseDirective: (content, options) => LLMConnector.parseDirective(content, options),
    cloneConfig: (template, overrides) => LLMConnector.cloneConfig(template, overrides)
};

export { am7chat };
export default am7chat;
