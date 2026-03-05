/**
 * objectViewRenderers.js — Custom format renderers for object views (ESM)
 * Port of Ux7 client/components/objectViewRenderers.js
 *
 * Registers custom renderers on am7view.customRenderers for:
 * portrait, image, video, audio, text, pdf, markdown, messageContent, memory
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';

function getClient() { return am7model._client; }
function getPage() { return am7model._page; }

function buildMediaPath(object) {
    let am7client = getClient();
    return am7client.mediaDataPath(object);
}

// ── Portrait ────────────────────────────────────────────────────────

function renderPortraitField(inst, fieldName, attrs) {
    let object = inst.entity;
    let maxMode = (attrs && attrs.maxMode !== undefined) ? attrs.maxMode : (inst.api.maxMode ? inst.api.maxMode() : false);
    let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);
    if (!active) return m("span", "");

    if (!object.profile || !object.profile.portrait || !object.profile.portrait.contentType) {
        return m("span", { class: "text-gray-400 text-sm" }, "No portrait available");
    }

    let pp = object.profile.portrait;
    let path = buildMediaPath(pp);
    let cls = maxMode
        ? "max-w-full max-h-[80vh] object-contain rounded shadow"
        : "max-w-xs max-h-64 object-contain rounded shadow";

    return m("img", { class: cls, src: path, alt: object.name || "Portrait" });
}

// ── Image ───────────────────────────────────────────────────────────

function renderImageField(inst, fieldName, attrs) {
    let object = inst.entity;
    let maxMode = (attrs && attrs.maxMode !== undefined) ? attrs.maxMode : (inst.api.maxMode ? inst.api.maxMode() : false);
    let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);
    if (!active) return m("span", "");

    let path = buildMediaPath(object);
    let cls = maxMode
        ? "max-w-full max-h-[80vh] object-contain rounded shadow"
        : "max-w-md max-h-80 object-contain rounded shadow";

    return m("img", { class: cls, src: path, alt: object.name || "Image" });
}

// ── Video ───────────────────────────────────────────────────────────

function renderVideoField(inst, fieldName, attrs) {
    let object = inst.entity;
    let maxMode = (attrs && attrs.maxMode !== undefined) ? attrs.maxMode : (inst.api.maxMode ? inst.api.maxMode() : false);
    let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);
    if (!active) return m("span", "");

    let path = buildMediaPath(object);
    let cls = maxMode ? "max-w-full max-h-[80vh]" : "max-w-md max-h-80";

    return m("video", { class: cls, preload: "auto", controls: true, autoplay: true }, [
        m("source", { src: path, type: object.contentType })
    ]);
}

// ── Audio ───────────────────────────────────────────────────────────

function renderAudioField(inst, fieldName, attrs) {
    let object = inst.entity;
    let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);
    if (!active) return m("span", "");

    let path = buildMediaPath(object);
    let mt = object.contentType;
    if (mt && mt.match(/mpeg3$/)) mt = "audio/mpeg";

    return m("audio", { class: "w-full", preload: "auto", controls: true, autoplay: true }, [
        m("source", { src: path, type: mt })
    ]);
}

// ── Text ────────────────────────────────────────────────────────────

function renderTextField(inst, fieldName, attrs) {
    let object = inst.entity;
    let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);
    if (!active) return m("span", "");

    let page = getPage();
    let am7client = getClient();
    let ctx = page.context();
    let objectId = object.objectId;

    if (!ctx.contextObjects[objectId]) {
        let model = inst.model.name;
        let q = am7view.viewQuery(am7model.newInstance(model));
        q.field("objectId", objectId);
        page.search(q).then(function(qr) {
            ctx.contextObjects[objectId] = (qr && qr.results.length ? qr.results[0] : null);
            ctx.tags[objectId] = [];
            m.redraw();
        });
        return m("div", { class: "p-4 text-sm text-gray-400" }, "Loading...");
    }

    let dat = ctx.contextObjects[objectId];
    let content = "";
    try {
        content = atob(dat.dataBytesStore || "");
    } catch (e) {
        content = dat.dataBytesStore || "";
    }

    return m("div", { class: "p-4" },
        m("pre", { class: "text-sm text-gray-700 dark:text-gray-300 whitespace-pre-wrap font-mono max-h-[70vh] overflow-y-auto" }, content)
    );
}

// ── PDF ─────────────────────────────────────────────────────────────

function renderPDFField(inst, fieldName, attrs) {
    let object = inst.entity;
    let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);
    if (!active) return m("span", "");

    let page = getPage();
    let ctx = page.context();
    let objectId = object.objectId;

    if (!ctx.contextObjects[objectId]) {
        let model = inst.model.name;
        let q = am7view.viewQuery(am7model.newInstance(model));
        q.field("objectId", objectId);
        page.search(q).then(function(qr) {
            ctx.contextObjects[objectId] = (qr && qr.results.length ? qr.results[0] : null);
            ctx.tags[objectId] = [];
            if (ctx.contextObjects[objectId] && page.components.pdf) {
                page.components.pdf.viewer(am7model.prepareInstance(ctx.contextObjects[objectId]));
            }
            m.redraw();
        });
        return m("div", { class: "p-4 text-sm text-gray-400" }, "Loading PDF...");
    }

    if (page.components.pdf) {
        let viewer = page.components.pdf.viewer(objectId);
        if (viewer) return m("div", { class: "p-2" }, viewer.container());
    }

    return m("div", { class: "p-4 text-sm text-gray-400" }, "PDF viewer not available");
}

// ── Markdown/Note ───────────────────────────────────────────────────

function renderMarkdownField(inst, fieldName, attrs) {
    let value = inst.api[fieldName] ? inst.api[fieldName]() : "";
    if (!value || !value.trim()) return m("span", "");

    let html = "";
    try {
        // Try to use markdownConverter if available
        if (typeof window !== "undefined" && window.markdownConverter) {
            html = window.markdownConverter.toHtml(value);
        } else {
            // marked should be available via markdownConverter module
            let { marked } = require('marked');
            html = marked.parse(value);
        }
    } catch (e) {
        html = "<pre>" + value + "</pre>";
    }

    return m("div", { class: "p-4 prose dark:prose-invert max-w-none" }, m.trust(html));
}

// ── Message Content ─────────────────────────────────────────────────

function renderMessageContentField(inst, fieldName, attrs) {
    let object = inst.entity;
    let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);
    if (!active) return m("span", "");

    let page = getPage();

    // Auto-acknowledge message
    if (object.spoolStatus === 'SPOOLED' || object.spoolStatus === 'TRANSMITTED') {
        object.spoolStatus = "ACKNOWLEGED_RECEIPT";
        page.patchObject(object);
    }

    let content = "";
    try {
        content = atob(object.data || "");
    } catch (e) {
        content = object.data || "";
    }

    return m("div", { class: "p-4" },
        m("div", { class: "text-sm text-gray-700 dark:text-gray-300" }, content)
    );
}

// ── Memory ──────────────────────────────────────────────────────────

function renderMemoryField(inst, fieldName, attrs) {
    let object = inst.entity;
    let objectId = object.objectId;
    let page = getPage();
    let ctx = page.context();

    if (!ctx.contextObjects[objectId]) {
        let q = am7view.viewQuery(am7model.newInstance("tool.memory"));
        q.field("objectId", objectId);
        page.search(q).then(function(qr) {
            ctx.contextObjects[objectId] = (qr && qr.results.length ? qr.results[0] : null);
            m.redraw();
        });
        return m("div", { class: "p-4 text-sm text-gray-400" }, "Loading memory...");
    }

    let dat = ctx.contextObjects[objectId] || object;
    let memType = dat.memoryType || "NOTE";
    let importance = dat.importance != null ? dat.importance : 5;
    let summary = dat.summary || "";
    let content = dat.content || "";
    let person1Name = (dat.person1 && dat.person1.name) ? dat.person1.name : "";
    let person2Name = (dat.person2 && dat.person2.name) ? dat.person2.name : "";

    let typeColors = {
        "RELATIONSHIP": "bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-200",
        "FACT": "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200",
        "DECISION": "bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200",
        "DISCOVERY": "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200",
        "EMOTION": "bg-rose-100 text-rose-800 dark:bg-rose-900 dark:text-rose-200",
        "NOTE": "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200"
    };
    let badgeClass = typeColors[memType] || typeColors["NOTE"];

    let header = [];
    header.push(m("span", { class: "inline-block px-2 py-0.5 rounded text-xs font-medium " + badgeClass }, memType));
    header.push(m("span", { class: "text-xs text-gray-500 dark:text-gray-400 ml-2" }, "Importance: " + importance + "/10"));
    if (person1Name || person2Name) {
        let people = [person1Name, person2Name].filter(function(n) { return n; }).join(" & ");
        header.push(m("span", { class: "text-xs text-gray-500 dark:text-gray-400 ml-2" }, people));
    }

    let sections = [];
    sections.push(m("div", { class: "flex items-center gap-1 mb-2 flex-wrap" }, header));
    if (summary) {
        sections.push(m("div", { class: "text-sm font-medium text-gray-700 dark:text-gray-200 mb-2" }, summary));
    }
    if (content) {
        sections.push(m("div", {
            class: "text-sm text-gray-600 dark:text-gray-300 whitespace-pre-wrap max-h-96 overflow-y-auto",
            style: "line-height: 1.5;"
        }, content));
    }

    return m("div", { class: "p-4" }, sections);
}

// ── Register All Renderers ──────────────────────────────────────────

function registerRenderers() {
    if (!am7view.customRenderers) {
        am7view.customRenderers = {};
    }

    am7view.customRenderers.portrait = renderPortraitField;
    am7view.customRenderers.image = renderImageField;
    am7view.customRenderers.video = renderVideoField;
    am7view.customRenderers.audio = renderAudioField;
    am7view.customRenderers.text = renderTextField;
    am7view.customRenderers.pdf = renderPDFField;
    am7view.customRenderers.markdown = renderMarkdownField;
    am7view.customRenderers.messageContent = renderMessageContentField;
    am7view.customRenderers.memory = renderMemoryField;
}

// Auto-register on import
registerRenderers();

// ── Export ───────────────────────────────────────────────────────────

const objectViewRenderers = {
    portrait: renderPortraitField,
    image: renderImageField,
    video: renderVideoField,
    audio: renderAudioField,
    text: renderTextField,
    pdf: renderPDFField,
    markdown: renderMarkdownField,
    messageContent: renderMessageContentField,
    memory: renderMemoryField,
    buildMediaPath,
    registerRenderers
};

export { objectViewRenderers };
export default objectViewRenderers;
