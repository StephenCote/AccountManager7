/**
 * designer.js — Rich text / code editor component (ESM)
 * Port of Ux7 client/components/designer.js
 *
 * Iframe-based WYSIWYG editing, source mode, CodeMirror code editing.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';

function getClient() { return am7model._client; }
function getPage() { return am7model._page; }

let designStyle = 'edit';
let designHtml = "";
let caller;
let init = false;
let toggled = false;
let pickData = false;
let pickDataObject = null;
let codeMirrorInst;

const formatOptions = ['BLOCK', 'P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6'];

const designDocumentHeader = `<html lang="en"><head><meta charset="utf-8"><title>editor</title>
<style>body{font-family:sans-serif;padding:1rem}pre,code{background:#f4f4f4;padding:2px 4px;border-radius:3px;font-family:monospace}
pre{padding:.5rem;overflow-x:auto}blockquote{border-left:3px solid #ccc;margin-left:0;padding-left:1rem;color:#555}
img{max-width:100%;height:auto}hr{border:none;border-top:1px solid #ccc;margin:1rem 0}</style></head><body>`;
const designDocumentFooter = `</body></html>`;

function handlePickData(dat) {
    if (dat && dat.length) {
        pickDataObject = dat[0];
        pickData = true;
    }
    caller.endPicker();
}

function insertObjectAtCaret(obj) {
    let am7client = getClient();
    let frame = document.querySelector("[rid=designFrame]");
    let doc = (frame.contentWindow || frame.contentDocument);
    doc.document.body.focus();

    let sOrg = am7client.dotPath(am7client.currentOrganization);
    let sUrl = am7client.base().replace("/rest", "") + "/media/" + sOrg + "/data.data" + obj.groupPath + "/" + obj.name;

    if (obj.contentType.match(/^image/)) {
        let sThumbUrl = am7client.base().replace("/rest", "") + "/thumbnail/" + sOrg + "/data.data" + obj.groupPath + "/" + obj.name + "/250x250";
        let oI = doc.document.createElement("img");
        oI.setAttribute("src", sThumbUrl);
        oI.setAttribute("alt", obj.name);
        insertNodeAtCaret(oI);
    } else {
        let oA = doc.document.createElement("a");
        oA.setAttribute("href", sUrl);
        oA.setAttribute("target", "_blank");
        oA.textContent = obj.name;
        insertNodeAtCaret(oA);
    }
}

function insertNodeAtCaret(oNode) {
    let frame = document.querySelector("[rid=designFrame]");
    let doc = (frame.contentWindow || frame.contentDocument);
    if (doc.getSelection) {
        let sel = doc.getSelection();
        if (sel.getRangeAt && sel.rangeCount) {
            let range = sel.getRangeAt(0);
            range.deleteContents();
            range.insertNode(oNode);
        }
    }
}

function pickImage() {
    if (pickData) {
        pickData = false;
        insertObjectAtCaret(pickDataObject);
        pickDataObject = null;
    } else {
        caller.preparePicker('data.data', handlePickData, "~/Gallery");
    }
}

function defAct(e, action) {
    let frame = document.querySelector("[rid=designFrame]");
    let doc = (frame.contentWindow || frame.contentDocument);
    doc.document.body.focus();
    doc.document.execCommand(action, false, null);
    doc.focus();
}

function formatBlock(tag) {
    let frame = document.querySelector("[rid=designFrame]");
    let doc = (frame.contentWindow || frame.contentDocument);
    doc.document.body.focus();
    doc.document.execCommand('formatBlock', false, '<' + tag + '>');
    doc.focus();
}

function insertLink() {
    let frame = document.querySelector("[rid=designFrame]");
    let doc = (frame.contentWindow || frame.contentDocument);
    doc.document.body.focus();
    let url = prompt("Enter URL:");
    if (url) doc.document.execCommand('createLink', false, url);
    doc.focus();
}

function transitionHandler() {
    init = false;
    let entity = caller.getEntity();
    if (!entity || entity[am7model.jsonModelKey] != 'data.data') return;

    let source;
    if (designStyle == 'code' && codeMirrorInst) {
        source = codeMirrorInst.getValue();
    } else {
        source = refreshDesignSources();
    }

    caller.getInstance().api.dataBytesStore(source);
    if (caller.getInstance().api.compressionType) caller.getInstance().api.compressionType("none");
    designHtml = "";
}

function writeFrame(frame, content) {
    let doc = (frame.contentWindow || frame.contentDocument);
    try {
        if (!doc || !doc.document) return;
        let d = doc.document;
        d.open();
        d.write('<!doctype html>' + content);
        d.close();
        enableDesigner(d);
    } catch (e) {
        console.error("[designer] writeFrame error:", e.message);
    }
}

function enableDesigner(doc) {
    if (doc) {
        doc.designMode = (designStyle == 'edit' ? "on" : "off");
        doc.documentElement.focus();
    }
}

function refreshDesignSources() {
    let sSource;
    switch (designStyle) {
        case 'edit':
            let frame = document.querySelector("[rid=designFrame]");
            if (frame) {
                let doc = (frame.contentWindow || frame.contentDocument);
                if (doc && doc.document) {
                    // Convert HTML back to markdown if converter available
                    if (window.markdownConverter && window.markdownConverter.convertNodes) {
                        sSource = window.markdownConverter.convertNodes(doc.document.body.childNodes);
                    } else {
                        sSource = doc.document.body.innerHTML;
                    }
                }
            }
            break;
        case 'source':
            let txt = document.querySelector("[rid=designText]");
            if (txt) sSource = txt.value;
            break;
    }
    if (!sSource) sSource = designHtml;
    else designHtml = sSource;
    return sSource;
}

function setupDesigner() {
    if (init) return;
    let entity = caller.getEntity();
    if (!entity || entity[am7model.jsonModelKey] != 'data.data') return;
    if (designStyle != 'edit' && designStyle != 'code') return;

    let bytes = caller.getInstance().api.dataBytesStore();
    if (designStyle == 'code') {
        let tarea = document.querySelector("[rid=designText]");
        if (!tarea) return;
        tarea.value = bytes;
        // CodeMirror integration (optional, loads dynamically)
        if (typeof CodeMirror !== 'undefined') {
            codeMirrorInst = CodeMirror.fromTextArea(tarea, {
                lineNumbers: true, mode: "javascript", indentUnit: 4,
                extraKeys: { Tab: cm => cm.replaceSelection("    ", "end") },
                autoFocus: false
            });
            codeMirrorInst.setSize("100%", "100%");
        }
    } else {
        let frame = document.querySelector("[rid=designFrame]");
        if (!frame) return;
        let html = bytes;
        if (window.markdownConverter && window.markdownConverter.toHtml) {
            html = window.markdownConverter.toHtml(bytes);
        }
        if (bytes.length) {
            writeFrame(frame, designDocumentHeader + html + designDocumentFooter);
        } else {
            writeFrame(frame, designDocumentHeader + "<h1>Title</h1><h2>Sub Title</h2><p>[ body ]</p>" + designDocumentFooter);
        }
    }
    init = true;
}

function alignSource() {
    toggled = false;
    if (!init) {
        setupDesigner();
    } else {
        switch (designStyle) {
            case 'source':
                let txt = document.querySelector("[rid=designText]");
                if (txt) txt.value = designHtml;
                break;
            case 'edit':
            case 'preview':
                let frame = document.querySelector("[rid=designFrame]");
                if (frame) {
                    let html = designHtml;
                    if (window.markdownConverter && window.markdownConverter.toHtml) html = window.markdownConverter.toHtml(designHtml);
                    writeFrame(frame, designDocumentHeader + html + designDocumentFooter);
                }
                break;
        }
    }
}

function toggleStyle(sStyle) {
    designHtml = refreshDesignSources();
    designStyle = sStyle;
    toggled = true;
    m.redraw();
}

function getDesignField() {
    let page = getPage();
    let hidden = page.components.picker && page.components.picker.inPickMode && page.components.picker.inPickMode();
    let cls = "w-full h-full" + (hidden ? " hidden" : "");

    switch (designStyle) {
        case 'edit':
        case 'preview':
            return m("iframe", { src: 'about:blank', rid: 'designFrame', class: cls });
        case 'code':
        case 'source':
            return m("textarea", { rid: 'designText', class: cls + " font-mono text-sm p-2" });
    }
    return "";
}

function getDesigner(attrs) {
    let page = getPage();
    if (attrs && attrs.entity) {
        let mt = attrs.entity.contentType;
        if (mt) {
            if (mt.match(/^text\//)) {
                if (designStyle == 'code') designStyle = 'edit';
                return getRichTextDesigner();
            }
            if (mt.match(/(css|javascript)$/)) {
                designStyle = 'code';
                return getCodeDesigner();
            }
        }
    }
    return "";
}

function getCodeDesigner() {
    return [m("div", { class: "flex-1 overflow-auto" }, getDesignField())];
}

function getRichTextDesigner() {
    let page = getPage();
    let hidden = page.components.picker && page.components.picker.inPickMode && page.components.picker.inPickMode();

    return [
        m("div", { class: "flex flex-wrap items-center gap-1 px-2 py-1 border-b border-gray-200 dark:border-gray-700 shrink-0" + (hidden ? " hidden" : "") }, [
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle == 'source' ? ' bg-blue-100 dark:bg-blue-900' : ''), "code", "", () => toggleStyle('source')),
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle == 'edit' ? ' bg-blue-100 dark:bg-blue-900' : ''), "design_services", "", () => toggleStyle('edit')),
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle == 'preview' ? ' bg-blue-100 dark:bg-blue-900' : ''), "preview", "", () => toggleStyle('preview')),
            m("span", { class: "w-px h-5 bg-gray-300 dark:bg-gray-600 mx-1" }),
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle != 'edit' ? ' opacity-40 pointer-events-none' : ''), "format_bold", "", e => defAct(e, 'Bold')),
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle != 'edit' ? ' opacity-40 pointer-events-none' : ''), "format_italic", "", e => defAct(e, 'Italic')),
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle != 'edit' ? ' opacity-40 pointer-events-none' : ''), "format_strikethrough", "", e => defAct(e, 'Strikethrough')),
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle != 'edit' ? ' opacity-40 pointer-events-none' : ''), "format_list_bulleted", "", e => defAct(e, 'InsertUnorderedList')),
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle != 'edit' ? ' opacity-40 pointer-events-none' : ''), "format_list_numbered", "", e => defAct(e, 'InsertOrderedList')),
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle != 'edit' ? ' opacity-40 pointer-events-none' : ''), "format_quote", "", () => formatBlock('blockquote')),
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle != 'edit' ? ' opacity-40 pointer-events-none' : ''), "horizontal_rule", "", e => defAct(e, 'InsertHorizontalRule')),
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle != 'edit' ? ' opacity-40 pointer-events-none' : ''), "code_blocks", "", () => formatBlock('pre')),
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle != 'edit' ? ' opacity-40 pointer-events-none' : (pickData ? ' bg-green-100 dark:bg-green-900' : '')), (pickData ? "content_paste_go" : "image"), "", pickImage),
            page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (designStyle != 'edit' ? ' opacity-40 pointer-events-none' : ''), "link", "", insertLink),
            designStyle == 'edit' ? m("select", {
                class: "text-xs border rounded px-1 py-0.5 bg-white dark:bg-gray-800",
                onchange: function(e) {
                    let val = e.target.value;
                    if (val !== 'BLOCK') formatBlock(val.toLowerCase());
                    e.target.selectedIndex = 0;
                }
            }, formatOptions.map(o => m("option", { value: o }, o))) : null
        ]),
        m("div", { class: "flex-1 overflow-auto" }, getDesignField())
    ];
}

// ── Component ─────────────────────────────────────────────────────

const designerComponent = {
    oninit: function(x) {
        if (x.attrs.caller) {
            caller = x.attrs.caller;
            caller.transitionHandler = transitionHandler;
        }
    },
    oncreate: function() { alignSource(); },
    onupdate: function() {
        if (!init) setupDesigner();
        else if (toggled) alignSource();
    },
    onremove: function() {
        init = false;
        caller = null;
        toggled = false;
        pickData = false;
        pickDataObject = null;
        codeMirrorInst = null;
    },
    view: function(v) {
        return (v.attrs.visible ? getDesigner(v.attrs) : "");
    }
};

const designer = { component: designerComponent };

export { designer };
export default designer;
