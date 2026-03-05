/**
 * AnalysisManager — In-page analysis session coordinator (ESM port)
 * Replaces cross-window chatInto() with in-page analysis that integrates
 * with ConversationManager, ContextPanel, and LLMConnector.
 *
 * Pattern: startAnalysis() stores pending context and navigates to /chat.
 * The chat view calls executePending() on init to create the server session.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';
import { LLMConnector } from './LLMConnector.js';
import { ConversationManager } from './ConversationManager.js';
import { ContextPanel } from './ContextPanel.js';

let pendingAnalysis = null;

/**
 * Resolve analysis chatConfig and promptConfig.
 * Uses purpose-based filtering with fallback chain:
 * 1. Configs with purpose === "analysis"
 * 2. Library "contentAnalysis" config
 * 3. Configs with name starting with "Analyze" or "Object"
 * 4. First available config
 */
async function resolveAnalysisConfigs() {
    let chatConfigs = ConversationManager.getChatConfigs() || [];
    let promptConfigs = ConversationManager.getPromptConfigs() || [];

    if (!chatConfigs.length) {
        let q = am7view.viewQuery(am7model.newInstance("olio.llm.chatConfig"));
        let qr = await page.search(q);
        if (qr && qr.results) chatConfigs = qr.results;
    }
    if (!promptConfigs.length) {
        let q = am7view.viewQuery(am7model.newInstance("olio.llm.promptConfig"));
        let qr = await page.search(q);
        if (qr && qr.results) promptConfigs = qr.results;
    }

    let cc = chatConfigs.filter(c => c.purpose === "analysis");
    if (!cc.length) {
        let libCfg = await LLMConnector.resolveConfig("contentAnalysis");
        if (libCfg) cc = [libCfg];
    }
    if (!cc.length) cc = chatConfigs.filter(c => c.name && c.name.match(/^(Analyze|Object|contentAnalysis)/i));
    if (!cc.length) cc = chatConfigs;

    let pt = await LLMConnector.resolveTemplate("summary");

    let pc = null;
    if (!pt) {
        let pcList = promptConfigs.filter(c => c.purpose === "analysis");
        if (!pcList.length) pcList = promptConfigs.filter(c => c.name && c.name.match(/^(Analyze|Object|contentAnalysis)/i));
        if (pcList.length) pc = pcList[0];
    }

    return {
        chatConfig: cc.length ? cc[0] : null,
        promptTemplate: pt,
        promptConfig: pc
    };
}

/**
 * Vectorize a session and return the vectorized record for working set.
 */
async function vectorizeSession(session) {
    if (!session || !session[am7model.jsonModelKey]) return null;
    let schema = session[am7model.jsonModelKey];
    let sq = am7view.viewQuery(schema);
    sq.entity.request = ["id", "objectId", "groupId", "groupPath", "organizationId", "organizationPath"];
    sq.field("id", session.id);
    let sess = await page.search(sq);
    if (sess && sess.results && sess.results.length) {
        let rec = sess.results[0];
        let x = await m.request({
            method: 'GET',
            url: am7client.base() + "/vector/vectorize/" + schema + "/" + rec.objectId + "/WORD/500",
            withCredentials: true
        });
        if (x) return rec;
        page.toast("error", "Session vectorization failed");
    }
    return null;
}

/**
 * Gather character tags for the working set.
 */
async function gatherCharacterTags(charNames) {
    if (!charNames || !charNames.length) return [];
    let grp = await page.findObject("auth.group", "data", "~/Tags");
    if (!grp) return [];
    let q = am7view.viewQuery(am7model.newInstance("data.tag"));
    q.field("groupId", grp.id);
    let q2 = q.field(null, null);
    q2.comparator = "group_or";
    q2.fields = charNames.map(a => ({ name: "name", comparator: "equals", value: a }));
    let qr = await page.search(q);
    if (qr && qr.results) {
        am7model.updateListModel(qr.results);
        return qr.results;
    }
    return [];
}

const AnalysisManager = {

    /**
     * Start an in-page analysis session for a reference object.
     * Stores analysis context in pendingAnalysis and navigates to /chat.
     */
    startAnalysis: async function(ref, sourceInst, sourceCCfg) {
        let configs = await resolveAnalysisConfigs();
        if (!configs.chatConfig) {
            page.toast("error", "No analysis chatConfig found. Create a chatConfig with purpose 'analysis' or name starting with 'contentAnalysis'.");
            return;
        }

        let cname = "Analyze " +
            (ref && ref[am7model.jsonModelKey] ? ref[am7model.jsonModelKey].toUpperCase() + " " : "") +
            (ref && ref.name ? ref.name : "Object") +
            " " + Date.now();

        let charNames = [];
        if (sourceInst && Array.isArray(sourceCCfg) && sourceCCfg.length) {
            let ccRef = sourceInst.api && sourceInst.api.chatConfig ? sourceInst.api.chatConfig() : null;
            let ccId = ccRef ? ccRef.objectId : null;
            let aC = ccId ? sourceCCfg.filter(c => c.objectId === ccId) : [];
            if (aC.length && aC[0].userCharacter && aC[0].systemCharacter) {
                charNames.push(aC[0].userCharacter.name);
                charNames.push(aC[0].systemCharacter.name);
            }
        } else if (ref && (ref[am7model.jsonModelKey] === "olio.charPerson" || ref[am7model.jsonModelKey] === "identity.person")) {
            charNames.push(ref.name);
        }

        pendingAnalysis = {
            ref: ref,
            configs: configs,
            sessionName: cname,
            charNames: charNames,
            sourceInst: sourceInst,
            sourceCCfg: sourceCCfg
        };

        if (m.route.get() !== "/chat") {
            m.route.set("/chat");
        }

        m.redraw();
    },

    /**
     * Execute the pending analysis — creates server session, populates
     * working set, and wires up ConversationManager.
     */
    executePending: async function() {
        if (!pendingAnalysis) return null;
        let pa = pendingAnalysis;
        pendingAnalysis = null;

        let wset = [];

        try {
            if (pa.sourceInst && pa.sourceInst.api && pa.sourceInst.api.session && pa.sourceInst.api.session() != null) {
                let vecRec = await vectorizeSession(pa.sourceInst.api.session());
                if (vecRec) wset.push(vecRec);
            }
        } catch (e) {
            console.warn("[AnalysisManager] vectorization failed, continuing without:", e);
        }

        if (pa.charNames && pa.charNames.length) {
            try {
                let tags = await gatherCharacterTags(pa.charNames);
                wset.push(...tags);
            } catch (e) {
                console.warn("[AnalysisManager] tag gathering failed, continuing without:", e);
            }
        }

        if (pa.ref) wset.push(pa.ref);

        let chatReq = {
            schema: "olio.llm.chatRequest",
            name: pa.sessionName,
            chatConfig: { objectId: pa.configs.chatConfig.objectId },
            uid: page.uid()
        };
        if (pa.configs.promptTemplate) {
            chatReq.promptTemplate = { objectId: pa.configs.promptTemplate.objectId };
        } else if (pa.configs.promptConfig) {
            chatReq.promptConfig = { objectId: pa.configs.promptConfig.objectId };
        }

        let obj;
        try {
            obj = await m.request({
                method: 'POST',
                url: applicationPath + "/rest/chat/new",
                withCredentials: true,
                body: chatReq
            });
        } catch (e) {
            console.error("[AnalysisManager] POST /rest/chat/new failed:", e);
            page.toast("error", "Failed to create analysis session");
            return null;
        }

        if (!obj) {
            page.toast("error", "Failed to create analysis session");
            return null;
        }

        ContextPanel.load(obj.objectId);
        await new Promise(r => setTimeout(r, 100));

        for (let i = 0; i < wset.length; i++) {
            let item = wset[i];
            let schema = item[am7model.jsonModelKey] || item.schema || "";
            let oid = item.objectId;
            if (!schema || !oid) continue;
            if (schema === "data.tag") {
                await ContextPanel.attach("tag", oid);
            } else {
                await ContextPanel.attach("context", oid, schema);
            }
        }

        if (pa.ref && pa.ref.objectId) {
            let refSchema = pa.ref[am7model.jsonModelKey] || pa.ref.schema || "";
            let alreadyInSet = wset.some(w => w.objectId === pa.ref.objectId);
            if (!alreadyInSet && refSchema) {
                await ContextPanel.attach("context", pa.ref.objectId, refSchema);
            }
        }

        await ConversationManager.refresh();
        ConversationManager.selectSession(obj);

        m.redraw();
        return obj;
    },

    getActiveAnalysis: function() {
        return pendingAnalysis;
    },

    clearAnalysis: function() {
        pendingAnalysis = null;
    }
};

export { AnalysisManager };
export default AnalysisManager;
