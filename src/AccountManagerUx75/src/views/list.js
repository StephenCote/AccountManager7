import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client, uwm } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { newPaginationControl } from '../components/pagination.js';
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
    let gridMode = 0;           // 0 = table, 1 = grid, 2 = carousel
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
    let carousel = false;
    let infiniteScroll = false;
    let infiniteLoading = false;

    let pagination = newPaginationControl();

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    function iconBtn(sClass, sIco, sLabel, fHandler) {
        return m('button', { onclick: fHandler, class: sClass }, [
            sIco ? m('span', { class: 'material-symbols-outlined material-icons-24' }, sIco) : '',
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

    // ------------------------------------------------------------------
    //  Navigation
    // ------------------------------------------------------------------

    function navigateUp() {
        let pg = pagination.pages();
        if (!pg.container) return;

        let type = modType ? (modType.type || listType) : listType;
        if (type === 'auth.group') {
            let parentPath = pg.container.path.substring(0, pg.container.path.lastIndexOf('/'));
            // In Ux75, page-level navigateToPath is not yet ported; fall back to route set
            m.route.set('/list/' + (containerMode ? baseListType : type) + '/' + encodeURIComponent(parentPath), { key: Date.now() });
        } else {
            console.warn('navigateUp: unhandled type ' + type);
        }
    }

    function navigateDown(sel) {
        let idx = getSelectedIndices();
        let type = modType ? (modType.type || listType) : listType;
        let byParent = am7model.isParent(modType) && type !== 'auth.group';

        if (sel && !sel[am7model.jsonModelKey]) sel = null;
        if (!sel && !idx.length) return;

        let pg = pagination.pages();
        let obj = sel || pg.pageResults[pg.currentPage][idx[0]];
        let ltype = obj[am7model.jsonModelKey] || baseListType;
        m.route.set('/' + (byParent ? 'p' : '') + 'list/' + (containerMode ? baseListType : ltype) + '/' + obj.objectId, { key: Date.now() });
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

        if (!items || !items.length) {
            return m('div', { class: 'list-empty' }, 'No results');
        }

        if (carousel || gridMode === 2) return renderCarousel(items);
        if (gridMode === 1) return renderGrid(items);
        return renderSimpleTable(items);
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

    function renderSimpleTable(items) {
        if (!items || !items.length) return m('div', 'No results');

        let type = items[0][am7model.jsonModelKey];
        let mod = type ? am7model.getModel(type) : null;

        // Choose display columns: name, plus a few useful inherited fields
        let columns = ['name'];
        if (mod) {
            let allFields = am7model.getModelFields(mod);
            let fieldNames = allFields.map(f => f.name);
            if (fieldNames.includes('description')) columns.push('description');
            if (fieldNames.includes('groupPath')) columns.push('groupPath');
            if (fieldNames.includes('type') && !columns.includes('type')) columns.push('type');
        }

        // Check if items have image content type or portrait for thumbnail column
        let showThumb = items.some(function(item) {
            let ct = item.contentType || '';
            return ct.match(/^image/) ||
                (item.profile && item.profile.portrait && item.profile.portrait.contentType);
        });

        let rows = items.map((item) => {
            let state = pagination.state(item);
            let checked = !!(state && state.checked);
            let dndAttrs = {};
            if (page.components.dnd) {
                dndAttrs.draggable = "true";
                dndAttrs.ondragstart = page.components.dnd.dragStartHandler(item);
            }
            return m('tr', Object.assign({
                class: 'list-tr' + (checked ? ' list-tr-selected' : ''),
                onclick: () => selectResult(item),
                ondblclick: () => navigateDown(item)
            }, dndAttrs), [
                m('td', { class: 'list-td list-td-check' },
                    m('input', {
                        type: 'checkbox',
                        checked,
                        onclick: (e) => { e.stopPropagation(); selectResult(item); }
                    })
                ),
                showThumb ? m('td', { class: 'list-td', style: 'width:40px' }, getItemThumbnail(item)) : null,
                ...columns.map(c => m('td', { class: 'list-td' }, displayValue(item, c)))
            ]);
        });

        return m('table', { class: 'list-table' }, [
            m('thead', [
                m('tr', [
                    m('th', { class: 'list-th list-th-check' }, ''),
                    showThumb ? m('th', { class: 'list-th', style: 'width:40px' }, '') : null,
                    ...columns.map(c => m('th', { class: 'list-th' }, c))
                ])
            ]),
            m('tbody', rows)
        ]);
    }

    function getItemThumbnail(item) {
        let url = null;
        let ct = item.contentType || '';
        if (ct.match(/^image/)) {
            url = am7client.mediaDataPath(item, true);
        } else if (item.profile && item.profile.portrait && item.profile.portrait.contentType) {
            url = am7client.mediaDataPath(item.profile.portrait, true);
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
    //  Grid / Carousel rendering
    // ------------------------------------------------------------------

    function renderGrid(items) {
        if (!items || !items.length) return m('div', { class: 'p-4 text-gray-400' }, 'No results');

        return m('div', { class: 'grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3 p-3' },
            items.map(function(item) {
                let state = pagination.state(item);
                let checked = !!(state && state.checked);
                let thumb = getItemThumbnail(item);
                let dndAttrs = {};
                if (page.components.dnd) {
                    dndAttrs.draggable = "true";
                    dndAttrs.ondragstart = page.components.dnd.dragStartHandler(item);
                }

                return m('div', Object.assign({
                    class: 'group relative rounded-lg border overflow-hidden cursor-pointer transition-all hover:shadow-md'
                        + (checked ? ' border-blue-500 ring-2 ring-blue-300 dark:ring-blue-700' : ' border-gray-200 dark:border-gray-700'),
                    onclick: function() { selectResult(item); },
                    ondblclick: function() { navigateDown(item); }
                }, dndAttrs), [
                    // Thumbnail area
                    thumb
                        ? m('div', { class: 'aspect-square bg-gray-100 dark:bg-gray-800 flex items-center justify-center overflow-hidden' },
                            m('img', { src: thumb.attrs.src, class: 'w-full h-full object-cover' }))
                        : m('div', { class: 'aspect-square bg-gray-100 dark:bg-gray-800 flex items-center justify-center' },
                            m('span', { class: 'material-symbols-outlined text-gray-300 dark:text-gray-600', style: 'font-size:48px' }, 'description')),
                    // Info
                    m('div', { class: 'p-2' }, [
                        m('div', { class: 'text-xs font-medium text-gray-700 dark:text-gray-300 truncate' }, item.name || '(unnamed)'),
                        item.description ? m('div', { class: 'text-xs text-gray-400 truncate mt-0.5' }, item.description) : null
                    ]),
                    // Check overlay
                    checked ? m('div', { class: 'absolute top-1 right-1' },
                        m('span', { class: 'material-symbols-outlined text-blue-500', style: 'font-size:20px' }, 'check_circle')
                    ) : null
                ]);
            })
        );
    }

    function renderCarousel(items) {
        if (!items || !items.length) return m('div', { class: 'p-4 text-gray-400' }, 'No results');

        let pg = pagination.pages();
        let currentIdx = pg.currentItem || 0;
        if (currentIdx >= items.length) currentIdx = items.length - 1;
        let item = items[currentIdx];

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
                ? m('div', { class: 'flex justify-center p-4' },
                    m('img', { src: thumb.attrs.src, class: maxMode ? 'max-h-[80vh] object-contain' : 'max-h-96 object-contain rounded shadow' }))
                : m('div', { class: 'p-8 text-center text-gray-400' }, 'No preview available');
        }

        return m('div', { class: 'flex flex-col h-full' }, [
            // Carousel nav
            m('div', { class: 'flex items-center justify-between px-3 py-2 border-b border-gray-200 dark:border-gray-700' }, [
                m('button', {
                    class: 'p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700' + (currentIdx <= 0 ? ' opacity-30' : ''),
                    onclick: function() { if (currentIdx > 0) { pg.currentItem = currentIdx - 1; m.redraw(); } }
                }, m('span', { class: 'material-symbols-outlined', style: 'font-size:20px' }, 'chevron_left')),
                m('span', { class: 'text-sm text-gray-600 dark:text-gray-300' },
                    (currentIdx + 1) + ' / ' + items.length + (item.name ? ' — ' + item.name : '')),
                m('button', {
                    class: 'p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700' + (currentIdx >= items.length - 1 ? ' opacity-30' : ''),
                    onclick: function() { if (currentIdx < items.length - 1) { pg.currentItem = currentIdx + 1; m.redraw(); } }
                }, m('span', { class: 'material-symbols-outlined', style: 'font-size:20px' }, 'chevron_right'))
            ]),
            // Content
            m('div', { class: 'flex-1 overflow-auto' }, content)
        ]);
    }

    function toggleGrid() {
        gridMode++;
        if (gridMode > 2) gridMode = 0;
        carousel = (gridMode === 2);
        let rc = (gridMode === 1) ? defaultIconRecordCount : defaultRecordCount;
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
        pagination.filter(navFilter);
    }

    // ------------------------------------------------------------------
    //  Toolbar
    // ------------------------------------------------------------------

    function toolbar(type) {
        let hasSelection = getSelectedIndices().length > 0;
        let isGroupNav = containerMode || type === 'auth.group' || (modType && am7model.isParent(modType));

        let buttons = [];

        // Add / Edit / Delete
        if (!modType || !modType.systemNew) {
            buttons.push(iconBtn('button', 'add', '', addNew));
        }
        buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'edit', '', editSelected));
        buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'delete', '', deleteSelected));

        // Navigate up/down for group navigation
        if (isGroupNav) {
            buttons.push(iconBtn('button', 'north_west', '', navigateUp));
            buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'south_east', '', () => navigateDown()));
        }

        // Grid/carousel toggle
        let gridIcons = ['view_list', 'grid_view', 'view_carousel'];
        buttons.push(iconBtn('button', gridIcons[gridMode] || 'view_list', '', toggleGrid));

        // Select all
        buttons.push(iconBtn('button', 'select_all', '', selectAll));

        // Full mode
        buttons.push(iconBtn('button' + (fullMode ? ' active' : ''),
            fullMode ? 'close_fullscreen' : 'open_in_new', '', toggleFullMode));

        // Infinite scroll toggle
        buttons.push(iconBtn('button' + (infiniteScroll ? ' active' : ''),
            'all_inclusive', '', toggleInfiniteScroll));

        return buttons;
    }

    function filterInput() {
        let pg = pagination.pages();
        let placeholder = (pg.container && pg.container.path) ? pg.container.path : 'Filter...';

        return m('input', {
            type: 'text',
            class: 'text-field',
            placeholder,
            onkeydown: (e) => {
                if (e.which === 13) {
                    doFilter(e.target.value);
                }
            }
        });
    }

    function pageButtons() {
        let pg = pagination.pages();
        if (!pg.pageCount || pg.pageCount <= 1) return m('span');
        return m('nav', { class: 'result-nav' }, pagination.pageButtons());
    }

    // ------------------------------------------------------------------
    //  Lifecycle helpers
    // ------------------------------------------------------------------

    let lastVnode = null;

    function initParams(vnode) {
        if (!vnode) return;

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
        },

        onupdate: function (vnode) {
            lastVnode = vnode;
            // Re-read route params in case the route changed
            let newType = vnode.attrs.type || m.route.param('type');
            let newId = vnode.attrs.objectId || m.route.param('objectId');
            if (newType !== listType || newId !== listContainerId) {
                initParams(vnode);
                updatePagination(vnode);
            }
        },

        onremove: function () {
            navFilter = null;
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
            // Toolbar row
            m('div', { class: 'result-nav-outer' }, [
                m('div', { class: 'result-nav-inner' }, [
                    m('div', { class: 'result-nav tab-container' }, [
                        ...toolbar(type),
                        filterInput()
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

    return listPage;
}

// ---------------------------------------------------------------------------
//  Exports
// ---------------------------------------------------------------------------

export { newListControl };
export default newListControl;
