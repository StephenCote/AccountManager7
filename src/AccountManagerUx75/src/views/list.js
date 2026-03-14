import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client, uwm } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { newPaginationControl } from '../components/pagination.js';
import { panel } from '../components/panel.js';
// Navigation is handled by router's pageLayout wrapper

// ---------------------------------------------------------------------------
//  newListControl  --  factory that returns a list-page controller + view
// ---------------------------------------------------------------------------

function newListControl() {

    const listPage = {};

    // --- State variables ---

    let listType;
    let baseListType;
    let listContainerId;
    let containerMode = false;
    let gridMode = 0;           // 0 = table, 1 = small grid, 2 = large grid, 3 = gallery
    let fullMode = false;
    let maxMode = false;
    let embeddedMode = false;
    let pickerMode = false;
    let modType;
    let navigateByParent = false;
    let systemList = false;
    let navContainerId = null;
    let navFilter = null;
    let defaultRecordCount = 10;
    let defaultIconRecordCount = 40;
    let infiniteScroll = false;
    let infiniteLoading = false;

    // Picker mode state — when the list is embedded inside the ObjectPicker modal
    let pickerHandler = null;      // onSelect callback
    let pickerType = null;         // model type being picked
    let pickerContainerId = null;  // current container objectId
    let pickerUserContainerId = null;     // user's own path (~/Colors)
    let pickerLibraryContainerId = null;  // shared library (/Library/Colors)
    let pickerFavoritesContainerId = null; // user's Favorites bucket
    let pickerActiveSource = 'home';      // 'home' | 'favorites' | 'library'

    // 11b-1: Group navigation state
    let childGroups = null;
    let childGroupsLoading = false;
    let groupPath = null;          // breadcrumb path segments [{name, objectId}]

    // 11b-2: Search state
    let searchQuery = '';
    let searchTimer = null;
    let searchActive = false;

    let pagination = newPaginationControl();

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

    function showListContextMenu(e, item) {
        let cm = page.components.contextMenu;
        if (!cm) return;
        cm.show(e, [
            { label: 'Open', icon: 'open_in_new', action: function () { navigateDown(item); } },
            { label: 'Edit', icon: 'edit', action: function () { editItem(item); } },
            { divider: true },
            { label: 'Delete', icon: 'delete', action: function () {
                selectResult(item);
                deleteSelected();
            }}
        ]);
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
            // In Ux75, page-level navigateToPath is not yet ported; fall back to route set
            m.route.set('/list/' + (containerMode ? baseListType : type) + '/' + encodeURIComponent(parentPath), { key: Date.now() });
        } else {
            console.warn('navigateUp: unhandled type ' + type);
        }
    }

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
        let ltype = obj[am7model.jsonModelKey] || baseListType;
        m.route.set('/' + (byParent ? 'p' : '') + 'list/' + (containerMode ? baseListType : ltype) + '/' + obj.objectId, { key: Date.now() });
    }

    // ------------------------------------------------------------------
    //  11b-1: Group navigation — child groups + breadcrumb path
    // ------------------------------------------------------------------

    function loadChildGroups(containerId) {
        if (!containerId || childGroupsLoading) return;
        childGroupsLoading = true;
        childGroups = null;

        // Search for child groups whose parentId matches this container
        let q = am7client.newQuery('auth.group');
        q.entity.request = ['id', 'objectId', 'name', 'type', 'path', 'parentId'];
        q.field('organizationId', page.user.organizationId);

        // Resolve containerId (objectId) to numeric id for parentId filter
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

    function loadGroupPath(containerId) {
        if (!containerId) { groupPath = null; return; }
        groupPath = null;

        // Fetch the container to get its path, then build breadcrumb segments
        let cq = am7client.newQuery('auth.group');
        cq.entity.request = ['id', 'objectId', 'name', 'path'];
        cq.field('objectId', containerId);
        page.search(cq).then(function (qr) {
            if (!qr || !qr.results || !qr.results.length) return;
            let container = qr.results[0];
            let fullPath = container.path;
            if (!fullPath) return;

            // Build path segments from the full path
            let segments = fullPath.split('/').filter(function (s) { return s.length > 0; });
            let pathPromises = [];
            let accumulated = '';

            for (let i = 0; i < segments.length; i++) {
                accumulated += '/' + segments[i];
                let segPath = accumulated;
                let segName = segments[i];
                pathPromises.push({ name: segName, path: segPath });
            }

            // Resolve each path segment to get objectId for navigation
            groupPath = pathPromises.map(function (seg) {
                return { name: seg.name, path: seg.path, objectId: null };
            });
            // Add current container name at the end
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
        });
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
        if (gridMode >= 1) {
            // Grid/gallery: render as folder cards
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
        // Table mode: render as folder rows
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
        return m('div', { class: 'flex items-center gap-1 px-2 py-1 text-sm text-gray-500 dark:text-gray-400 border-b border-gray-100 dark:border-gray-800' },
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
    //  11b-2: Search — debounced server-side search
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
        // Store current list URL so object view cancel can return here with container context
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

    // ------------------------------------------------------------------
    //  Display
    // ------------------------------------------------------------------

    function displayList() {
        let pg = pagination.pages();
        let items;

        if (infiniteScroll) {
            // Concatenate all loaded pages
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
                m('div', { class: isSmall ? 'p-1' : 'p-2' }, [
                    m('div', { class: 'text-xs font-medium text-gray-700 dark:text-gray-300 truncate' }, item.name || '(unnamed)'),
                    !isSmall && item.description ? m('div', { class: 'text-xs text-gray-400 truncate mt-0.5' }, item.description) : null
                ]),
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
        let mod = type ? am7model.getModel(type) : null;
        let columns = ['name'];
        if (mod) {
            let allFields = am7model.getModelFields(mod);
            let fieldNames = allFields.map(f => f.name);
            if (fieldNames.includes('description')) columns.push('description');
            if (fieldNames.includes('groupPath')) columns.push('groupPath');
            if (fieldNames.includes('type') && !columns.includes('type')) columns.push('type');
        }

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
                    ...columns.map(c => m('th', { class: 'list-th' }, c))
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
            // pagination.next() triggers redraw; reset loading flag after
            setTimeout(function() { infiniteLoading = false; }, 200);
        }
    }

    function toggleInfiniteScroll() {
        infiniteScroll = !infiniteScroll;
        if (infiniteScroll) {
            // Switch to a larger record count for smoother scrolling
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
    //  Gallery rendering
    // ------------------------------------------------------------------

    function galleryNav(delta, items) {
        let pg = pagination.pages();
        let idx = (pg.currentItem || 0) + delta;
        if (idx >= 0 && idx < items.length) {
            pg.currentItem = idx;
            m.redraw();
        }
    }

    function renderGallery(items) {
        if (!items || !items.length) return m('div', { class: 'p-4 text-gray-400' }, 'No results');

        let pg = pagination.pages();
        let currentIdx = pg.currentItem || 0;
        if (currentIdx < 0 || currentIdx >= items.length) currentIdx = 0;
        let item = items[currentIdx];
        if (!item) return m('div', { class: 'p-4 text-gray-400' }, 'No results');

        // Try to render via objectViewRenderers
        let rendererType = am7view.selectObjectRenderer ? am7view.selectObjectRenderer(item) : null;
        let content;
        if (rendererType && am7view.prepareObjectView) {
            let viewInst = am7view.prepareObjectView(item);
            if (viewInst && am7view.customRenderers && am7view.customRenderers[rendererType]) {
                content = am7view.customRenderers[rendererType](viewInst, 'content', { maxMode: maxMode, active: true });
            }
        }
        if (!content) {
            let thumb = getItemThumbnail(item);
            content = thumb
                ? m('img', { src: thumb.attrs.src, class: 'max-w-full max-h-full object-contain' })
                : m('span', { class: 'text-gray-400' }, 'No preview available');
        }

        return m('div', {
            class: 'flex flex-col h-full',
            tabindex: 0,
            onkeydown: function(e) {
                if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); galleryNav(-1, items); }
                else if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); galleryNav(1, items); }
            },
            oncreate: function(vnode) { vnode.dom.focus(); }
        }, [
            // Gallery nav bar
            m('div', { class: 'flex items-center justify-between px-3 py-2 border-b border-gray-200 dark:border-gray-700' }, [
                m('button', {
                    class: 'p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700' + (currentIdx <= 0 ? ' opacity-30' : ''),
                    onclick: function() { galleryNav(-1, items); },
                    'aria-label': 'Previous item'
                }, m('span', { class: 'material-symbols-outlined', style: 'font-size:20px', 'aria-hidden': 'true' }, 'chevron_left')),
                m('span', { class: 'text-sm text-gray-600 dark:text-gray-300' },
                    (currentIdx + 1) + ' / ' + items.length + (item.name ? ' — ' + item.name : '')),
                pickerMode ? m('button', {
                    class: 'px-3 py-1 rounded bg-blue-500 text-white text-sm hover:bg-blue-600',
                    onclick: function() { if (pickerHandler && item) pickerHandler(item); }
                }, 'Select') : null,
                m('button', {
                    class: 'p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700' + (currentIdx >= items.length - 1 ? ' opacity-30' : ''),
                    onclick: function() { galleryNav(1, items); },
                    'aria-label': 'Next item'
                }, m('span', { class: 'material-symbols-outlined', style: 'font-size:20px', 'aria-hidden': 'true' }, 'chevron_right'))
            ]),
            // Content — centered, fills available space
            m('div', { class: 'flex-1 overflow-hidden p-2 flex justify-center items-center' }, content)
        ]);
    }

    function toggleGrid() {
        gridMode++;
        if (gridMode > 3) gridMode = 0;
        let pg = pagination.pages();
        pg.currentItem = 0;
        pagination.new();
        updatePagination(lastVnode);
        m.redraw();
    }

    function toggleFullMode() {
        fullMode = !fullMode;
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
        // Parallel batch — all requests fire concurrently
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
    //  Toolbar
    // ------------------------------------------------------------------

    function toolbar(type) {
        let hasSelection = getSelectedIndices().length > 0;
        let isGroupNav = containerMode || type === 'auth.group' || (modType && am7model.isParent(modType));

        let buttons = [];

        if (!pickerMode) {
            // Add / Edit / Delete — only in normal mode
            if (!modType || !modType.systemNew) {
                buttons.push(iconBtn('button', 'add', '', addNew, 'Add new item'));
            }
            buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'edit', '', editSelected, 'Edit selected'));
            buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'delete', '', deleteSelected, 'Delete selected'));
        }

        // Navigate up — useful in both normal and picker mode for group browsing
        if (isGroupNav || pickerMode) {
            buttons.push(iconBtn('button', 'north_west', '', navigateUp, 'Navigate up'));
            if (!pickerMode) {
                buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'south_east', '', () => navigateDown(), 'Navigate into'));
            }
        }

        // Grid/gallery toggle: table → small icons → large icons → gallery
        let gridIcons = ['view_list', 'grid_view', 'apps', 'view_carousel'];
        let gridLabels = ['Table view', 'Small icons', 'Large icons', 'Gallery view'];
        buttons.push(iconBtn('button', gridIcons[gridMode] || 'view_list', '', toggleGrid, gridLabels[gridMode] || 'Toggle view'));

        // Picker mode: Home / Favorites / Library navigation buttons
        if (pickerMode) {
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
            if (pickerFavoritesContainerId) {
                buttons.push(iconBtn(
                    'button' + (pickerActiveSource === 'favorites' ? ' active' : ''),
                    'star', '', function() {
                        if (pickerActiveSource === 'favorites') return;
                        pickerActiveSource = 'favorites';
                        pickerNavigateTo(pickerFavoritesContainerId);
                    },
                    'Favorites'
                ));
            }
            if (pickerLibraryContainerId) {
                buttons.push(iconBtn(
                    'button' + (pickerActiveSource === 'library' ? ' active' : ''),
                    'local_library', '', function() {
                        if (pickerActiveSource === 'library') return;
                        pickerActiveSource = 'library';
                        pickerNavigateTo(pickerLibraryContainerId);
                    },
                    'Shared library'
                ));
            }
        }

        if (!pickerMode) {
            // Bulk apply image tags
            buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'label', '', bulkApplyTags, 'Apply tags to selected'));

            // Select all
            buttons.push(iconBtn('button', 'select_all', '', selectAll, 'Select all'));

            // Full mode
            buttons.push(iconBtn('button' + (fullMode ? ' active' : ''),
                fullMode ? 'close_fullscreen' : 'open_in_new', '', toggleFullMode, fullMode ? 'Exit full mode' : 'Full mode'));

            // Infinite scroll toggle
            buttons.push(iconBtn('button' + (infiniteScroll ? ' active' : ''),
                'all_inclusive', '', toggleInfiniteScroll, infiniteScroll ? 'Disable infinite scroll' : 'Enable infinite scroll'));
        }

        return buttons;
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
        let recordCount = vnode.attrs.recordCount || m.route.param('recordCount') || defaultRecordCount;

        pagination.update(listType, listContainerId, navigateByParent, listFilter, startRecord, recordCount, systemList);
    }

    // ------------------------------------------------------------------
    //  View -- Mithril component
    // ------------------------------------------------------------------

    listPage.view = {
        oninit: function (vnode) {
            lastVnode = vnode;
            initParams(vnode);
        },

        oncreate: function (vnode) {
            lastVnode = vnode;
            updatePagination(vnode);
            // Load child groups and path for group navigation (11b-1)
            if (listContainerId) {
                loadChildGroups(listContainerId);
                loadGroupPath(listContainerId);
            }
            // Track this list view as a recent item
            let mod = am7model.getModel(listType);
            if (mod && panel.trackRecent) {
                panel.trackRecent(mod.label || listType, m.route.get(), mod.icon || 'list');
            }
        },

        onupdate: function (vnode) {
            lastVnode = vnode;
            // Re-read route params in case the route changed
            let newType = vnode.attrs.type || m.route.param('type');
            let newId = vnode.attrs.objectId || m.route.param('objectId');
            if (newType !== listType || newId !== listContainerId) {
                let oldContainerId = listContainerId;
                initParams(vnode);
                updatePagination(vnode);
                // Reload child groups and path if container changed (11b-1)
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
            if (searchTimer) clearTimeout(searchTimer);
            childGroups = null;
            groupPath = null;
            pagination.stop();
        },

        view: function (vnode) {
            return listPage.renderContent(vnode);
        }
    };

    // Inner content renderer — called by router's pageLayout wrapper
    listPage.renderContent = function (vnode) {
        let type = (vnode && vnode.attrs && vnode.attrs.type) || m.route.param('type') || listType;
        let containerClass = fullMode
            ? 'fixed inset-0 z-50 bg-white dark:bg-gray-900 flex flex-col overflow-hidden'
            : 'flex-1 flex flex-col overflow-hidden';
        return m('div', { class: containerClass, style: fullMode ? '' : 'flex:1;display:flex;flex-direction:column;overflow:hidden' }, [
            // Group breadcrumb (11b-1)
            listContainerId ? renderGroupBreadcrumb() : null,
            // Toolbar row
            m('div', { class: 'result-nav-outer' }, [
                m('div', { class: 'result-nav-inner' }, [
                    m('div', { class: 'result-nav tab-container' }, [
                        ...toolbar(type),
                        filterInput(),
                        searchInput()
                    ]),
                    pageButtons()
                ])
            ]),
            // List body
            m('div', {
                style: 'flex:1;overflow:auto;padding:8px',
                onscroll: infiniteScroll ? checkScrollPagination : undefined
            }, [
                displayList(),
                infiniteScroll && infiniteLoading ? m('div', { class: 'text-center py-2 text-sm text-gray-400' }, 'Loading more...') : null
            ])
        ]);
    };

    // Expose pagination on the controller for external access
    listPage.pagination = function () {
        return pagination;
    };

    // Expose internal helpers for testing (11b)
    listPage._testHelpers = function () {
        return { doSearch, clearSearch, navigateToChildGroup, renderGroupBreadcrumb, renderChildGroups };
    };

    // ------------------------------------------------------------------
    //  Picker mode — embed list inside ObjectPicker modal
    // ------------------------------------------------------------------

    listPage.openForPicker = function(opts) {
        pickerMode = true;
        pickerHandler = opts.onSelect;
        pickerType = opts.type;
        pickerContainerId = opts.containerId;
        pickerUserContainerId = opts.userContainerId || null;
        pickerLibraryContainerId = opts.libraryContainerId || null;
        pickerFavoritesContainerId = opts.favoritesContainerId || null;
        // Determine which source we're starting at
        if (opts.containerId === opts.libraryContainerId) pickerActiveSource = 'library';
        else if (opts.containerId === opts.favoritesContainerId) pickerActiveSource = 'favorites';
        else pickerActiveSource = 'home';

        gridMode = 0;
        fullMode = false;
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

    return listPage;
}

// ---------------------------------------------------------------------------
//  Exports
// ---------------------------------------------------------------------------

export { newListControl };
export default newListControl;
