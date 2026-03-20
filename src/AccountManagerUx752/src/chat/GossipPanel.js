/**
 * GossipPanel — Memory gossip flyout for chat (ESM)
 * Loads gossip memories for the current character context.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { applicationPath } from '../core/config.js';

let _visible = false;
let _results = [];
let _loading = false;
let _query = "";
let _onInject = null;

async function loadGossip(personId, opts) {
    if (!personId) return;
    _loading = true;
    m.redraw();

    try {
        let body = {
            personId: personId,
            query: opts.query || "*",
            limit: opts.limit || 10,
            threshold: opts.threshold || 0.5
        };
        if (opts.excludePairPersonId) body.excludePairPersonId = opts.excludePairPersonId;

        let result = await m.request({
            method: 'POST',
            url: applicationPath + "/rest/memory/gossip",
            withCredentials: true,
            body: body
        });

        _results = Array.isArray(result) ? result : (result && result.results ? result.results : []);
    } catch (e) {
        console.warn("[GossipPanel] loadGossip failed:", e);
        _results = [];
    }

    _loading = false;
    m.redraw();
}

const GossipPanel = {
    show: function(personId, opts, onInject) {
        _visible = true;
        _onInject = onInject || null;
        _query = (opts && opts.query) || "";
        loadGossip(personId, opts || {});
    },

    hide: function() {
        _visible = false;
        _results = [];
        _query = "";
        _onInject = null;
        m.redraw();
    },

    toggle: function(personId, opts, onInject) {
        if (_visible) GossipPanel.hide();
        else GossipPanel.show(personId, opts, onInject);
    },

    isVisible: function() { return _visible; },

    PanelView: {
        view: function() {
            if (!_visible) return null;

            return m("div", { class: "absolute right-0 top-full mt-1 w-80 max-h-96 overflow-y-auto bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-700 rounded-lg shadow-xl z-40" }, [
                // Header
                m("div", { class: "flex items-center justify-between px-3 py-2 border-b border-gray-200 dark:border-gray-700" }, [
                    m("span", { class: "text-sm font-semibold text-gray-700 dark:text-gray-200" }, "Memory Gossip"),
                    m("button", {
                        class: "text-gray-400 hover:text-gray-600",
                        onclick: function() { GossipPanel.hide(); }
                    }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "close"))
                ]),
                // Results
                _loading
                    ? m("div", { class: "flex items-center justify-center py-6" }, [
                        m("span", { class: "material-symbols-outlined animate-spin text-blue-500", style: "font-size:20px" }, "progress_activity"),
                        m("span", { class: "ml-2 text-gray-500 text-xs" }, "Loading...")
                    ])
                    : _results.length === 0
                        ? m("div", { class: "text-center py-6 text-gray-400 text-xs" }, "No gossip found")
                        : _results.map(function(item, idx) {
                            return m("button", {
                                key: idx,
                                class: "w-full text-left px-3 py-2 hover:bg-blue-50 dark:hover:bg-blue-900/20 border-b border-gray-100 dark:border-gray-800 text-xs",
                                onclick: function() {
                                    if (_onInject && item.content) {
                                        _onInject(item.content);
                                    }
                                }
                            }, [
                                m("div", { class: "text-gray-800 dark:text-gray-200 line-clamp-2" }, item.content || item.text || "(empty)"),
                                item.score ? m("div", { class: "text-gray-400 mt-0.5" }, "Score: " + (item.score * 100).toFixed(0) + "%") : null
                            ]);
                        })
            ]);
        }
    }
};

export { GossipPanel };
export default GossipPanel;
