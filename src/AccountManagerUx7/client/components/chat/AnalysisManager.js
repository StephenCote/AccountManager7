/**
 * AnalysisManager — In-page analysis session coordinator
 * Phase 13a: Replaces the cross-window chatInto() pattern with in-page analysis
 * that integrates with ConversationManager, ContextPanel, and LLMConnector.
 *
 * Resolves: OI-56, OI-57, OI-58
 *
 * Exposes: window.AnalysisManager
 */
(function() {
    "use strict";

    let pendingAnalysis = null;

    /**
     * Resolve analysis chatConfig and promptConfig.
     * Uses purpose-based filtering with fallback chain:
     * 1. Configs with purpose === "analysis"
     * 2. Configs with name starting with "Analyze" or "Object"
     * 3. First available config
     */
    async function resolveAnalysisConfigs() {
        let chatConfigs = [];
        let promptConfigs = [];

        if (window.ConversationManager) {
            chatConfigs = ConversationManager.getChatConfigs() || [];
            promptConfigs = ConversationManager.getPromptConfigs() || [];
        }

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

        // Purpose-based filtering with fallback
        let cc = chatConfigs.filter(function(c) { return c.purpose === "analysis"; });
        if (!cc.length) cc = chatConfigs.filter(function(c) { return c.name && c.name.match(/^(Analyze|Object)/i); });
        if (!cc.length) cc = chatConfigs;

        let pc = promptConfigs.filter(function(c) { return c.purpose === "analysis"; });
        if (!pc.length) pc = promptConfigs.filter(function(c) { return c.name && c.name.match(/^(Analyze|Object)/i); });
        if (!pc.length) pc = promptConfigs;

        return {
            chatConfig: cc.length ? cc[0] : null,
            promptConfig: pc.length ? pc[0] : null
        };
    }

    /**
     * Vectorize a session and return the vectorized record for workingSet.
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
        q2.fields = charNames.map(function(a) {
            return { name: "name", comparator: "equals", value: a };
        });
        let qr = await page.search(q);
        if (qr && qr.results) {
            am7model.updateListModel(qr.results);
            return qr.results;
        }
        return [];
    }

    let AnalysisManager = {

        /**
         * Start an in-page analysis session for a reference object.
         * Replaces dialog.chatInto() — no window.open, no remoteEntity.
         *
         * @param {Object} ref - the reference object to analyze
         * @param {Object} [sourceInst] - optional source chat instance (for session vectorization)
         * @param {Object} [sourceCCfg] - optional source chat config list
         */
        startAnalysis: async function(ref, sourceInst, sourceCCfg) {
            let configs = await resolveAnalysisConfigs();
            if (!configs.chatConfig || !configs.promptConfig) {
                page.toast("error", "No analysis config found. Create a chatConfig/promptConfig with name starting with 'Object' or 'Analyze'.");
                return;
            }

            // Build analysis session name
            let cname = "Analyze " +
                (ref && ref[am7model.jsonModelKey] ? ref[am7model.jsonModelKey].toUpperCase() + " " : "") +
                (ref && ref.name ? ref.name : "Object");

            // Build working set
            let wset = [];

            // Gather character tags from source config
            let charNames = [];
            if (sourceInst && sourceCCfg) {
                let aC = sourceCCfg.filter(function(c) {
                    return sourceInst.api.chatConfig && c.name === sourceInst.api.chatConfig().name;
                });
                if (aC.length && aC[0].userCharacter && aC[0].systemCharacter) {
                    charNames.push(aC[0].userCharacter.name);
                    charNames.push(aC[0].systemCharacter.name);
                }
            } else if (ref && (ref[am7model.jsonModelKey] === "olio.charPerson" || ref[am7model.jsonModelKey] === "identity.person")) {
                charNames.push(ref.name);
            }

            // Vectorize source session if present
            if (sourceInst && sourceInst.api.session && sourceInst.api.session() != null) {
                let vecRec = await vectorizeSession(sourceInst.api.session());
                if (vecRec) wset.push(vecRec);
            }

            // Gather character tags
            if (charNames.length) {
                let tags = await gatherCharacterTags(charNames);
                wset.push(...tags);
            }

            // Add the reference object itself
            if (ref) wset.push(ref);

            // Create server-side chat request via POST /rest/chat/new
            let chatReq = {
                schema: "olio.llm.chatRequest",
                name: cname,
                chatConfig: { objectId: configs.chatConfig.objectId },
                promptConfig: { objectId: configs.promptConfig.objectId },
                uid: page.uid()
            };

            let obj = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/chat/new",
                withCredentials: true,
                body: chatReq
            });

            if (!obj) {
                page.toast("error", "Failed to create analysis session");
                return;
            }

            // Attach reference to context panel
            if (window.ContextPanel && ref && ref.objectId) {
                let schema = ref[am7model.jsonModelKey] || ref.schema || "";
                ContextPanel.attach("context", ref.objectId, schema);
            }

            // Populate working set
            if (page.components.dnd && page.components.dnd.workingSet) {
                page.components.dnd.workingSet.push(...wset);
            }
            if (wset.length && page.components.topMenu && typeof page.components.topMenu.activeShuffle === "function") {
                page.components.topMenu.activeShuffle(wset[0]);
            }

            // Refresh conversation manager to show new session
            if (window.ConversationManager) {
                await ConversationManager.refresh();
                ConversationManager.selectSession(obj);
            }

            // Navigate to chat if not already there
            if (m.route.get() !== "/chat") {
                m.route.set("/chat");
            }

            m.redraw();
        },

        /**
         * Returns the pending analysis context or null.
         */
        getActiveAnalysis: function() {
            return pendingAnalysis;
        },

        /**
         * Clear any pending analysis state.
         */
        clearAnalysis: function() {
            pendingAnalysis = null;
        }
    };

    // ── Export ───────────────────────────────────────────────────────────

    window.AnalysisManager = AnalysisManager;

    console.log("[AnalysisManager] loaded");
}());
