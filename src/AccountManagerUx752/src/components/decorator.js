/**
 * decorator.js — Full rendering module for list views (ESM)
 * Ported from Ux7 client/decorator.js (557 lines) with:
 *   - ES6 exports, Tailwind classes
 *   - polarListView/polarListItem removed (approved dead code)
 *   - modelHeaderMap added (approved per-model column overrides)
 *   - renderMediaPreview(item) added (approved standalone media renderer)
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { applicationPath } from '../core/config.js';

// ── Helpers ──────────────────────────────────────────────────────────

function getDnd() {
    return page.components.dnd || {};
}

function dndDragProps(item) {
    let dnd = getDnd();
    if (typeof dnd.dragProps === 'function') return dnd.dragProps(item);
    // Fallback: build from dragStartHandler if available
    if (typeof dnd.dragStartHandler === 'function') {
        return { ondragstart: dnd.dragStartHandler(item) };
    }
    return {};
}

function dndDragDecorate(item) {
    let dnd = getDnd();
    if (typeof dnd.doDragDecorate === 'function') return dnd.doDragDecorate(item);
    return "";
}

// ── 3. getFileTypeIcon (Ux7 lines 3-10) ─────────────────────────────

function getFileTypeIcon(p, i) {
    let mtIco = "";
    if (p.contentType && p.name && p.name.indexOf(".") > 0) {
        let ext = p.name.substring(p.name.lastIndexOf(".") + 1, p.name.length).toLowerCase();
        mtIco = m("span", { class: "fontLabel" + (i && i > 0 ? "-" + i : "") + " fiv-cla fiv-icon-" + ext });
    }
    return mtIco;
}

// ── 2. getThumbnail (Ux7 lines 12-53) ───────────────────────────────

function getThumbnail(ctl, p) {
    let type = am7model.getModel(p[am7model.jsonModelKey]);
    let ico = "question_mark";
    if (type && type.icon) ico = type.icon;
    let gridMode = ctl.gridMode;
    let icon;

    if (p.profile && p.profile.portrait && p.profile.portrait.contentType) {
        let pp = p.profile.portrait;
        let icoPath = applicationPath + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name + "/" + (gridMode == 1 ? "256x256" : "512x512");
        if (gridMode == 2 && (pp.contentType.match(/gif$/) || pp.contentType.match(/webp$/))) {
            icoPath = applicationPath + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name;
        }
        let icoCls = "image-grid-image carousel-item-img";
        icon = m("img", { class: icoCls, src: icoPath });
    }
    else if (p.contentType && p.contentType.match(/^image/)) {
        let icoPath = applicationPath + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + p.groupPath + "/" + p.name + "/" + (gridMode == 1 ? "256x256" : "512x512");
        if (p.dataBytesStore && p.dataBytesStore.length) {
            icoPath = "data:" + p.contentType + ";base64," + p.dataBytesStore;
        }
        if (gridMode == 2 && (p.contentType.match(/gif$/) || p.contentType.match(/webp$/))) {
            icoPath = applicationPath + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + p.groupPath + "/" + p.name;
        }
        let icoCls = "image-grid-image carousel-item-img";
        icon = m("img", { class: icoCls, src: icoPath });
    }
    else if (p.contentType && p.name && p.name.indexOf(".") > 0) {
        let ext = p.name.substring(p.name.lastIndexOf(".") + 1, p.name.length);
        icon = m("span", { class: "fontLabel-" + (gridMode == 1 ? "6" : "10") + " fiv-cla fiv-icon-" + ext });
    }
    else {
        let icoCls = "material-symbols-outlined";
        let col = "";
        let sty = "";
        if (p.hex) {
            icoCls = "material-icons";
            col = " stroke-slate-50";
            sty = "color: " + p.hex + ";";
        }
        icon = m("span", { style: sty, class: icoCls + " material-icons-72" + (icoCls ? " " + icoCls : "") + col }, ico);
    }
    return icon;
}

// ── 1. getIcon (Ux7 lines 55-103) ───────────────────────────────────

function getIcon(p) {
    let type = am7model.getModel(p[am7model.jsonModelKey]);
    let ico = "question_mark";
    if (type && type.icon) ico = type.icon;
    let icon;
    let icoCls = "";

    if (p[am7model.jsonModelKey] == "message.spool") {
        if (p.spoolStatus == "ERROR") {
            icoCls = "text-red-600";
            ico = "quickreply";
        }
        else if (p.spoolStates == "ACKNOWLEGED_RECEIPT") {
            ico = "mark_chat_read";
        }
        else if (p.spoolStates == "TRANSMITTED" || p.spoolStatus == "SPOOLED") {
            icoCls = "text-green-600";
            ico = "mark_chat_unread";
        }
    }

    if (p.profile && p.profile.portrait && p.profile.portrait.contentType) {
        let icoPath = applicationPath + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + p.profile.portrait.groupPath + "/" + p.profile.portrait.name + "/48x48";
        icon = m("img", { height: 48, width: 48, src: icoPath });
    }
    else if (p.contentType && p.contentType.match(/^image/)) {
        let icoPath = applicationPath + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + p.groupPath + "/" + p.name + "/48x48";
        if (p.dataBytesStore && p.dataBytesStore.length) {
            icoPath = "data:" + p.contentType + ";base64," + p.dataBytesStore;
        }
        icon = m("img", { height: 48, width: 48, src: icoPath });
    }
    else if (p.contentType && p.name && p.name.indexOf(".") > 0) {
        icon = getFileTypeIcon(p, 3);
    }
    else {
        icoCls = "material-symbols-outlined";
        let col = "";
        let sty = "";
        if (p.hex) {
            icoCls = "material-icons";
            col = " stroke-slate-50";
            sty = "color: " + p.hex + ";";
        }
        icon = m("span", { style: sty, class: icoCls + " material-icons-48" + (icoCls ? " " + icoCls : "") + col }, ico);
    }
    return icon;
}

// ── 4. getFavoriteStyle (Ux7 lines 107-113) ──────────────────────────

function getFavoriteStyle(p) {
    let isFavcls = "";
    if (page.isFavorite(p)) {
        isFavcls = " material-symbols-outlined-red filled";
    }
    return isFavcls;
}

// ── 5. defaultHeaderMap (Ux7 lines 188-201) — EXACTLY as Stephen wrote

let defaultHeaderMap = [
    "_rowNum",
    "_icon",
    "id",
    "name",
    "modifiedDate",
    "age",
    "gender",
    /// Need to correctly plan out embedded fields
    //"userCharacter.name",
    // "_thumbnail",
    "_tags",
    "_favorite"
];

// ── modelHeaderMap — per-model column overrides (approved by Stephen)

let modelHeaderMap = {
    'auth.group':           ["_rowNum", "_icon", "name", "type", "path", "_favorite"],
    'data.data':            ["_rowNum", "_icon", "name", "contentType", "description", "_tags", "_favorite"],
    'olio.llm.chatConfig':  ["_rowNum", "_icon", "name", "model", "rating", "_favorite"],
    'olio.llm.chatRequest': ["_rowNum", "_icon", "name", "chatTitle", "modifiedDate", "_favorite"]
};

// ── 6. getHeaders (Ux7 lines 203-208) + modelHeaderMap lookup ────────

function getHeaders(type, map) {
    let base = map || modelHeaderMap[type] || defaultHeaderMap;
    return base.filter((h) => {
        if (h == "_tags" && type == "data.tag") return false;
        return am7model.hasField(type, h) || h.match(/^_/);
    });
}

// ── 9. getCellStyle (Ux7 lines 334-350) ─────────────────────────────

function getCellStyle(ctl, h) {
    let w = "";
    if (h == "_icon" || h == "id" || h == "_rowNum") {
        w = "w-14";
    }
    else if (h == "_favorite") {
        w = "w-12";
    }

    if (h == "id" || h == "_rowNum") {
        w += " align-right";
    }
    else {
        w += " align-center";
    }
    return w;
}

// ── 10. getFormattedValue (Ux7 lines 352-394) ───────────────────────

function getFormattedValue(ctl, p, h) {
    if (h.match(/^_/)) {
        console.warn("Unhandled special column: " + h);
        return "";
    }
    let ah = h.split(".");
    let lastFld;
    let val;
    let om = p;
    for (let i = 0; i < ah.length; i++) {
        let um;
        if (lastFld) {
            um = am7model.getModelField(lastFld.baseModel, ah[i]);
        }
        else {
            um = am7model.getModelField(p[am7model.jsonModelKey], ah[i]);
        }

        if (!um) {
            console.warn("Couldn't find field", ah[i]);
            return;
        }

        if (um.type == "timestamp") {
            val = (om[um.name] ? " " + (new Date(om[um.name])).toLocaleDateString("en-US", { year: 'numeric', month: 'long', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }) : "");
        }
        else if (um.type == "model") {
            if (!om[um.name]) {
                console.warn("Couldn't find model for field", um.name, "in", p);
                return "";
            }
            lastFld = um;
            om = om[um.name];
        }
        else {
            val = om[um.name];
        }
    }
    return val;
}

// ── 7. getHeadersView (Ux7 lines 210-261) ───────────────────────────

function getHeadersView(ctl, map) {
    let type = ctl.listType;
    let mod = am7model.getModel(type);
    let pages = ctl.pagination.pages();
    let headers = getHeaders(type, map).map((h) => {
        let fld = am7model.getModelField(type, h);
        let lbl = fld?.label || h;
        if (h.match(/^_/)) {
            lbl = "";
            if (h == "_tags") {
                lbl = "Tags";
            }
        }
        let ico = "";
        if (h == "_icon" && mod && mod.icon) {
            ico = m("span", { class: "material-icons-cm material-symbols-outlined" }, mod.icon);
        }
        else if (h == "_rowNum") {
            function selectHandler() {
                let pages = ctl.pagination.pages();
                pages.pageResults[pages.currentPage].forEach((v) => {
                    pages.pageState[v.objectId] = { checked: true };
                });
            }
            ico = m("span", { onclick: selectHandler, class: "material-icons-cm material-symbols-outlined" }, "tag");
        }
        let cw = getCellStyle(ctl, h);
        let sortButton = "";
        if (!h.match(/^_/)) {
            let sico = "swap_vert";
            let icoCls = "text-red-200";
            if (pages.sort == h) {
                sico = (pages.order == "ascending" ? "arrow_upward" : "arrow_downward");
                icoCls = "text-blue-700";
            }
            sortButton = m("span", {
                class: "mr-2 material-icons-cm material-symbols-outlined " + icoCls,
                onclick: function () {
                    if (pages.sort == h) {
                        pages.order = (pages.order == "ascending" ? "descending" : "ascending");
                    }
                    else {
                        pages.sort = h;
                        pages.order = "ascending";
                    }
                }
            }, sico);
        }
        return m("th", { class: cw }, [ico, sortButton, lbl]);
    });
    return m("thead", m("tr", { class: "tabular-header" }, headers));
}

// ── 8. getTabularRow (Ux7 lines 263-332) ─────────────────────────────

function getTabularRow(ctl, p, idx, map) {
    let isFavcls = getFavoriteStyle(p);
    let dcls = dndDragDecorate(p);
    let cls = "tabular-row";
    if (ctl.pagination.state(p).checked) {
        cls += " tabular-row-active";
    }
    if (dcls) cls += " " + dcls;
    let props = Object.assign(dndDragProps(p), {
        class: cls,
        onselectstart: function () { return false; },
        ondblclick: function () { if (ctl.containerMode) ctl.down(p); else ctl.open(p); },
        onclick: function () { ctl.select(p); }
    });
    let attr = "[draggable]";
    return m("tr" + attr, props, getHeaders(ctl.listType, map).map((h) => {
        let cw = getCellStyle(ctl, h);
        if (h == "_thumbnail") {
            return m("td", { class: cw }, [getThumbnail(ctl, p)]);
        }
        else if (h == "_icon") {
            return m("td", { class: cw }, [getIcon(p)]);
        }
        else if (h == "_tags") {
            let rawTags = p.tags || [];
            let allTags = rawTags.filter((t) => {
                if (!t || !t.name) {
                    console.warn("Invalid tag found on object:", p.objectId || p.id, "tag:", t);
                    return false;
                }
                return true;
            });
            let maxVisible = 3;
            let visibleTags = allTags.slice(0, maxVisible);
            let remainingCount = allTags.length - maxVisible;

            let tagElements = visibleTags.map((t) => {
                return m("span", { class: "tag-pill", title: t.name }, t.name);
            });

            if (remainingCount > 0) {
                tagElements.push(m("span", {
                    class: "tag-pill tag-more",
                    title: allTags.slice(maxVisible).map(t => t.name).join(", ")
                }, "+" + remainingCount));
            }

            return m("td", { class: cw + " tags-cell" }, tagElements);
        }
        else if (h == "_favorite") {
            return m("td", { class: cw }, m("span", {
                class: "cursor-pointer material-symbols-outlined" + isFavcls,
                onclick: function (e) {
                    e.preventDefault();
                    page.toggleFavorite(p).then(() => {
                        m.redraw();
                    });
                    return false;
                }
            }, "favorite"));
        }
        else if (h == "_rowNum") {
            return m("td", { class: cw }, (parseInt(ctl.pagination.pages().startRecord) + 1) + idx);
        }

        return m("td", { class: cw }, getFormattedValue(ctl, p, h));
    }));
}

// ── Render memoization helpers ──────────────────────────────────────

function checkedKey(pg) {
    let keys = [];
    for (let k in pg.pageState) {
        if (pg.pageState[k].checked) keys.push(k);
    }
    return keys.sort().join(',');
}

// ── 11. tabularView (Ux7 lines 396-410) — with memoization ─────────

function renderTabular(ctl, rset) {
    let results = (rset || []).map((p, i) => {
        return getTabularRow(ctl, p, i);
    });
    if (results.length == 0) {
        return "";
    }

    let table = m("table", { class: "tabular-results-table" }, [getHeadersView(ctl), m("tbody", results)]);

    return m("div", { rid: 'resultList', onscroll: ctl.onscroll, class: "tabular-results-overflow" },
        table
    );
}

let _tabState = null;

const TabularMemo = {
    onbeforeupdate(vnode) {
        let pg = vnode.attrs.ctl.pagination.pages();
        let k = { data: vnode.attrs.rset, sort: pg.sort, order: pg.order,
            start: pg.startRecord, type: vnode.attrs.ctl.listType, checked: checkedKey(pg) };
        if (_tabState && _tabState.data === k.data && _tabState.sort === k.sort &&
            _tabState.order === k.order && _tabState.start === k.start &&
            _tabState.type === k.type && _tabState.checked === k.checked) {
            return false;
        }
        _tabState = k;
        return true;
    },
    view(vnode) {
        return renderTabular(vnode.attrs.ctl, vnode.attrs.rset);
    }
};

function getTabularView(ctl, rset) {
    return m(TabularMemo, { ctl, rset });
}

// ── 12. getGridListItem (Ux7 lines 412-447) ─────────────────────────

function getGridListItem(ctl, p) {
    let type = am7model.getModel(p[am7model.jsonModelKey]);
    let ico = "question_mark";
    let gridMode = ctl.gridMode;
    let showInfo = ctl.info;
    if (type && type.icon) ico = type.icon;
    let icon = getThumbnail(ctl, p);

    let cls = (gridMode == 1 ? "image-grid-item-tile" : "image-grid-item-1");
    if (ctl.pagination.state(p).checked) {
        cls += " image-grid-item-active";
    }
    let title = "";
    let footer = "";
    if (showInfo) {
        title = m("p", { class: "card-title" }, p.name);
        if (ctl.showIconFooter) footer = m("p", { class: "card-footer" }, 'footer');
    }

    let gridCellCls = "image-grid-item-content-32";
    if (gridMode == 2) gridCellCls = "image-grid-item-1";
    let props = Object.assign(dndDragProps(p), {
        ondblclick: function () {
            if (ctl.containerMode) ctl.down(p);
            else ctl.open(p);
        },
        onclick: function () {
            ctl.select(p);
        },
        class: cls
    });
    let attr = "[draggable]";

    return m("div" + attr, props, [title, m("div", { class: gridCellCls }, icon), footer]);
}

// ── 16. displayIndicators (Ux7 lines 449-473) ───────────────────────

function displayIndicators(ctl) {
    let results = [];
    let pages = ctl.pagination.pages();
    if (pages.pageResults[pages.currentPage]) {
        results = pages.pageResults[pages.currentPage].map((p, i) => {
            let cls = "material-symbols-outlined carousel-bullet";
            let ico = "radio_button_unchecked";
            if (i == pages.currentItem) {
                cls += " carousel-bullet-active";
                ico = "radio_button_checked";
            }
            return m("li", { onclick: function () { ctl.moveTo(i); }, class: "carousel-indicator" }, [
                m("span", { class: cls }, ico)
            ]);
        });
    }

    return m("ul", { class: "carousel-indicators" }, [
        m("li", { onclick: function () { ctl.pagination.prev(ctl.embeddedMode || ctl.pickerMode); }, class: "carousel-indicator" }, [
            m("span", { class: "material-symbols-outlined carousel-bullet" }, "arrow_back")
        ]),
        results,
        m("li", { onclick: function () { ctl.pagination.next(ctl.embeddedMode || ctl.pickerMode); }, class: "carousel-indicator" }, [
            m("span", { class: "material-symbols-outlined carousel-bullet" }, "arrow_forward")
        ])
    ]);
}

// ── 17. displayObjects (Ux7 lines 475-488) ──────────────────────────

function displayObjects(ctl) {
    let results = [];
    let pages = ctl.pagination.pages();
    let pr = pages.pageResults[pages.currentPage];
    if (pr) {
        let objectComponent = page.components.object_v2 || page.components.object;
        if (objectComponent) {
            results = pr.map((p, i) => {
                return m(objectComponent, { view: ctl.listPage, app: undefined, object: p, model: ctl.listType, active: (pages.currentItem == i), maxMode: ctl.maxMode, inner: true });
            });
        }
    }
    return results;
}

// ── 13. gridListView (Ux7 lines 490-499) — with memoization ────────

function renderGridList(ctl) {
    let results = [];
    let gridMode = ctl.gridMode;
    let pages = ctl.pagination.pages();
    if (pages.pageResults[pages.currentPage]) {
        results = pages.pageResults[pages.currentPage].map((p, i) => {
            return getGridListItem(ctl, p);
        });
    }

    return m("div", { class: 'h-full overflow-hidden' }, m("div", { class: (gridMode == 1 ? 'image-grid-tile' : 'image-grid-5') }, results));
}

let _gridState = null;

const GridMemo = {
    onbeforeupdate(vnode) {
        let ctl = vnode.attrs.ctl;
        let pg = ctl.pagination.pages();
        let data = pg.pageResults[pg.currentPage];
        let k = { data, gridMode: ctl.gridMode, info: ctl.info, checked: checkedKey(pg) };
        if (_gridState && _gridState.data === k.data && _gridState.gridMode === k.gridMode &&
            _gridState.info === k.info && _gridState.checked === k.checked) {
            return false;
        }
        _gridState = k;
        return true;
    },
    view(vnode) {
        return renderGridList(vnode.attrs.ctl);
    }
};

function getGridListView(ctl) {
    return m(GridMemo, { ctl });
}

// ── 14. carouselItem (Ux7 lines 500-525) ─────────────────────────────

function getCarouselItem(ctl) {
    let pages = ctl.pagination.pages();
    let pr = ctl.results();
    let uimarkers = [];
    if (!ctl.embeddedMode || ctl.fullMode) {
        uimarkers = [
            m("span", { onclick: ctl.toggleCarouselFull, class: "carousel-full" }, [m("span", { class: "material-symbols-outlined" }, (ctl.fullMode ? "close_fullscreen" : "open_in_new"))]),
            m("span", { onclick: ctl.toggleCarouselMax, class: "carousel-max" }, [m("span", { class: "material-symbols-outlined" }, (ctl.maxMode ? "photo_size_select_small" : "aspect_ratio"))]),
            m("span", { onclick: ctl.toggleInfo, class: "carousel-info" }, [m("span", { class: "material-symbols-outlined" + (ctl.info ? "" : "-outlined") }, (ctl.info ? "info" : "info"))]),
            m("span", { onclick: function () { ctl.edit(pr[pages.currentItem]); }, class: "carousel-edit" }, [m("span", { class: "material-symbols-outlined" }, "edit")]),
            m("span", { onclick: ctl.toggleCarousel, class: "carousel-exit" }, [m("span", { class: "material-symbols-outlined" }, "close")]),
            m("span", { onclick: function () { ctl.move(-1); }, class: "carousel-prev" }, [m("span", { class: "material-symbols-outlined" }, "arrow_back")]),
            m("span", { onclick: function () { ctl.move(1); }, class: "carousel-next" }, [m("span", { class: "material-symbols-outlined" }, "arrow_forward")]),
        ];
    }
    return m("div", { class: "carousel" }, [
        m("div", { class: "carousel-inner" },
            [
                displayObjects(ctl),
                uimarkers,
                displayIndicators(ctl)
            ]
        )
    ]);
}

// ── 15. carouselView (Ux7 lines 527-536) ─────────────────────────────

function getCarouselView(ctl) {
    return m("div", { class: "content-outer" }, [
        (ctl.fullMode ? "" : (page.components.navigation ? m(page.components.navigation) : "")),
        m("div", { class: "content-main" }, [
            getCarouselItem(ctl)
        ])
    ]);
}

// ── 18. renderMediaPreview (approved standalone media renderer) ──────

function renderMediaPreview(item) {
    if (!item) return "";
    let ct = item.contentType || "";
    let path = am7client.mediaDataPath(item);

    if (ct.match(/^image/)) {
        return m("img", {
            class: "max-w-full max-h-80 object-contain rounded shadow",
            src: path,
            alt: item.name || "Image"
        });
    }
    else if (ct.match(/^video/)) {
        return m("video", {
            class: "max-w-full max-h-80",
            preload: "auto",
            controls: true
        }, [
            m("source", { src: path, type: ct })
        ]);
    }
    else if (ct.match(/^audio/)) {
        let mt = ct;
        if (mt && mt.match(/mpeg3$/)) mt = "audio/mpeg";
        return m("audio", {
            class: "w-full",
            preload: "auto",
            controls: true
        }, [
            m("source", { src: path, type: mt })
        ]);
    }
    else if (ct.match(/pdf$/)) {
        return m("iframe", {
            class: "w-full h-96 border rounded",
            src: path,
            type: "application/pdf"
        });
    }
    return "";
}

// ── Export ────────────────────────────────────────────────────────────

const am7decorator = {
    map: () => defaultHeaderMap,
    modelHeaderMap,
    getHeaders,
    icon: getIcon,
    fileIcon: getFileTypeIcon,
    thumbnail: getThumbnail,
    favoriteStyle: getFavoriteStyle,
    cellStyle: getCellStyle,
    formattedValue: getFormattedValue,
    headersView: getHeadersView,
    tabularRow: getTabularRow,
    tabularView: getTabularView,
    gridListItem: getGridListItem,
    gridListView: getGridListView,
    carouselItem: getCarouselItem,
    carouselView: getCarouselView,
    displayIndicators,
    displayObjects,
    renderMediaPreview
};

export {
    am7decorator,
    defaultHeaderMap,
    modelHeaderMap,
    getHeaders,
    getIcon,
    getFileTypeIcon,
    getThumbnail,
    getFavoriteStyle,
    getCellStyle,
    getFormattedValue,
    getHeadersView,
    getTabularRow,
    getTabularView,
    getGridListItem,
    getGridListView,
    getCarouselItem,
    getCarouselView,
    displayIndicators,
    displayObjects,
    renderMediaPreview
};

export default am7decorator;
