import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client, uwm } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { newPaginationControl } from '../components/pagination.js';
import { panel } from '../components/panel.js';

// ---------------------------------------------------------------------------
//  Per-model column defaults (Fix B) — ported from Ux7 am7decorator.map()
// ---------------------------------------------------------------------------

const MODEL_COLUMNS = {
    'auth.group':           ['name', 'type', 'path'],
    'auth.role':            ['name', 'type'],
    'auth.permission':      ['name', 'type'],
    'data.data':            ['name', 'contentType', 'description'],
    'data.note':            ['name', 'description'],
    'data.tag':             ['name', 'type'],
    'olio.charPerson':      ['name', 'firstName', 'lastName', 'gender', 'age'],
    'olio.llm.chatConfig':  ['name', 'model', 'rating', 'serviceType'],
    'olio.llm.chatRequest': ['name', 'chatTitle'],
    'olio.item':            ['name', 'type', 'description'],
    'olio.color':           ['name', 'hex'],
    'policy.policy':        ['name', 'type', 'description'],
    'policy.rule':          ['name', 'type'],
    'policy.pattern':       ['name', 'type'],
    'policy.fact':          ['name', 'type'],
    'policy.operation':     ['name', 'type'],
    'identity.person':      ['name', 'firstName', 'lastName'],
    'identity.account':     ['name', 'type', 'status']
};

// ---------------------------------------------------------------------------
//  newListControl  --  factory that returns a list-page controller + view
// ---------------------------------------------------------------------------

function newListControl() {

    const listPage = {};

    // --- State variables (ported from Ux7 list.js) ---

    let listType;
    let baseListType;
    let listContainerId;
    let containerMode = false;
    let gridMode = 0;           // 0 = table, 1 = small grid, 2 = large grid, 3 = gallery
    let carousel = false;       // Fix C: carousel state (Ux7 pattern)
    let fullMode = false;
    let maxMode = false;
    let info = true;            // Fix E: info toggle (Ux7 pattern)
    let wentBack = false;       // Fix C: carousel back-navigation flag
    let pickerMode = false;
    let modType;
    let navigateByParent = false;
    let systemList = false;     // Fix F: system list toggle
    let navContainerId = null;
    let navFilter = null;
    let defaultRecordCount = 10;
    let defaultIconRecordCount = 40;
    let infiniteScroll = false;
    let infiniteLoading = false;

    // Picker mode state
    let pickerHandler = null;
    let pickerType = null;
    let pickerContainerId = null;
    let pickerUserContainerId = null;
    let pickerLibraryContainerId = null;
    let pickerFavoritesContainerId = null;
    let pickerActiveSource = 'home';

    // Group navigation state
    let childGroups = null;
    let childGroupsLoading = false;
    let groupPath = null;

    // Search state
    let searchQuery = '';
    let searchTimer = null;
    let searchActive = false;

    // Column customization per model type (persisted in localStorage)
    let columnConfigCache = {};
    let columnPickerOpen = false;

    function getColumnConfigKey(type) { return 'am7_columns_' + type; }

    function getCustomColumns(type) {
        if (columnConfigCache[type]) return columnConfigCache[type];
        try {
            let saved = localStorage.getItem(getColumnConfigKey(type));
            if (saved) {
                columnConfigCache[type] = JSON.parse(saved);
                return columnConfigCache[type];
            }
        } catch (e) { /* ignore */ }
        return null;
    }

    function saveCustomColumns(type, columns) {
        columnConfigCache[type] = columns;
        try { localStorage.setItem(getColumnConfigKey(type), JSON.stringify(columns)); } catch (e) { /* ignore */ }
    }

    // Fix B: per-model column defaults
    function getColumns(type) {
        let custom = getCustomColumns(type);
        if (custom && custom.length) return custom;

        // Per-model defaults
        if (MODEL_COLUMNS[type]) return MODEL_COLUMNS[type];

        // Fallback: name + first 2-3 fields from model.query
        let mod = am7model.getModel(type);
        let columns = ['name'];
        if (mod) {
            let qf = am7model.queryFields(type);
            let extras = qf.filter(f =>
                f !== 'name' && f !== 'id' && f !== 'objectId' && f !== 'ownerId' &&
                f !== 'organizationId' && f !== 'organizationPath' && f !== 'urn' &&
                f !== 'groupId' && !f.match(/^_/)
            ).slice(0, 3);
            columns.push(...extras);
        }
        return columns;
    }

    function toggleColumnSort(col) {
        let ent = pagination.entity;
        if (ent.sort === col) {
            ent.order = (ent.order === 'ascending') ? 'descending' : 'ascending';
        } else {
            ent.sort = col;
            ent.order = 'ascending';
        }
        pagination.new();
        initParams(lastVnode);
        updatePagination(lastVnode);
        m.redraw();
    }

    let pagination = newPaginationControl();
    pagination.setColumnProvider(getCustomColumns);

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    function iconBtn(sClass, sIco, sLabel, fHandler, ariaLabel) {
        let attrs = { onclick: fHandler, class: sClass };
        if (ariaLabel || (sLabel == null || sLabel === '')) {
            attrs['aria-label'] = ariaLabel || sIco;
        }
        return m('button', attrs, [
            sIco ? m('span', { class: 'material-symbols-outlined material-icons-24', 'aria-hidden': 'true' }, sIco) : '',
            sLabel || ''
        ]);
    }

    function getType(o) {
        return o[am7model.jsonModelKey] || listType;
    }

    function getSelected() {
        let pg = pagination.pages();
        let results = pg.pageResults[pg.currentPage];
        if (!results) return [];
        return results.filter((v) => {
            return pg.pageState[v.objectId] && pg.pageState[v.objectId].checked;
        });
    }

    function getSelectedIndices() {
        let pg = pagination.pages();
        let results = pg.pageResults[pg.currentPage];
        if (!results) return [];
        let sel = [];
        Object.keys(pg.pageState).forEach((k) => {
            if (pg.pageState[k].checked) {
                let idx = results.findIndex((v) => v.objectId === k);
                if (idx > -1) sel.push(idx);
            }
        });
        return sel;
    }

    function selectResult(o) {
        let state = pagination.state(o);
        state.checked = !state.checked;
        m.redraw();
    }

    // Fix G: context menu "Open" calls openItem, not navigateDown
    function showListContextMenu(e, item) {
        let cm = page.components.contextMenu;
        if (!cm) return;
        cm.show(e, [
            { label: 'Open', icon: 'open_in_new', action: function () { openItem(item); } },
            { label: 'Edit', icon: 'edit', action: function () { editItem(item); } },
            { divider: true },
            { label: 'Delete', icon: 'delete', action: function () {
                selectResult(item);
                deleteSelected();
            }}
        ]);
    }

    // ------------------------------------------------------------------
    //  Carousel/Gallery (Fix C) — ported from Ux7
    // ------------------------------------------------------------------

    function openItem(o) {
        let pg = pagination.pages();
        let pr = pg.pageResults[pg.currentPage];
        if (!pr) return;
        let idx = pr.findIndex((v) => v.objectId === o.objectId);
        if (idx > -1) {
            carousel = true;
            pg.currentItem = idx;
            m.redraw();
        }
    }

    function openSelected() {
        let idx = getSelectedIndices();
        if (idx.length) {
            carousel = true;
            pagination.pages().currentItem = idx[0];
            m.redraw();
        }
    }

    function closeSelected() {
        carousel = false;
        pagination.pages().currentItem = undefined;
        m.redraw();
    }

    function toggleCarousel() {
        carousel = !carousel;
        if (!carousel) {
            pagination.pages().currentItem = undefined;
        }
        m.redraw();
    }

    function moveCarouselTo(i) {
        let pg = pagination.pages();
        wentBack = false;
        pg.currentItem = i;
        m.redraw();
    }

    function moveCarousel(delta) {
        let pg = pagination.pages();
        let pr = pg.pageResults[pg.currentPage];
        wentBack = false;

        let idx = (pg.currentItem || 0) + delta;
        if (pr && idx >= pr.length) {
            if (pg.currentPage < pg.pageCount) pagination.next(pickerMode);
        } else if (idx < 0) {
            if (pg.currentPage > 1) {
                wentBack = true;
                pagination.prev(pickerMode);
            }
        } else {
            pg.currentItem = idx;
            m.redraw();
        }
    }

    function getCurrentResults() {
        let pg = pagination.pages();
        let pr = pg.pageResults[pg.currentPage];
        if (pr) {
            if (wentBack) {
                pg.currentItem = pr.length - 1;
            }
            if (pg.currentItem < 0 && pr.length) pg.currentItem = 0;
        }
        return pr;
    }

    // ------------------------------------------------------------------
    //  Navigation
    // ------------------------------------------------------------------

    function pickerNavigateTo(containerId) {
        pickerContainerId = containerId;
        listContainerId = containerId;
        childGroups = null;
        groupPath = null;
        pagination.new();
        let fakeVnode = { attrs: { type: pickerType, objectId: containerId } };
        lastVnode = fakeVnode;
        initParams(fakeVnode);
        updatePagination(fakeVnode);
        loadChildGroups(listContainerId);
        loadGroupPath(listContainerId);
    }

    function navigateUp() {
        if (pickerMode) {
            let pg = pagination.pages();
            if (!pg.container || !pg.container.path) return;
            let parentPath = pg.container.path.substring(0, pg.container.path.lastIndexOf('/'));
            if (!parentPath) return;
            page.findObject('auth.group', 'data', parentPath).then(function(grp) {
                if (grp && grp.objectId) pickerNavigateTo(grp.objectId);
            });
            return;
        }
        let pg = pagination.pages();
        if (!pg.container || !pg.container.path) return;

        let type = modType ? (modType.type || listType) : listType;
        if (type === 'auth.group') {
            let parentPath = pg.container.path.substring(0, pg.container.path.lastIndexOf('/'));
            if (!parentPath) { m.route.set('/main'); return; }
            page.findObject('auth.group', 'data', parentPath).then(function(grp) {
                if (grp && grp.objectId) {
                    m.route.set('/list/' + (containerMode ? baseListType : type) + '/' + grp.objectId, { key: Date.now() });
                }
            });
        } else {
            console.warn('navigateUp: unhandled type ' + type);
        }
    }

    // Fix A: navigateDown opens carousel for non-group items
    function navigateDown(sel) {
        if (pickerMode) {
            let obj = sel;
            if (!obj) {
                let idx = getSelectedIndices();
                let pg = pagination.pages();
                let results = pg.pageResults[pg.currentPage];
                if (idx.length && results) obj = results[idx[0]];
            }
            if (obj && pickerHandler) pickerHandler(obj);
            return;
        }
        let idx = getSelectedIndices();
        let type = modType ? (modType.type || listType) : listType;
        let byParent = am7model.isParent(modType) && type !== 'auth.group';

        if (sel && !sel[am7model.jsonModelKey]) sel = null;
        if (!sel && !idx.length) return;

        let pg = pagination.pages();
        let results = pg.pageResults[pg.currentPage];
        if (!sel && (!results || !results.length)) return;
        let obj = sel || results[idx[0]];
        let objType = obj[am7model.jsonModelKey] || baseListType;

        // Check if target is a group (navigable container) vs a data item
        let objModel = am7model.getModel(objType);
        let isGroup = objModel && (am7model.isGroup(objModel) || objType === 'auth.group');

        if (isGroup || containerMode) {
            // Navigate into the group as a container
            let ltype = containerMode ? baseListType : objType;
            m.route.set('/' + (byParent ? 'p' : '') + 'list/' + ltype + '/' + obj.objectId, { key: Date.now() });
        } else {
            // Fix A: non-group item — open in carousel instead of broken container lookup
            openItem(obj);
        }
    }

    // ------------------------------------------------------------------
    //  Group navigation — child groups + breadcrumb path
    // ------------------------------------------------------------------

    function loadChildGroups(containerId) {
        if (!containerId || childGroupsLoading) return;
        childGroupsLoading = true;
        childGroups = null;

        let cq = am7client.newQuery('auth.group');
        cq.entity.request = ['id', 'objectId'];
        cq.field('objectId', containerId);
        page.search(cq).then(function (qr) {
            if (!qr || !qr.results || !qr.results.length) {
                childGroupsLoading = false;
                childGroups = [];
                m.redraw();
                return;
            }
            let numericId = qr.results[0].id;
            let q = am7client.newQuery('auth.group');
            q.entity.request = ['id', 'objectId', 'name', 'type', 'path', 'parentId'];
            q.field('organizationId', page.user.organizationId);
            q.field('parentId', numericId);
            q.range(0, 200);
            q.sort('name');
            q.cache(false);
            am7client.search(q, function (result) {
                childGroupsLoading = false;
                let items = result;
                if (result && result.results) items = result.results;
                if (!Array.isArray(items)) items = [];
                childGroups = items.filter(function (g) {
                    return g.name && !g.name.match(/^\./);
                });
                m.redraw();
            });
        });
    }

    // Fix J: breadcrumb handles non-group paths
    function loadGroupPath(containerId) {
        if (!containerId) { groupPath = null; return; }
        groupPath = null;

        let cq = am7client.newQuery('auth.group');
        cq.entity.request = ['id', 'objectId', 'name', 'path'];
        cq.field('objectId', containerId);
        page.search(cq).then(function (qr) {
            if (!qr || !qr.results || !qr.results.length) {
                // Fix J: containerId might be a non-group item — try to find its parent group
                resolveNonGroupBreadcrumb(containerId);
                return;
            }
            let container = qr.results[0];
            buildBreadcrumbFromPath(container, containerId);
        });
    }

    function resolveNonGroupBreadcrumb(objectId) {
        // Try to look up the item to find its groupPath, then resolve the group
        let types = [baseListType || listType, 'data.data'];
        let tried = 0;
        types.forEach(function(t) {
            let iq = am7client.newQuery(t);
            iq.entity.request = ['objectId', 'groupPath', 'groupId', 'name'];
            iq.field('objectId', objectId);
            page.search(iq).then(function(qr2) {
                tried++;
                if (qr2 && qr2.results && qr2.results.length && qr2.results[0].groupPath) {
                    let gp = qr2.results[0].groupPath;
                    // Find the parent group by path
                    am7client.find('auth.group', 'data', gp, function(grp) {
                        if (grp) {
                            buildBreadcrumbFromPath(grp, grp.objectId);
                        }
                    });
                }
            });
        });
    }

    function buildBreadcrumbFromPath(container, containerId) {
        let fullPath = container.path;
        if (!fullPath) return;

        let segments = fullPath.split('/').filter(function (s) { return s.length > 0; });
        groupPath = segments.map(function (seg, i) {
            let segPath = '/' + segments.slice(0, i + 1).join('/');
            return { name: seg, path: segPath, objectId: null };
        });
        // Set current container
        groupPath[groupPath.length - 1].objectId = containerId;
        groupPath[groupPath.length - 1].name = container.name || segments[segments.length - 1];
        m.redraw();

        // Resolve earlier segments lazily
        for (let i = 0; i < groupPath.length - 1; i++) {
            (function (idx, spath) {
                am7client.find('auth.group', 'data', spath, function (v) {
                    if (v && groupPath && groupPath[idx]) {
                        groupPath[idx].objectId = v.objectId;
                        m.redraw();
                    }
                });
            })(i, groupPath[i].path);
        }
    }

    function navigateToChildGroup(group) {
        if (pickerMode) {
            pickerNavigateTo(group.objectId);
            return;
        }
        let type = baseListType || listType;
        childGroups = null;
        groupPath = null;
        m.route.set('/list/' + type + '/' + group.objectId, { key: Date.now() });
    }

    function renderChildGroups() {
        if (!childGroups || !childGroups.length) return null;
        if (gridMode >= 1 && !carousel) {
            let isSmall = (gridMode === 1);
            return childGroups.map(function (group) {
                return m('div', {
                    class: 'group relative rounded-lg border overflow-hidden cursor-pointer transition-shadow hover:shadow-md border-gray-200 dark:border-gray-700',
                    onclick: function () { navigateToChildGroup(group); }
                }, [
                    m('div', { class: 'aspect-square bg-gray-50 dark:bg-gray-800 flex items-center justify-center' },
                        m('span', { class: 'material-symbols-outlined text-yellow-500 dark:text-yellow-400', style: 'font-size:' + (isSmall ? '32px' : '48px') }, 'folder')),
                    m('div', { class: isSmall ? 'p-1' : 'p-2' },
                        m('div', { class: 'text-xs font-medium text-gray-700 dark:text-gray-300 truncate' }, group.name))
                ]);
            });
        }
        // Table mode: folder rows
        return m('tbody', { class: 'child-groups-section' },
            childGroups.map(function (group) {
                return m('tr', {
                    class: 'list-tr cursor-pointer hover:bg-blue-50 dark:hover:bg-blue-900/20',
                    onclick: function () { navigateToChildGroup(group); },
                    ondblclick: function () { navigateToChildGroup(group); }
                }, [
                    m('td', { class: 'list-td list-td-check' },
                        m('span', { class: 'material-symbols-outlined text-yellow-500', style: 'font-size:18px' }, 'folder')),
                    m('td', { class: 'list-td', colspan: 10 },
                        m('span', { class: 'font-medium' }, group.name))
                ]);
            })
        );
    }

    function renderGroupBreadcrumb() {
        if (!groupPath || !groupPath.length) return null;
        let type = baseListType || listType;
        return m('div', { class: 'breadcrumb-bar flex items-center gap-1 px-2 py-1 text-sm text-gray-500 dark:text-gray-400 border-b border-gray-100 dark:border-gray-800' },
            groupPath.map(function (seg, i) {
                let isLast = (i === groupPath.length - 1);
                let sep = i > 0 ? m('span', { class: 'mx-0.5' }, '/') : null;
                if (isLast || !seg.objectId) {
                    return [sep, m('span', { class: isLast ? 'font-medium text-gray-700 dark:text-gray-300' : '' }, seg.name)];
                }
                return [sep, m('button', {
                    class: 'hover:text-blue-600 dark:hover:text-blue-400 hover:underline',
                    onclick: function () {
                        if (pickerMode) {
                            pickerNavigateTo(seg.objectId);
                        } else {
                            childGroups = null;
                            groupPath = null;
                            m.route.set('/list/' + type + '/' + seg.objectId, { key: Date.now() });
                        }
                    }
                }, seg.name)];
            })
        );
    }

    // ------------------------------------------------------------------
    //  Search — debounced server-side search
    // ------------------------------------------------------------------

    function doSearch(value) {
        searchQuery = value || '';
        if (searchTimer) clearTimeout(searchTimer);
        if (!searchQuery.length) {
            searchActive = false;
            navFilter = null;
            pagination.new();
            initParams(lastVnode);
            updatePagination(lastVnode);
            m.redraw();
            return;
        }
        searchTimer = setTimeout(function () {
            searchActive = true;
            navFilter = searchQuery;
            pagination.new();
            initParams(lastVnode);
            updatePagination(lastVnode);
            m.redraw();
        }, 300);
    }

    function clearSearch() {
        searchQuery = '';
        searchActive = false;
        if (searchTimer) clearTimeout(searchTimer);
        navFilter = null;
        pagination.new();
        initParams(lastVnode);
        updatePagination(lastVnode);
        m.redraw();
    }

    // ------------------------------------------------------------------
    //  CRUD actions
    // ------------------------------------------------------------------

    function addNew() {
        let pg = pagination.pages();
        let path = '/new/' + pg.resultType + '/' + pg.containerId;
        m.route.set(path, { key: Date.now() });
    }

    function editItem(o) {
        if (!o) {
            console.error('editItem: invalid object');
            return;
        }
        let ctx = page.context();
        ctx.listReturnUrl = m.route.get();
        m.route.set('/view/' + getType(o) + '/' + o.objectId);
    }

    function editSelected() {
        let idx = getSelectedIndices();
        if (idx.length) {
            let pg = pagination.pages();
            editItem(pg.pageResults[pg.currentPage][idx[0]]);
        }
    }

    function deleteSelected() {
        let selected = getSelectedIndices();
        if (!selected.length) return;

        let pg = pagination.pages();
        let subType = pg.containerSubType;
        let isBucket = subType && subType.match(/^(bucket|account|person)$/gi);
        let label = isBucket ? 'Remove' : 'Delete';

        page.components.dialog.confirm(label + ' selected objects?', async function () {
            let promises = [];
            for (let i = 0; i < selected.length; i++) {
                let obj = pg.pageResults[pg.currentPage][selected[i]];
                if (isBucket) {
                    promises.push(
                        new Promise((res) => {
                            am7client.member(pg.containerType, listContainerId, 'null', getType(obj), obj.objectId, false, (r) => {
                                if (r) page.toast('success', 'Removed member');
                                else page.toast('error', 'Failed to remove member');
                                res(r);
                            });
                        })
                    );
                } else {
                    promises.push(
                        page.deleteObject(getType(obj), obj.objectId).then((r) => {
                            if (r) page.toast('success', 'Deleted object');
                            else page.toast('error', 'Failed to delete object');
                            return r;
                        })
                    );
                }
            }
            await Promise.all(promises);
            pagination.new();
            initParams(lastVnode);
            updatePagination(lastVnode);
            m.redraw();
        });
    }

    // Fix F: system list toggle (ported from Ux7)
    function listSystemType() {
        systemList = !systemList;
        pagination.new();
        initParams(lastVnode);
        updatePagination(lastVnode);
        m.redraw();
    }

    // Fix D: container mode toggle (ported from Ux7)
    function toggleContainer() {
        containerMode = !containerMode;
        pagination.new();
        initParams(lastVnode);
        updatePagination(lastVnode);
        m.redraw();
    }

    // Fix E: info toggle (ported from Ux7)
    function toggleInfo() {
        info = !info;
        m.redraw();
    }

    // ------------------------------------------------------------------
    //  Display
    // ------------------------------------------------------------------

    function displayList() {
        let pg = pagination.pages();
        let items;

        if (infiniteScroll) {
            items = [];
            for (let i = 1; i <= pg.currentPage; i++) {
                if (pg.pageResults[i]) items = items.concat(pg.pageResults[i]);
            }
        } else {
            items = pg.pageResults[pg.currentPage];
        }

        let hasItems = items && items.length;
        let hasChildGroups = childGroups && childGroups.length && !searchActive;

        if (!hasItems && !hasChildGroups) {
            return m('div', { class: 'list-empty' }, 'No results');
        }

        // Carousel mode overrides all display
        if (carousel) return renderCarousel(items || []);
        if (gridMode === 3) return renderGallery(items || []);
        if (gridMode >= 1) return renderGridWithGroups(items || [], gridMode === 1 ? 'small' : 'large');
        return renderSimpleTableWithGroups(items || []);
    }

    function renderGridWithGroups(items, size) {
        let isSmall = (size === 'small');
        let gridClass = isSmall
            ? 'grid grid-cols-4 sm:grid-cols-5 md:grid-cols-6 lg:grid-cols-8 gap-2 p-2'
            : 'grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3 p-3';
        let folderCards = (!searchActive ? renderChildGroups() : null) || [];
        let itemCards = renderGridItems(items, size);
        if (!folderCards.length && !itemCards.length) return m('div', { class: 'list-empty' }, 'No results');
        return m('div', { class: gridClass }, [...folderCards, ...itemCards]);
    }

    function renderGridItems(items, size) {
        if (!items || !items.length) return [];
        let isSmall = (size === 'small');
        let thumbSize = isSmall ? '128x128' : '256x256';
        let iconSize = isSmall ? '32px' : '48px';

        return items.map(function(item) {
            let state = pagination.state(item);
            let checked = !!(state && state.checked);
            let thumb = getItemThumbnail(item, thumbSize);
            let dndAttrs = {};
            if (!pickerMode && page.components.dnd) {
                dndAttrs.draggable = "true";
                dndAttrs.ondragstart = page.components.dnd.dragStartHandler(item);
            }

            return m('div', Object.assign({
                class: 'group relative rounded-lg border overflow-hidden cursor-pointer transition-shadow hover:shadow-md'
                    + (pickerMode ? ' hover:border-blue-400 border-gray-200 dark:border-gray-700'
                        : (checked ? ' border-blue-500 ring-2 ring-blue-300 dark:ring-blue-700' : ' border-gray-200 dark:border-gray-700')),
                onclick: pickerMode
                    ? function() { if (pickerHandler) pickerHandler(item); }
                    : function() { selectResult(item); },
                ondblclick: pickerMode ? undefined : function() { navigateDown(item); },
                oncontextmenu: pickerMode ? undefined : function(e) { showListContextMenu(e, item); }
            }, dndAttrs), [
                thumb
                    ? m('div', { class: 'aspect-square bg-gray-100 dark:bg-gray-800 flex items-center justify-center overflow-hidden' },
                        m('img', { src: thumb.attrs.src, class: 'w-full h-full object-cover' }))
                    : m('div', { class: 'aspect-square bg-gray-100 dark:bg-gray-800 flex items-center justify-center' },
                        m('span', { class: 'material-symbols-outlined text-gray-300 dark:text-gray-600', style: 'font-size:' + iconSize }, 'description')),
                info ? m('div', { class: isSmall ? 'p-1' : 'p-2' }, [
                    m('div', { class: 'text-xs font-medium text-gray-700 dark:text-gray-300 truncate' }, item.name || '(unnamed)'),
                    !isSmall && item.description ? m('div', { class: 'text-xs text-gray-400 truncate mt-0.5' }, item.description) : null
                ]) : null,
                (!pickerMode && checked) ? m('div', { class: 'absolute top-1 right-1' },
                    m('span', { class: 'material-symbols-outlined text-blue-500', style: 'font-size:' + (isSmall ? '16px' : '20px') }, 'check_circle')
                ) : null
            ]);
        });
    }

    function renderSimpleTableWithGroups(items) {
        let childGroupRows = (!searchActive ? renderChildGroups() : null);
        let hasItems = items && items.length;

        if (!hasItems && !childGroupRows) {
            return m('div', { class: 'list-empty' }, 'No results');
        }

        let type = hasItems ? items[0][am7model.jsonModelKey] : null;
        let columns = type ? getColumns(type) : ['name'];
        let ent = pagination.entity;

        let showThumb = hasItems && items.some(function(item) {
            let ct = item.contentType || '';
            return ct.match(/^image/) ||
                (item.profile && item.profile.portrait && item.profile.portrait.contentType);
        });

        let rows = hasItems ? items.map((item) => {
            let state = pagination.state(item);
            let checked = !!(state && state.checked);
            let dndAttrs = {};
            if (!pickerMode && page.components.dnd) {
                dndAttrs.draggable = "true";
                dndAttrs.ondragstart = page.components.dnd.dragStartHandler(item);
            }
            return m('tr', Object.assign({
                class: 'list-tr' + (checked && !pickerMode ? ' list-tr-selected' : '') + (pickerMode ? ' cursor-pointer hover:bg-blue-50 dark:hover:bg-blue-900/30' : ''),
                onclick: pickerMode
                    ? () => { if (pickerHandler) pickerHandler(item); }
                    : () => selectResult(item),
                ondblclick: pickerMode ? undefined : () => navigateDown(item),
                oncontextmenu: pickerMode ? undefined : (e) => showListContextMenu(e, item)
            }, dndAttrs), [
                pickerMode ? null : m('td', { class: 'list-td list-td-check' },
                    m('input', {
                        type: 'checkbox',
                        checked,
                        onclick: (e) => { e.stopPropagation(); selectResult(item); }
                    })
                ),
                showThumb ? m('td', { class: 'list-td', style: 'width:40px' }, getItemThumbnail(item)) : null,
                ...columns.map(c => m('td', { class: 'list-td' }, displayValue(item, c)))
            ]);
        }) : [];

        return m('table', { class: 'list-table' }, [
            m('thead', [
                m('tr', [
                    pickerMode ? null : m('th', { class: 'list-th list-th-check' }, ''),
                    showThumb ? m('th', { class: 'list-th', style: 'width:40px' }, '') : null,
                    ...columns.map(function(c) {
                        let isSorted = ent.sort === c;
                        let arrow = isSorted ? (ent.order === 'ascending' ? 'arrow_upward' : 'arrow_downward') : '';
                        return m('th', {
                            class: 'list-th cursor-pointer select-none hover:bg-gray-100 dark:hover:bg-gray-800',
                            onclick: function() { toggleColumnSort(c); }
                        }, [
                            m('span', c),
                            arrow ? m('span', { class: 'material-symbols-outlined text-xs ml-1', style: 'font-size:14px;vertical-align:middle' }, arrow) : null
                        ]);
                    })
                ])
            ]),
            childGroupRows,
            m('tbody', rows)
        ]);
    }

    function checkScrollPagination(e) {
        if (!infiniteScroll || infiniteLoading) return;
        let pg = pagination.pages();
        let el = e.target;
        let nearBottom = (el.clientHeight + el.scrollTop) > (el.scrollHeight - 50);
        if (pg.currentPage < pg.pageCount && nearBottom) {
            infiniteLoading = true;
            pagination.next();
            setTimeout(function() { infiniteLoading = false; }, 200);
        }
    }

    function toggleInfiniteScroll() {
        infiniteScroll = !infiniteScroll;
        if (infiniteScroll) {
            pagination.new();
            updatePagination(lastVnode);
        }
        m.redraw();
    }

    function getItemThumbnail(item, size) {
        let url = null;
        let ct = item.contentType || '';
        if (ct.match(/^image/)) {
            url = am7client.mediaDataPath(item, true, size);
        } else if (item.profile && item.profile.portrait && item.profile.portrait.contentType) {
            url = am7client.mediaDataPath(item.profile.portrait, true, size);
        }
        if (!url) return null;
        return m('img', {
            src: url,
            class: 'w-8 h-8 rounded object-cover',
            onerror: function(e) { e.target.style.display = 'none'; }
        });
    }

    function displayValue(item, field) {
        let v = item[field];
        if (v == null || v === undefined) return '';
        if (typeof v === 'object') return JSON.stringify(v);
        return String(v);
    }

    // ------------------------------------------------------------------
    //  Gallery rendering (gridMode === 3, no carousel overlay)
    // ------------------------------------------------------------------

    function galleryNav(delta, items) {
        let pg = pagination.pages();
        let idx = (pg.currentItem || 0) + delta;
        if (idx >= 0 && idx < items.length) {
            pg.currentItem = idx;
            m.redraw();
        } else if (idx >= items.length && pg.currentPage < pg.pageCount) {
            pagination.next(pickerMode);
        } else if (idx < 0 && pg.currentPage > 1) {
            wentBack = true;
            pagination.prev(pickerMode);
        }
    }

    function renderGallery(items) {
        if (!items || !items.length) return m('div', { class: 'p-4 text-gray-400' }, 'No results');

        let pg = pagination.pages();
        let currentIdx = pg.currentItem || 0;
        if (currentIdx < 0 || currentIdx >= items.length) currentIdx = 0;
        let item = items[currentIdx];
        if (!item) return m('div', { class: 'p-4 text-gray-400' }, 'No results');

        let content = renderMediaPreview(item);

        return m('div', {
            class: 'flex flex-col h-full',
            tabindex: 0,
            onkeydown: function(e) {
                if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); galleryNav(-1, items); }
                else if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); galleryNav(1, items); }
                else if (e.key === 'Escape') { toggleGrid(); }
            },
            oncreate: function(vnode) { vnode.dom.focus(); }
        }, [
            m('div', { class: 'flex items-center justify-between px-3 py-2 border-b border-gray-200 dark:border-gray-700' }, [
                m('button', {
                    class: 'p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700' + (currentIdx <= 0 && pg.currentPage <= 1 ? ' opacity-30' : ''),
                    onclick: function() { galleryNav(-1, items); },
                    'aria-label': 'Previous item'
                }, m('span', { class: 'material-symbols-outlined', style: 'font-size:20px' }, 'chevron_left')),
                m('span', { class: 'text-sm text-gray-600 dark:text-gray-300' },
                    (currentIdx + 1) + ' / ' + items.length + (item.name ? ' — ' + item.name : '')),
                pickerMode ? m('button', {
                    class: 'px-3 py-1 rounded bg-blue-500 text-white text-sm hover:bg-blue-600',
                    onclick: function() { if (pickerHandler && item) pickerHandler(item); }
                }, 'Select') : null,
                m('button', {
                    class: 'p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700' + (currentIdx >= items.length - 1 && pg.currentPage >= pg.pageCount ? ' opacity-30' : ''),
                    onclick: function() { galleryNav(1, items); },
                    'aria-label': 'Next item'
                }, m('span', { class: 'material-symbols-outlined', style: 'font-size:20px' }, 'chevron_right'))
            ]),
            info ? m('div', { class: 'px-3 py-1 text-xs text-gray-500 border-b border-gray-100 dark:border-gray-800' }, [
                item.contentType ? m('span', { class: 'mr-3' }, item.contentType) : null,
                item.description ? m('span', item.description) : null
            ]) : null,
            m('div', { class: 'flex-1 overflow-hidden p-2 flex justify-center items-center' }, content)
        ]);
    }

    // ------------------------------------------------------------------
    //  Carousel rendering (Fix C) — full-screen overlay, ported from Ux7
    // ------------------------------------------------------------------

    function renderCarousel(items) {
        let pr = getCurrentResults() || items || [];
        if (!pr.length) return m('div', { class: 'p-4 text-gray-400' }, 'No items');

        let pg = pagination.pages();
        let currentIdx = pg.currentItem;
        if (currentIdx == null || currentIdx < 0 || currentIdx >= pr.length) currentIdx = 0;
        let item = pr[currentIdx];
        if (!item) return m('div', { class: 'p-4 text-gray-400' }, 'No items');

        let content = renderMediaPreview(item);

        // Carousel controls (ported from Ux7 decorator.js carouselItem)
        let controls = [
            m('span', { onclick: function() { toggleCarouselFull(); }, class: 'carousel-full' },
                m('span', { class: 'material-symbols-outlined' }, fullMode ? 'close_fullscreen' : 'open_in_new')),
            m('span', { onclick: function() { toggleCarouselMax(); }, class: 'carousel-max' },
                m('span', { class: 'material-symbols-outlined' }, maxMode ? 'photo_size_select_small' : 'aspect_ratio')),
            m('span', { onclick: toggleInfo, class: 'carousel-info' },
                m('span', { class: 'material-symbols-outlined' }, 'info')),
            m('span', { onclick: function() { editItem(item); }, class: 'carousel-edit' },
                m('span', { class: 'material-symbols-outlined' }, 'edit')),
            m('span', { onclick: toggleCarousel, class: 'carousel-exit' },
                m('span', { class: 'material-symbols-outlined' }, 'close')),
            m('span', { onclick: function() { moveCarousel(-1); }, class: 'carousel-prev' },
                m('span', { class: 'material-symbols-outlined' }, 'arrow_back')),
            m('span', { onclick: function() { moveCarousel(1); }, class: 'carousel-next' },
                m('span', { class: 'material-symbols-outlined' }, 'arrow_forward'))
        ];

        // Indicators (ported from Ux7 decorator.js displayIndicators)
        let indicators = pr.map(function(p, i) {
            let cls = 'material-symbols-outlined carousel-bullet';
            let ico = 'radio_button_unchecked';
            if (i === currentIdx) { cls += ' carousel-bullet-active'; ico = 'radio_button_checked'; }
            return m('li', { onclick: function() { moveCarouselTo(i); }, class: 'carousel-indicator' },
                m('span', { class: cls }, ico));
        });

        let indicatorBar = m('ul', { class: 'carousel-indicators' }, [
            m('li', { onclick: function() { pagination.prev(pickerMode); }, class: 'carousel-indicator' },
                m('span', { class: 'material-symbols-outlined carousel-bullet' }, 'arrow_back')),
            indicators,
            m('li', { onclick: function() { pagination.next(pickerMode); }, class: 'carousel-indicator' },
                m('span', { class: 'material-symbols-outlined carousel-bullet' }, 'arrow_forward'))
        ]);

        let carouselContent = m('div', { class: 'carousel', 'data-testid': 'carousel' }, [
            m('div', { class: 'carousel-inner' }, [
                m('div', {
                    class: 'carousel-item' + (maxMode ? ' carousel-item-max' : ''),
                    style: 'display:flex;justify-content:center;align-items:center;width:100%;height:100%'
                }, content),
                info ? m('div', { class: 'carousel-nav-text px-3 py-1 text-sm text-gray-300', 'data-testid': 'carousel-index' },
                    (currentIdx + 1) + ' / ' + pr.length + (item.name ? ' — ' + item.name : '')) : null,
                controls,
                indicatorBar
            ])
        ]);

        return carouselContent;
    }

    // ------------------------------------------------------------------
    //  Media preview rendering (Fix C) — image, audio, video, PDF
    // ------------------------------------------------------------------

    function renderMediaPreview(item) {
        let ct = item.contentType || '';
        let content;

        if (ct.match(/^image\//i)) {
            let url = am7client.mediaDataPath(item, false);
            content = m('img', {
                src: url,
                class: 'max-w-full max-h-full object-contain',
                onerror: function(e) { e.target.alt = 'Failed to load image'; }
            });
        } else if (ct.match(/^audio\//i)) {
            let url = am7client.mediaDataPath(item, false);
            content = m('div', { class: 'flex flex-col items-center gap-4' }, [
                m('span', { class: 'material-symbols-outlined', style: 'font-size:64px;color:#888' }, 'audio_file'),
                m('div', { class: 'text-sm text-gray-500' }, item.name || 'Audio'),
                m('audio', { controls: true, src: url, style: 'width:100%;max-width:400px' })
            ]);
        } else if (ct.match(/^video\//i)) {
            let url = am7client.mediaDataPath(item, false);
            content = m('video', {
                controls: true,
                src: url,
                class: 'max-w-full max-h-full',
                style: 'max-height:80vh'
            });
        } else if (ct.match(/pdf/i)) {
            let url = am7client.mediaDataPath(item, false);
            content = m('iframe', {
                src: url,
                style: 'width:100%;height:100%;min-height:60vh;border:none'
            });
        } else if (item.profile && item.profile.portrait && item.profile.portrait.contentType) {
            // Character with portrait
            let url = am7client.mediaDataPath(item.profile.portrait, false);
            content = m('img', {
                src: url,
                class: 'max-w-full max-h-full object-contain'
            });
        } else {
            // Fallback: thumbnail or icon
            let thumb = getItemThumbnail(item, '512x512');
            content = thumb
                ? m('img', { src: thumb.attrs.src, class: 'max-w-full max-h-full object-contain' })
                : m('div', { class: 'flex flex-col items-center gap-2 text-gray-400' }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:64px' }, 'description'),
                    m('div', { class: 'text-sm' }, item.name || 'No preview available')
                ]);
        }

        return content;
    }

    // Fix H: adaptive record count based on grid mode
    function getRecordCountForMode(mode) {
        switch (mode) {
            case 0: return defaultRecordCount;       // table: 10
            case 1: return defaultIconRecordCount;   // small grid: 40
            case 2: return 20;                       // large grid: 20
            case 3: return defaultRecordCount;       // gallery/carousel: 10
            default: return defaultRecordCount;
        }
    }

    function toggleGrid() {
        gridMode++;
        if (gridMode > 3) gridMode = 0;
        carousel = false; // Exit carousel when switching modes

        // Fix H: adaptive record count
        let rc = getRecordCountForMode(gridMode);
        let pg = pagination.pages();
        pg.currentItem = 0;
        pagination.new();
        initParams(lastVnode);
        // Override record count in update
        if (lastVnode) {
            let vnode = Object.assign({}, lastVnode);
            vnode.attrs = Object.assign({}, lastVnode.attrs, { recordCount: rc });
            updatePagination(vnode);
        } else {
            updatePagination(lastVnode);
        }
        m.redraw();
    }

    function toggleFullMode() {
        fullMode = !fullMode;
        m.redraw();
    }

    function toggleCarouselFull() {
        fullMode = !fullMode;
        m.redraw();
    }

    function toggleCarouselMax() {
        maxMode = !maxMode;
        m.redraw();
    }

    function toggleMaxMode() {
        maxMode = !maxMode;
        m.redraw();
    }

    // ------------------------------------------------------------------
    //  Batch operations
    // ------------------------------------------------------------------

    async function bulkApplyTags() {
        let selected = getSelected();
        if (!selected.length) return;
        let total = selected.length;
        page.toast('info', 'Applying tags to ' + total + ' items...');
        let results = await Promise.allSettled(
            selected.map(function(item) {
                return am7client.applyImageTags(item.objectId);
            })
        );
        let failed = results.filter(function(r) { return r.status === 'rejected'; }).length;
        if (failed > 0) {
            page.toast('warn', 'Tags applied: ' + (total - failed) + '/' + total + ' (' + failed + ' failed)');
        } else {
            page.toast('success', 'Tags applied to ' + total + ' items');
        }
    }

    function selectAll() {
        let pg = pagination.pages();
        let items = pg.pageResults[pg.currentPage];
        if (!items) return;
        let allChecked = items.every(function(item) {
            let state = pg.pageState[item.objectId];
            return state && state.checked;
        });
        items.forEach(function(item) {
            let state = pagination.state(item);
            state.checked = !allChecked;
        });
        m.redraw();
    }

    // ------------------------------------------------------------------
    //  Filter
    // ------------------------------------------------------------------

    function doFilter(value) {
        navFilter = value && value.length ? value : null;
        pagination.new();
        initParams(lastVnode);
        updatePagination(lastVnode);
        m.redraw();
    }

    // ------------------------------------------------------------------
    //  Keyboard navigation (ported from Ux7 navListKey)
    // ------------------------------------------------------------------

    function navListKey(e) {
        // Don't capture keys when typing in inputs
        if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.tagName === 'SELECT')) return;

        wentBack = false;
        switch (e.keyCode) {
            case 37: // ArrowLeft
                if (!carousel || e.shiftKey) {
                    wentBack = true;
                    pagination.prev(pickerMode);
                } else {
                    moveCarousel(-1);
                }
                break;
            case 39: // ArrowRight
                if (!carousel || e.shiftKey) {
                    pagination.next(pickerMode);
                } else {
                    moveCarousel(1);
                }
                break;
            case 27: // Escape
                if (carousel) toggleCarousel();
                else if (gridMode > 0) {
                    gridMode = 0;
                    carousel = false;
                    pagination.new();
                    initParams(lastVnode);
                    updatePagination(lastVnode);
                    m.redraw();
                }
                else if (fullMode) toggleFullMode();
                break;
        }
    }

    // ------------------------------------------------------------------
    //  Toolbar (Fix D, E, F integrated)
    // ------------------------------------------------------------------

    function toolbar(type) {
        let hasSelection = getSelectedIndices().length > 0;
        let isGroupNav = containerMode || type === 'auth.group' || (modType && am7model.isParent(modType));
        let pg = pagination.pages();

        let buttons = [];

        // ── CRUD buttons (normal mode only) ──
        if (!pickerMode) {
            if (!modType || !modType.systemNew) {
                buttons.push(iconBtn('button', 'add', '', addNew, 'Add new item'));
            }
            buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'file_open', '', openSelected, 'Open in carousel'));
            buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'edit', '', editSelected, 'Edit selected'));
            let isBucket = pg.containerSubType && pg.containerSubType.match(/^(bucket|account|person)$/gi);
            buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), isBucket ? 'playlist_remove' : 'delete', '', deleteSelected, 'Delete selected'));
        }

        // ── Navigation (both modes) ──
        if (isGroupNav || pickerMode) {
            buttons.push(iconBtn('button', 'north_west', '', navigateUp, 'Navigate up'));
            if (!pickerMode) {
                buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'south_east', '', () => navigateDown(), 'Navigate into'));
            }
        }

        // ── Carousel toggle (non-group, non-picker) ──
        if (!pickerMode && !isGroupNav) {
            buttons.push(iconBtn('button' + (carousel ? ' active' : ''), 'view_carousel', '', toggleCarousel, 'Toggle carousel'));
        }

        // ── Grid/gallery toggle (both modes) ──
        let gridIcons = ['view_list', 'grid_view', 'apps', 'view_carousel'];
        let gridLabels = ['Table view', 'Small icons', 'Large icons', 'Gallery view'];
        buttons.push(iconBtn('button' + (gridMode > 0 ? ' active' : ''), gridIcons[gridMode] || 'view_list', '', toggleGrid, gridLabels[gridMode] || 'Toggle view'));

        // Fix D: container mode toggle — only show for models with group=true
        if (!pickerMode && modType && modType.group && !containerMode && type !== 'auth.group') {
            buttons.push(iconBtn('button', 'group_work', '', toggleContainer, 'Toggle container mode'));
        } else if (!pickerMode && containerMode) {
            buttons.push(iconBtn('button active', 'group_work', '', toggleContainer, 'Toggle container mode'));
        }

        // Fix E: info toggle
        buttons.push(iconBtn('button' + (info ? ' active' : ''), 'info', '', toggleInfo, 'Toggle info'));

        // Fix F: system list toggle — for applicable types
        if (!pickerMode) {
            let rs = page.context().roles;
            if (rs && (rs.accountAdmin || (rs.roleReader && type && type.match(/^auth\.role$/gi)) || (rs.permissionReader && type && type.match(/^auth\.permission$/gi)))) {
                buttons.push(iconBtn('button' + (systemList ? ' active' : ''), 'admin_panel_settings', '', listSystemType, 'System list'));
            } else if (am7model.system && am7model.system.library && am7model.system.library[baseListType || type]) {
                let libActive = pg.container && pg.container.path && pg.container.path.match(/^\/Library/gi);
                buttons.push(iconBtn('button' + (libActive ? ' active' : ''), 'admin_panel_settings', '', async function() {
                    let libPath = am7model.system.library[baseListType || type];
                    let grp = await page.findObject("auth.group", "data", libPath);
                    if (grp) {
                        m.route.set('/list/' + (baseListType || type) + '/' + grp.objectId, { key: Date.now() });
                    }
                }, 'System library'));
            }
        }

        // ── Type-specific action buttons (normal mode only) ──
        if (!pickerMode && type === 'olio.charPerson' && am7model.forms.commands && am7model.forms.commands.characterWizard) {
            buttons.push(iconBtn('button', 'steppers', '', am7model.forms.commands.characterWizard, 'Character wizard'));
        }

        // ── Favorites (both modes) ──
        if (pickerMode) {
            if (pickerFavoritesContainerId) {
                buttons.push(iconBtn(
                    'button' + (pickerActiveSource === 'favorites' ? ' active' : ''),
                    'favorite', '', function() {
                        if (pickerActiveSource === 'favorites') return;
                        pickerActiveSource = 'favorites';
                        pickerNavigateTo(pickerFavoritesContainerId);
                    },
                    'Favorites'
                ));
            } else {
                buttons.push(iconBtn('button', 'favorite', '', async function() {
                    let fav = await page.favorites();
                    if (fav) {
                        pickerFavoritesContainerId = fav.objectId;
                        pickerActiveSource = 'favorites';
                        pickerNavigateTo(fav.objectId);
                    }
                }, 'Favorites'));
            }
        } else {
            let isFavActive = pg.container && pg.container.name && pg.container.name.match(/favorites/gi);
            buttons.push(iconBtn('button' + (isFavActive ? ' active' : ''), 'favorite', '', async function() {
                let fav = await page.favorites();
                if (fav) {
                    m.route.set('/list/' + (baseListType || type) + '/' + fav.objectId, { key: Date.now() });
                }
            }, 'Favorites'));
        }

        // ── Picker source buttons (picker mode only) ──
        if (pickerMode) {
            if (pickerLibraryContainerId) {
                buttons.push(iconBtn(
                    'button' + (pickerActiveSource === 'library' ? ' active' : ''),
                    'admin_panel_settings', '', function() {
                        pickerActiveSource = 'library';
                        pickerNavigateTo(pickerLibraryContainerId);
                    },
                    'System library'
                ));
            }
            if (pickerUserContainerId) {
                buttons.push(iconBtn(
                    'button' + (pickerActiveSource === 'home' ? ' active' : ''),
                    'home', '', function() {
                        if (pickerActiveSource === 'home') return;
                        pickerActiveSource = 'home';
                        pickerNavigateTo(pickerUserContainerId);
                    },
                    'My items'
                ));
            }
        }

        // ── Full mode toggle ──
        if (!pickerMode) {
            buttons.push(iconBtn('button' + (fullMode ? ' active' : ''),
                fullMode ? 'close_fullscreen' : 'open_in_new', '', toggleFullMode, fullMode ? 'Exit full mode' : 'Full mode'));
        }

        // ── Normal mode extras ──
        if (!pickerMode) {
            if (gridMode === 0 && !carousel) {
                buttons.push(iconBtn('button' + (columnPickerOpen ? ' active' : ''), 'view_column', '', function() {
                    columnPickerOpen = !columnPickerOpen;
                    m.redraw();
                }, 'Customize columns'));
            }
            if (type && type === 'data.data') {
                buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'label', '', bulkApplyTags, 'Apply tags to selected'));
            }
            buttons.push(iconBtn('button', 'select_all', '', selectAll, 'Select all'));
            buttons.push(iconBtn('button' + (infiniteScroll ? ' active' : ''),
                'all_inclusive', '', toggleInfiniteScroll, infiniteScroll ? 'Disable infinite scroll' : 'Enable infinite scroll'));
        }

        return buttons;
    }

    function renderColumnPicker(type) {
        let resultType = baseListType || type;
        let mod = am7model.getModel(resultType);
        if (!mod) return null;
        let allFields = am7model.getModelFields(mod).map(f => f.name).filter(f => !f.match(/^(id|ownerId|organizationId|organizationPath)$/));
        let currentCols = getColumns(resultType);
        return m('div', { class: 'px-3 py-2 bg-gray-50 dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700 flex flex-wrap gap-1 items-center' }, [
            m('span', { class: 'text-xs text-gray-500 mr-1' }, 'Columns:'),
            ...allFields.map(function(f) {
                let active = currentCols.includes(f);
                return m('button', {
                    class: 'px-1.5 py-0.5 rounded text-xs ' + (active
                        ? 'bg-blue-500 text-white'
                        : 'bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-400'),
                    onclick: function() {
                        let cols = currentCols.slice();
                        if (active) {
                            cols = cols.filter(c => c !== f);
                            if (!cols.length) cols = ['name'];
                        } else {
                            cols.push(f);
                        }
                        saveCustomColumns(resultType, cols);
                        pagination.new();
                        initParams(lastVnode);
                        updatePagination(lastVnode);
                        m.redraw();
                    }
                }, f);
            }),
            m('button', {
                class: 'px-1.5 py-0.5 rounded text-xs bg-gray-300 dark:bg-gray-600 text-gray-700 dark:text-gray-300 ml-2',
                onclick: function() {
                    columnConfigCache[resultType] = null;
                    localStorage.removeItem(getColumnConfigKey(resultType));
                    pagination.new();
                    initParams(lastVnode);
                    updatePagination(lastVnode);
                    m.redraw();
                }
            }, 'Reset')
        ]);
    }

    function filterInput() {
        let pg = pagination.pages();
        let placeholder = (pg.container && pg.container.path) ? pg.container.path : 'Filter...';

        return m('input', {
            type: 'text',
            class: 'text-field',
            placeholder,
            'aria-label': 'Filter items',
            onkeydown: (e) => {
                if (e.which === 13) {
                    doFilter(e.target.value);
                }
            }
        });
    }

    function searchInput() {
        return m('div', { class: 'flex items-center gap-1' }, [
            m('input', {
                type: 'text',
                class: 'text-field',
                placeholder: 'Search...',
                'aria-label': 'Search items',
                value: searchQuery,
                oninput: function (e) { doSearch(e.target.value); },
                onkeydown: function (e) {
                    if (e.key === 'Escape') { clearSearch(); e.target.value = ''; }
                    if (e.key === 'Enter') {
                        if (searchTimer) clearTimeout(searchTimer);
                        doSearch(e.target.value);
                    }
                }
            }),
            searchActive ? m('button', {
                class: 'button',
                onclick: function () { clearSearch(); },
                title: 'Clear search',
                'aria-label': 'Clear search'
            }, m('span', { class: 'material-symbols-outlined material-icons-24', 'aria-hidden': 'true' }, 'close')) : null
        ]);
    }

    function pageButtons() {
        let pg = pagination.pages();
        if (!pg.pageCount || pg.pageCount <= 1) return m('span');
        return m('nav', { class: 'result-nav', 'aria-label': 'Pagination' }, pagination.pageButtons());
    }

    // ------------------------------------------------------------------
    //  Lifecycle helpers
    // ------------------------------------------------------------------

    let lastVnode = null;

    function initParams(vnode) {
        if (!vnode) return;

        if (pickerMode) {
            listType = pickerType || 'data';
            modType = am7model.getModel(listType);
            baseListType = listType;
            listContainerId = pickerContainerId;
            navigateByParent = false;
            return;
        }

        listType = vnode.attrs.type || m.route.param('type') || 'data';
        modType = am7model.getModel(listType);

        if (!modType) {
            console.error('list: missing model for type ' + listType);
        } else {
            navigateByParent = vnode.attrs.navigateByParent || (m.route.get().match(/^\/plist/) != null);
            if (navigateByParent && !am7model.isParent(modType)) {
                console.warn('list: type is not clustered by parent');
                navigateByParent = false;
            }
        }

        baseListType = listType;
        if (modType && modType.group && containerMode && !listType.match(/^auth\.group$/gi)) {
            listType = 'auth.group';
        }

        listContainerId = navContainerId || vnode.attrs.objectId || m.route.param('objectId');
        navContainerId = null;
    }

    function updatePagination(vnode) {
        if (!vnode) return;

        let listFilter = navFilter || vnode.attrs.filter || m.route.param('filter');
        if (listFilter) listFilter = decodeURI(listFilter);

        let startRecord = vnode.attrs.startRecord || m.route.param('startRecord') || 0;
        let recordCount = vnode.attrs.recordCount || m.route.param('recordCount') || getRecordCountForMode(gridMode);

        pagination.update(listType, listContainerId, navigateByParent, listFilter, startRecord, recordCount, systemList);
    }

    // ------------------------------------------------------------------
    //  View -- Mithril component
    // ------------------------------------------------------------------

    listPage.view = {
        oninit: function (vnode) {
            lastVnode = vnode;
            initParams(vnode);
            document.documentElement.addEventListener('keydown', navListKey);
        },

        oncreate: function (vnode) {
            lastVnode = vnode;
            updatePagination(vnode);
            if (listContainerId) {
                loadChildGroups(listContainerId);
                loadGroupPath(listContainerId);
            }
            let mod = am7model.getModel(listType);
            if (mod && panel.trackRecent) {
                panel.trackRecent(mod.label || listType, m.route.get(), mod.icon || 'list');
            }
        },

        onupdate: function (vnode) {
            lastVnode = vnode;
            let newType = vnode.attrs.type || m.route.param('type');
            let newId = vnode.attrs.objectId || m.route.param('objectId');
            let newStart = vnode.attrs.startRecord || m.route.param('startRecord') || 0;
            let newFilter = vnode.attrs.filter || m.route.param('filter') || '';
            let pg = pagination.pages();
            let curStart = pg.startRecord;
            let curFilter = pg.filter || '';
            let routeChanged = newType !== listType || newId !== listContainerId;
            let paginationChanged = parseInt(newStart) !== curStart || newFilter !== curFilter;
            if (routeChanged || paginationChanged) {
                let oldContainerId = listContainerId;
                initParams(vnode);
                updatePagination(vnode);
                if (listContainerId && listContainerId !== oldContainerId) {
                    childGroups = null;
                    groupPath = null;
                    loadChildGroups(listContainerId);
                    loadGroupPath(listContainerId);
                }
            }
        },

        onremove: function () {
            navFilter = null;
            searchQuery = '';
            searchActive = false;
            carousel = false;
            if (searchTimer) clearTimeout(searchTimer);
            childGroups = null;
            groupPath = null;
            document.documentElement.removeEventListener('keydown', navListKey);
            pagination.stop();
        },

        view: function (vnode) {
            return listPage.renderContent(vnode);
        }
    };

    // Inner content renderer
    listPage.renderContent = function (vnode) {
        let type = (vnode && vnode.attrs && vnode.attrs.type) || m.route.param('type') || listType;
        let containerClass = fullMode
            ? 'fixed inset-0 z-50 bg-white dark:bg-gray-900 flex flex-col overflow-hidden'
            : 'flex-1 flex flex-col overflow-hidden';
        return m('div', { class: containerClass, style: fullMode ? '' : 'flex:1;display:flex;flex-direction:column;overflow:hidden' }, [
            // Group breadcrumb
            !carousel && listContainerId ? renderGroupBreadcrumb() : null,
            // Toolbar row (hidden when carousel is in full mode)
            (!carousel || !fullMode) ? m('div', { class: 'result-nav-outer' }, [
                m('div', { class: 'result-nav-inner' }, [
                    m('div', { class: 'result-nav tab-container' }, [
                        ...toolbar(type),
                        !carousel ? filterInput() : null,
                        !carousel ? searchInput() : null
                    ]),
                    !carousel ? pageButtons() : null
                ])
            ]) : null,
            // Column picker panel
            !carousel && columnPickerOpen ? renderColumnPicker(type) : null,
            // List body
            m('div', {
                style: 'flex:1;overflow:auto;padding:' + (carousel ? '0' : '8px'),
                onscroll: infiniteScroll && !carousel ? checkScrollPagination : undefined
            }, [
                displayList(),
                infiniteScroll && infiniteLoading && !carousel ? m('div', { class: 'text-center py-2 text-sm text-gray-400' }, 'Loading more...') : null
            ])
        ]);
    };

    // Expose pagination
    listPage.pagination = function () {
        return pagination;
    };

    // Expose test helpers
    listPage._testHelpers = function () {
        return {
            doSearch, clearSearch, navigateToChildGroup, renderGroupBreadcrumb, renderChildGroups,
            openItem, closeSelected, toggleCarousel, moveCarousel, moveCarouselTo,
            toggleContainer, toggleInfo, listSystemType, getColumns
        };
    };

    // ------------------------------------------------------------------
    //  Picker mode
    // ------------------------------------------------------------------

    listPage.openForPicker = function(opts) {
        pickerMode = true;
        pickerHandler = opts.onSelect;
        pickerType = opts.type;
        pickerContainerId = opts.containerId;
        pickerUserContainerId = opts.userContainerId || null;
        pickerLibraryContainerId = opts.libraryContainerId || null;
        pickerFavoritesContainerId = opts.favoritesContainerId || null;
        if (opts.containerId === opts.libraryContainerId) pickerActiveSource = 'library';
        else if (opts.containerId === opts.favoritesContainerId) pickerActiveSource = 'favorites';
        else pickerActiveSource = 'home';

        gridMode = 0;
        fullMode = false;
        carousel = false;
        searchQuery = '';
        searchActive = false;
        navFilter = null;
        childGroups = null;
        groupPath = null;

        pagination.new();
        pagination.setEmbeddedMode(true);

        let fakeVnode = { attrs: { type: opts.type, objectId: opts.containerId } };
        lastVnode = fakeVnode;
        initParams(fakeVnode);
        updatePagination(fakeVnode);

        if (listContainerId) {
            loadChildGroups(listContainerId);
            loadGroupPath(listContainerId);
        }
    };

    listPage.closePickerMode = function() {
        pickerMode = false;
        pickerHandler = null;
        pickerType = null;
        pickerContainerId = null;
        pickerUserContainerId = null;
        pickerLibraryContainerId = null;
        pickerFavoritesContainerId = null;
        pickerActiveSource = 'home';
        carousel = false;
        pagination.setEmbeddedMode(false);
        pagination.stop();
        childGroups = null;
        groupPath = null;
        searchQuery = '';
        searchActive = false;
        navFilter = null;
    };

    listPage.isPickerMode = function() {
        return pickerMode;
    };

    // Expose closeView for external callers (ported from Ux7)
    listPage.closeView = function() {
        closeSelected();
    };

    return listPage;
}

// ---------------------------------------------------------------------------
//  Exports
// ---------------------------------------------------------------------------

export { newListControl };
export default newListControl;
